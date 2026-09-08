package com.bleach.mod.ability.kits;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.tuning.BleachTuning;

import org.joml.Vector3f;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * <b>Gift Bad Sonnenschein</b> — Schrift D's Vollständig · {@code BALANCE.md} §P.6.
 *
 * <p>Askin's release is a standing dome of poison, and this one is too: it is anchored where the
 * player released, <b>not</b> to the player. Walk out of it and you leave it behind. That is the
 * whole reason the letter plays differently from every other tier in the mod — the others are
 * stances that follow you, and this is terrain you make and then have to fight around.
 *
 * <p>Inside the dome, everything that is not the owner takes {@link BleachTuning#DOME_DAMAGE} of
 * bleach damage every {@link BleachTuning#DOME_DAMAGE_INTERVAL} ticks and gains a dose on the same
 * clock, which is what makes the dome an amplifier for {@link Doses} rather than a second, unrelated
 * power. Vanilla's Poison effect rides along purely as the on-screen tell: <b>Poison cannot kill</b>
 * — it stops at half a heart — so it can never be the damage itself.
 *
 * <p>One dome per player, replaced if they release again, and dropped on revert, death, logout or
 * dimension change through {@link #remove}.
 */
public final class PoisonDome {
	private PoisonDome() {
	}

	/** An anchored dome. Immutable — a new release replaces the entry rather than moving it. */
	private record Dome(ResourceKey<Level> dimension, Vec3 centre, UUID owner) {
	}

	private static final Map<UUID, Dome> ACTIVE = new ConcurrentHashMap<>();

	/** Anchors a dome at the player's feet, replacing any dome they already own. */
	public static void place(ServerPlayer player) {
		ACTIVE.put(player.getUUID(),
				new Dome(player.level().dimension(), player.position(), player.getUUID()));
	}

	/** Drops a player's dome. Idempotent — every revert path reaches it. */
	public static void remove(ServerPlayer player) {
		ACTIVE.remove(player.getUUID());
	}

	/** Whether the given player currently owns a dome. */
	public static boolean has(ServerPlayer player) {
		return ACTIVE.containsKey(player.getUUID());
	}

	/**
	 * Ticks every live dome: damage and doses on the damage clock, particles on their own slower one.
	 *
	 * <p>A dome whose owner has gone offline is dropped rather than left running. Persisting one past
	 * its owner would make it unremovable — nothing else knows it exists — and a permanent poison
	 * field nobody can switch off is the worst possible failure mode for this feature.
	 */
	public static void tickAll(MinecraftServer server) {
		if (ACTIVE.isEmpty()) {
			return;
		}

		int now = server.getTickCount();
		boolean damageTick = now % Math.max(1, BleachTuning.DOME_DAMAGE_INTERVAL) == 0;
		boolean drawTick = now % Math.max(1, BleachTuning.DOME_PARTICLE_INTERVAL) == 0;

		Iterator<Map.Entry<UUID, Dome>> entries = ACTIVE.entrySet().iterator();
		while (entries.hasNext()) {
			Dome dome = entries.next().getValue();

			ServerPlayer owner = server.getPlayerList().getPlayer(dome.owner());
			ServerLevel level = server.getLevel(dome.dimension());
			if (owner == null || level == null) {
				entries.remove();
				continue;
			}

			if (drawTick) {
				draw(level, dome);
			}
			if (damageTick) {
				soak(level, dome, owner, now);
			}
		}
	}

	/** Damage, dose and mark everything inside the dome except its owner. */
	private static void soak(ServerLevel level, Dome dome, ServerPlayer owner, int nowTick) {
		double radius = BleachTuning.DOME_RADIUS;
		AABB box = new AABB(dome.centre(), dome.centre()).inflate(radius);

		List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, box,
				candidate -> candidate.isAlive()
						&& !candidate.getUUID().equals(dome.owner())
						&& candidate.position().distanceTo(dome.centre()) <= radius);

		for (LivingEntity victim : inside) {
			Doses.add(victim, BleachTuning.DOME_DOSES_PER_TICK, nowTick);
			victim.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, owner),
					(float) BleachTuning.DOME_DAMAGE);

			// Presentation only — the damage above is the real effect. Poison's own damage is
			// incapable of killing, so it must never be relied on to.
			victim.addEffect(new MobEffectInstance(MobEffects.POISON,
					Math.max(1, BleachTuning.DOME_POISON_TICKS), 0, false, true, true));
		}
	}

	/**
	 * The shell. Points are scattered over the sphere's surface rather than traced in rings, because
	 * a ring pattern reads as a wireframe prop and a scatter reads as a volume of gas.
	 *
	 * <p>Uses the level's own RNG rather than a player's — the dome exists independently of anyone
	 * standing near it.
	 */
	private static void draw(ServerLevel level, Dome dome) {
		DustParticleOptions dust = new DustParticleOptions(
				colour(), (float) BleachTuning.DOME_PARTICLE_SCALE);

		int count = Math.max(0, BleachTuning.DOME_PARTICLES);
		double radius = BleachTuning.DOME_RADIUS;

		for (int i = 0; i < count; i++) {
			// Uniform on a sphere: z uniform in [-1,1], angle uniform in [0,2pi).
			double z = level.random.nextDouble() * 2.0 - 1.0;
			double angle = level.random.nextDouble() * Math.PI * 2.0;
			double ring = Math.sqrt(Math.max(0.0, 1.0 - z * z));

			level.sendParticles(dust,
					dome.centre().x + Math.cos(angle) * ring * radius,
					dome.centre().y + z * radius,
					dome.centre().z + Math.sin(angle) * ring * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static Vector3f colour() {
		int rgb = BleachTuning.DOME_COLOR;
		return new Vector3f(((rgb >> 16) & 0xFF) / 255.0f,
				((rgb >> 8) & 0xFF) / 255.0f,
				(rgb & 0xFF) / 255.0f);
	}
}
