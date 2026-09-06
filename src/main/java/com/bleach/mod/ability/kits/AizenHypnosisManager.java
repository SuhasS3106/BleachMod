package com.bleach.mod.ability.kits;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Kyōka Suigetsu · Complete Hypnosis Manager (Kanzen Saimin).
 *
 * <p>Handles line-of-sight hypnosis upon Shikai release, starting position caching,
 * illusion mob spawning with strictly private visibility, damage tracking, and reflection
 * upon release revert.
 */
public final class AizenHypnosisManager {
	private AizenHypnosisManager() {
	}

	public static final String ILLUSION_TAG_PREFIX = "aizen_illusion_for_";

	public record HypnosisRecord(
			UUID victimId,
			UUID aizenId,
			Vec3 startPos,
			ResourceKey<Level> dimension,
			List<Double> accumulatedDamage,
			Set<Integer> illusionEntityIds
	) {
		public void addDamage(double amount) {
			synchronized (accumulatedDamage) {
				accumulatedDamage.set(0, accumulatedDamage.get(0) + amount);
			}
		}

		public double getDamage() {
			synchronized (accumulatedDamage) {
				return accumulatedDamage.get(0);
			}
		}
	}

	/** Victim UUID -> HypnosisRecord */
	private static final Map<UUID, HypnosisRecord> ACTIVE_HYPNOSIS = new ConcurrentHashMap<>();
	/** Aizen UUID -> Set of Victim UUIDs */
	private static final Map<UUID, Set<UUID>> AIZEN_VICTIMS = new ConcurrentHashMap<>();

	/**
	 * Checks if an entity is an Aizen illusion mob that should be hidden from the given player.
	 */
	public static boolean isEntityHiddenFrom(Entity entity, ServerPlayer player) {
		if (entity == null || player == null) {
			return false;
		}
		for (String tag : entity.getTags()) {
			if (tag.startsWith(ILLUSION_TAG_PREFIX)) {
				String targetUuidStr = tag.substring(ILLUSION_TAG_PREFIX.length());
				return !player.getStringUUID().equals(targetUuidStr);
			}
		}
		return false;
	}

	/**
	 * The victim an illusion belongs to, or {@code null} if this entity is not an illusion.
	 *
	 * <p>The tag is the single source of truth for ownership — it already drives packet-level
	 * visibility, and reusing it here means an illusion's audience and its legal target can never
	 * disagree.
	 */
	@Nullable
	public static UUID illusionOwner(@Nullable Entity entity) {
		if (entity == null) {
			return null;
		}
		for (String tag : entity.getTags()) {
			if (tag.startsWith(ILLUSION_TAG_PREFIX)) {
				try {
					return UUID.fromString(tag.substring(ILLUSION_TAG_PREFIX.length()));
				} catch (IllegalArgumentException malformed) {
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Checks if an entity is an unkillable Aizen illusion mob.
	 */
	public static boolean isIllusionMob(Entity entity) {
		if (entity == null) {
			return false;
		}
		for (String tag : entity.getTags()) {
			if (tag.startsWith(ILLUSION_TAG_PREFIX)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks if the player is currently under Aizen's complete hypnosis.
	 */
	public static boolean isHypnotized(ServerPlayer player) {
		return player != null && ACTIVE_HYPNOSIS.containsKey(player.getUUID());
	}

	/**
	 * Records damage dealt by a player under hypnosis.
	 */
	public static void recordDamage(ServerPlayer player, double amount) {
		if (player == null || amount <= 0.0) {
			return;
		}
		HypnosisRecord record = ACTIVE_HYPNOSIS.get(player.getUUID());
		if (record != null) {
			record.addDamage(amount);
		}
	}

	/**
	 * Hypnotise every player who <em>watched</em> Aizen release Kyōka Suigetsu.
	 *
	 * <p>"Watched" is two separate claims and both have to hold at the instant of release:
	 *
	 * <ol>
	 *   <li><b>Aizen was on their screen.</b> The victim's own look vector has to have him inside a
	 *       {@link BleachTuning#AIZEN_SHIKAI_FOV_DEG} cone. This is the half that was missing — a
	 *       radius plus a clear line between two eyes hypnotised players who were facing the other
	 *       way, running away, or staring at the floor, none of whom saw anything.</li>
	 *   <li><b>Nothing was in the way.</b> The existing block raycast, unchanged.</li>
	 * </ol>
	 *
	 * <p>Order matters for cost as well as meaning: the cone test is a dot product and the raycast
	 * is a voxel walk that can run the full 100 blocks, so the cheap test rejects first. On a busy
	 * server that turns most of this method into arithmetic.
	 */
	public static void unleashShikai(ServerPlayer aizen) {
		ServerLevel level = aizen.serverLevel();
		Vec3 aizenEye = aizen.getEyePosition();
		double radius = BleachTuning.AIZEN_SHIKAI_RADIUS; // 100.0
		double radiusSq = radius * radius;

		// Half-angle, as a cosine, so the per-victim test is a dot product against a unit vector.
		double minLookDot = Math.cos(Math.toRadians(Math.min(180.0, BleachTuning.AIZEN_SHIKAI_FOV_DEG) * 0.5));

		Set<UUID> victims = AIZEN_VICTIMS.computeIfAbsent(aizen.getUUID(), k -> ConcurrentHashMap.newKeySet());

		List<ServerPlayer> nearbyPlayers = level.getEntitiesOfClass(ServerPlayer.class,
				aizen.getBoundingBox().inflate(radius),
				p -> p != aizen && p.isAlive() && !p.isSpectator() && p.distanceToSqr(aizen) <= radiusSq);

		for (ServerPlayer victim : nearbyPlayers) {
			Vec3 victimEye = victim.getEyePosition();

			// 1. Was Aizen on this player's screen? Measured eye-to-eye against the direction they
			//    are actually facing, not their body yaw — a player can look away from where they
			//    are walking, and the eyes are what Kyōka Suigetsu needs.
			Vec3 toAizen = aizenEye.subtract(victimEye);
			double distance = toAizen.length();
			if (distance < 1.0E-4) {
				// Standing inside each other. There is no meaningful direction to test, and at that
				// range he is unmissable.
				distance = 1.0E-4;
			}
			if (victim.getLookAngle().dot(toAizen.scale(1.0 / distance)) < minLookDot) {
				continue;
			}

			// 2. Raycast line of sight between victim eye and Aizen eye
			HitResult hit = level.clip(new ClipContext(victimEye, aizenEye,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, victim));

			// If clear line of sight (hit missed or reached Aizen), they saw him unleash Kyōka Suigetsu
			if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(aizenEye) < 4.0) {
				hypnotize(aizen, victim);
				victims.add(victim.getUUID());
			}
		}

		// Play hypnosis release chime
		level.playSound(null, aizenEye.x, aizenEye.y, aizenEye.z,
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 2.0f, 0.8f);
	}

	private static void hypnotize(ServerPlayer aizen, ServerPlayer victim) {
		UUID victimId = victim.getUUID();
		Vec3 startPos = victim.position();
		ResourceKey<Level> dim = victim.serverLevel().dimension();
		List<Double> dmgList = new ArrayList<>(Collections.singletonList(0.0));
		Set<Integer> entityIds = ConcurrentHashMap.newKeySet();

		HypnosisRecord record = new HypnosisRecord(victimId, aizen.getUUID(), startPos, dim, dmgList, entityIds);
		ACTIVE_HYPNOSIS.put(victimId, record);

		// Spawn unkillable illusion mobs around victim with private visibility
		spawnIllusionMobs(aizen, victim, record);

		// Subtle private chime to victim
		victim.playNotifySound(SoundEvents.GLASS_BREAK, SoundSource.AMBIENT, 0.6f, 1.8f);
	}

	/**
	 * How many illusions this Aizen conjures, scaled by his Soul Level and clamped.
	 *
	 * <p>Scaled off the <em>caster</em>, not the victim: the illusion is Aizen's construct, so its
	 * scale is a measure of him. Scaling off the victim would reward being weak.
	 */
	private static int illusionCountFor(ServerPlayer aizen) {
		int soulLevel = BleachAttachments.get(aizen).soulLevel;
		int scaled = BleachTuning.AIZEN_ILLUSION_MOB_COUNT
				+ (int) Math.floor(BleachTuning.AIZEN_ILLUSION_MOB_PER_SL * (soulLevel - 1));
		return Math.max(1, Math.min(BleachTuning.AIZEN_ILLUSION_MOB_MAX, scaled));
	}

	private static void spawnIllusionMobs(ServerPlayer aizen, ServerPlayer victim, HypnosisRecord record) {
		ServerLevel level = victim.serverLevel();
		Vec3 pos = victim.position();
		String tag = ILLUSION_TAG_PREFIX + victim.getStringUUID();

		int mobCount = illusionCountFor(aizen);
		for (int i = 0; i < mobCount; i++) {
			double angle = (Math.PI * 2.0 * i) / mobCount;
			double spawnX = pos.x + Math.cos(angle) * 4.0;
			double spawnZ = pos.z + Math.sin(angle) * 4.0;

			Zombie illusion = EntityType.ZOMBIE.create(level);
			if (illusion == null) {
				continue;
			}

			illusion.setPos(spawnX, pos.y, spawnZ);
			illusion.addTag(tag);
			illusion.setPersistenceRequired();

			// No custom name. A name rides along in death messages, in /data output and in the
			// hover text of anything that reports the entity, so naming the illusion "Illusion"
			// is the one channel that leaks its existence past the packet filter.

			// The illusion belongs to one victim and may pursue nobody else. Stripping the target
			// goals outright is what stops it wandering onto Aizen — a zombie left with its vanilla
			// NearestAttackableTargetGoal re-picks the closest player every few ticks, which is how
			// the caster ended up being mauled by mobs he could not see.
			((com.bleach.mod.mixin.MobGoalsAccessor) illusion).bleach$targetSelector().removeAllGoals(goal -> true);
			illusion.setTarget(victim);

			level.addFreshEntity(illusion);
			record.illusionEntityIds().add(illusion.getId());
		}
	}

	/**
	 * Re-assert every illusion's target, once per Aizen tick.
	 *
	 * <p>{@code setTarget} alone is not durable: the mob clears it the moment the victim leaves
	 * pathfinding range or breaks line of sight, and with the target goals removed nothing puts it
	 * back. Without this the illusions go inert after the first corner the victim turns.
	 */
	public static void tickIllusions(ServerPlayer aizen) {
		Set<UUID> victimIds = AIZEN_VICTIMS.get(aizen.getUUID());
		if (victimIds == null || victimIds.isEmpty()) {
			return;
		}

		MinecraftServer server = aizen.server;
		for (UUID victimId : victimIds) {
			HypnosisRecord record = ACTIVE_HYPNOSIS.get(victimId);
			if (record == null) {
				continue;
			}

			ServerLevel level = server.getLevel(record.dimension());
			ServerPlayer victim = server.getPlayerList().getPlayer(victimId);
			if (level == null || victim == null || !victim.isAlive()) {
				continue;
			}

			for (int entityId : record.illusionEntityIds()) {
				if (level.getEntity(entityId) instanceof Mob illusion
						&& illusion.isAlive() && illusion.getTarget() != victim) {
					illusion.setTarget(victim);
				}
			}
		}
	}

	/**
	 * Ends complete hypnosis for all players affected by this Aizen on revert/recharge.
	 * Teleports every victim back to their start position in the cached dimension, cleans up the
	 * illusion mobs, and — unless Aizen is dead — reflects their accumulated damage back onto them.
	 *
	 * <p><b>Death cancels the reflection.</b> The accumulated damage is the illusion collapsing:
	 * Kyōka Suigetsu holding the victim inside it and then handing back everything they did while
	 * they were under. Killing the caster is what breaks that hold, so a corpse cannot still be
	 * collecting on it — reflecting anyway would mean Aizen's death <em>detonates</em>, quietly
	 * making him strongest at the moment he loses, and would punish the very players who beat him.
	 *
	 * <p>The release itself is unconditional. Victims are still freed, returned and given the
	 * shatter regardless of how the Shikai ended; only the damage is dropped.
	 */
	public static void endShikai(ServerPlayer aizen) {
		Set<UUID> victimIds = AIZEN_VICTIMS.remove(aizen.getUUID());
		if (victimIds == null || victimIds.isEmpty()) {
			return;
		}

		MinecraftServer server = aizen.server;

		// Read once, before the loop: every victim of this release is resolved the same way, and a
		// per-victim re-check could disagree with itself mid-loop.
		boolean reflectDamage = aizen.isAlive();

		for (UUID victimId : victimIds) {
			HypnosisRecord record = ACTIVE_HYPNOSIS.remove(victimId);
			if (record == null) {
				continue;
			}

			// Despawn illusion mobs using cached dimension level regardless of victim state
			ServerLevel cachedLevel = server.getLevel(record.dimension());
			if (cachedLevel != null) {
				for (int entityId : record.illusionEntityIds()) {
					Entity entity = cachedLevel.getEntity(entityId);
					if (entity != null) {
						entity.discard();
					}
				}
			}

			ServerPlayer victim = server.getPlayerList().getPlayer(victimId);
			if (victim != null && victim.isAlive()) {
				ServerLevel targetLevel = cachedLevel != null ? cachedLevel : victim.serverLevel();

				// Teleport back to starting position in the cached dimension
				Vec3 start = record.startPos();
				victim.teleportTo(targetLevel, start.x, start.y, start.z, java.util.Set.of(), victim.getYRot(), victim.getXRot());

				// Inflict all accumulated damage back to the player — unless his death is what ended it.
				double totalDamage = record.getDamage();
				if (reflectDamage && totalDamage > 0.0) {
					victim.invulnerableTime = 0;
					victim.hurt(BleachDamage.source(targetLevel, BleachDamage.SPIRIT_PRESSURE, aizen), (float) totalDamage);
				}

				// Glass break shatter sound in the target dimension
				targetLevel.playSound(null, start.x, start.y, start.z,
						SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 2.5f, 0.9f);
			}
		}
	}

	public static void register() {
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			onPlayerDisconnect(handler.player);
		});
	}

	/**
	 * Cleans up illusion mobs and hypnosis records when a player disconnects, preventing entity leaks.
	 */
	public static void onPlayerDisconnect(ServerPlayer player) {
		if (player == null) {
			return;
		}

		// If Aizen disconnects, end Shikai and clean up all victims
		endShikai(player);

		// If a victim disconnects, clean up their illusion mobs and remove record
		HypnosisRecord record = ACTIVE_HYPNOSIS.remove(player.getUUID());
		if (record != null) {
			MinecraftServer server = player.server;
			if (server != null) {
				ServerLevel level = server.getLevel(record.dimension());
				if (level != null) {
					for (int entityId : record.illusionEntityIds()) {
						Entity entity = level.getEntity(entityId);
						if (entity != null) {
							entity.discard();
						}
					}
				}
			}
		}
	}
}
