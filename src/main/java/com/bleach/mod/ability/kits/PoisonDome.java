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

	/**
	 * Damage, dose and mark everything inside the dome except its owner.
	 *
	 * <p>The volume is a <b>hemisphere</b>, matching what {@link #draw} puts on screen: horizontal
	 * distance within the radius, and height between the ground and the crown. It used to be a full
	 * sphere, which meant the lower half of the effect sat underground where nothing was drawn — a
	 * mob standing in a cave below the caster took damage from a dome it could not see, and the
	 * shell the player *could* see was not the shape that actually hurt.
	 */
	private static boolean inside(Dome dome, LivingEntity candidate, double radius) {
		double dx = candidate.getX() - dome.centre().x;
		double dz = candidate.getZ() - dome.centre().z;
		double dy = candidate.getY() - dome.centre().y;
		if (dy < 0.0 || dy > radius) {
			return false;
		}
		// Ellipsoid test rather than a cylinder, so the volume narrows toward the crown the way the
		// drawn shell does.
		double horizontal = dx * dx + dz * dz;
		double allowed = radius * radius - dy * dy;
		return horizontal <= allowed;
	}

	private static void soak(ServerLevel level, Dome dome, ServerPlayer owner, int nowTick) {
		double radius = BleachTuning.DOME_RADIUS;
		AABB box = new AABB(dome.centre(), dome.centre()).inflate(radius);

		List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, box,
				candidate -> candidate.isAlive()
						&& !candidate.getUUID().equals(dome.owner())
						&& inside(dome, candidate, radius));

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
	 * The shell — a <b>hemisphere</b> traced as latitude rings, plus a heavier ring on the ground.
	 *
	 * <h2>Two things this used to get wrong</h2>
	 *
	 * <p><b>It drew a full sphere centred on the player's feet, so half of it was underground.</b>
	 * A dome sits <em>on</em> the ground; only the upper half was ever going to be visible, and
	 * spending half the particle budget below the floor is what made it look like a thin scatter
	 * rather than a shell.
	 *
	 * <p><b>And it scattered points at random.</b> The class note used to claim a scatter reads as a
	 * volume of gas while rings read as a wireframe prop — which is true at high density and false at
	 * the density this can afford. Forty random points over the ~615 m² surface of a 7-block sphere
	 * is not a gas, it is noise. Rings give the eye a continuous edge to follow, so the same budget
	 * reads as a surface. Points are spaced by arc length rather than split evenly per ring, so the
	 * shell stays even instead of bunching at the top.
	 */
	private static void draw(ServerLevel level, Dome dome) {
		DustParticleOptions dust = new DustParticleOptions(
				colour(), (float) BleachTuning.DOME_PARTICLE_SCALE);

		double radius = BleachTuning.DOME_RADIUS;
		int rings = Math.max(1, BleachTuning.DOME_RINGS);
		double spacing = Math.max(0.1, BleachTuning.DOME_POINT_SPACING);

		for (int r = 0; r < rings; r++) {
			// Latitude from the ground (0) to the crown (90 degrees).
			double lat = (r / (double) rings) * (Math.PI / 2.0);
			double ringRadius = Math.cos(lat) * radius;
			double height = Math.sin(lat) * radius;

			// Arc-length spacing, so a wide ring gets proportionally more points than a narrow one.
			int points = (int) Math.max(4, Math.ceil(2.0 * Math.PI * ringRadius / spacing));

			for (int i = 0; i < points; i++) {
				double angle = (i / (double) points) * Math.PI * 2.0;
				level.sendParticles(dust,
						dome.centre().x + Math.cos(angle) * ringRadius,
						dome.centre().y + height,
						dome.centre().z + Math.sin(angle) * ringRadius,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		drawFootprint(level, dome, dust, radius, spacing);
	}

	/**
	 * The ground ring, at double density. It is the only part of the shell a player standing inside
	 * can always see, so it is what actually tells them where the edge is.
	 */
	private static void drawFootprint(ServerLevel level, Dome dome, DustParticleOptions dust,
			double radius, double spacing) {
		int points = (int) Math.max(8, Math.ceil(2.0 * Math.PI * radius / (spacing * 0.5)));
		for (int i = 0; i < points; i++) {
			double angle = (i / (double) points) * Math.PI * 2.0;
			level.sendParticles(dust,
					dome.centre().x + Math.cos(angle) * radius,
					dome.centre().y + 0.1,
					dome.centre().z + Math.sin(angle) * radius,
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
