package com.bleach.mod.ability.kits;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.network.EnmaKorogiSyncPayload;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Enma Kōrogi Dome Manager · Kaname Tōsen's Bankai.
 *
 * <p>Tracks active sensory deprivation domes, syncs them to clients, generates boundary particle
 * rings, and strips the senses of every mob caught inside — see {@link #depriveMobs}, which is the
 * server-side counterpart to the client blackout players get.
 */
public final class EnmaKorogiManager {
	private EnmaKorogiManager() {
	}

	public record Dome(UUID casterId, Vec3 center, double radius, ResourceKey<Level> dimension) {
		public boolean contains(Vec3 pos, ResourceKey<Level> dim) {
			return this.dimension.equals(dim) && pos.distanceToSqr(center) <= radius * radius;
		}
	}

	private static final Map<UUID, Dome> ACTIVE_DOMES = new ConcurrentHashMap<>();

	public static void startDome(ServerPlayer caster, double radius) {
		Dome dome = new Dome(caster.getUUID(), caster.position(), radius, caster.serverLevel().dimension());
		ACTIVE_DOMES.put(caster.getUUID(), dome);

		EnmaKorogiSyncPayload payload = new EnmaKorogiSyncPayload(
				caster.getUUID(), dome.center().x, dome.center().y, dome.center().z, (float) radius, true);

		for (ServerPlayer player : PlayerLookup.world(caster.serverLevel())) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	public static void endDome(ServerPlayer caster) {
		Dome dome = ACTIVE_DOMES.remove(caster.getUUID());
		if (dome != null) {
			EnmaKorogiSyncPayload payload = new EnmaKorogiSyncPayload(
					caster.getUUID(), 0, 0, 0, 0, false);

			for (ServerPlayer player : PlayerLookup.world(caster.serverLevel())) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	public static void tickDome(ServerPlayer caster) {
		Dome dome = ACTIVE_DOMES.get(caster.getUUID());
		if (dome == null) {
			return;
		}

		ServerLevel level = caster.serverLevel();
		Vec3 center = dome.center();
		double r = dome.radius();

		depriveMobs(level, center, r);

		// Spawn boundary perimeter ink particles
		int count = 60;
		for (int i = 0; i < count; i++) {
			double phi = Math.acos(1.0 - 2.0 * (i + 0.5) / count);
			double theta = Math.PI * (1.0 + Math.sqrt(5.0)) * i;
			double x = center.x + r * Math.sin(phi) * Math.cos(theta);
			double y = center.y + r * Math.cos(phi);
			double z = center.z + r * Math.sin(phi) * Math.sin(theta);
			level.sendParticles(ParticleTypes.SQUID_INK, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * The mobs' half of the deprivation.
	 *
	 * <p>For a player the dome is entirely client-side: the client stops rendering and stops
	 * hearing, and the server does not have to take anything away because the information never
	 * reaches a screen. A mob has no screen, so its senses have to be removed where they actually
	 * live — in the AI. Blindness for the look of it, and a target lock cleared every tick for the
	 * substance: inside Enma Kōrogi a mob cannot find anything, cannot keep hold of anything it had
	 * already found, and cannot be provoked into remembering.
	 *
	 * <p>Cleared every tick rather than on an interval the way Shinji re-points his. Shinji is
	 * <em>reassigning</em> targets and needs to leave the mob long enough to reach one; this is
	 * denying targets outright, and a gap between sweeps is a gap in which the dome does not work.
	 *
	 * <p>Anchored on the dome's own centre, not the caster: the dome is planted where it was cast
	 * and the caster is free to walk out of it. Anything alive inside is deprived, hostile or not
	 * — the dome does not ask, and neither does {@link #isDeprived} for players.
	 */
	private static void depriveMobs(ServerLevel level, Vec3 center, double radius) {
		double radiusSq = radius * radius;
		AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
				center.x + radius, center.y + radius, center.z + radius);

		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box,
				mob -> mob.isAlive() && mob.position().distanceToSqr(center) <= radiusSq);

		for (Mob mob : mobs) {
			mob.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
					BleachTuning.TOSEN_BANKAI_MOB_BLIND_TICKS, 0, false, false, true));
			mob.setTarget(null);
			mob.setLastHurtByMob(null);
		}
	}

	/**
	 * Whether this player is inside someone else's dome and therefore stripped of their senses.
	 *
	 * <p>The server-side twin of {@code ClientEnmaKorogiState#isAffected}, and deliberately the same
	 * shape: inside the sphere, in the same dimension, and not the caster. The caster keeps every
	 * sense — that asymmetry is the ability.
	 *
	 * <p>Spectators are excluded because they are not in the fight at all, which is the same call
	 * every other check in the mod makes about them.
	 */
	public static boolean isDeprived(ServerPlayer player) {
		if (ACTIVE_DOMES.isEmpty() || player.isSpectator()) {
			return false;
		}

		Vec3 position = player.position();
		ResourceKey<Level> dimension = player.serverLevel().dimension();
		UUID id = player.getUUID();

		for (Dome dome : ACTIVE_DOMES.values()) {
			if (!dome.casterId().equals(id) && dome.contains(position, dimension)) {
				return true;
			}
		}
		return false;
	}

	public static void syncAllTo(ServerPlayer player) {
		for (Dome dome : ACTIVE_DOMES.values()) {
			ServerPlayNetworking.send(player, new EnmaKorogiSyncPayload(
					dome.casterId(), dome.center().x, dome.center().y, dome.center().z, (float) dome.radius(), true));
		}
	}
}
