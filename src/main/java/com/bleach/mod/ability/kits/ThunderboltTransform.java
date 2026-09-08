package com.bleach.mod.ability.kits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/**
 * Schrift <b>T — The Thunderbolt</b>, Candice Catnipp · {@code BALANCE.md} §P.5.
 *
 * <p>One power at two intensities, which is the split {@link QuincyTransform} exists to serve:
 *
 * <ul>
 *   <li><b>Schrift (tier 1):</b> a landed reishi arrow calls a single lightning bolt on whatever it
 *       hit, gated by {@link BleachTuning#THUNDER_COOLDOWN_TICKS} so it is a rhythm rather than a
 *       rider on every shot.</li>
 *   <li><b>Vollständig (tier 2):</b> the same bolt now chains to
 *       {@link BleachTuning#THUNDER_CHAIN_COUNT} further entities within
 *       {@link BleachTuning#THUNDER_CHAIN_RADIUS}, each link keeping
 *       {@link BleachTuning#THUNDER_CHAIN_FALLOFF} of the previous link's damage, and the cooldown
 *       drops to {@link BleachTuning#THUNDER_VOLL_COOLDOWN_TICKS}.</li>
 * </ul>
 *
 * <p>Everything routes through {@link TransformAbility#onProjectileHit}. There is no melee path and
 * no activated move — {@code BleachKeybinds} has no free key, and a tier in this mod is a stance
 * whose power is expressed as passives and on-hit hooks, exactly as {@link IchigoTransform}'s is.
 *
 * <p><b>The bolt is visual-only.</b> {@link LightningBolt#setVisualOnly(boolean)} buys the flash,
 * the thunderclap and the dynamic lighting for free while suppressing every vanilla side effect:
 * no fire, no flat-5 damage, no charged creepers or witch conversions. Damage is then applied by
 * hand as {@link BleachDamage#SPIRIT_PRESSURE}, which is already in the {@code BLEACH} tag, so Soul
 * Level scaling applies and the numbers stay inside {@code BALANCE.md} rather than escaping into a
 * vanilla constant. Losing the mob conversions is the deliberate price of that.
 *
 * <p><b>Per-player state is never an instance field</b> — one kit object serves every player who
 * picked this Schrift. The cooldown lives in {@link #LAST_BOLT_TICK}, keyed by player UUID, as
 * {@link QuincyTransform}'s class note requires.
 */
public final class ThunderboltTransform {
	private ThunderboltTransform() {
	}

	public static final ResourceLocation SCHRIFT_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "thunderbolt/schrift");
	public static final ResourceLocation VOLLSTANDIG_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "thunderbolt/vollstandig");

	/**
	 * Game tick of each player's most recent bolt. Entries are dropped on revert, so a player who
	 * leaves the stance cannot carry a stale cooldown into their next one, and the map cannot grow
	 * without bound across a server's lifetime.
	 */
	private static final Map<UUID, Integer> LAST_BOLT_TICK = new ConcurrentHashMap<>();

	public static TransformAbility schrift() {
		return new Schrift();
	}

	public static TransformAbility vollstandig() {
		return new Vollstandig();
	}

	// ---- pure logic, unit tested ---------------------------------------------------

	/**
	 * Damage for one link of the chain. Link 0 is the struck target and takes the full bolt; each
	 * subsequent link keeps {@code falloff} of the one before it.
	 *
	 * <p>A negative or zero {@code falloff} is clamped to zero rather than rejected, so a config
	 * that disables chaining by setting the falloff to 0 produces harmless zero-damage links instead
	 * of throwing inside a hit handler.
	 */
	public static double chainDamage(double base, int link, double falloff) {
		if (link <= 0) {
			return base;
		}
		return base * Math.pow(Math.max(0.0, falloff), link);
	}

	/**
	 * Whether a bolt may fire. {@code lastTick} is the tick of the previous bolt; a player with no
	 * recorded bolt is always ready.
	 *
	 * <p>Written as {@code now - last >= cooldown} rather than comparing against a stored expiry so
	 * that a world whose tick counter has been moved backwards — {@code /bleach test reishi} does
	 * exactly this — cannot leave a player locked out until it catches up.
	 *
	 * <p><b>The subtraction is widened to {@code long} deliberately.</b> The no-bolt-yet sentinel is
	 * {@link Integer#MIN_VALUE}, and {@code 0 - Integer.MIN_VALUE} overflows back to a negative in
	 * {@code int} arithmetic — which made a freshly transformed player permanently <em>not</em>
	 * ready, the exact inverse of the intent. A unit test caught it; nothing in play would have,
	 * because the symptom is a power that simply never fires.
	 */
	public static boolean isReady(int lastTick, int nowTick, int cooldownTicks) {
		long elapsed = (long) nowTick - (long) lastTick;
		return elapsed >= cooldownTicks || elapsed < 0L;
	}

	// ---- the strike ----------------------------------------------------------------

	/**
	 * Calls the bolt on {@code target} and, when {@code chainCount} is positive, on its nearest
	 * neighbours.
	 *
	 * <p><b>The primary target's invulnerability frames are cleared first.</b> This handler runs
	 * from {@code ReishiArrow.onHitEntity} immediately after the arrow's own {@code hurt} landed, so
	 * the target is inside vanilla's 20-tick immunity window and would otherwise swallow the bolt
	 * whole — {@code LivingEntity.hurt} only applies the excess over {@code lastHurt}, and the bolt
	 * is smaller than the arrow. Without this the tier-1 power would be silently inert while
	 * compiling, passing every unit test and looking correct in review. Chain links are untouched by
	 * the arrow and need no such reset.
	 */
	private static void strike(ServerPlayer player, LivingEntity target, int chainCount) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		double base = BleachTuning.THUNDER_BOLT_DAMAGE;
		double falloff = BleachTuning.THUNDER_CHAIN_FALLOFF;

		flash(level, target);
		target.invulnerableTime = 0;
		target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, player), (float) base);

		if (chainCount <= 0) {
			return;
		}

		List<LivingEntity> links = chainTargets(level, player, target, chainCount);
		for (int i = 0; i < links.size(); i++) {
			LivingEntity link = links.get(i);
			flash(level, link);
			link.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, player),
					(float) chainDamage(base, i + 1, falloff));
		}
	}

	/**
	 * The nearest living entities to {@code origin}, excluding the shooter and the struck target
	 * itself. Sorted by distance so the falloff runs outward, which is the only ordering a player
	 * can actually read in play.
	 */
	private static List<LivingEntity> chainTargets(ServerLevel level, ServerPlayer player,
			LivingEntity origin, int limit) {
		double radius = BleachTuning.THUNDER_CHAIN_RADIUS;
		AABB box = origin.getBoundingBox().inflate(radius);

		List<LivingEntity> found = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, box,
				candidate -> candidate != origin
						&& candidate != player
						&& candidate.isAlive()
						&& candidate.distanceTo(origin) <= radius));

		found.sort(Comparator.comparingDouble(candidate -> candidate.distanceTo(origin)));
		return found.size() <= limit ? found : found.subList(0, limit);
	}

	/** A side-effect-free lightning bolt: flash, sound and light, nothing else. */
	private static void flash(ServerLevel level, LivingEntity at) {
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
		if (bolt == null) {
			return;
		}
		bolt.moveTo(at.getX(), at.getY(), at.getZ());
		bolt.setVisualOnly(true);
		level.addFreshEntity(bolt);
	}

	/** Shared by both tiers: cooldown check, bookkeeping, then the strike. */
	private static void tryBolt(ServerPlayer player, LivingEntity target, int cooldown, int chainCount) {
		UUID id = player.getUUID();
		int now = player.tickCount;
		int last = LAST_BOLT_TICK.getOrDefault(id, Integer.MIN_VALUE);

		if (!isReady(last, now, cooldown)) {
			return;
		}

		LAST_BOLT_TICK.put(id, now);
		strike(player, target, chainCount);
	}

	// ---- the two tiers -------------------------------------------------------------

	/** Release 1 — the Schrift. One bolt, no chain. */
	private static final class Schrift extends QuincyTransform.Tier1 {
		private Schrift() {
			super(SCHRIFT_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
			LAST_BOLT_TICK.remove(player.getUUID());
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
			LAST_BOLT_TICK.remove(player.getUUID());
		}

		@Override
		public void onProjectileHit(ServerPlayer player, LivingEntity target, float damage) {
			tryBolt(player, target, BleachTuning.THUNDER_COOLDOWN_TICKS, 0);
		}

		@Override
		protected boolean wingJagged() {
			return true;
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_THUNDERBOLT_PARTICLE_COLOR;
		}
	}

	/** Release 2 — Vollständig. The same bolt, chaining, on a shorter clock. */
	private static final class Vollstandig extends QuincyTransform.Tier2 {
		private Vollstandig() {
			super(VOLLSTANDIG_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
			LAST_BOLT_TICK.remove(player.getUUID());
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
			LAST_BOLT_TICK.remove(player.getUUID());
		}

		@Override
		public void onProjectileHit(ServerPlayer player, LivingEntity target, float damage) {
			tryBolt(player, target, BleachTuning.THUNDER_VOLL_COOLDOWN_TICKS,
					BleachTuning.THUNDER_CHAIN_COUNT);
		}

		@Override
		protected boolean wingJagged() {
			return true;
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_THUNDERBOLT_PARTICLE_COLOR;
		}
	}
}
