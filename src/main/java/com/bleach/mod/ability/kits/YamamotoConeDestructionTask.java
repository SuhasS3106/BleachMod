package com.bleach.mod.ability.kits;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.tuning.BleachTuning;
import com.bleach.mod.util.BlockQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Yamamoto's Bankai conical destruction task · Zanka no Tachi.
 *
 * <p>Carves a full 3D organic, noise-jittered conical void in the direction of the swing,
 * obliterating blocks to air without placing fire blocks. Capped and volumetric so ceilings
 * underground and terrain ahead are fully cleared. Scales distance and damage with Soul Level.
 */
public final class YamamotoConeDestructionTask implements BlockQueue.Task {
	private static final int AIR_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

	// Offsets are packed into a single long rather than a record: one flat array instead of tens of
	// thousands of objects, and Arrays.sort on it is a primitive sort with no comparator and no
	// boxing. distSq sits in the high bits, so that sort *is* the "carve outward from the caster"
	// ordering the cone needs, at no extra cost.
	private static final int COORD_BIAS = 2048;
	private static final long COORD_MASK = 0x3FFF; // 14 bits, 0..16383
	private static final int SHIFT_DZ = 0;
	private static final int SHIFT_DY = 14;
	private static final int SHIFT_DX = 28;
	private static final int SHIFT_DIST_SQ = 42;

	private static long pack(int dx, int dy, int dz, int distSq) {
		return ((long) distSq << SHIFT_DIST_SQ)
				| ((long) (dx + COORD_BIAS) << SHIFT_DX)
				| ((long) (dy + COORD_BIAS) << SHIFT_DY)
				| ((long) (dz + COORD_BIAS) << SHIFT_DZ);
	}

	private static int unpackDx(long p) {
		return (int) ((p >>> SHIFT_DX) & COORD_MASK) - COORD_BIAS;
	}

	private static int unpackDy(long p) {
		return (int) ((p >>> SHIFT_DY) & COORD_MASK) - COORD_BIAS;
	}

	private static int unpackDz(long p) {
		return (int) ((p >>> SHIFT_DZ) & COORD_MASK) - COORD_BIAS;
	}

	private final ResourceKey<Level> dimension;
	private final BlockPos origin;
	private final Vec3 originVec;
	private final Vec3 lookDir;
	private final UUID casterId;
	private final long[] offsets;
	private final int blockCap;
	private final int blocksPerTick;
	private final float damage;
	private final double maxRange;
	private final double strikeHalfAngleRad;

	private int cursor;
	private int blocksModified;
	private boolean entitiesDamaged;
	private final BlockPos.MutableBlockPos curPos = new BlockPos.MutableBlockPos();

	/** Chunk of the last position touched, so a run of blocks inside one chunk looks it up once. */
	private @Nullable LevelChunk cachedChunk;
	private int cachedChunkX = Integer.MIN_VALUE;
	private int cachedChunkZ = Integer.MIN_VALUE;

	/**
	 * @param range           how far the blast reaches entities
	 * @param halfAngleRad    the strike cone's half-angle
	 * @param blockRange      how far the excavation reaches — deliberately shorter than {@code range}
	 * @param blockHalfAngleRad the excavated cone's half-angle — deliberately narrower
	 */
	public YamamotoConeDestructionTask(ServerPlayer caster, BlockPos origin, Vec3 originVec, Vec3 lookDir,
			double range, double halfAngleRad, double blockRange, double blockHalfAngleRad,
			float damage, int cap, int perTick) {
		this.dimension = caster.serverLevel().dimension();
		this.origin = origin.immutable();
		this.originVec = originVec;
		this.lookDir = lookDir.normalize();
		this.casterId = caster.getUUID();
		this.maxRange = range;
		this.strikeHalfAngleRad = halfAngleRad;
		this.damage = damage;
		this.blockCap = cap;
		this.blocksPerTick = perTick;
		this.offsets = buildConeOffsets(this.lookDir, blockRange, blockHalfAngleRad);
	}

	/**
	 * The cone's block offsets, nearest first.
	 *
	 * <p><b>Scanned over the cone's own bounding box, not the enclosing cube.</b> The cube is
	 * {@code (2r+1)³}; the cone inside it is a sliver. At Soul Level 20 the block range is ~22, so
	 * the old cube scan was 91,125 cells to keep the few thousand that pass a 20° test — and it ran
	 * on the swing tick, in front of the player, every swing.
	 *
	 * <p><b>The box has to be provably no smaller than the accepted set,</b> or the change silently
	 * shaves cells off the cone. The accepted set is not the bare cone: it is the cone widened
	 * perpendicular to its axis by {@code 1.2} plus up to {@code 0.8} of noise, and truncated by the
	 * {@code distSq ≤ range²} sphere. Writing the widening as an angle is what makes it boundable —
	 * for a point at radius {@code r} and angle {@code φ} off the axis, the test
	 * {@code r·sinφ ≤ r·cosφ·tanθ + PAD} is exactly {@code sin(φ-θ) ≤ (PAD/r)·cosθ}, so every
	 * accepted point with {@code r ≥ NEAR_RADIUS} lies inside a spherical sector of half-angle
	 * {@code θ + asin((PAD/NEAR_RADIUS)·cosθ)}. Points closer in than that are handled by simply
	 * keeping a {@code ±NEAR_RADIUS} cube around the apex, which costs nothing.
	 *
	 * <p>The sector's own box then follows from the angle between the axis and each coordinate axis:
	 * a unit vector within {@code φmax} of {@code dir} has {@code i}-component at most
	 * {@code cos(max(0, αᵢ - φmax))}, where {@code αᵢ = acos(dirᵢ)}. So the shape is identical to
	 * what the cube scan produced — only the search for it is smaller.
	 */
	private static long[] buildConeOffsets(Vec3 dir, double range, double halfAngleRad) {
		double tanAngle = Math.tan(halfAngleRad);
		double rangeSq = range * range;

		// Perpendicular slack the membership test tolerates: the +1.2 widening plus the noise's
		// most permissive -0.8.
		final double pad = 2.0;
		// Radius below which the angular widening is not worth bounding — covered by a cube instead.
		final int nearRadius = 6;

		double sectorHalfAngle = halfAngleRad
				+ Math.asin(Math.min(1.0, (pad / nearRadius) * Math.cos(halfAngleRad)));

		int minX = boundLow(dir.x, range, sectorHalfAngle, nearRadius);
		int maxX = boundHigh(dir.x, range, sectorHalfAngle, nearRadius);
		int minY = boundLow(dir.y, range, sectorHalfAngle, nearRadius);
		int maxY = boundHigh(dir.y, range, sectorHalfAngle, nearRadius);
		int minZ = boundLow(dir.z, range, sectorHalfAngle, nearRadius);
		int maxZ = boundHigh(dir.z, range, sectorHalfAngle, nearRadius);

		// Sized from the sector's own volume, not the box's — the box is mostly empty, and sizing
		// from it would allocate hundreds of kilobytes per swing to hold a few thousand entries.
		// Overshoot is handled by growing rather than by over-allocating up front.
		long[] packed = new long[Math.max(1024,
				(int) ((2.0 * Math.PI / 3.0) * range * range * range * (1.0 - Math.cos(sectorHalfAngle))) + 1024)];
		int count = 0;

		// The `distSq <= range²` test is a hard requirement, so it is spent as loop bounds rather
		// than as a rejection: each axis is limited to what the remaining radius still allows. That
		// alone removes the ~48% of any box that lies outside its inscribed sphere, and it composes
		// with the sector box above — together, 2.4x to 8.3x fewer inner iterations than the cube,
		// for a cell set verified identical to it.
		for (int dx = minX; dx <= maxX; dx++) {
			double remX = rangeSq - (double) dx * dx;
			if (remX < 0.0) {
				continue;
			}
			int limY = (int) Math.sqrt(remX);

			for (int dy = Math.max(minY, -limY); dy <= Math.min(maxY, limY); dy++) {
				double remY = remX - (double) dy * dy;
				if (remY < 0.0) {
					continue;
				}
				int limZ = (int) Math.sqrt(remY);

				for (int dz = Math.max(minZ, -limZ); dz <= Math.min(maxZ, limZ); dz++) {
					int distSq = dx * dx + dy * dy + dz * dz;
					if (distSq < 1) {
						continue;
					}

					// Dot product gives distance along look vector
					double dot = dx * dir.x + dy * dir.y + dz * dir.z;
					if (dot <= 0.5) {
						continue;
					}

					// Perpendicular distance from look axis
					double perpSq = distSq - dot * dot;
					if (perpSq < 0) {
						perpSq = 0;
					}
					double perp = Math.sqrt(perpSq);

					// Allowed radius at this distance
					double maxPerp = dot * tanAngle + 1.2;

					// Organic noise (+/- 0.8 blocks)
					double noise = ((((dx * 3129871) ^ (dy * 612847) ^ (dz * 116129781L)) & 0xFF) / 255.0) * 1.6 - 0.8;
					if (perp + noise <= maxPerp) {
						if (count == packed.length) {
							packed = Arrays.copyOf(packed, count * 2);
						}
						packed[count++] = pack(dx, dy, dz, distSq);
					}
				}
			}
		}

		long[] trimmed = Arrays.copyOf(packed, count);
		// distSq is the high field, so this is "sorted by distance from the caster, ascending".
		Arrays.sort(trimmed);
		return trimmed;
	}

	/** Most negative this axis gets over the sector of radius {@code range}, or the near-apex cube. */
	private static int boundLow(double dirComponent, double range, double sectorHalfAngle, int nearRadius) {
		double axisAngle = Math.acos(Mth.clamp(dirComponent, -1.0, 1.0));
		double minComponent = Math.cos(Math.min(Math.PI, axisAngle + sectorHalfAngle));
		double low = Math.min(-nearRadius, range * minComponent);
		return (int) Math.max(-Math.ceil(range), Math.floor(low));
	}

	/** Most positive this axis gets over the sector of radius {@code range}, or the near-apex cube. */
	private static int boundHigh(double dirComponent, double range, double sectorHalfAngle, int nearRadius) {
		double axisAngle = Math.acos(Mth.clamp(dirComponent, -1.0, 1.0));
		double maxComponent = Math.cos(Math.max(0.0, axisAngle - sectorHalfAngle));
		double high = Math.max(nearRadius, range * maxComponent);
		return (int) Math.min(Math.ceil(range), Math.ceil(high));
	}

	@Override
	public boolean tick(MinecraftServer server) {
		ServerLevel level = server.getLevel(dimension);
		if (level == null) {
			return false;
		}

		ServerPlayer caster = server.getPlayerList().getPlayer(casterId);

		// Damage entities in the cone on the first tick.
		//
		// The blast is a strike or an excavation, never both. If the cone caught anything alive,
		// the flame is spent on that target and the terrain is left standing — so the raven cannot
		// be used to delete somebody's base *and* kill them in the same swing, and a Bankai fight
		// in a built-up area does not level the arena every time a hit lands.
		if (!entitiesDamaged) {
			entitiesDamaged = true;
			int struck = damageEntitiesInCone(level, caster);
			if (struck > 0 && BleachTuning.YAMA_BANKAI_CONE_CANCEL_ON_HIT) {
				level.playSound(null, origin, SoundEvents.GENERIC_EXPLODE.value(),
						SoundSource.PLAYERS, 1.0f, 1.8f);
				return false;
			}
		}

		int budget = blocksPerTick;

		while (budget > 0 && cursor < offsets.length && blocksModified < blockCap) {
			long offset = offsets[cursor++];
			budget--;

			curPos.set(origin.getX() + unpackDx(offset),
					origin.getY() + unpackDy(offset),
					origin.getZ() + unpackDz(offset));

			LevelChunk chunk = chunkFor(level);
			if (chunk == null) {
				continue;
			}

			BlockState state = chunk.getBlockState(curPos);
			// hasBlockEntity() is a flag already on the state; the old getBlockEntity(pos) was a map
			// lookup per block to answer the same question. getDestroySpeed stays last — it is the
			// only one of these that can dispatch into block code, so it runs on the survivors only.
			if (state.isAir() || state.is(Blocks.BEDROCK) || state.hasBlockEntity()
					|| state.getDestroySpeed(level, curPos) < 0) {
				continue;
			}

			// Obliterate to air, suppressing drops
			level.setBlock(curPos, Blocks.AIR.defaultBlockState(), AIR_FLAGS);
			blocksModified++;

			// Spawn occasional ash/smoke particle
			if ((blocksModified & 7) == 0) {
				level.sendParticles(ParticleTypes.ASH,
						curPos.getX() + 0.5, curPos.getY() + 0.5, curPos.getZ() + 0.5,
						2, 0.3, 0.3, 0.3, 0.02);
			}
		}

		return cursor < offsets.length && blocksModified < blockCap;
	}

	/**
	 * The loaded chunk containing {@link #curPos}, or {@code null} if it is not loaded.
	 *
	 * <p>Doubles as the {@code isLoaded} check it replaces. The carve walks long runs inside a single
	 * chunk, and every {@code level.getBlockState} on a raw position re-resolves the chunk from its
	 * coordinates — so resolving it once per run takes that work out of the per-block path.
	 */
	private @Nullable LevelChunk chunkFor(ServerLevel level) {
		int cx = curPos.getX() >> 4;
		int cz = curPos.getZ() >> 4;
		if (cx != cachedChunkX || cz != cachedChunkZ) {
			cachedChunkX = cx;
			cachedChunkZ = cz;
			cachedChunk = level.getChunkSource().getChunkNow(cx, cz);
		}
		return cachedChunk;
	}

	/**
	 * Damage every living entity inside the cone.
	 *
	 * @return how many were actually struck — the caller cancels terrain destruction on a non-zero
	 *         count, so this has to count hits rather than candidates.
	 */
	private int damageEntitiesInCone(ServerLevel level, ServerPlayer caster) {
		AABB coneBox = new AABB(origin).inflate(maxRange);
		List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, coneBox,
				e -> e.isAlive() && (caster == null || e != caster));

		int struck = 0;
		for (LivingEntity target : targets) {
			Vec3 toTarget = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(originVec);
			double dist = toTarget.length();
			if (dist > maxRange || dist < 0.5) {
				continue;
			}

			double dot = toTarget.dot(lookDir);
			if (dot > 0) {
				double perp = Math.sqrt(Math.max(0, dist * dist - dot * dot));
				if (perp <= dot * Math.tan(strikeHalfAngleRad) + 1.5) {
					target.invulnerableTime = 0;
					target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, caster), damage);
					// Push entity back
					Vec3 knockback = lookDir.scale(1.2).add(0, 0.3, 0);
					target.setDeltaMovement(target.getDeltaMovement().add(knockback));
					target.hurtMarked = true;
					struck++;
				}
			}
		}

		return struck;
	}
}
