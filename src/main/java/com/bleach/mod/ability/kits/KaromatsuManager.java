package com.bleach.mod.ability.kits;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.network.KaromatsuSyncPayload;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.progression.SoulLevelCurve;
import com.bleach.mod.tuning.BleachTuning;

import org.joml.Vector3f;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Karamatsu Shinjū — four-act Bankai state machine for Shunsui Kyōraku.
 *
 * <h2>Act progression</h2>
 * <pre>
 *   PRE_ACT → ACT_1 → ACT_2 → ACT_3 → FINAL_ACT (→ CONCLUDED)
 * </pre>
 * <ul>
 *   <li><b>PRE_ACT</b> — zone is established; all living entities inside become participants.</li>
 *   <li><b>ACT_1</b>  — shared-damage link (damage dealt to one is mirrored to the other side),
 *       death-floored at {@link BleachTuning#SHUNSUI_ACT1_DEATH_FLOOR_HP}. Advances after
 *       {@link BleachTuning#SHUNSUI_ACT1_EXCHANGE_THRESHOLD} exchanges.</li>
 *   <li><b>ACT_2</b>  — bleed ticks land on all target participants, growing over time. Advances
 *       after {@link BleachTuning#SHUNSUI_ACT2_DURATION_TICKS} ticks.</li>
 *   <li><b>ACT_3</b>  — SP drain race. Both sides drain; first to fall to
 *       {@link BleachTuning#SHUNSUI_ACT3_LOSS_SP_THRESHOLD} loses. If a target loses, the Final
 *       Act fires. If Shunsui loses, the sequence concludes without the execute.</li>
 *   <li><b>FINAL_ACT</b> — 40-tick thread-particle charge, then execute damage
 *       ({@link BleachDamage#SPIRIT_MECHANIC_KILL}).</li>
 * </ul>
 *
 * <h2>Target model — multi-target</h2>
 * All living entities inside the zone at Bankai entry (and those who enter during PRE_ACT)
 * are participants. Act progression thresholds are shared across the full set.
 *
 * <h2>Containment</h2>
 * Non-caster participants cannot leave. Members are resolved <b>by UUID</b> and moved back to their
 * own side of the shell — a {@code ServerPlayer} through its connection, everything else through
 * {@code teleportTo}. Both halves of that matter, and the second was wrong before: see
 * {@link #contain} and {@link #place}.
 *
 * <h2>Shape</h2>
 * The zone is a <b>hemisphere</b> standing on the anchor, and the volume that contains is the same
 * shape as the shell that is drawn. Anything below the anchor plane counts as inside.
 *
 * <h2>Abort path</h2>
 * If all participants become invalid (dead or disconnected), {@link #abortSequence} tears down
 * all effects cleanly and removes the zone, leaving Shunsui in Bankai but with no active stage.
 */
public final class KaromatsuManager {
	private KaromatsuManager() {
	}

	// ---- Act state enum ----------------------------------------------------------

	public enum ActState {
		PRE_ACT((byte) 0),
		ACT_1  ((byte) 1),
		ACT_2  ((byte) 2),
		ACT_3  ((byte) 3),
		FINAL_ACT((byte) 4),
		CONCLUDED((byte) -1);

		public final byte index;
		ActState(byte index) { this.index = index; }
	}

	// ---- Stage data --------------------------------------------------------------

	private static final class BankaiStage {
		final UUID casterId;
		final Vec3 center;
		final double radius;
		final ResourceKey<Level> dimension;

		/** All participant entity UUIDs — everyone sealed in when the stage was set. */
		final Set<UUID> participantIds = new LinkedHashSet<>();
		/** Re-entrancy guard: caster UUID is present while a mirror hit is being applied. */
		final Set<UUID> mirrorGuard = new HashSet<>();

		ActState act = ActState.PRE_ACT;

		// Act 1
		int exchangeCount = 0;

		// Act 2
		int act2TicksActive     = 0;
		int act2TicksSinceGrowth = 0;
		double currentBleedDps  = BleachTuning.SHUNSUI_ACT2_BLEED_DPS;

		// Act 3
		UUID act3Loser = null;

		// Final Act
		int finalActChargeTicks = BleachTuning.SHUNSUI_FINAL_ACT_CHARGE_TICKS;
		boolean finalActFired   = false;

		BankaiStage(UUID casterId, Vec3 center, double radius, ResourceKey<Level> dimension) {
			this.casterId  = casterId;
			this.center    = center;
			this.radius    = radius;
			this.dimension = dimension;
		}
	}

	/** Active Bankai stages keyed by caster UUID. */
	private static final Map<UUID, BankaiStage> STAGES = new ConcurrentHashMap<>();

	// ---- Lifecycle ---------------------------------------------------------------

	/**
	 * Called from {@code ShunsuiTransform.Bankai#onEnter}. Plants the zone, seals in everyone
	 * standing inside it at that moment, advances to ACT_1 immediately (PRE_ACT is just the entry
	 * frame), and tells every participant what has happened to them.
	 *
	 * <p>Sealing at placement rather than letting membership settle on the first tick is the
	 * difference between "the stage closes around whoever is here" and "…around whoever happens to
	 * be here one tick later", and the latter lets a fast target walk out of a zone that has visibly
	 * already formed.
	 */
	public static void startBankai(ServerPlayer shunsui) {
		SpiritualData data = BleachAttachments.get(shunsui);
		int sl = data.soulLevel;
		double radius = BleachTuning.SHUNSUI_BANKAI_ZONE_RADIUS_BASE
				+ BleachTuning.SHUNSUI_BANKAI_ZONE_RADIUS_PER_SL * Math.max(0, sl - 1);

		ServerLevel level = shunsui.serverLevel();
		Vec3 center = shunsui.position();

		BankaiStage stage = new BankaiStage(shunsui.getUUID(), center, radius, level.dimension());
		STAGES.put(shunsui.getUUID(), stage);

		for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(center, center).inflate(radius),
				e -> e != shunsui && e.isAlive() && !e.isSpectator() && inside(stage, e))) {
			stage.participantIds.add(candidate.getUUID());
		}

		// Advance to ACT_1 immediately
		stage.act = ActState.ACT_1;
		syncTint(stage, level);

		level.playSound(null, center.x, center.y, center.z,
				SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.9f, 0.55f);

		announce(stage, level, Component.literal("Karamatsu Shinjū — the stage is set."));
		announce(stage, level, actAnnouncement(ActState.ACT_1));
	}

	/**
	 * Called from {@code ShunsuiTransform.Bankai#onRevert} and from {@link #abortSequence}.
	 * Cleans up all state and notifies clients. Idempotent.
	 *
	 * <p>Every teardown path in this class goes through {@link #clearAllTints}, because the tint is
	 * driven by edges: a missed falling edge is a permanent gloom on somebody's screen with nothing
	 * left in the world causing it, and no way to clear it short of relogging.
	 */
	public static void endBankai(ServerPlayer shunsui) {
		BankaiStage stage = STAGES.remove(shunsui.getUUID());
		if (stage == null) {
			return;
		}
		clearAllTints(stage, shunsui.server);
	}

	// ---- Main Tick Driver --------------------------------------------------------

	/**
	 * Called from {@code ShunsuiTransform.Bankai#onTick} every server tick.
	 */
	public static void tickBankai(ServerPlayer shunsui) {
		BankaiStage stage = STAGES.get(shunsui.getUUID());
		if (stage == null) {
			return;
		}

		// A concluded stage is over: nothing is contained, nothing is drawn, nobody is tinted. The
		// entry survives only so getCurrentMeleeDmgBonus keeps answering until Shunsui reverts.
		// Without this the cage outlived the performance and held its participants indefinitely.
		if (stage.act == ActState.CONCLUDED) {
			return;
		}

		ServerLevel level = shunsui.serverLevel();
		MinecraftServer server = shunsui.server;

		// A stage is a place. If Shunsui is no longer in the dimension he planted it in, none of the
		// participant lookups below can resolve anyone anyway — collapse it deliberately rather than
		// letting it fall over one tick later looking like an abort.
		if (!stage.dimension.equals(level.dimension())) {
			abortSequence(shunsui, stage, level);
			return;
		}

		int now = server.getTickCount();

		// 1. Check target validity — abort if everyone is gone
		if (!checkTargetValidity(stage, level, server)) {
			abortSequence(shunsui, stage, level);
			return;
		}

		// 2. Containment. Every tick, without exception: a wall checked on a slower clock is a wall
		// anything faster than one block per tick walks straight through.
		contain(stage, level);

		// 3. The shell, on its own slower clock and drawn a slice at a time.
		if (now % Math.max(1, BleachTuning.SHUNSUI_ZONE_PARTICLE_INTERVAL) == 0) {
			drawZone(stage, level, now);
		}

		// 4. Act-specific logic
		switch (stage.act) {
			case ACT_1     -> tickAct1(shunsui, stage, level);
			case ACT_2     -> tickAct2(shunsui, stage, level, now);
			case ACT_3     -> tickAct3(shunsui, stage, level, server);
			case FINAL_ACT -> tickFinalAct(shunsui, stage, level, server);
			default        -> {} // PRE_ACT, CONCLUDED — nothing
		}

		// 5. "What is happening to me, and how far along is it." Once a second, on the action bar,
		// for everyone on the stage including Shunsui. Without this the Bankai is four unannounced
		// state changes that only differ in what colour the screen is.
		if (now % 20 == 0) {
			reportState(stage, level);
		}
	}

	// ---- Act 1 ------------------------------------------------------------------

	private static void tickAct1(ServerPlayer shunsui, BankaiStage stage, ServerLevel level) {
		if (stage.exchangeCount >= BleachTuning.SHUNSUI_ACT1_EXCHANGE_THRESHOLD) {
			advanceTo(ActState.ACT_2, shunsui, stage, level);
		}
	}

	/**
	 * Called from {@link com.bleach.mod.mixin.LivingEntityDamageMixin} when Act 1 is active and
	 * a participant takes damage inside the zone. Mirrors the damage to the opposite side.
	 *
	 * <p>Re-entrancy guard: the caster UUID is in {@code mirrorGuard} while the mirror is being
	 * applied, so the mirrored hit does not loop.
	 *
	 * @param victim the entity that was just hit
	 * @param amount the damage amount (pre-scaling)
	 */
	public static void onSharedDamage(LivingEntity victim, float amount) {
		UUID victimId = victim.getUUID();

		for (BankaiStage stage : STAGES.values()) {
			if (stage.act != ActState.ACT_1) {
				continue;
			}
			if (stage.mirrorGuard.contains(stage.casterId)) {
				continue; // already inside a mirror call for this zone
			}

			boolean victimIsCaster   = victimId.equals(stage.casterId);
			boolean victimIsTarget   = stage.participantIds.contains(victimId);
			if (!victimIsCaster && !victimIsTarget) {
				continue;
			}

			ServerLevel level = (ServerLevel) victim.level();

			stage.mirrorGuard.add(stage.casterId);
			try {
				if (victimIsCaster) {
					// Mirror to all target participants
					for (UUID targetId : stage.participantIds) {
						LivingEntity target = (LivingEntity) level.getEntity(targetId);
						if (target != null && target.isAlive()) {
							applyMirrorDamage(target, amount,
									level.getEntity(stage.casterId) instanceof LivingEntity c ? c : victim, level);
						}
					}
				} else {
					// Mirror to Shunsui
					LivingEntity caster = (LivingEntity) level.getEntity(stage.casterId);
					if (caster instanceof ServerPlayer shunsui) {
						applyMirrorDamage(shunsui, amount, victim, level);
					}
				}
				stage.exchangeCount++;
			} finally {
				stage.mirrorGuard.remove(stage.casterId);
			}
			return; // each victim belongs to at most one zone
		}
	}

	private static void applyMirrorDamage(LivingEntity target, float amount,
			LivingEntity source, ServerLevel level) {
		// Clamp: the mirror cannot reduce health below the death floor.
		float clamped = Math.min(amount, target.getHealth() - BleachTuning.SHUNSUI_ACT1_DEATH_FLOOR_HP);
		if (clamped <= 0.0f) {
			return;
		}
		target.invulnerableTime = 0;
		target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, source), clamped);
	}

	// ---- Act 2 ------------------------------------------------------------------

	/**
	 * The rot · <i>Zanki no Shitone</i>. A full interval's bleed in one hit, not a twentieth of it
	 * twenty times a second.
	 *
	 * <p>The DPS is identical; what changed is how often the game says so. Hurting every tick with
	 * {@code invulnerableTime} forced to zero produced twenty hurt sounds, twenty red flashes and
	 * twenty camera kicks a second, which is what playtest reported as "very annoying" and as
	 * "noise from damage" — <b>not</b> the screen shake, which belongs to the low-SP overlay and has
	 * nothing to do with Shunsui.
	 *
	 * <p>Nothing zeroes {@code invulnerableTime} any more either: at
	 * {@link BleachTuning#SHUNSUI_ACT2_DAMAGE_INTERVAL} of 20 the interval lines up exactly with
	 * vanilla's own immunity window, so the hit lands on its own. The honest cost of that is that a
	 * bleed tick landing inside the immunity left by <em>someone else's</em> hit is swallowed rather
	 * than forced through — so the rot is very slightly cheaper in a busy fight, which is the right
	 * direction to be wrong in and far cheaper than twenty flashes a second.
	 *
	 * <p>The blemish particles moved to the same clock for the same reason: three per participant
	 * per tick was a smear, and three per participant per second is a mark.
	 */
	private static void tickAct2(ServerPlayer shunsui, BankaiStage stage, ServerLevel level, int now) {
		stage.act2TicksActive++;

		// Bleed growth: every 5 seconds (100 ticks)
		stage.act2TicksSinceGrowth++;
		if (stage.act2TicksSinceGrowth >= 100) {
			stage.currentBleedDps *= BleachTuning.SHUNSUI_ACT2_BLEED_GROWTH;
			stage.act2TicksSinceGrowth = 0;
		}

		int interval = Math.max(1, BleachTuning.SHUNSUI_ACT2_DAMAGE_INTERVAL);
		if (now % interval == 0) {
			float bleed = (float) (stage.currentBleedDps * interval / BleachTuning.TICKS_PER_SECOND);
			for (UUID targetId : stage.participantIds) {
				LivingEntity target = (LivingEntity) level.getEntity(targetId);
				if (target == null || !target.isAlive()) {
					continue;
				}
				target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE_BLEED, shunsui),
						bleed);

				// Dark blemish particles anchored to hitbox centre
				Vec3 bodyCenter = target.getBoundingBox().getCenter();
				for (int i = 0; i < 3; i++) {
					double ox = (level.getRandom().nextDouble() - 0.5) * 0.5;
					double oy = (level.getRandom().nextDouble() - 0.5) * 0.5;
					double oz = (level.getRandom().nextDouble() - 0.5) * 0.5;
					level.sendParticles(new PressureParticleOptions(0x050505, 0.5f),
							bodyCenter.x + ox, bodyCenter.y + oy, bodyCenter.z + oz,
							1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}

		if (stage.act2TicksActive >= BleachTuning.SHUNSUI_ACT2_DURATION_TICKS) {
			advanceTo(ActState.ACT_3, shunsui, stage, level);
		}
	}

	// ---- Act 3 ------------------------------------------------------------------

	/**
	 * SP drain replaces Bankai's passive drain for Shunsui (gated in {@code SpiritualTicker}),
	 * and replaces regen for target participants.
	 *
	 * <p>Slowness (exempt for Shunsui) + ambient bubble particles + per-second loss check.
	 */
	private static void tickAct3(ServerPlayer shunsui, BankaiStage stage,
			ServerLevel level, MinecraftServer server) {
		SpiritualData shunsuiData = BleachAttachments.get(shunsui);

		// Drain Shunsui — replaces DRAIN_BANKAI (gated in SpiritualTicker.tickTransformed), so it
		// carries the same Soul Level taper. Without it, levelling makes ordinary Bankai cheaper
		// while Act 3 stays flat, and the strongest part of the kit becomes the one you can least
		// afford to reach.
		shunsuiData.spend(BleachTuning.SHUNSUI_ACT3_SP_DRAIN_PER_SEC
				* SoulLevelCurve.bankaiDrainMultiplier(shunsuiData.soulLevel)
				/ BleachTuning.TICKS_PER_SECOND);
		SpiritualTicker.sync(shunsui, false);

		// Drain and slow all target participants
		for (UUID targetId : stage.participantIds) {
			LivingEntity targetEntity = (LivingEntity) level.getEntity(targetId);
			if (targetEntity == null || !targetEntity.isAlive()) {
				continue;
			}

			if (targetEntity instanceof ServerPlayer targetPlayer) {
				SpiritualData targetData = BleachAttachments.get(targetPlayer);
				targetData.spend(BleachTuning.SHUNSUI_ACT3_SP_DRAIN_PER_SEC / BleachTuning.TICKS_PER_SECOND);
				targetData.pauseRegen();
				SpiritualTicker.sync(targetPlayer, false);
			}

			// Slowness (not applied to Shunsui — home-turf immunity)
			targetEntity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
					2, BleachTuning.SHUNSUI_ACT3_SLOWNESS_AMP, false, false, false));

			// Bubble particles for the underwater feel
			Vec3 pos = targetEntity.position();
			level.sendParticles(ParticleTypes.BUBBLE_POP,
					pos.x, pos.y + 1.0, pos.z, 3, 0.3, 0.4, 0.3, 0.0);
		}

		// Per-second loss check (every 20 ticks)
		if (shunsui.tickCount % 20 != 0) {
			return;
		}

		// Check if Shunsui himself hit the threshold
		double shunsuiSpFraction = shunsuiData.maxSp() > 0
				? shunsuiData.sp / shunsuiData.maxSp() : 0.0;
		if (shunsuiSpFraction <= BleachTuning.SHUNSUI_ACT3_LOSS_SP_THRESHOLD) {
			// Shunsui loses — sequence concludes without the execute
			announce(stage, level, Component.literal("The petals... fall only for me."));
			conclude(stage, level);
			return;
		}

		// Check target participants
		for (UUID targetId : stage.participantIds) {
			if (targetId.equals(stage.casterId)) {
				continue;
			}
			LivingEntity targetEntity = (LivingEntity) level.getEntity(targetId);
			if (!(targetEntity instanceof ServerPlayer targetPlayer)) {
				continue;
			}
			SpiritualData targetData = BleachAttachments.get(targetPlayer);
			double targetSpFraction = targetData.maxSp() > 0
					? targetData.sp / targetData.maxSp() : 0.0;
			if (targetSpFraction <= BleachTuning.SHUNSUI_ACT3_LOSS_SP_THRESHOLD) {
				stage.act3Loser = targetId;
				advanceTo(ActState.FINAL_ACT, shunsui, stage, level);
				return;
			}
		}
	}

	// ---- Final Act --------------------------------------------------------------

	private static void tickFinalAct(ServerPlayer shunsui, BankaiStage stage,
			ServerLevel level, MinecraftServer server) {
		// Validate the losing target is still present
		LivingEntity loser = stage.act3Loser != null
				? (LivingEntity) level.getEntity(stage.act3Loser) : null;
		if (loser == null || !loser.isAlive()) {
			abortSequence(shunsui, stage, level);
			return;
		}

		// Thread particle line from Shunsui's eye to target's centre
		Vec3 from = shunsui.getEyePosition();
		Vec3 to = loser.getBoundingBox().getCenter().add(0, loser.getBbHeight() * 0.1, 0);
		Vec3 step = to.subtract(from).scale(1.0 / 12.0);
		for (int i = 0; i < 12; i++) {
			Vec3 pt = from.add(step.scale(i));
			level.sendParticles(new PressureParticleOptions(0xFFFFFF, 0.25f),
					pt.x, pt.y, pt.z, 1, 0.0, 0.0, 0.0, 0.0);
		}

		stage.finalActChargeTicks--;
		if (stage.finalActChargeTicks <= 0) {
			deliverFinalStrike(shunsui, loser, stage, level);
		}
	}

	private static void deliverFinalStrike(ServerPlayer shunsui, LivingEntity loser,
			BankaiStage stage, ServerLevel level) {
		loser.invulnerableTime = 0;
		loser.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_MECHANIC_KILL, shunsui),
				(float) BleachTuning.SHUNSUI_FINAL_ACT_DMG);

		level.playSound(null, loser.getX(), loser.getY(), loser.getZ(),
				SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.0f, 0.7f);

		stage.finalActFired = true;
		announce(stage, level, Component.literal("Itokiribasami — the thread is cut."));
		conclude(stage, level);
	}

	// ---- Target Validity + Abort -------------------------------------------------

	/**
	 * Removes invalid participant UUIDs (dead or disconnected). Returns {@code true} if at
	 * least one participant remains; {@code false} if the set is empty and the zone should abort.
	 */
	private static boolean checkTargetValidity(BankaiStage stage, ServerLevel level,
			MinecraftServer server) {
		Iterator<UUID> it = stage.participantIds.iterator();
		while (it.hasNext()) {
			UUID id = it.next();
			LivingEntity entity = (LivingEntity) level.getEntity(id);
			boolean invalid = entity == null || !entity.isAlive();
			// Extra check: player disconnected
			if (!invalid && server.getPlayerList().getPlayer(id) != null) {
				// it's a player and they're still connected — keep
			} else if (!invalid && entity instanceof ServerPlayer) {
				// it's a player reference but not in the player list → disconnected
				invalid = true;
			}
			if (invalid) {
				it.remove();
				// The falling edge, sent even though we could not resolve the entity: a participant who
				// changed dimension is still online, and would otherwise carry the gloom with them.
				tell(server, id, stage.act, false);
			}
		}
		return !stage.participantIds.isEmpty();
	}

	/**
	 * Called when all participants are gone. Tears down in-flight effects and removes the zone.
	 * Shunsui remains in Bankai — the zone collapses but the transformation persists.
	 */
	private static void abortSequence(ServerPlayer shunsui, BankaiStage stage, ServerLevel level) {
		stage.act = ActState.CONCLUDED;
		STAGES.remove(stage.casterId);
		clearAllTints(stage, level.getServer());
		shunsui.displayClientMessage(Component.literal("The stage has collapsed."), true);
		// No per-target Slowness to remove: participants are already gone.
		// Bleed stops because tickAct2 won't be called again.
		// mirrorGuard is already clear (abort is called from the top of tickBankai, not from onSharedDamage).
	}

	/**
	 * Ends the performance without ending the Bankai — the stage clears, everyone walks off it, and
	 * Shunsui keeps his release.
	 *
	 * <p>Both halves are mandatory. Dropping membership is what stops containment holding people on
	 * a stage that is no longer running, and clearing the tint is what stops them staring through a
	 * gloom nothing is causing. Concluding used to do neither.
	 */
	private static void conclude(BankaiStage stage, ServerLevel level) {
		stage.act = ActState.CONCLUDED;
		clearAllTints(stage, level.getServer());
	}

	// ---- Act Advancement --------------------------------------------------------

	private static void advanceTo(ActState next, ServerPlayer shunsui,
			BankaiStage stage, ServerLevel level) {
		stage.act = next;
		syncTint(stage, level);
		announce(stage, level, actAnnouncement(next));

		switch (next) {
			case ACT_2 -> level.playSound(null, stage.center.x, stage.center.y, stage.center.z,
					SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.2f, 0.6f);
			case ACT_3 -> level.playSound(null, stage.center.x, stage.center.y, stage.center.z,
					SoundEvents.ELDER_GUARDIAN_AMBIENT, SoundSource.PLAYERS, 1.0f, 0.8f);
			case FINAL_ACT -> {
				stage.finalActChargeTicks = BleachTuning.SHUNSUI_FINAL_ACT_CHARGE_TICKS;
				level.playSound(null, stage.center.x, stage.center.y, stage.center.z,
						SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.2f, 1.4f);
			}
			default -> {}
		}
	}

	// ---- Containment ------------------------------------------------------------

	/**
	 * The volume the stage actually holds — a <b>hemisphere</b> standing on the anchor, matching
	 * what {@link #drawZone} puts on screen.
	 *
	 * <p>It used to be a full sphere centred on Shunsui's feet, so half of it sat underground where
	 * nothing was drawn <em>and</em> anything below the anchor read as outside. The second half of
	 * that is the nastier one: a contained player who stepped one block downhill became "outside"
	 * while still a member, so containment shoved them back toward the anchor and then did it again
	 * the next tick. The symptom is being pinned a few steps from where the zone was planted.
	 *
	 * <p>So height above the anchor is floored at zero. Below it — down a slope, in a dip, in a cave
	 * — is still under the dome, and still inside.
	 */
	private static boolean inside(BankaiStage stage, LivingEntity candidate) {
		double dx = candidate.getX() - stage.center.x;
		double dz = candidate.getZ() - stage.center.z;
		double dy = Math.max(0.0, candidate.getY() - stage.center.y);
		if (dy > stage.radius) {
			return false;
		}
		// Ellipsoid rather than cylinder, so the volume narrows toward the crown the way the shell does.
		return dx * dx + dz * dz <= stage.radius * stage.radius - dy * dy;
	}

	/** Straight-line distance from the anchor, with below-anchor treated as level with it. */
	private static double distanceFrom(BankaiStage stage, LivingEntity entity) {
		double dx = entity.getX() - stage.center.x;
		double dz = entity.getZ() - stage.center.z;
		double dy = Math.max(0.0, entity.getY() - stage.center.y);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Pulls every participant back inside. Shunsui himself is not a participant and is not held —
	 * the stage is his, and walking off it is his prerogative.
	 *
	 * <p><b>Members are resolved by UUID, never found by searching a box around the zone.</b> That
	 * was already true here and is worth keeping true: a box small enough to be cheap is smaller
	 * than a Flash Step, and anyone who blinked past it would never be examined at all — neither
	 * pushed back nor released.
	 */
	private static void contain(BankaiStage stage, ServerLevel level) {
		double radius = stage.radius;
		double margin = BleachTuning.SHUNSUI_BANKAI_CONTAIN_MARGIN;

		Iterator<UUID> members = stage.participantIds.iterator();
		while (members.hasNext()) {
			UUID id = members.next();
			Entity found = level.getEntity(id);

			if (!(found instanceof LivingEntity entity) || !entity.isAlive()) {
				// checkTargetValidity drops it on the same tick; nothing to hold here.
				continue;
			}

			if (inside(stage, entity)) {
				continue;
			}

			// Safety net for genuine displacement — a command teleport, a portal, a bug in the
			// containment test itself. Being wrongly freed is cosmetic; being wrongly pinned ends the
			// play session, which is exactly what the below-anchor bug did.
			if (distanceFrom(stage, entity) > radius * BleachTuning.SHUNSUI_BANKAI_RELEASE_FACTOR) {
				members.remove();
				tell(level.getServer(), id, stage.act, false);
				continue;
			}

			push(entity, stage, radius, margin);
		}
	}

	/**
	 * Moves an entity back to its own side of the shell and kills the component of its velocity that
	 * carried it across.
	 */
	private static void push(LivingEntity entity, BankaiStage stage, double radius, double margin) {
		double dx = entity.getX() - stage.center.x;
		double dz = entity.getZ() - stage.center.z;
		double dy = Math.max(0.0, entity.getY() - stage.center.y);

		// Above the crown: the only case that is genuinely a vertical correction. Handled first so
		// the horizontal path below never has to reason about it.
		if (dy > radius) {
			place(entity, entity.getX(), stage.center.y + radius - margin, entity.getZ());
			damp(entity, new Vec3(0.0, 1.0, 0.0));
			return;
		}

		// Everything else is corrected in the horizontal plane only, and the entity keeps its own Y.
		// Rescaling all three axes at once would teleport anyone standing below the anchor up to the
		// anchor's height as a side effect of a sideways correction.
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 1.0e-4) {
			return;
		}

		double limit = Math.max(0.0, Math.sqrt(Math.max(0.0, radius * radius - dy * dy)) - margin);
		double scale = limit / horizontal;

		place(entity, stage.center.x + dx * scale, entity.getY(), stage.center.z + dz * scale);
		damp(entity, new Vec3(dx / horizontal, 0.0, dz / horizontal));
	}

	/**
	 * Moves an entity, using the route that actually holds for the kind of entity it is.
	 *
	 * <p><b>A {@code ServerPlayer} must be moved through its connection.</b> This is the whole of
	 * "you are able to leave the boundary". Containment used to only cancel outward velocity through
	 * {@code setDeltaMovement}, which does nothing authoritative to a player: the client keeps
	 * sending its own position from where it thinks it is, and the server accepts it. Even a
	 * server-side {@code teleportTo} is undone within a tick or two for the same reason. Only
	 * {@code connection.teleport} tells the client where it now is.
	 */
	private static void place(LivingEntity entity, double x, double y, double z) {
		if (entity instanceof ServerPlayer player) {
			player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
		} else {
			entity.teleportTo(x, y, z);
		}
	}

	/**
	 * Cancels the component of an entity's velocity along {@code outward}. Without this a contained
	 * player's own held input re-applies the crossing every tick and the wall reads as a judder
	 * rather than as a surface.
	 */
	private static void damp(LivingEntity entity, Vec3 outward) {
		Vec3 velocity = entity.getDeltaMovement();
		entity.setDeltaMovement(velocity.subtract(outward.scale(velocity.dot(outward))));
		entity.hurtMarked = true;
	}

	// ---- The shell --------------------------------------------------------------

	/**
	 * The zone, traced as latitude rings with a heavier ring on the ground and a skirt hanging from
	 * the rim · lifted from {@link PoisonDome}, which learned all of this the hard way.
	 *
	 * <p>The old version scattered 48 points over a full sphere every tick using
	 * {@code PressureParticle}, and got three things wrong at once. <b>That particle rises by
	 * design</b>, so a static shape smeared into a vertical sprinkle — vanilla dust stays put.
	 * <b>Half the sphere was underground</b>, spending half the budget where nothing could see it.
	 * And <b>a scatter is not a surface</b>: 48 random points over the ~14,000 m2 of a capped
	 * Shunsui's shell is noise, where rings give the eye a continuous edge to follow.
	 *
	 * <p>Points are spaced by arc length rather than split evenly per ring, so the shell stays even
	 * instead of bunching at the crown, and each pass draws one point in every
	 * {@link BleachTuning#SHUNSUI_ZONE_DRAW_STRIDE}, advancing the phase. Dust outlives several
	 * passes, so the eye assembles the whole shell anyway and a 67-block zone costs no more per tick
	 * than an 18-block one.
	 */
	private static void drawZone(BankaiStage stage, ServerLevel level, int nowTick) {
		DustParticleOptions dust = new DustParticleOptions(
				colour(BleachTuning.KIT_SHUNSUI_PARTICLE_COLOR),
				(float) BleachTuning.SHUNSUI_ZONE_PARTICLE_SCALE);

		double radius = stage.radius;
		int rings = Math.max(1, BleachTuning.SHUNSUI_ZONE_RINGS);
		double spacing = Math.max(0.1, BleachTuning.SHUNSUI_ZONE_POINT_SPACING);
		int stride = Math.max(1, BleachTuning.SHUNSUI_ZONE_DRAW_STRIDE);
		int phase = (nowTick / Math.max(1, BleachTuning.SHUNSUI_ZONE_PARTICLE_INTERVAL)) % stride;

		for (int r = 0; r < rings; r++) {
			// Latitude from the ground (0) to the crown (90 degrees).
			double lat = (r / (double) rings) * (Math.PI / 2.0);
			double ringRadius = Math.cos(lat) * radius;
			double height = Math.sin(lat) * radius;

			int points = (int) Math.max(4, Math.ceil(2.0 * Math.PI * ringRadius / spacing));
			for (int i = phase; i < points; i += stride) {
				double angle = (i / (double) points) * Math.PI * 2.0;
				level.sendParticles(dust,
						stage.center.x + Math.cos(angle) * ringRadius,
						stage.center.y + height,
						stage.center.z + Math.sin(angle) * ringRadius,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		drawFootprint(stage, level, dust, radius, spacing, stride, phase);
	}

	/**
	 * The ground ring, at double density, plus its skirt. It is the only part of the shell a player
	 * standing inside can always see, so it is what actually tells them where the edge is.
	 */
	private static void drawFootprint(BankaiStage stage, ServerLevel level, DustParticleOptions dust,
			double radius, double spacing, int stride, int phase) {
		int points = (int) Math.max(8, Math.ceil(2.0 * Math.PI * radius / (spacing * 0.5)));

		for (int i = phase; i < points; i += stride) {
			double angle = (i / (double) points) * Math.PI * 2.0;
			double x = stage.center.x + Math.cos(angle) * radius;
			double z = stage.center.z + Math.sin(angle) * radius;

			level.sendParticles(dust, x, stage.center.y + 0.1, z, 1, 0.0, 0.0, 0.0, 0.0);
			drawSkirt(level, dust, x, z, stage.center.y, BleachTuning.SHUNSUI_ZONE_SKIRT_DEPTH);
		}
	}

	/**
	 * The skirt — the wall hanging from the rim down to whatever the ground actually is at that
	 * column.
	 *
	 * <p>The shell stands on Shunsui's feet, so on sloping terrain its rim floats above ground that
	 * falls away, leaving a visible arch you can see straight under. Containment already covers that
	 * gap — below the anchor counts as inside — but the picture said otherwise, and a barrier that
	 * <em>looks</em> open is one people keep trying to walk under and then report as broken.
	 */
	private static void drawSkirt(ServerLevel level, DustParticleOptions dust,
			double x, double z, double rimY, double maxDepth) {
		int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				Mth.floor(x), Mth.floor(z));

		double bottom = Math.max(ground, rimY - maxDepth);
		for (double y = rimY - 1.0; y >= bottom; y -= 1.0) {
			level.sendParticles(dust, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static Vector3f colour(int rgb) {
		return new Vector3f(((rgb >> 16) & 0xFF) / 255.0f,
				((rgb >> 8) & 0xFF) / 255.0f,
				(rgb & 0xFF) / 255.0f);
	}

	// ---- Public Queries ---------------------------------------------------------

	/**
	 * Whether this entity is a participant in any active Act 1 zone (for the damage mixin).
	 */
	public static boolean isLinked(LivingEntity entity) {
		UUID id = entity.getUUID();
		for (BankaiStage stage : STAGES.values()) {
			if (stage.act != ActState.ACT_1) {
				continue;
			}
			if (id.equals(stage.casterId) || stage.participantIds.contains(id)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether Shunsui is currently in Act 3 — used by {@code SpiritualTicker} to suspend the
	 * normal {@code DRAIN_BANKAI} spend and let Act 3's own drain path handle it.
	 */
	public static boolean isInAct3(ServerPlayer shunsui) {
		BankaiStage stage = STAGES.get(shunsui.getUUID());
		return stage != null && stage.act == ActState.ACT_3;
	}

	/**
	 * Current melee damage bonus — zero before Act 2, {@link BleachTuning#SHUNSUI_SECOND_BLADE_DMG_BONUS}
	 * once Act 2 or later is reached.
	 */
	public static double getCurrentMeleeDmgBonus(ServerPlayer shunsui) {
		BankaiStage stage = STAGES.get(shunsui.getUUID());
		if (stage == null) {
			return 0.0;
		}
		return switch (stage.act) {
			case ACT_2, ACT_3, FINAL_ACT, CONCLUDED -> BleachTuning.SHUNSUI_SECOND_BLADE_DMG_BONUS;
			default -> 0.0;
		};
	}

	// ---- Tint edges -------------------------------------------------------------

	/**
	 * Tells every participant they are on the stage, and which act it is in.
	 *
	 * <p>Sent to <b>participants only</b>, and only when the answer changes. It used to go to every
	 * player in the world carrying the zone's centre and radius, and the client decided for itself
	 * whether it was inside — which meant the tint and containment could disagree, and which handed
	 * every client the exact geometry of a wall it was supposed to have to find.
	 */
	private static void syncTint(BankaiStage stage, ServerLevel level) {
		MinecraftServer server = level.getServer();
		for (UUID id : stage.participantIds) {
			tell(server, id, stage.act, true);
		}
	}

	/**
	 * Clears the gloom for everyone the stage was holding, then drops them.
	 *
	 * <p>Called from every teardown path there is — revert, abort, conclusion. The tint is driven by
	 * edges, so a missed falling edge is permanent: a purple screen with nothing left in the world
	 * causing it and no way to clear it short of relogging. {@code PoisonDome} learned that one the
	 * hard way and this is the same lesson applied.
	 */
	private static void clearAllTints(BankaiStage stage, MinecraftServer server) {
		for (UUID id : stage.participantIds) {
			tell(server, id, ActState.CONCLUDED, false);
		}
		stage.participantIds.clear();
	}

	/** Sends one tint edge, if that UUID belongs to a player who is online. Silent otherwise. */
	private static void tell(MinecraftServer server, UUID id, ActState act, boolean active) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player != null) {
			ServerPlayNetworking.send(player, new KaromatsuSyncPayload(act.index, active));
		}
	}

	// ---- Telling people what is happening to them --------------------------------

	/**
	 * Everyone the performance is happening to, Shunsui included. Offline and non-player
	 * participants are simply absent — a mob does not need to be told which act it is in.
	 */
	private static List<ServerPlayer> audience(BankaiStage stage, ServerLevel level) {
		MinecraftServer server = level.getServer();
		List<ServerPlayer> out = new ArrayList<>();

		ServerPlayer caster = server.getPlayerList().getPlayer(stage.casterId);
		if (caster != null) {
			out.add(caster);
		}
		for (UUID id : stage.participantIds) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				out.add(player);
			}
		}
		return out;
	}

	/** One line in chat, where it stays long enough to read. Used for entry and for act changes. */
	private static void announce(BankaiStage stage, ServerLevel level, Component message) {
		for (ServerPlayer player : audience(stage, level)) {
			player.displayClientMessage(message, false);
		}
	}

	/**
	 * The running state, once a second, on the action bar.
	 *
	 * <p>The acts were already synced to the client — the gap was never plumbing, it was that
	 * nothing ever said out loud what act it was or how close the next one is. A four-stage sequence
	 * whose stages differ only in what colour the screen has gone is a sequence nobody can play
	 * around; this is the same treatment {@code DeathdealingTransform} gives its dose stack.
	 */
	private static void reportState(BankaiStage stage, ServerLevel level) {
		Component line = switch (stage.act) {
			case ACT_1 -> Component.literal(String.format(
					"Karamatsu Shinjū · Act I — wounds shared %d/%d",
					stage.exchangeCount, BleachTuning.SHUNSUI_ACT1_EXCHANGE_THRESHOLD));
			case ACT_2 -> Component.literal(String.format(
					"Karamatsu Shinjū · Act II — the rot, %ds left",
					Math.max(0, (BleachTuning.SHUNSUI_ACT2_DURATION_TICKS - stage.act2TicksActive) / 20)));
			case ACT_3 -> Component.literal(
					"Karamatsu Shinjū · Act III — the water rises. Do not run dry.");
			case FINAL_ACT -> Component.literal(String.format(
					"Karamatsu Shinjū · Final Act — the thread draws, %.1fs",
					Math.max(0, stage.finalActChargeTicks) / 20.0));
			default -> null;
		};

		if (line == null) {
			return;
		}
		for (ServerPlayer player : audience(stage, level)) {
			player.displayClientMessage(line, true);
		}
	}

	/**
	 * What each act is called and what it does to you, in one line.
	 *
	 * <p>The names are Karamatsu Shinjū's own acts rather than invented labels, which is both more
	 * faithful and more useful: a player who has seen "Dangyo no Fuchi" once knows what the blue
	 * screen means the next time.
	 */
	private static Component actAnnouncement(ActState act) {
		return switch (act) {
			case ACT_1 -> Component.literal(
					"First Act · Ittan Momen — every wound is shared, both ways.");
			case ACT_2 -> Component.literal(
					"Second Act · Zanki no Shitone — the wounds fester, and the rot deepens.");
			case ACT_3 -> Component.literal(
					"Third Act · Dangyo no Fuchi — the water closes over. Whoever runs dry first, loses.");
			case FINAL_ACT -> Component.literal(
					"Final Act · Itokiribasami Chizome no Shitone — the thread is drawn.");
			default -> Component.literal("Karamatsu Shinjū.");
		};
	}
}
