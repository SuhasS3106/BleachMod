package com.bleach.mod.ability.kits;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.network.DomeTintPayload;
import com.bleach.mod.tuning.BleachTuning;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
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

	/**
	 * An anchored dome and, crucially, <b>who is inside it</b>.
	 *
	 * <p>The membership set is what makes the wall solid in both directions. Containment cannot be
	 * decided from position alone: an entity exactly on the boundary is neither in nor out, and one
	 * that teleports across would be silently reclassified rather than stopped. Tracking who was
	 * inside last tick means someone inside stays inside and someone outside stays outside, which is
	 * what <i>Gift Bereich</i> — the cage — actually does.
	 */
	private static final class Dome {
		private final ResourceKey<Level> dimension;
		private final Vec3 centre;
		private final UUID owner;
		private final Set<UUID> members = ConcurrentHashMap.newKeySet();

		private Dome(ResourceKey<Level> dimension, Vec3 centre, UUID owner) {
			this.dimension = dimension;
			this.centre = centre;
			this.owner = owner;
		}

		private ResourceKey<Level> dimension() {
			return dimension;
		}

		private Vec3 centre() {
			return centre;
		}

		private UUID owner() {
			return owner;
		}
	}

	private static final Map<UUID, Dome> ACTIVE = new ConcurrentHashMap<>();

	/**
	 * Anchors a dome at the player's feet, replacing any dome they already own, and seals in
	 * everything that is standing inside it at that moment — the caster included.
	 *
	 * <p>Sealing at placement rather than letting membership settle on the first tick matters: it is
	 * the difference between "the cage closes around whoever is here" and "the cage closes around
	 * whoever happens to be here one tick later", and the latter lets a fast target walk out of a
	 * dome that has visibly already formed.
	 */
	public static void place(ServerPlayer player) {
		Dome dome = new Dome(player.level().dimension(), player.position(), player.getUUID());
		ACTIVE.put(player.getUUID(), dome);

		if (player.level() instanceof ServerLevel level) {
			double radius = BleachTuning.DOME_RADIUS;
			for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
					new AABB(dome.centre(), dome.centre()).inflate(radius),
					entity -> entity.isAlive() && inside(dome, entity, radius))) {
				dome.members.add(candidate.getUUID());
				tell(candidate, true);
			}
		}
	}

	/**
	 * Drops a player's dome. Idempotent — every revert path reaches it.
	 *
	 * <p>Everyone it was holding is told the tint is over. Without this a player who was inside a
	 * dome that vanished keeps a purple screen with nothing causing it, and no way to clear it short
	 * of relogging: the tint is driven by edges, so a missed falling edge is permanent.
	 */
	public static void remove(ServerPlayer player) {
		Dome dome = ACTIVE.remove(player.getUUID());
		if (dome != null) {
			clearAllTints(player.server, dome);
		}
	}

	/** Clears the purple wash for every player a dome was holding. Safe to call more than once. */
	private static void clearAllTints(MinecraftServer server, Dome dome) {
		for (UUID id : dome.members) {
			ServerPlayer held = server.getPlayerList().getPlayer(id);
			if (held != null) {
				tell(held, false);
			}
		}
		dome.members.clear();
	}

	/** Tells a player whether their screen should be washed purple. Silent for non-players. */
	private static void tell(LivingEntity entity, boolean inside) {
		if (entity instanceof ServerPlayer player) {
			ServerPlayNetworking.send(player, new DomeTintPayload(inside));
		}
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
				// Clear every held player's tint before dropping the dome. This path fires when the
				// caster logs out, and it is the one teardown that does not go through remove() — so
				// without this the people they had caged keep a purple screen with nothing left in
				// the world causing it.
				clearAllTints(server, dome);
				entries.remove();
				continue;
			}

			// Every tick, without exception. A wall checked on the damage clock is a wall anything
			// faster than one block per second walks straight through.
			contain(level, dome);

			if (drawTick) {
				draw(level, dome, now);
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

		// Height above the anchor, floored at zero. Anything BELOW the anchor plane is still under
		// the dome — standing one block downhill, in a dip, or in a cave beneath it — and must count
		// as inside.
		//
		// Treating below-anchor as outside was a real bug and a nasty one: a contained player who
		// stepped down a single block became "outside" while still a member, so the cage pushed them
		// "back in" to radius minus the margin — 35 blocks away — and then did it again every tick.
		// The symptom was being frozen in place a few steps from where you released.
		double dy = Math.max(0.0, candidate.getY() - dome.centre().y);
		if (dy > radius) {
			return false;
		}

		// Ellipsoid test rather than a cylinder, so the volume narrows toward the crown the way the
		// drawn shell does.
		double horizontal = dx * dx + dz * dz;
		double allowed = radius * radius - dy * dy;
		return horizontal <= allowed;
	}

	/**
	 * <b>Gift Bereich — the cage.</b> Nothing crosses the shell in either direction, the caster
	 * included.
	 *
	 * <p>Membership decides which way each entity is held, so the same wall keeps prisoners in and
	 * keeps rescuers out. An entity is pushed back to just inside its own side of the boundary and
	 * has its outward velocity cancelled — cancelling the velocity is what stops a contained player
	 * from juddering against the wall every tick while their input keeps re-applying it.
	 *
	 * <p>The floor is not part of the wall. Below the anchor is simply outside, so a contained player
	 * who digs down is stopped by the shell's lower edge like anything else, and a dome placed on a
	 * cliff does not trap things in the air beneath it.
	 */
	private static void contain(ServerLevel level, Dome dome) {
		double radius = BleachTuning.DOME_RADIUS;
		double margin = BleachTuning.DOME_WALL_MARGIN;

		holdMembers(level, dome, radius, margin);
		keepOutsidersOut(level, dome, radius, margin);
	}

	/**
	 * Pulls every member back inside.
	 *
	 * <p><b>Members are resolved by UUID, not found by searching a box around the dome.</b> The box
	 * version had a hole big enough to walk a Schrift through: it reached
	 * {@code radius + margin + 2}, about 38 blocks, while a Flash Step covers up to
	 * {@code FS_RANGE_BASE + FS_RANGE_SP_TERM} times Vollständig's multiplier — comfortably past it.
	 * A player who blinked that far was never examined at all, so they were neither pushed back nor
	 * released, which is exactly one bug producing two symptoms: Flash Step escaped the cage, and
	 * the purple tint never cleared because the falling edge that clears it is sent from here.
	 *
	 * <p>Iterating the membership set costs nothing by comparison — it holds only what was sealed in
	 * — and it cannot miss anyone however far they have gone.
	 */
	private static void holdMembers(ServerLevel level, Dome dome, double radius, double margin) {
		for (UUID id : dome.members) {
			Entity found = level.getEntity(id);

			// Gone, dead, or in another dimension. Release rather than hold a member nothing can
			// reach, and clear their tint on the way out.
			if (!(found instanceof LivingEntity entity) || !entity.isAlive()) {
				release(dome, id, level.getServer().getPlayerList().getPlayer(id));
				continue;
			}

			if (inside(dome, entity, radius)) {
				continue;
			}

			// Safety net for genuine displacement — a command teleport, a portal, a bug in the
			// containment test itself. Being wrongly freed is cosmetic; being wrongly pinned ends
			// the play session, which is what the below-anchor bug did. The threshold sits well past
			// any Flash Step so blinking at the wall is caught rather than rewarded.
			if (distanceFrom(dome, entity) > radius * BleachTuning.DOME_RELEASE_FACTOR) {
				release(dome, id, entity);
				continue;
			}

			push(entity, dome, radius, -margin);
		}
	}

	/** Stops anything that was not sealed in from getting in. */
	private static void keepOutsidersOut(ServerLevel level, Dome dome, double radius, double margin) {
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(dome.centre(), dome.centre()).inflate(radius + margin + 2.0),
				LivingEntity::isAlive)) {

			if (dome.members.contains(entity.getUUID()) || !inside(dome, entity, radius)) {
				continue;
			}
			push(entity, dome, radius, margin);
		}
	}

	/** Drops one member and clears its tint. {@code entity} may be null if it could not be resolved. */
	private static void release(Dome dome, UUID id, @Nullable Entity entity) {
		dome.members.remove(id);
		if (entity instanceof LivingEntity living) {
			tell(living, false);
		}
	}

	/** Straight-line distance from the anchor, with below-anchor treated as level with it. */
	private static double distanceFrom(Dome dome, LivingEntity entity) {
		double dx = entity.getX() - dome.centre().x;
		double dz = entity.getZ() - dome.centre().z;
		double dy = Math.max(0.0, entity.getY() - dome.centre().y);
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Moves an entity back to its own side of the shell and kills the component of its velocity that
	 * carried it across.
	 */
	private static void push(LivingEntity entity, Dome dome, double radius, double offset) {
		double dx = entity.getX() - dome.centre().x;
		double dz = entity.getZ() - dome.centre().z;
		double dy = Math.max(0.0, entity.getY() - dome.centre().y);

		// Above the crown: the only case that is genuinely a vertical correction. Handled first so
		// the horizontal path below never has to reason about it.
		if (dy > radius) {
			place(entity, entity.getX(), dome.centre().y + radius + offset, entity.getZ());
			damp(entity, new Vec3(0.0, 1.0, 0.0));
			return;
		}

		// Everything else is corrected in the horizontal plane only, and the entity keeps its own Y.
		// The previous version rescaled all three axes at once, which teleported anyone standing
		// below the anchor up to the anchor's height as a side effect of a sideways correction.
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 1.0e-4) {
			return;
		}

		double limit = Math.sqrt(Math.max(0.0, radius * radius - dy * dy)) + offset;
		double scale = limit / horizontal;

		place(entity, dome.centre().x + dx * scale, entity.getY(), dome.centre().z + dz * scale);
		damp(entity, new Vec3(dx / horizontal, 0.0, dz / horizontal));
	}

	/**
	 * Moves an entity, using the route that actually holds for the kind of entity it is.
	 *
	 * <p><b>A {@code ServerPlayer} must be moved through its connection.</b> {@code teleportTo} only
	 * changes the server's copy of the position; the client keeps sending its own movement packets
	 * from where it thinks it is, and the server accepts them, so the correction is undone within a
	 * tick or two. That is why the wall stopped mobs reliably but a player could push through it —
	 * and why a Flash Step across the boundary was never pulled back.
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
	private static void draw(ServerLevel level, Dome dome, int nowTick) {
		DustParticleOptions dust = new DustParticleOptions(
				colour(), (float) BleachTuning.DOME_PARTICLE_SCALE);

		double radius = BleachTuning.DOME_RADIUS;
		int rings = Math.max(1, BleachTuning.DOME_RINGS);
		double spacing = Math.max(0.1, BleachTuning.DOME_POINT_SPACING);
		int stride = Math.max(1, BleachTuning.DOME_DRAW_STRIDE);

		// Which slice of the shell this pass draws. Successive passes take different points, and
		// because dust outlives several passes the eye assembles the whole shell anyway — so a dome
		// three times the radius costs no more per tick than a small one. Drawing every point of a
		// 36-block hemisphere in a single pass would be several thousand particles.
		int phase = (nowTick / Math.max(1, BleachTuning.DOME_PARTICLE_INTERVAL)) % stride;

		for (int r = 0; r < rings; r++) {
			// Latitude from the ground (0) to the crown (90 degrees).
			double lat = (r / (double) rings) * (Math.PI / 2.0);
			double ringRadius = Math.cos(lat) * radius;
			double height = Math.sin(lat) * radius;

			// Arc-length spacing, so a wide ring gets proportionally more points than a narrow one.
			int points = (int) Math.max(4, Math.ceil(2.0 * Math.PI * ringRadius / spacing));

			for (int i = phase; i < points; i += stride) {
				double angle = (i / (double) points) * Math.PI * 2.0;
				level.sendParticles(dust,
						dome.centre().x + Math.cos(angle) * ringRadius,
						dome.centre().y + height,
						dome.centre().z + Math.sin(angle) * ringRadius,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		drawFootprint(level, dome, dust, radius, spacing, stride, phase);
	}

	/**
	 * The ground ring, at double density. It is the only part of the shell a player standing inside
	 * can always see, so it is what actually tells them where the edge is.
	 */
	private static void drawFootprint(ServerLevel level, Dome dome, DustParticleOptions dust,
			double radius, double spacing, int stride, int phase) {
		int points = (int) Math.max(8, Math.ceil(2.0 * Math.PI * radius / (spacing * 0.5)));
		double skirtLimit = BleachTuning.DOME_SKIRT_DEPTH;

		for (int i = phase; i < points; i += stride) {
			double angle = (i / (double) points) * Math.PI * 2.0;
			double x = dome.centre().x + Math.cos(angle) * radius;
			double z = dome.centre().z + Math.sin(angle) * radius;

			level.sendParticles(dust, x, dome.centre().y + 0.1, z, 1, 0.0, 0.0, 0.0, 0.0);
			drawSkirt(level, dust, x, z, dome.centre().y, skirtLimit);
		}
	}

	/**
	 * The skirt — the wall hanging from the rim down to whatever the ground actually is at that
	 * column.
	 *
	 * <p>The shell is anchored at the caster's feet, so on sloping terrain its rim floats above
	 * ground that falls away, leaving a visible arch you can see straight under. Containment already
	 * covers that gap — below the anchor counts as inside — but the picture said otherwise, and a
	 * barrier that <em>looks</em> open is one people will keep trying to walk under and then report
	 * as broken. This closes the picture rather than the mechanic.
	 *
	 * <p>Capped at {@link BleachTuning#DOME_SKIRT_DEPTH} so a dome placed on a clifftop draws a
	 * reasonable curtain rather than a column all the way to bedrock.
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

	private static Vector3f colour() {
		int rgb = BleachTuning.DOME_COLOR;
		return new Vector3f(((rgb >> 16) & 0xFF) / 255.0f,
				((rgb >> 8) & 0xFF) / 255.0f,
				(rgb & 0xFF) / 255.0f);
	}
}
