package com.bleach.mod.ability.kits;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.item.Zanpakuto;
import com.bleach.mod.item.ZanpakutoItem;
import com.bleach.mod.network.GinBeamPayload;
import com.bleach.mod.network.GinBeamStatePayload;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Gin Ichimaru's released states · Shinsō & Kamishini no Yari.
 */
public final class GinTransform {
	private GinTransform() {
	}

	public static final ResourceLocation SHIKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "gin/shikai");
	public static final ResourceLocation BANKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "gin/bankai");

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	private static final Map<UUID, Integer> lastShikaiSwingTick = new ConcurrentHashMap<>();
	private static final Map<UUID, Map<UUID, Integer>> lastEntitySliceTick = new ConcurrentHashMap<>();

	/**
	 * Gin's Shikai single long-range piercing attack.
	 *
	 * <p>Charged at {@link BleachTuning#GIN_SHIKAI_SP_COST} per thrust, on top of the passive
	 * {@link BleachTuning#DRAIN_SHIKAI}. The passive rate pays for <em>being</em> in Shikai; a
	 * 55-block instant hit off the attack key has to pay for itself, or the release is a free
	 * ranged weapon with a one-second cooldown and no resource attached to it at all.
	 *
	 * <p>The cost is taken before anything else happens, and the swing is refused outright when it
	 * cannot be paid — no sound, no particles, no hit. Charging on a swing that did nothing would
	 * make an unaffordable thrust indistinguishable from an affordable one that missed.
	 *
	 * <p>Charged on <b>every</b> swing, including one that also lands as ordinary melee. Shinsō is
	 * not a finisher you mix into a normal combo — it is meant to be punishing enough that swinging
	 * at all is a decision, and exempting anything within arm's reach would give back a free version
	 * of the same ability.
	 */
	public static void onShikaiSwing(ServerPlayer player) {
		SpiritualData data = BleachAttachments.get(player);

		ServerLevel level = player.serverLevel();
		int currentTick = player.tickCount;
		Integer lastTick = lastShikaiSwingTick.get(player.getUUID());
		if (lastTick != null && currentTick - lastTick < BleachTuning.GIN_SHIKAI_COOLDOWN_TICKS) {
			return;
		}

		double cost = BleachTuning.GIN_SHIKAI_SP_COST;
		if (data.sp < cost) {
			player.displayClientMessage(Component.literal(
					String.format(Locale.ROOT, "Not enough spiritual pressure — Shinsō needs %.0f SP.", cost)),
					true);
			return;
		}

		// spend() pauses regen as well as deducting, so a held attack key cannot tick the bar back
		// up between thrusts. The sync is forced because the pool moved outside the ticker's own
		// pass and the bar would otherwise lag the swing by up to a keepalive.
		data.spend(cost);
		SpiritualTicker.sync(player, true);

		lastShikaiSwingTick.put(player.getUUID(), currentTick);

		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double range = BleachTuning.GIN_SHIKAI_RANGE; // 55.0
		Vec3 end = eye.add(look.scale(range));

		// Piercing thrust sound
		level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1.8f, 1.9f);
		level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.5f, 1.2f);

		// Particle line
		for (double d = 1.0; d <= range; d += 1.5) {
			Vec3 p = eye.add(look.scale(d));
			level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.02);
			level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}

		// Entity hit test along ray
		AABB rayBox = new AABB(eye, end).inflate(1.2);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, player, eye, end, rayBox,
				e -> e.isAlive() && e != player && !e.isSpectator());

		if (hit != null && hit.getEntity() instanceof LivingEntity target) {
			target.invulnerableTime = 0;
			target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, player), (float) BleachTuning.GIN_SHIKAI_DMG);
			Vec3 knockback = look.scale(1.5).add(0, 0.2, 0);
			target.setDeltaMovement(target.getDeltaMovement().add(knockback));
			target.hurtMarked = true;
		}
	}

	/** Casters whose beam the clients around them currently believe is extended. */
	private static final Map<UUID, Integer> beamKeepaliveTick = new ConcurrentHashMap<>();

	/**
	 * How often an active beam is reasserted to nearby clients, in ticks.
	 *
	 * <p>Edges alone leave two holes: a viewer who walks into range mid-hold never saw the start,
	 * and a caster who dies or disconnects mid-hold may never send the stop. Repeating the "on"
	 * edge closes the first and lets the client's own expiry close the second.
	 */
	private static final int BEAM_KEEPALIVE_TICKS = 10;

	/**
	 * Server receiver for Gin's Bankai beam: damage, and the on/off state clients render from.
	 *
	 * <p><b>No beam geometry is sent from here.</b> The shape is entirely determined by where the
	 * caster is and which way they are looking, both of which every client tracking them already
	 * knows, so all that goes out is one flag — see {@link GinBeamStatePayload}. Spawning it as
	 * particles cost ~115 packets per viewer per tick; this costs two per hold plus a keepalive.
	 */
	public static void handleBeam(ServerPlayer player, GinBeamPayload payload) {
		ItemStack held = player.getMainHandItem();
		boolean canBeam = held.getItem() instanceof ZanpakutoItem blade
				&& BleachKits.GIN.equals(blade.kitId())
				&& BleachAttachments.get(player).state == SpiritualData.STATE_BANKAI;

		if (!payload.active() || !canBeam) {
			// Handles the release edge and every way the beam can become illegal mid-hold — sheathing,
			// reverting, or a client that keeps sending after either.
			endBeam(player);
			return;
		}

		SpiritualData data = BleachAttachments.get(player);
		broadcastBeamState(player, true);

		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double range = BleachTuning.GIN_BANKAI_BEAM_RANGE; // 70.0
		Vec3 end = eye.add(look.scale(range));


		// Only deal damage if mouse velocity is substantial (fast swipe)
		if (payload.angularSpeed() >= BleachTuning.GIN_MIN_SWIPE_SPEED) {
			AABB beamBox = new AABB(eye, end).inflate(1.5);
			List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, beamBox,
					e -> e.isAlive() && e != player && !e.isSpectator());

			Map<UUID, Integer> entityHitMap = lastEntitySliceTick.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
			int currentTick = player.tickCount;

			for (LivingEntity target : targets) {
				Vec3 toTarget = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(eye);
				double dot = toTarget.dot(look);
				if (dot > 0 && dot <= range) {
					double perpSq = toTarget.lengthSqr() - dot * dot;
					if (perpSq <= 2.25) { // within 1.5 blocks of the beam axis
						Integer lastHit = entityHitMap.get(target.getUUID());
						if (lastHit == null || currentTick - lastHit >= BleachTuning.GIN_BANKAI_ENTITY_SLICE_COOLDOWN_TICKS) {
							entityHitMap.put(target.getUUID(), currentTick);
							target.invulnerableTime = 0;
							target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, player),
									(float) BleachTuning.GIN_BANKAI_BEAM_DMG);
							target.hurtMarked = true;

							level.playSound(null, target.getX(), target.getY(), target.getZ(),
									SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.5f, 1.6f);
						}
					}
				}
			}
		}
	}

	/**
	 * Tell nearby clients the beam is on, on the start edge and on the keepalive beat.
	 *
	 * <p>{@code PlayerLookup.tracking} rather than a radius scan: the set of players who can see the
	 * beam is exactly the set already tracking the caster's entity, and a client that is not
	 * tracking him could not resolve the entity id to draw from anyway.
	 */
	private static void broadcastBeamState(ServerPlayer caster, boolean active) {
		Integer last = beamKeepaliveTick.get(caster.getUUID());
		if (active && last != null && caster.tickCount - last < BEAM_KEEPALIVE_TICKS) {
			return;
		}

		if (active) {
			beamKeepaliveTick.put(caster.getUUID(), caster.tickCount);
		} else if (last == null) {
			// Never announced, so there is nothing to retract.
			return;
		} else {
			beamKeepaliveTick.remove(caster.getUUID());
		}

		GinBeamStatePayload state = new GinBeamStatePayload(caster.getId(), active);
		for (ServerPlayer viewer : PlayerLookup.tracking(caster)) {
			ServerPlayNetworking.send(viewer, state);
		}
	}

	/**
	 * Retract the beam. Safe to call for a player who never had one, and safe to call twice — which
	 * it has to be, because it is reached from the release edge, from reverting Bankai and from
	 * logging out, none of which know about each other.
	 */
	public static void endBeam(ServerPlayer caster) {
		broadcastBeamState(caster, false);
	}

	// --- Shikai & Bankai TransformAbility ----------------------------------------------

	private static final class Shikai implements TransformAbility {
		@Override
		public ResourceLocation id() {
			return SHIKAI_ID;
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_SHIKAI;
		}

		@Override
		public double drainPerSecond() {
			return BleachTuning.DRAIN_SHIKAI;
		}

		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_SHIKAI, soulLevel);
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
		}
	}

	private static final class Bankai implements TransformAbility {
		@Override
		public ResourceLocation id() {
			return BANKAI_ID;
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_BANKAI;
		}

		@Override
		public double drainPerSecond() {
			return BleachTuning.DRAIN_BANKAI;
		}

		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_BANKAI, soulLevel);
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			lastEntitySliceTick.remove(player.getUUID());

			// Reverting out of Bankai mid-hold: the client will stop asking, but nobody would ever
			// have told the watchers, so retract it here too. forceRevert reaches this from manual
			// toggle, SP exhaustion, death, dimension change, logout and sheathing alike.
			endBeam(player);
		}
	}
}
