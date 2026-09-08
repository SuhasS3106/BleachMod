package com.bleach.mod.ability.kits;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
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
 * Non-caster entities inside the zone cannot leave: each tick their outward velocity is zeroed
 * if they would breach the boundary.
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

		/** All participant entity UUIDs — everyone inside the zone. */
		final Set<UUID> participantIds = new HashSet<>();
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

		boolean contains(Vec3 pos, ResourceKey<Level> dim) {
			return dimension.equals(dim) && pos.distanceToSqr(center) <= radius * radius;
		}
	}

	/** Active Bankai stages keyed by caster UUID. */
	private static final Map<UUID, BankaiStage> STAGES = new ConcurrentHashMap<>();

	// ---- Lifecycle ---------------------------------------------------------------

	/**
	 * Called from {@code ShunsuiTransform.Bankai#onEnter}. Plants the zone, scans for initial
	 * participants, advances to ACT_1 immediately (PRE_ACT is just the entry frame), and syncs.
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

		// Collect initial participants
		double radiusSq = radius * radius;
		List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class,
				shunsui.getBoundingBox().inflate(radius),
				e -> e != shunsui && e.isAlive() && !e.isSpectator()
						&& e.position().distanceToSqr(center) <= radiusSq);
		for (LivingEntity e : inside) {
			stage.participantIds.add(e.getUUID());
		}

		// Advance to ACT_1 immediately
		stage.act = ActState.ACT_1;
		sendSync(shunsui, stage, level);

		level.playSound(null, center.x, center.y, center.z,
				SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.9f, 0.55f);
	}

	/**
	 * Called from {@code ShunsuiTransform.Bankai#onRevert} and from {@link #abortSequence}.
	 * Cleans up all state and notifies clients. Idempotent.
	 */
	public static void endBankai(ServerPlayer shunsui) {
		BankaiStage stage = STAGES.remove(shunsui.getUUID());
		if (stage == null) {
			return;
		}
		sendClear(shunsui, stage, shunsui.serverLevel());
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

		ServerLevel level = shunsui.serverLevel();
		MinecraftServer server = shunsui.server;

		// 1. Check target validity — abort if everyone is gone
		if (!checkTargetValidity(stage, level, server)) {
			abortSequence(shunsui, stage, level);
			return;
		}

		// 2. Containment — push participants back if they approach the boundary
		tickContainment(stage, level, server);

		// 3. Zone boundary ring — visible to all, no filter
		tickBoundaryParticles(stage, level);

		// 4. Act-specific logic
		switch (stage.act) {
			case ACT_1     -> tickAct1(shunsui, stage, level);
			case ACT_2     -> tickAct2(shunsui, stage, level);
			case ACT_3     -> tickAct3(shunsui, stage, level, server);
			case FINAL_ACT -> tickFinalAct(shunsui, stage, level, server);
			default        -> {} // PRE_ACT, CONCLUDED — nothing
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

	private static void tickAct2(ServerPlayer shunsui, BankaiStage stage, ServerLevel level) {
		stage.act2TicksActive++;

		// Bleed growth: every 5 seconds (100 ticks)
		stage.act2TicksSinceGrowth++;
		if (stage.act2TicksSinceGrowth >= 100) {
			stage.currentBleedDps *= BleachTuning.SHUNSUI_ACT2_BLEED_GROWTH;
			stage.act2TicksSinceGrowth = 0;
		}

		// Apply bleed tick to each target participant
		float bleedThisTick = (float) (stage.currentBleedDps / BleachTuning.TICKS_PER_SECOND);
		for (UUID targetId : stage.participantIds) {
			LivingEntity target = (LivingEntity) level.getEntity(targetId);
			if (target == null || !target.isAlive()) {
				continue;
			}
			target.invulnerableTime = 0;
			target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE_BLEED, shunsui),
					bleedThisTick);

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

		// Drain Shunsui — replaces DRAIN_BANKAI (gated in SpiritualTicker.tickTransformed)
		shunsuiData.spend(BleachTuning.SHUNSUI_ACT3_SP_DRAIN_PER_SEC / BleachTuning.TICKS_PER_SECOND);
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
			stage.act = ActState.CONCLUDED;
			sendSync(shunsui, stage, level);
			shunsui.displayClientMessage(
					Component.literal("The petals... fall only for me."), true);
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
		stage.act = ActState.CONCLUDED;
		sendSync(shunsui, stage, level);
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
		sendClear(shunsui, stage, level);
		shunsui.displayClientMessage(Component.literal("The stage has collapsed."), true);
		// No per-target Slowness to remove: participants are already gone.
		// Bleed stops because tickAct2 won't be called again.
		// mirrorGuard is already clear (abort is called from the top of tickBankai, not from onSharedDamage).
	}

	// ---- Act Advancement --------------------------------------------------------

	private static void advanceTo(ActState next, ServerPlayer shunsui,
			BankaiStage stage, ServerLevel level) {
		stage.act = next;
		sendSync(shunsui, stage, level);

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
	 * Zeroes the outward velocity of any non-caster participant who is approaching or crossing
	 * the boundary. Runs every tick so it acts like a wall rather than a ceiling.
	 */
	private static void tickContainment(BankaiStage stage, ServerLevel level, MinecraftServer server) {
		double containRadius = stage.radius - BleachTuning.SHUNSUI_BANKAI_CONTAIN_MARGIN;

		for (UUID id : stage.participantIds) {
			LivingEntity entity = (LivingEntity) level.getEntity(id);
			if (entity == null || !entity.isAlive()) {
				continue;
			}

			Vec3 pos = entity.position();
			Vec3 toCenter = stage.center.subtract(pos);
			double dist = toCenter.length();
			if (dist < 1.0e-4) {
				continue;
			}

			if (dist >= containRadius) {
				// Entity is at or beyond the containment shell.
				// Zero the outward component of velocity (the component pointing away from center).
				Vec3 outward = pos.subtract(stage.center).normalize();
				Vec3 vel = entity.getDeltaMovement();
				double outwardComponent = vel.dot(outward);
				if (outwardComponent > 0) {
					// Has velocity pointing away from center — cancel that component.
					Vec3 corrected = vel.subtract(outward.scale(outwardComponent));
					entity.setDeltaMovement(corrected);
				}
			}
		}
	}

	// ---- Boundary Particles -----------------------------------------------------

	private static void tickBoundaryParticles(BankaiStage stage, ServerLevel level) {
		int count = BleachTuning.SHUNSUI_BANKAI_ZONE_RING_PARTICLES;
		double r = stage.radius;
		Vec3 center = stage.center;

		for (int i = 0; i < count; i++) {
			double phi   = Math.acos(1.0 - 2.0 * (i + 0.5) / count);
			double theta = Math.PI * (1.0 + Math.sqrt(5.0)) * i;
			double x = center.x + r * Math.sin(phi) * Math.cos(theta);
			double y = center.y + r * Math.cos(phi);
			double z = center.z + r * Math.sin(phi) * Math.sin(theta);
			level.sendParticles(new PressureParticleOptions(BleachTuning.KIT_SHUNSUI_PARTICLE_COLOR, 0.8f),
					x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
		}
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

	/**
	 * Syncs current zone state to a newly joined player (called from
	 * {@code SpiritualTicker}'s join handler if needed, same pattern as {@code EnmaKorogiManager}).
	 */
	public static void syncAllTo(ServerPlayer player) {
		for (BankaiStage stage : STAGES.values()) {
			ServerPlayNetworking.send(player, new KaromatsuSyncPayload(
					stage.casterId,
					stage.center.x, stage.center.y, stage.center.z,
					(float) stage.radius,
					stage.act.index,
					true));
		}
	}

	// ---- Sync Helpers -----------------------------------------------------------

	private static void sendSync(ServerPlayer caster, BankaiStage stage, ServerLevel level) {
		KaromatsuSyncPayload payload = new KaromatsuSyncPayload(
				stage.casterId,
				stage.center.x, stage.center.y, stage.center.z,
				(float) stage.radius,
				stage.act.index,
				true);
		for (ServerPlayer player : PlayerLookup.world(level)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void sendClear(ServerPlayer caster, BankaiStage stage, ServerLevel level) {
		KaromatsuSyncPayload payload = new KaromatsuSyncPayload(
				stage.casterId, 0, 0, 0, 0, (byte) -1, false);
		for (ServerPlayer player : PlayerLookup.world(level)) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
