package com.bleach.mod.ability.kits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import java.util.function.Predicate;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Genryūsai Shigekuni Yamamoto's released states · PRD §6.2 · {@code BALANCE.md} §J.2.
 *
 * <p>Both states are the same idea at two intensities: the air around Yamamoto cannot hold water.
 * <ul>
 *   <li><b>Shikai — Ryūjin Jakka:</b> an instant ignite burst. Sets every non-allied entity within
 *       {@link BleachTuning#YAMA_SHIKAI_RADIUS} alight for {@link BleachTuning#YAMA_SHIKAI_BURN_TICKS},
 *       draws a perimeter flame ring, grants the caster fire immunity, and runs a
 *       {@link ScorchSweepTask} over the ground.</li>
 *   <li><b>Bankai — Zanka no Tachi:</b> the same, sustained. Fire immunity and
 *       {@code +YAMA_BANKAI_DMG} bleach melee for as long as it is held, targets burn on hit for
 *       {@link BleachTuning#YAMA_BANKAI_ONHIT_BURN_TICKS}, and the scorch sweep runs at the full
 *       Bankai radius.</li>
 * </ul>
 *
 * <h2>The scorch sweep</h2>
 *
 * <p>Structurally Rukia's snowfall run in reverse, and deliberately so — the two ultimates should
 * leave the same <em>kind</em> of mark on the world in opposite directions. Water sources, flowing
 * water, ice in all four flavours, snow layers and snow blocks are boiled out of every column in
 * radius down to {@link BleachTuning#YAMA_EVAPORATE_DEPTH}, and the surface that is left is set
 * alight at {@link BleachTuning#YAMA_FIRE_CHANCE}.
 *
 * <p>Bankai used to <em>extinguish</em> fire instead — the flame-absorbing reading of Zanka no
 * Tachi. That is the one thing an ability called "the remnant flame" must not do to a player who
 * just pressed the button expecting an inferno, so both states now sweep in the same direction and
 * the old extinguish task is gone.
 */
public final class YamamotoTransform {
	private YamamotoTransform() {
	}

	public static final ResourceLocation SHIKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "yamamoto/shikai");
	public static final ResourceLocation BANKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "yamamoto/bankai");

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	/**
	 * Ignite everything in radius, ring the perimeter in flame, and queue the ground scorch. Shared
	 * because the two states differ only in radius and in how long they last.
	 */
	private static void ignite(ServerPlayer player, double radius, int burnTicks, double scorchRadius) {
		player.clearFire();

		ServerLevel level = player.serverLevel();
		double px = player.getX();
		double py = player.getY();
		double pz = player.getZ();

		// Only the opening release announces itself. The trail discs fire every few blocks walked, and
		// a fire-charge whoosh on each of those is a car alarm.
		if (scorchRadius >= radius) {
			level.playSound(null, px, py, pz, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0f, 1.0f);
		}

		double radiusSq = radius * radius;
		AABB searchBox = player.getBoundingBox().inflate(radius);

		List<LivingEntity> targets = level.getEntitiesOfClass(
				LivingEntity.class, searchBox,
				e -> e != player && e.isAlive() && !e.isAlliedTo(player) && player.distanceToSqr(e) <= radiusSq);

		for (LivingEntity target : targets) {
			target.setRemainingFireTicks(burnTicks);
		}

		int ringCount = BleachTuning.YAMA_SHIKAI_RING_PARTICLES;
		if (ringCount > 0) {
			double particleY = py + BleachTuning.YAMA_RING_PARTICLE_Y_OFFSET;
			for (int i = 0; i < ringCount; i++) {
				double angle = (Mth.TWO_PI * i) / ringCount;
				double x = px + Math.cos(angle) * scorchRadius;
				double z = pz + Math.sin(angle) * scorchRadius;
				level.sendParticles(ParticleTypes.FLAME, x, particleY, z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}

		BlockQueue.submit(new ScorchSweepTask(
				level.dimension(),
				player.blockPosition(),
				(int) Math.ceil(scorchRadius),
				BleachTuning.YAMA_SCORCH_COLUMNS_PER_TICK,
				BleachTuning.YAMA_SCORCH_BLOCK_CAP,
				BleachTuning.YAMA_EVAPORATE_DEPTH,
				BleachTuning.YAMA_FIRE_CHANCE));
	}

	// --- Shikai: Ryūjin Jakka ---------------------------------------------------------

	private static final class Shikai implements TransformAbility {
		/**
		 * Where each caster's fire was last laid.
		 *
		 * <p>Ryūjin Jakka burned one disc at the point of release and then followed the caster with
		 * nothing at all — walk out of it and you were an ordinary swordsman standing in a cold
		 * field. The flame now goes where Yamamoto goes, and <b>nothing already burning is put
		 * out</b>: what is left behind is the point of it.
		 */
		private final Map<UUID, BlockPos> lastScorchedAt = new ConcurrentHashMap<>();

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
		public boolean isFireImmune() {
			return true;
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			lastScorchedAt.put(player.getUUID(), player.blockPosition());
			ignite(player, BleachTuning.YAMA_SHIKAI_RADIUS, BleachTuning.YAMA_SHIKAI_BURN_TICKS,
					BleachTuning.YAMA_SHIKAI_RADIUS);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			player.clearFire();

			// Distance-gated rather than tick-gated, so standing still costs nothing and sprinting
			// cannot outrun the flame. The follow-up discs are small · YAMA_TRAIL_RADIUS.
			BlockPos last = lastScorchedAt.get(player.getUUID());
			if (last == null || last.distSqr(player.blockPosition())
					>= BleachTuning.YAMA_TRAIL_STEP * BleachTuning.YAMA_TRAIL_STEP) {
				lastScorchedAt.put(player.getUUID(), player.blockPosition());
				ignite(player, BleachTuning.YAMA_SHIKAI_RADIUS, BleachTuning.YAMA_SHIKAI_BURN_TICKS,
						BleachTuning.YAMA_TRAIL_RADIUS);
			}
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			// Only the bookkeeping. The fire stays lit — putting it out on revert would erase the
			// one lasting mark the ability makes.
			lastScorchedAt.remove(player.getUUID());
		}
	}

	// --- Bankai: Zanka no Tachi -------------------------------------------------------

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
		public double meleeDamageBonus() {
			return BleachTuning.YAMA_BANKAI_DMG;
		}

		@Override
		public boolean isFireImmune() {
			return true;
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			player.clearFire();

			ServerLevel level = player.serverLevel();
			double px = player.getX();
			double py = player.getY();
			double pz = player.getZ();

			level.playSound(null, px, py, pz, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 3.0f, 0.6f);
			level.playSound(null, px, py, pz, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 2.0f, 0.5f);

			// Zanka no Tachi is the flame *withdrawn* into the blade, not thrown out of it: every
			// fire Ryūjin Jakka lit gets pulled back in. Burning entities are put out too — the
			// blade takes all of it, not merely the blocks.
			double radius = BleachTuning.YAMA_BANKAI_RADIUS;
			double radiusSq = radius * radius;
			AABB searchBox = player.getBoundingBox().inflate(radius);
			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, searchBox,
					e -> e.isAlive() && player.distanceToSqr(e) <= radiusSq)) {
				target.clearFire();
			}

			BlockQueue.submit(new ExtinguishSweepTask(
					level.dimension(),
					player.blockPosition(),
					(int) Math.ceil(radius)));

			// The flame is now in the sword, so the ambient inferno collapses inward spherically to the caster.
			for (int i = 0; i < BleachTuning.YAMA_BANKAI_INHALE_PARTICLES; i++) {
				double u = level.random.nextDouble() * 2.0 - 1.0;
				double phi = Mth.TWO_PI * level.random.nextDouble();
				double r = radius * Math.cbrt(level.random.nextDouble());
				double sinT = Math.sqrt(Math.max(0.0, 1.0 - u * u));
				double sx = px + r * sinT * Math.cos(phi);
				double sy = (py + 1.0) + r * u;
				double sz = pz + r * sinT * Math.sin(phi);

				double vx = (px - sx) * 0.08;
				double vy = (py + 1.0 - sy) * 0.08;
				double vz = (pz - sz) * 0.08;

				level.sendParticles(ParticleTypes.FLAME, sx, sy, sz, 0, vx, vy, vz, 1.0);
			}
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			player.clearFire();
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onMeleeHit(ServerPlayer player, LivingEntity target, float damage) {
			if (damage > 0.0f && target.isAlive()) {
				target.setRemainingFireTicks(BleachTuning.YAMA_BANKAI_ONHIT_BURN_TICKS);
			}
		}
	}

	/** Attacker UUID -> tick of last Bankai swing raven */
	private static final Map<UUID, Integer> lastRavenSwingTick = new ConcurrentHashMap<>();

	/**
	 * Called when swinging Yamamoto's Zanpakutō in Bankai without hitting an enemy directly.
	 * Costs 30 SP, spawns the raven visual, and unleashes the 3D conical destruction.
	 */
	public static void onBankaiSwingMiss(ServerPlayer player) {
		ServerLevel level = player.serverLevel();
		int currentTick = player.tickCount;
		Integer lastTick = lastRavenSwingTick.get(player.getUUID());
		if (lastTick != null && currentTick - lastTick < BleachTuning.YAMA_BANKAI_SWING_COOLDOWN_TICKS) {
			return;
		}

		SpiritualData data = BleachAttachments.get(player);
		double cost = BleachTuning.YAMA_BANKAI_CONE_SP_COST;
		if (data.sp < cost) {
			return;
		}

		data.spend(cost);
		lastRavenSwingTick.put(player.getUUID(), currentTick);
		SpiritualTicker.sync(player, true);

		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		BlockPos startPos = BlockPos.containing(eye);

		// Raven sound effects: deep wing flap + roar
		level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 1.8f, 0.6f);
		level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.2f, 1.4f);

		// Raven visual trail along look vector
		for (int step = 1; step <= 8; step++) {
			Vec3 p = eye.add(look.scale(step * 0.8));
			level.sendParticles(ParticleTypes.SQUID_INK, p.x, p.y, p.z, 4, 0.2, 0.2, 0.2, 0.05);
			level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 3, 0.15, 0.15, 0.15, 0.02);
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.01);
		}

		int sl = data.soulLevel;
		double range = BleachTuning.YAMA_BANKAI_CONE_RANGE_BASE + BleachTuning.YAMA_BANKAI_CONE_RANGE_PER_SL * (sl - 1);
		float dmg = (float) (BleachTuning.YAMA_BANKAI_CONE_DMG_BASE + BleachTuning.YAMA_BANKAI_CONE_DMG_PER_SL * (sl - 1));
		double angleRad = Math.toRadians(BleachTuning.YAMA_BANKAI_CONE_ANGLE_DEG);

		BlockQueue.submit(new YamamotoConeDestructionTask(
				player,
				startPos,
				eye,
				look,
				range,
				angleRad,
				range * BleachTuning.YAMA_BANKAI_CONE_BLOCK_RANGE_MULT,
				Math.toRadians(BleachTuning.YAMA_BANKAI_CONE_BLOCK_ANGLE_DEG),
				dmg,
				BleachTuning.YAMA_BANKAI_CONE_BLOCK_CAP,
				BleachTuning.YAMA_BANKAI_CONE_BLOCKS_PER_TICK));
	}

	// --- Scorch Task ------------------------------------------------------------------

	/**
	 * Tick-sliced column sweep: boils water, ice and snow out of the top of every column inside a
	 * radius, then sets the surface alight.
	 *
	 * <p>Written as a column walk rather than a full cylinder scan for the same reason
	 * {@code RukiaSnowTask} is: a radius-30 cylinder eight blocks tall is over twenty thousand
	 * positions, and all but the top few of each column are underground and irrelevant. The
	 * heightmap gives the one row that matters for the price of a lookup.
	 *
	 * <p>Blocks are set with {@code UPDATE_CLIENTS | UPDATE_SUPPRESS_DROPS}: no neighbour updates,
	 * so evaporating the top of a lake cannot cascade the rest of it into flowing-water ticks, and
	 * no drops, so a hundred snow layers do not become a hundred snowball entities.
	 */
	public static final class ScorchSweepTask implements BlockQueue.Task {
		private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

		private record Column(int dx, int dz) {}

		private final ResourceKey<Level> dimension;
		private final BlockPos origin;
		private final int radius;
		private final List<Column> columns;
		private final int columnsPerTick;
		private final int blockCap;
		private final int evaporateDepth;
		private final double fireChance;

		private int cursorIndex;
		private int blocksModified;
		private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		public ScorchSweepTask(ResourceKey<Level> dimension, BlockPos origin, int radius,
				int columnsPerTick, int blockCap, int evaporateDepth, double fireChance) {
			this.dimension = dimension;
			this.origin = origin.immutable();
			this.radius = radius;
			this.columnsPerTick = columnsPerTick;
			this.blockCap = blockCap;
			this.evaporateDepth = evaporateDepth;
			this.fireChance = fireChance;
			this.columns = radialColumns(radius);
		}

		/**
		 * Every column in the disc, ordered by distance from the centre.
		 *
		 * <p>The sweep used to walk the bounding square row by row, which meant the fire arrived as a
		 * line sweeping across the ground from one corner — visibly a raster scan, and nothing like a
		 * wave of flame coming off a person. Sorting by radius costs one sort at submission and makes
		 * the same budget spend itself outward in rings from the caster's feet.
		 */
		private static List<Column> radialColumns(int radius) {
			int radiusSq = radius * radius;
			List<Column> list = new ArrayList<>();

			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx * dx + dz * dz <= radiusSq) {
						list.add(new Column(dx, dz));
					}
				}
			}

			list.sort(Comparator.comparingInt(c -> c.dx() * c.dx() + c.dz() * c.dz()));
			return list;
		}

		@Override
		public boolean tick(MinecraftServer server) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				return false;
			}

			int done = 0;
			while (done < columnsPerTick) {
				if (blocksModified >= blockCap || cursorIndex >= columns.size()) {
					return false;
				}

				Column column = columns.get(cursorIndex++);
				cursor.set(origin.getX() + column.dx(), origin.getY(), origin.getZ() + column.dz());

				if (level.isLoaded(cursor)) {
					BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cursor);

					// Same guard Rukia's sweep uses: without it a column under an overhang finds
					// its "surface" on a cave ceiling forty blocks away and sets that on fire.
					if (Math.abs(top.getY() - origin.getY()) <= radius) {
						scorchColumn(level, top);
					}
				}
				done++;
			}

			return true;
		}

		private void scorchColumn(ServerLevel level, BlockPos top) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos().set(top);

			// 1. Boil the column dry from the surface down.
			for (int depth = 0; depth <= evaporateDepth; depth++) {
				pos.set(top.getX(), top.getY() - depth, top.getZ());
				if (level.getBlockEntity(pos) != null) {
					continue;
				}

				BlockState state = level.getBlockState(pos);
				if (isVapourisable(level, pos, state)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
					blocksModified++;
				}
			}

			// 2. Set light to whatever surface is left. Walking back down from the old surface finds
			// the new one after however much of the column just boiled away.
			for (int depth = 0; depth <= evaporateDepth + 1; depth++) {
				pos.set(top.getX(), top.getY() - depth, top.getZ());
				if (!level.getBlockState(pos).isAir()) {
					continue;
				}

				BlockState below = level.getBlockState(pos.below());
				if (below.isAir() || isVapourisable(level, pos.below(), below)) {
					continue;
				}

				if (level.random.nextDouble() < fireChance) {
					BlockState fire = Blocks.FIRE.defaultBlockState();
					if (fire.canSurvive(level, pos)) {
						level.setBlock(pos, fire, FLAGS);
						blocksModified++;
					}
				}
				return;
			}
		}

		/** Water in either form, ice in all four, and snow in both. */
		private static boolean isVapourisable(ServerLevel level, BlockPos pos, BlockState state) {
			FluidState fluid = level.getFluidState(pos);
			if (fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER) {
				return true;
			}
			return state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE) || state.is(Blocks.PACKED_ICE)
					|| state.is(Blocks.BLUE_ICE) || state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
					|| state.is(Blocks.POWDER_SNOW);
		}
	}

	// --- Block Extinguish Task --------------------------------------------------------

	/**
	 * Sphere sweep that extinguishes every fire, soul fire, lit campfire, lit candle and torch
	 * within {@link BleachTuning#YAMA_BANKAI_RADIUS} of Yamamoto.
	 *
	 * <h2>Why this walks sections and not blocks</h2>
	 *
	 * <p>The obvious implementation — visit all 195,269 positions in a radius-36 sphere and read the
	 * block at each — spends essentially all of its time proving that stone is not on fire. What it
	 * is looking for is vanishingly rare: even a torch-lit base holds a few dozen candidates in the
	 * whole volume, and the overwhelmingly common case is a sweep that finds nothing at all and pays
	 * two hundred thousand chunk lookups to establish it.
	 *
	 * <p>So the scan is hierarchical. The volume is decomposed into the 16×16×16 chunk sections it
	 * covers — about ninety of them — and each is put through two cheap filters before a single
	 * position inside it is read:
	 *
	 * <ul>
	 *   <li>{@link LevelChunkSection#hasOnlyAir()} — open sky and unexcavated air, rejected outright;</li>
	 *   <li>{@link LevelChunkSection#maybeHas(java.util.function.Predicate)} — a scan of the section's
	 *       <em>palette</em>, the list of distinct block states it actually contains. A section of
	 *       stone and dirt has a palette of two, so proving that 4,096 positions hold nothing
	 *       flammable costs two comparisons rather than 4,096 lookups.</li>
	 * </ul>
	 *
	 * <p>Only a section whose palette genuinely mentions a torch or a fire is opened and walked, and
	 * even then positions are read straight off the section rather than through
	 * {@code Level#getBlockState}, which re-resolves the chunk and the section on every call.
	 *
	 * <p>The common case therefore costs about ninety palette scans instead of 195,269 block reads,
	 * and finishes inside a single tick rather than four seconds of slicing. The worst case is
	 * bounded by how much is genuinely burning — which is the thing that should have been paying for
	 * it all along.
	 *
	 * <p>Sections are still ordered nearest-first, so a budget-limited sweep through a genuinely
	 * fire-filled area puts out what is closest to the caster first.
	 */
	public static final class ExtinguishSweepTask implements BlockQueue.Task {

		/** Edge length of a chunk section. */
		private static final int SECTION_SIZE = 16;

		/**
		 * What the palette filter looks for.
		 *
		 * <p>Deliberately a <b>superset</b> of what {@link #extinguishBlock} actually changes — the
		 * config toggles are not consulted here. A predicate narrower than the action would let a
		 * section full of torches be skipped while torch extinguishing was switched on; a wider one
		 * only ever costs one unnecessary walk.
		 */
		private static final Predicate<BlockState> EXTINGUISHABLE = state ->
				state.getBlock() instanceof BaseFireBlock
						|| state.getBlock() instanceof CampfireBlock
						|| state.getBlock() instanceof AbstractCandleBlock
						|| state.getBlock() instanceof TorchBlock;

		/** One 16³ section overlapping the sphere. {@code distSq} is measured from the blast origin. */
		private record SectionRef(int chunkX, int sectionY, int chunkZ, long distSq) {}

		private final ResourceKey<Level> dimension;
		private final BlockPos origin;
		private final int radiusSq;
		private final List<SectionRef> sections;

		private int cursor;
		private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

		public ExtinguishSweepTask(ResourceKey<Level> dimension, BlockPos origin, int radius) {
			this.dimension = dimension;
			this.origin = origin.immutable();
			this.radiusSq = radius * radius;
			this.sections = buildSectionList(this.origin, radius);
		}

		/**
		 * Every section whose box overlaps the sphere, nearest-first.
		 *
		 * <p>Overlap is measured to the section's <em>closest point</em> rather than its centre, so a
		 * section clipped by the very edge of the sphere is still included.
		 */
		private static List<SectionRef> buildSectionList(BlockPos origin, int radius) {
			List<SectionRef> list = new ArrayList<>();
			long radiusSq = (long) radius * radius;

			int minX = (origin.getX() - radius) >> 4;
			int maxX = (origin.getX() + radius) >> 4;
			int minY = (origin.getY() - radius) >> 4;
			int maxY = (origin.getY() + radius) >> 4;
			int minZ = (origin.getZ() - radius) >> 4;
			int maxZ = (origin.getZ() + radius) >> 4;

			for (int cx = minX; cx <= maxX; cx++) {
				for (int sy = minY; sy <= maxY; sy++) {
					for (int cz = minZ; cz <= maxZ; cz++) {
						long dx = axisDistance(origin.getX(), cx << 4);
						long dy = axisDistance(origin.getY(), sy << 4);
						long dz = axisDistance(origin.getZ(), cz << 4);
						long distSq = dx * dx + dy * dy + dz * dz;
						if (distSq <= radiusSq) {
							list.add(new SectionRef(cx, sy, cz, distSq));
						}
					}
				}
			}

			list.sort(Comparator.comparingLong(SectionRef::distSq));
			return list;
		}

		/** Distance from {@code point} to the nearest coordinate of the 16-wide span at {@code spanMin}. */
		private static long axisDistance(int point, int spanMin) {
			if (point < spanMin) {
				return spanMin - point;
			}
			int spanMax = spanMin + SECTION_SIZE - 1;
			return point > spanMax ? point - spanMax : 0;
		}

		@Override
		public boolean tick(MinecraftServer server) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				return false;
			}

			// The budget counts positions actually examined. Sections rejected by the palette cost
			// nothing against it, which is the whole point: a sweep over empty terrain should not be
			// spread across four seconds on account of blocks it never had to look at.
			int budget = Math.max(1, BleachTuning.YAMA_BLOCK_SWEEP_BUDGET);
			int examined = 0;

			while (cursor < sections.size() && examined < budget) {
				SectionRef ref = sections.get(cursor++);

				// Never force-loaded: an ability must not drag chunks into memory.
				LevelChunk chunk = level.getChunkSource().getChunkNow(ref.chunkX(), ref.chunkZ());
				if (chunk == null) {
					continue;
				}

				int index = level.getSectionIndexFromSectionY(ref.sectionY());
				LevelChunkSection[] all = chunk.getSections();
				if (index < 0 || index >= all.length) {
					continue;
				}

				LevelChunkSection section = all[index];
				if (section == null || section.hasOnlyAir() || !section.maybeHas(EXTINGUISHABLE)) {
					continue;
				}

				examined += sweepSection(level, section, ref);
			}

			return cursor < sections.size();
		}

		/**
		 * Walk the positions of one section that fall inside the sphere.
		 *
		 * @return how many positions were examined, for the tick budget
		 */
		private int sweepSection(ServerLevel level, LevelChunkSection section, SectionRef ref) {
			int baseX = ref.chunkX() << 4;
			int baseY = ref.sectionY() << 4;
			int baseZ = ref.chunkZ() << 4;
			int examined = 0;

			for (int lx = 0; lx < SECTION_SIZE; lx++) {
				int dx = baseX + lx - origin.getX();
				int dxSq = dx * dx;
				if (dxSq > radiusSq) {
					continue;
				}

				for (int lz = 0; lz < SECTION_SIZE; lz++) {
					int dz = baseZ + lz - origin.getZ();
					int horizontalSq = dxSq + dz * dz;
					if (horizontalSq > radiusSq) {
						continue;
					}

					for (int ly = 0; ly < SECTION_SIZE; ly++) {
						int dy = baseY + ly - origin.getY();
						if (horizontalSq + dy * dy > radiusSq) {
							continue;
						}

						examined++;

						// Read straight off the section: Level#getBlockState would re-resolve the
						// chunk and the section for every one of these.
						BlockState state = section.getBlockState(lx, ly, lz);
						if (state.isAir()) {
							continue;
						}

						mutablePos.set(baseX + lx, baseY + ly, baseZ + lz);
						extinguishBlock(level, mutablePos, state);
					}
				}
			}

			return examined;
		}

		private static void extinguishBlock(ServerLevel level, BlockPos pos, BlockState state) {
			// 1. Regular & soul fire
			if (state.getBlock() instanceof BaseFireBlock) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(),
						Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
			}
			// 2. Fireplaces / Campfires (CampfireBlock.LIT)
			else if (BleachTuning.YAMA_EXTINGUISH_CAMPFIRES && state.getBlock() instanceof CampfireBlock
					&& state.getValue(CampfireBlock.LIT)) {
				level.setBlock(pos, state.setValue(CampfireBlock.LIT, false), Block.UPDATE_CLIENTS);
				level.playSound(null, pos, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 0.5f, 1.0f);
				level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
			}
			// 3. Lit candles and candle cakes.
			//
			// Matched by block class, never by "has a LIT property and it is true". LIT is shared
			// with redstone torches, redstone lamps, redstone ore and every furnace variant, so the
			// property test alone turns a flame-absorbing sweep into a redstone-circuit wrecker:
			// a lamp or redstone torch forced to LIT=false stays wrong until something pokes it
			// into a neighbour update, and a furnace desyncs from its block entity.
			else if (state.getBlock() instanceof AbstractCandleBlock
					&& state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT)) {
				level.setBlock(pos, state.setValue(BlockStateProperties.LIT, false), Block.UPDATE_CLIENTS);
				level.playSound(null, pos, SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 0.5f, 1.0f);
				level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 2, 0.05, 0.05, 0.05, 0.01);
			}
			// 4. Torches (regular torch, wall torch, soul torch, soul wall torch). RedstoneTorchBlock
			// extends BaseTorchBlock, a sibling of TorchBlock, so redstone torches are not caught here.
			else if (BleachTuning.YAMA_EXTINGUISH_TORCHES && state.getBlock() instanceof TorchBlock) {
				level.destroyBlock(pos, true);
				level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.3f, 1.4f);
				level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 3, 0.05, 0.05, 0.05, 0.01);
			}
		}
	}
}
