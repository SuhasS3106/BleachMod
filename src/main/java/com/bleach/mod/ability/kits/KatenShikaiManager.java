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
 *   <li>{@code DONT_JUMP}   — break if the enemy jumps (upward velocity while not on ground).
 *       Detected per-tick in {@link #tickRuleCheck}.</li>
 *   <li>{@code DONT_SPRINT} — break if the enemy sprints.
 *       Detected per-tick in {@link #tickRuleCheck}.</li>
 * </ul>
 *
 * <p>The rule assigned to each enemy is never revealed — only the <em>consequence</em> of
 * breaking it is visible (pink particle burst, gotcha chime, Weakness + Slowness).
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

	// ---- State maps --------------------------------------------------------------

	/** Shunsui UUID → (target entity UUID → assigned rule). */
	private static final Map<UUID, Map<UUID, IrooniRule>> RULES_BY_CASTER = new ConcurrentHashMap<>();
	/** Shunsui UUID → tick of last successful cast (for cooldown). */
	private static final Map<UUID, Integer> LAST_CAST_TICK = new ConcurrentHashMap<>();

	// ---- Lifecycle ---------------------------------------------------------------

	/**
	 * Called from {@code ShunsuiTransform.Shikai#onEnter}. Sets up an empty rule map for this
	 * player so subsequent calls never null-check the outer map.
	 */
	public static void init(ServerPlayer shunsui) {
		RULES_BY_CASTER.put(shunsui.getUUID(), new ConcurrentHashMap<>());
	}

	/**
	 * Called from {@code ShunsuiTransform.Shikai#onRevert}. Idempotent.
	 */
	public static void clearAll(ServerPlayer shunsui) {
		RULES_BY_CASTER.remove(shunsui.getUUID());
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
		// Cooldown gate
		int now = shunsui.tickCount;
		Integer lastCast = LAST_CAST_TICK.get(shunsui.getUUID());
		if (lastCast != null && (now - lastCast) < BleachTuning.SHUNSUI_IROONI_CAST_COOLDOWN_TICKS) {
			return;
		}

		SpiritualData data = BleachAttachments.get(shunsui);
		if (data.sp < BleachTuning.SHUNSUI_IROONI_CAST_SP_COST) {
			return;
		}

		ServerLevel level = shunsui.serverLevel();
		double radius = BleachTuning.SHUNSUI_IROONI_CAST_RADIUS;
		double radiusSq = radius * radius;

		List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
				shunsui.getBoundingBox().inflate(radius),
				e -> e != shunsui && e.isAlive() && !e.isSpectator() && e.distanceToSqr(shunsui) <= radiusSq);

		if (targets.isEmpty()) {
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

		rules.entrySet().removeIf(entry -> {
			LivingEntity target = (LivingEntity) level.getEntity(entry.getKey());
			if (target == null || !target.isAlive()) {
				return true; // prune dead/unloaded
			}

			IrooniRule rule = entry.getValue();
			if (rule == IrooniRule.DONT_JUMP && isJumping(target)) {
				applyRuleBreak(shunsui, target, level);
				return true; // rule consumed
			}
			if (rule == IrooniRule.DONT_SPRINT && target.isSprinting()) {
				applyRuleBreak(shunsui, target, level);
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

	/** True if the entity has upward momentum and is not on the ground — i.e., it just jumped. */
	private static boolean isJumping(LivingEntity entity) {
		return !entity.onGround() && entity.getDeltaMovement().y > 0.4;
	}
}
