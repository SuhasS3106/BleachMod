package com.bleach.mod.ability.kits;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.tuning.BleachTuning;
import com.bleach.mod.util.BlockQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Rukia Kuchiki's released states · PRD §6.4 · {@code BALANCE.md} §J.4.
 *
 * <p>Built on four modular layers:
 * <ol>
 *   <li><b>Freeze effect:</b> heavy slowness and periodic freeze tick damage via {@link BleachEffects#FREEZE}.</li>
 *   <li><b>Local snowfall:</b> {@link BleachTuning#RUKIA_SNOWFALL_PARTICLES} snowflake particles per tick falling inside the field.</li>
 *   <li><b>Snow accumulation:</b> tick-sliced queue via {@link RukiaSnowTask} stacking {@link Blocks#SNOW} layers.</li>
 *   <li><b>Frozen water:</b> surface water sources convert to {@link Blocks#FROSTED_ICE}, which thaws naturally via vanilla random ticks.</li>
 * </ol>
 *
 * <p>Shikai reuses all four layers in miniature at {@link BleachTuning#RUKIA_SHIKAI_ONHIT_RADIUS} on landed melee hits,
 * rate-limited by {@link BleachTuning#RUKIA_SHIKAI_ONHIT_COOLDOWN}.
 */
public final class RukiaTransform {
	private RukiaTransform() {
	}

	public static final ResourceLocation SHIKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "rukia/shikai");
	public static final ResourceLocation BANKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "rukia/bankai");

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	// --- Shikai: Sode no Shirayuki ----------------------------------------------------

	private static final class Shikai implements TransformAbility {
		/**
		 * Attacker UUID -> tick of last burst. Per player, not a single field on the ability: kits
		 * are registered once and shared by everyone who picked them, so a lone {@code int} here
		 * meant one Rukia's swing put every other Rukia on the server on cooldown.
		 */
		private final Map<UUID, Integer> lastHitTicks = new ConcurrentHashMap<>();

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
			lastHitTicks.remove(player.getUUID());
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			lastHitTicks.remove(player.getUUID());
		}

		@Override
		public void onMeleeHit(ServerPlayer player, LivingEntity target, float damage) {
			if (damage <= 0.0f || !target.isAlive()) {
				return;
			}

			int lastHit = lastHitTicks.getOrDefault(player.getUUID(), Integer.MIN_VALUE / 2);
			if (player.tickCount - lastHit < BleachTuning.RUKIA_SHIKAI_ONHIT_COOLDOWN) {
				return;
			}
			lastHitTicks.put(player.getUUID(), player.tickCount);

			ServerLevel level = player.serverLevel();
			Vec3 hitPos = target.position();
			double radius = BleachTuning.RUKIA_SHIKAI_ONHIT_RADIUS;
			double radiusSq = radius * radius;

			// 1. Freeze effect on target and nearby enemies
			AABB searchBox = target.getBoundingBox().inflate(radius);
			List<LivingEntity> targets = level.getEntitiesOfClass(
					LivingEntity.class, searchBox,
					e -> e != player && e.isAlive() && !e.isAlliedTo(player) && target.distanceToSqr(e) <= radiusSq);

			for (LivingEntity e : targets) {
				e.addEffect(new MobEffectInstance(
						BleachEffects.FREEZE, BleachTuning.RUKIA_FREEZE_DURATION_TICKS, 0, false, false, true));
			}

			// 2. Snowflake burst particles & sound
			level.sendParticles(ParticleTypes.SNOWFLAKE, hitPos.x, hitPos.y + 0.8, hitPos.z,
					20, 0.6, 0.6, 0.6, 0.05);
			level.playSound(null, hitPos.x, hitPos.y, hitPos.z,
					SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 1.0f, 1.2f);

			// 3 & 4. Mini snow accumulation and water freeze
			int intRadius = (int) Math.ceil(radius);
			int cap = (int) Math.ceil(Math.PI * radius * radius);
			BlockQueue.submit(new RukiaSnowTask(
					level.dimension(),
					target.blockPosition(),
					intRadius,
					BleachTuning.RUKIA_SNOW_BLOCKS_PER_TICK,
					cap,
					1));
		}
	}

	// --- Bankai: Hakka no Togame ------------------------------------------------------

	private static final class Bankai implements TransformAbility {
		/**
		 * Where each caster's field was last frozen.
		 *
		 * <p>Hakka no Togame froze one disc at the point of activation and then followed the caster
		 * around with nothing but particles — walk twenty blocks and the ice was somewhere behind
		 * you. The field now lays fresh ice wherever it is taken, and <b>never removes what it has
		 * already laid</b>: the trail left behind is the ability.
		 */
		private final Map<UUID, BlockPos> lastFrozenAt = new ConcurrentHashMap<>();

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
			ServerLevel level = player.serverLevel();
			double px = player.getX();
			double py = player.getY();
			double pz = player.getZ();

			level.playSound(null, px, py, pz, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0f, 0.8f);
			level.playSound(null, px, py, pz, SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 1.0f, 0.7f);

			freezeAround(player, level);
		}

		/**
		 * Queue one disc of snow and ice centred on the caster, and remember where.
		 *
		 * <p>The follow-up discs use {@link BleachTuning#RUKIA_TRAIL_RADIUS} rather than the full
		 * field radius: a 16-block disc re-queued every time the caster moves a block would have
		 * thousands of columns in flight at once, almost all of them already frozen. A smaller disc
		 * laid often paints the same trail for a fraction of the work.
		 */
		private void freezeAround(ServerPlayer player, ServerLevel level) {
			BlockPos here = player.blockPosition();
			BlockPos previous = lastFrozenAt.put(player.getUUID(), here);

			double radius = previous == null
					? BleachTuning.RUKIA_BANKAI_RADIUS
					: BleachTuning.RUKIA_TRAIL_RADIUS;

			BlockQueue.submit(new RukiaSnowTask(
					level.dimension(),
					here,
					(int) Math.ceil(radius),
					BleachTuning.RUKIA_SNOW_BLOCKS_PER_TICK,
					BleachTuning.RUKIA_SNOW_BLOCK_CAP,
					BleachTuning.RUKIA_SNOW_MAX_LAYERS));
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			ServerLevel level = player.serverLevel();
			double radius = BleachTuning.RUKIA_BANKAI_RADIUS;
			double radiusSq = radius * radius;

			// 0. Lay ice wherever the field has been taken. Distance-gated rather than tick-gated so
			// standing still costs nothing and sprinting does not outrun the freeze.
			BlockPos last = lastFrozenAt.get(player.getUUID());
			if (last == null || last.distSqr(player.blockPosition())
					>= BleachTuning.RUKIA_TRAIL_STEP * BleachTuning.RUKIA_TRAIL_STEP) {
				freezeAround(player, level);
			}

			// 1. Continuous freeze aura on all enemies in radius
			AABB searchBox = player.getBoundingBox().inflate(radius);
			List<LivingEntity> targets = level.getEntitiesOfClass(
					LivingEntity.class, searchBox,
					e -> e != player && e.isAlive() && !e.isAlliedTo(player) && player.distanceToSqr(e) <= radiusSq);

			for (LivingEntity e : targets) {
				e.addEffect(new MobEffectInstance(
						BleachEffects.FREEZE, BleachTuning.RUKIA_FREEZE_DURATION_TICKS, 0, false, false, true));
			}

			// 2. Local snowfall flurry faking weather in the field
			int particleCount = BleachTuning.RUKIA_SNOWFALL_PARTICLES;
			double px = player.getX();
			double py = player.getY() + BleachTuning.RUKIA_SNOWFALL_HEIGHT;
			double pz = player.getZ();

			for (int i = 0; i < particleCount; i++) {
				double angle = Mth.TWO_PI * level.random.nextDouble();
				double dist = radius * Math.sqrt(level.random.nextDouble());
				double x = px + Math.cos(angle) * dist;
				double z = pz + Math.sin(angle) * dist;
				level.sendParticles(ParticleTypes.SNOWFLAKE, x, py, z, 0, 0.0, -BleachTuning.RUKIA_SNOWFALL_SPEED, 0.0, 1.0);
			}
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			// Only the bookkeeping. The ice itself stays where it was laid — melting it on revert
			// would erase the one lasting mark the ability makes.
			lastFrozenAt.remove(player.getUUID());
		}
	}

	// --- Snow & Frosted Ice Task ------------------------------------------------------

	/**
	 * Tick-sliced task iterating columns inside a radius, freezing surface water to {@link Blocks#FROSTED_ICE}
	 * and stacking {@link Blocks#SNOW} layers up to {@code maxLayers}.
	 */
	public static final class RukiaSnowTask implements BlockQueue.Task {
		private static final int SNOW_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

		/**
		 * The columns inside a disc of this radius, packed as {@code (dx+bias)<<16 | (dz+bias)},
		 * built once per radius and shared.
		 *
		 * <p>The disc is a pure function of the radius, and Bankai re-queues one every
		 * {@link BleachTuning#RUKIA_TRAIL_STEP} blocks the caster walks — so the same circle was
		 * being re-derived, corner cells and all, several times a second for as long as the
		 * transformation was held. There are two radii in play (the opening disc and the trail), so
		 * this settles at two entries.
		 */
		private static final Map<Integer, int[]> DISC_CACHE = new ConcurrentHashMap<>();

		private static final int COORD_BIAS = 2048;

		private final ResourceKey<Level> dimension;
		private final BlockPos origin;
		private final int radius;
		private final int[] columns;
		private final int blocksPerTick;
		private final int blockCap;
		private final int maxLayers;

		private int cursor;
		private int blocksModified;
		private final BlockPos.MutableBlockPos colPos = new BlockPos.MutableBlockPos();

		/** Chunk of the last column touched, so the ~256 columns in one chunk resolve it once. */
		private @Nullable LevelChunk cachedChunk;
		private int cachedChunkX = Integer.MIN_VALUE;
		private int cachedChunkZ = Integer.MIN_VALUE;

		public RukiaSnowTask(ResourceKey<Level> dimension, BlockPos origin,
				int radius, int blocksPerTick, int blockCap, int maxLayers) {
			this.dimension = dimension;
			this.origin = origin.immutable();
			this.radius = radius;
			this.blocksPerTick = blocksPerTick;
			this.blockCap = blockCap;
			// SnowLayerBlock.LAYERS is a 1..8 property. Now that the depth is written in one go
			// rather than incremented under a `< maxLayers` guard, an out-of-range config value would
			// reach setValue directly and throw, so it is clamped here instead.
			this.maxLayers = Mth.clamp(maxLayers, 1, 8);
			this.columns = DISC_CACHE.computeIfAbsent(radius, RukiaSnowTask::buildDisc);
		}

		/** The in-circle columns only. The old scan walked the bounding square and rejected ~21% of it. */
		private static int[] buildDisc(int radius) {
			int radiusSq = radius * radius;
			int[] packed = new int[(2 * radius + 1) * (2 * radius + 1)];
			int count = 0;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx * dx + dz * dz <= radiusSq) {
						packed[count++] = ((dx + COORD_BIAS) << 16) | (dz + COORD_BIAS);
					}
				}
			}
			return Arrays.copyOf(packed, count);
		}

		/**
		 * The loaded chunk containing {@link #colPos}, or {@code null} if it is not loaded.
		 *
		 * <p>Stands in for the {@code isLoaded} check it replaces, and for the chunk resolution that
		 * {@code getHeightmapPos}, {@code getFluidState} and {@code getBlockState} each performed
		 * separately on the same column — four lookups per column reduced to one per chunk.
		 */
		private @Nullable LevelChunk chunkFor(ServerLevel level) {
			int cx = colPos.getX() >> 4;
			int cz = colPos.getZ() >> 4;
			if (cx != cachedChunkX || cz != cachedChunkZ) {
				cachedChunkX = cx;
				cachedChunkZ = cz;
				cachedChunk = level.getChunkSource().getChunkNow(cx, cz);
			}
			return cachedChunk;
		}

		/**
		 * One tick's worth of columns.
		 *
		 * <p><b>One pass, not {@code maxLayers} of them.</b> The disc used to be walked once per snow
		 * layer, each walk redoing the heightmap query, both fluid queries and the state read purely
		 * to raise {@code LAYERS} by one — four full scans of a 16-block disc to reach the depth a
		 * single write could set. The visible result is identical because every pass ran within the
		 * same burst of ticks; nobody could see the intermediate depths. {@code blocksModified} still
		 * counts layers rather than columns, so {@link BleachTuning#RUKIA_SNOW_BLOCK_CAP} keeps the
		 * meaning it was tuned with.
		 */
		@Override
		public boolean tick(MinecraftServer server) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				return false;
			}

			int checked = 0;

			while (checked < blocksPerTick) {
				if (blocksModified >= blockCap || cursor >= columns.length) {
					return false;
				}

				int packedColumn = columns[cursor++];
				checked++;

				colPos.set(origin.getX() + ((packedColumn >> 16) - COORD_BIAS),
						origin.getY(),
						origin.getZ() + ((packedColumn & 0xFFFF) - COORD_BIAS));

				LevelChunk chunk = chunkFor(level);
				if (chunk == null) {
					continue;
				}

				// +1 because ChunkAccess.getHeight is the heightmap's own "highest occupied" value,
				// where Level.getHeight — which getHeightmapPos used — returns the first free block
				// above it. Same square, one block up.
				int topY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						colPos.getX() & 15, colPos.getZ() & 15) + 1;
				BlockPos topPos = new BlockPos(colPos.getX(), topY, colPos.getZ());

				// Constrain vertical range to prevent modifying distant ceilings or floors
				if (Math.abs(topY - origin.getY()) > radius) {
					continue;
				}

				// 4. Freeze water source blocks to frosted ice
				BlockPos belowPos = topPos.below();
				FluidState belowFluid = chunk.getFluidState(belowPos);
				if (belowFluid.isSourceOfType(Fluids.WATER)) {
					level.setBlock(belowPos, Blocks.FROSTED_ICE.defaultBlockState(), SNOW_FLAGS);
					blocksModified++;
				}

				BlockState state = chunk.getBlockState(topPos);
				FluidState topFluid = state.getFluidState();
				if (topFluid.isSourceOfType(Fluids.WATER)) {
					level.setBlock(topPos, Blocks.FROSTED_ICE.defaultBlockState(), SNOW_FLAGS);
					blocksModified++;
					continue;
				}

				// 3. Snow accumulation, laid to full depth in one write
				if (state.is(Blocks.SNOW)) {
					int layers = state.getValue(SnowLayerBlock.LAYERS);
					if (layers < maxLayers) {
						level.setBlock(topPos, state.setValue(SnowLayerBlock.LAYERS, maxLayers), SNOW_FLAGS);
						blocksModified += maxLayers - layers;
					}
				} else if ((state.isAir() || state.canBeReplaced()) && !topFluid.isSource()) {
					BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, maxLayers);
					if (snow.canSurvive(level, topPos)) {
						level.setBlock(topPos, snow, SNOW_FLAGS);
						blocksModified += maxLayers;
					}
				}
			}

			return cursor < columns.length;
		}
	}
}
