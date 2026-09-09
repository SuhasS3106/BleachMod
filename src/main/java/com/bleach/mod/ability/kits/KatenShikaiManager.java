package com.bleach.mod.ability.kits;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Irooni — Shikai rule-assignment and rule-break detection for Shunsui Kyōraku.
 *
 * <h2>The three detectable rules</h2>
 * <ul>
 *   <li>{@code DONT_ATTACK} — break if the enemy attacks Shunsui while he is not looking at them.
 *       Detected in {@link #onMeleeHitCheck}, called from Shikai's {@code onMeleeHit}.</li>
 *   <li>{@code DONT_JUMP}   — break on the tick the enemy leaves the ground upward, via
 *       {@link #isJumpEdge}. Detected per-tick in {@link #tickRuleCheck}.</li>
 *   <li>{@code DONT_SPRINT} — break if the enemy sprints.
 *       Detected per-tick in {@link #tickRuleCheck}.</li>
 * </ul>
 *
 * <p>The rule assigned to each enemy is never revealed — only the <em>consequence</em> of
 * breaking it is visible (pink particle burst, gotcha chime, Weakness + Slowness). Adil's item 3
 * asked for the rules to be shown to the caster on look; that was declined 2026-09-09 as a reversal
 * of this decision rather than a fix to it. What <em>was</em> wrong is that the caster could not
 * tell a failed cast from a missing ability — see {@link #evaluateCast}.
 */
public final class KatenShikaiManager {
	private KatenShikaiManager() {
	}

	// ---- Rule pool ---------------------------------------------------------------

	public enum IrooniRule {
		DONT_ATTACK,
		DONT_JUMP,
		DONT_SPRINT
	}

	private static final IrooniRule[] RULE_VALUES = IrooniRule.values();

	/** Why a cast did or did not happen. Every value is something Shunsui gets told. */
	public enum CastOutcome {
		CAST,
		COOLDOWN,
		NOT_ENOUGH_SP,
		NO_TARGETS
	}

	/**
	 * Whether a cast may proceed, and if not, which blocker to report.
	 *
	 * <p>Order is deliberate: the cooldown is checked before the pool so that a Shunsui who is both
	 * on cooldown and broke is told about the one that clears on its own.
	 *
	 * @param ticksSinceCast ticks since the last successful cast; pass a large value if none
	 */
	public static CastOutcome evaluateCast(int ticksSinceCast, int cooldownTicks,
			double sp, double cost, int targetCount) {
		if (ticksSinceCast < cooldownTicks) {
			return CastOutcome.COOLDOWN;
		}
		if (sp < cost) {
			return CastOutcome.NOT_ENOUGH_SP;
		}
		if (targetCount <= 0) {
			return CastOutcome.NO_TARGETS;
		}
		return CastOutcome.CAST;
	}

	/**
	 * Whether this tick is the moment the entity left the ground upward.
	 *
	 * <p>The old check was {@code !onGround && deltaY > 0.4}, which is a window a real jump barely
	 * occupies: a vanilla jump starts at 0.42 and is already near 0.33 one tick later, and a remote
	 * player's server-side velocity is reconstructed from movement packets rather than simulated. So
	 * {@code DONT_JUMP} was close to undetectable while the other two rules worked, which is most of
	 * what "Shikai doesn't work" meant.
	 *
	 * <p>The edge is the honest predicate. Stepping off a ledge is excluded by the sign of
	 * {@code deltaY} rather than by its size, so no threshold has to be guessed. Being launched off
	 * the ground still counts — that is leaving the ground upward, whoever started it.
	 */
	public static boolean isJumpEdge(boolean wasOnGround, boolean onGround, double deltaY) {
		return wasOnGround && !onGround && deltaY > 0.0;
	}

	// ---- State maps --------------------------------------------------------------

	/** Shunsui UUID → (target entity UUID → assigned rule). */
	private static final Map<UUID, Map<UUID, IrooniRule>> RULES_BY_CASTER = new ConcurrentHashMap<>();
	/** Shunsui UUID → tick of last successful cast (for cooldown). */
	private static final Map<UUID, Integer> LAST_CAST_TICK = new ConcurrentHashMap<>();
	/**
	 * Shunsui UUID → (target entity UUID → was that target on the ground last tick).
	 *
	 * <p>Parallel to {@link #RULES_BY_CASTER} and pruned by the same pass, because
	 * {@link #isJumpEdge} needs a previous tick to compare against and a stateless check cannot
	 * tell a jump from a fall.
	 */
	private static final Map<UUID, Map<UUID, Boolean>> GROUND_BY_CASTER = new ConcurrentHashMap<>();

	// ---- Lifecycle ---------------------------------------------------------------

	/**
	 * Called from {@code ShunsuiTransform.Shikai#onEnter}. Sets up an empty rule map for this
	 * player so subsequent calls never null-check the outer map.
	 */
	public static void init(ServerPlayer shunsui) {
		RULES_BY_CASTER.put(shunsui.getUUID(), new ConcurrentHashMap<>());
		GROUND_BY_CASTER.put(shunsui.getUUID(), new ConcurrentHashMap<>());
	}

	/**
	 * Called from {@code ShunsuiTransform.Shikai#onRevert}. Idempotent.
	 */
	public static void clearAll(ServerPlayer shunsui) {
		RULES_BY_CASTER.remove(shunsui.getUUID());
		GROUND_BY_CASTER.remove(shunsui.getUUID());
		LAST_CAST_TICK.remove(shunsui.getUUID());
	}

	// ---- Rule-Set Cast -----------------------------------------------------------

	/**
	 * Assigns one hidden rule per living enemy within {@link BleachTuning#SHUNSUI_IROONI_CAST_RADIUS},
	 * if the cooldown has elapsed and Shunsui can afford the SP cost.
	 *
	 * <p>Called from {@code LivingEntitySwingMixin} on a confirmed swing-miss.
	 */
	public static void castRules(ServerPlayer shunsui) {
		int now = shunsui.tickCount;
		Integer lastCast = LAST_CAST_TICK.get(shunsui.getUUID());
		int sinceCast = lastCast == null ? Integer.MAX_VALUE : now - lastCast;
		int cooldown = BleachTuning.SHUNSUI_IROONI_CAST_COOLDOWN_TICKS;

		SpiritualData data = BleachAttachments.get(shunsui);

		ServerLevel level = shunsui.serverLevel();
		double radius = BleachTuning.SHUNSUI_IROONI_CAST_RADIUS;
		double radiusSq = radius * radius;

		// Counted before the outcome is decided so NO_TARGETS can be told apart from the rest. The
		// search is a box query against an already-loaded chunk section and is cheap enough to run
		// on a swing that turns out to be on cooldown.
		List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
				shunsui.getBoundingBox().inflate(radius),
				e -> e != shunsui && e.isAlive() && !e.isSpectator() && e.distanceToSqr(shunsui) <= radiusSq);

		CastOutcome outcome = evaluateCast(sinceCast, cooldown, data.sp,
				BleachTuning.SHUNSUI_IROONI_CAST_SP_COST, targets.size());

		if (outcome != CastOutcome.CAST) {
			// Every one of these used to be a bare return. A cast that fails in silence is
			// indistinguishable from an ability that does not exist, which is what item 3 reported.
			shunsui.displayClientMessage(switch (outcome) {
				case COOLDOWN -> Component.literal("Katen is not ready — "
						+ String.format("%.1f", (cooldown - sinceCast) / (double) BleachTuning.TICKS_PER_SECOND) + "s");
				case NOT_ENOUGH_SP -> Component.literal("Not enough pressure — need "
						+ (int) Math.ceil(BleachTuning.SHUNSUI_IROONI_CAST_SP_COST));
				case NO_TARGETS -> Component.literal("No one within "
						+ (int) radius + " blocks");
				case CAST -> Component.empty();
			}, true);
			return;
		}

		data.spend(BleachTuning.SHUNSUI_IROONI_CAST_SP_COST);
		SpiritualTicker.sync(shunsui, true);
		LAST_CAST_TICK.put(shunsui.getUUID(), now);

		Map<UUID, IrooniRule> rules = RULES_BY_CASTER.computeIfAbsent(
				shunsui.getUUID(), k -> new ConcurrentHashMap<>());

		for (LivingEntity target : targets) {
			// Re-assign — new cast overwrites any existing rule.
			int idx = shunsui.getRandom().nextInt(RULE_VALUES.length);
			rules.put(target.getUUID(), RULE_VALUES[idx]);
		}

		// Soft, playful cast cue — audible to all nearby but not alarming.
		level.playSound(null, shunsui.getX(), shunsui.getY(), shunsui.getZ(),
				SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 1.0f, 1.2f);

		// A count, never which rules. The rule stays hidden — this only confirms the cast landed,
		// which is the whole of what was missing.
		shunsui.displayClientMessage(Component.literal("Rules set on " + targets.size()), true);
	}

	// ---- Per-Tick Rule Detection -------------------------------------------------

	/**
	 * Called once per server tick from {@code ShunsuiTransform.Shikai#onTick}. Checks all
	 * assigned targets for DONT_JUMP and DONT_SPRINT rule violations. Dead or unloaded entities
	 * are pruned lazily.
	 */
	public static void tickRuleCheck(ServerPlayer shunsui) {
		Map<UUID, IrooniRule> rules = RULES_BY_CASTER.get(shunsui.getUUID());
		if (rules == null || rules.isEmpty()) {
			return;
		}

		ServerLevel level = shunsui.serverLevel();
		Map<UUID, Boolean> ground = GROUND_BY_CASTER.computeIfAbsent(
				shunsui.getUUID(), k -> new ConcurrentHashMap<>());

		rules.entrySet().removeIf(entry -> {
			UUID targetId = entry.getKey();
			LivingEntity target = (LivingEntity) level.getEntity(targetId);
			if (target == null || !target.isAlive()) {
				ground.remove(targetId); // prune dead/unloaded from both maps
				return true;
			}

			// Recorded every tick whatever the rule is, so a re-cast onto DONT_JUMP has a previous
			// tick to compare against instead of missing the first jump after it.
			boolean onGround = target.onGround();
			Boolean previous = ground.put(targetId, onGround);
			boolean wasOnGround = previous == null ? onGround : previous;

			IrooniRule rule = entry.getValue();
			if (rule == IrooniRule.DONT_JUMP
					&& isJumpEdge(wasOnGround, onGround, target.getDeltaMovement().y)) {
				applyRuleBreak(shunsui, target, level);
				ground.remove(targetId);
				return true; // rule consumed
			}
			if (rule == IrooniRule.DONT_SPRINT && target.isSprinting()) {
				applyRuleBreak(shunsui, target, level);
				ground.remove(targetId);
				return true;
			}
			return false;
		});
	}

	/**
	 * Checks whether the attacker hit Shunsui while Shunsui's back was turned (DONT_ATTACK break).
	 *
	 * <p>Called from Shikai's {@code onMeleeHit} — at that point the attacker is the one who
	 * swung, and {@code target} is Shunsui (the victim of the incoming hit resolved by the rule).
	 * The rule lives on the attacker's UUID, not on Shunsui's, so we look up the attacker.
	 *
	 * @param shunsui the Shunsui player who was hit
	 * @param attacker the entity who landed the hit on Shunsui
	 */
	public static void onMeleeHitCheck(ServerPlayer shunsui, LivingEntity attacker) {
		Map<UUID, IrooniRule> rules = RULES_BY_CASTER.get(shunsui.getUUID());
		if (rules == null) {
			return;
		}
		IrooniRule rule = rules.get(attacker.getUUID());
		if (rule != IrooniRule.DONT_ATTACK) {
			return;
		}

		// Break condition: Shunsui's back is turned toward the attacker.
		// If Shunsui is facing away, dot(lookAngle, dir_to_attacker) < 0.
		Vec3 toAttacker = attacker.position().subtract(shunsui.position());
		double dist = toAttacker.length();
		if (dist < 1.0e-4) {
			return; // standing inside each other — no meaningful direction
		}
		double dot = shunsui.getLookAngle().dot(toAttacker.scale(1.0 / dist));
		if (dot < 0.0) {
			// Back was turned — rule broken.
			rules.remove(attacker.getUUID());
			Map<UUID, Boolean> ground = GROUND_BY_CASTER.get(shunsui.getUUID());
			if (ground != null) {
				ground.remove(attacker.getUUID());
			}
			applyRuleBreak(shunsui, attacker, shunsui.serverLevel());
		}
	}

	// ---- Rule-Break Consequence --------------------------------------------------

	/**
	 * Applies the punishment for a rule break: Weakness + Slowness (SL-gap-scaled),
	 * a pink pressure-particle burst, and a "gotcha" chime.
	 */
	private static void applyRuleBreak(ServerPlayer shunsui, LivingEntity target, ServerLevel level) {
		int slGap = 0;
		if (target instanceof ServerPlayer targetPlayer) {
			SpiritualData targetData = BleachAttachments.get(targetPlayer);
			SpiritualData shunsuiData = BleachAttachments.get(shunsui);
			slGap = Math.max(0, shunsuiData.soulLevel - targetData.soulLevel);
		}

		int weaknessAmp = BleachTuning.SHUNSUI_IROONI_PUNISH_WEAKNESS_AMP + slGap;
		int slownessAmp = BleachTuning.SHUNSUI_IROONI_PUNISH_SLOWNESS_AMP + slGap;
		int duration    = BleachTuning.SHUNSUI_IROONI_PUNISH_DURATION_TICKS;

		target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, duration, weaknessAmp, false, true, true));
		target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, slownessAmp, false, true, true));

		// Pink burst — the only visible tell that a rule was broken.
		level.sendParticles(
				new PressureParticleOptions(BleachTuning.SHUNSUI_IROONI_BREAK_PARTICLE_COLOR, 1.2f),
				target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
				20, 0.4, 0.4, 0.4, 0.05);

		// Gotcha chime — distinct from the cast cue (amethyst block chime).
		level.playSound(null, target.getX(), target.getY(), target.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5f, 1.8f);

		if (target instanceof ServerPlayer targetPlayer) {
			targetPlayer.displayClientMessage(Component.literal("...!"), true);
		}
	}

	// ---- Helpers -----------------------------------------------------------------

}
