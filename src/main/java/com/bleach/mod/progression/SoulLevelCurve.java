package com.bleach.mod.progression;

import com.bleach.mod.tuning.BleachTuning;

/**
 * The Soul Level scaling curve · PRD §2.4 · {@code BALANCE.md} §E.
 *
 * <h2>Why this is not a straight line any more</h2>
 *
 * <p>Every §E multiplier used to be linear in the level — {@code 1 − rate × (SL − 1)} — floored at
 * zero. That is fine while the cap is 20 and wrong the instant it moves: at
 * {@code SL_BLEACH_DMG_TAKEN_PER_LEVEL = 0.015} the line crosses zero at <b>SL 68</b>, so raising
 * the cap to 100 handed a mid-ladder player total immunity to every ability in the mod, and the
 * damage-<i>dealt</i> line reached <b>+198%</b> at the new cap. A curve whose behaviour depends on
 * where you happen to stop it is not a curve, it is an accident that had not happened yet.
 *
 * <p>So scaling is now asymptotic. One shared shape drives all three multipliers:
 *
 * <pre>
 *   progress(SL) = 1 − exp(−k × (SL − 1))          0 at level 1, never 1
 *   generalTaken = 1 − SL_GENERAL_DMG_TAKEN_CAP × progress
 *   bleachTaken  = 1 − SL_BLEACH_DMG_TAKEN_CAP  × progress
 *   bleachDealt  = 1 + SL_BLEACH_DMG_DEALT_CAP  × progress
 * </pre>
 *
 * <p>Each multiplier now has a ceiling it approaches and cannot pass, whatever the cap is set to,
 * and the caps are chosen so the two reductions multiply out to exactly the {@link
 * BleachTuning#SL_DMG_TAKEN_FLOOR} — half damage — rather than to nothing.
 *
 * <p>The cost is deliberate: the same level is worth less than it was. SL 20 lands near −26% taken
 * where it used to be −42%, because the same progression is now stretched over five times the
 * levels. That is the trade the cap raise buys.
 *
 * <h2>Precomputed</h2>
 *
 * <p>{@link #rebuild()} is a {@link BleachTuning} reload hook, for the same reason {@link SpxTable}
 * is one: {@code Math.exp} has no business running inside a damage event. A level past the table —
 * a config that shrank {@code SL_MAX} under a saved player — falls back to computing it directly
 * rather than throwing.
 */
public final class SoulLevelCurve {
	private SoulLevelCurve() {
	}

	/** Indexed by level: {@code PROGRESS[L]} is {@code 1 − exp(−k × (L − 1))}. */
	private static double[] progress = new double[0];

	/** Recompute from the current tuning values. Registered as a {@link BleachTuning} reload hook. */
	public static void rebuild() {
		int max = Math.max(1, BleachTuning.SL_MAX);
		double[] table = new double[max + 1];
		for (int level = 1; level <= max; level++) {
			table[level] = compute(level);
		}
		progress = table;
	}

	/** How far along the curve a level sits, 0 at level 1 and asymptotically 1. */
	public static double progress(int soulLevel) {
		if (soulLevel <= 1) {
			return 0.0;
		}
		double[] table = progress;
		return soulLevel < table.length ? table[soulLevel] : compute(soulLevel);
	}

	/**
	 * The multiplier on damage a player takes.
	 *
	 * @param bleach whether the source is bleach damage, which takes the second reduction on top of
	 *        the general one
	 * @return a multiplier never below {@link BleachTuning#SL_DMG_TAKEN_FLOOR}
	 */
	public static double damageTaken(int soulLevel, boolean bleach) {
		double p = progress(soulLevel);
		double multiplier = 1.0 - fraction(BleachTuning.SL_GENERAL_DMG_TAKEN_CAP) * p;
		if (bleach) {
			multiplier *= 1.0 - fraction(BleachTuning.SL_BLEACH_DMG_TAKEN_CAP) * p;
		}
		return Math.max(fraction(BleachTuning.SL_DMG_TAKEN_FLOOR), multiplier);
	}

	/** The multiplier on bleach damage a player deals. Never below 1. */
	public static double damageDealt(int soulLevel) {
		return 1.0 + Math.max(0.0, BleachTuning.SL_BLEACH_DMG_DEALT_CAP) * progress(soulLevel);
	}

	/**
	 * Shikai drain in SP per second at this Soul Level.
	 *
	 * <p>Tapers onto {@link BleachTuning#SHIKAI_DRAIN_FLOOR} rather than onto zero. See that
	 * constant for why the difference matters more than it looks.
	 */
	public static double shikaiDrain(int soulLevel) {
		return BleachTuning.DRAIN_SHIKAI * drainMultiplier(soulLevel,
				BleachTuning.SHIKAI_DRAIN_TAPER_CAP, BleachTuning.SHIKAI_DRAIN_FLOOR);
	}

	/** Bankai drain in SP per second at this Soul Level. Never below half the posted rate. */
	public static double bankaiDrain(int soulLevel) {
		return BleachTuning.DRAIN_BANKAI * bankaiDrainMultiplier(soulLevel);
	}

	/**
	 * The Bankai taper on its own, for the drains that <em>replace</em>
	 * {@link BleachTuning#DRAIN_BANKAI} instead of adding to it — Shunsui's Act 3 is the one that
	 * exists today. Without this they would keep their flat rate while ordinary Bankai got cheaper,
	 * so levelling up would make the substituted state the expensive one.
	 */
	public static double bankaiDrainMultiplier(int soulLevel) {
		return drainMultiplier(soulLevel,
				BleachTuning.BANKAI_DRAIN_TAPER_CAP, BleachTuning.BANKAI_DRAIN_FLOOR);
	}

	/**
	 * The shared taper: the same {@link #progress} shape the damage multipliers use, so retuning
	 * {@link BleachTuning#SL_CURVE_K} moves the cost of a release and the value of a level together
	 * instead of letting them drift apart.
	 *
	 * @param taperCap the most of the drain the curve may remove, held to 0..1
	 * @param floor the least of the drain that always remains, held to 0..1
	 */
	private static double drainMultiplier(int soulLevel, double taperCap, double floor) {
		double kept = 1.0 - fraction(taperCap) * progress(soulLevel);
		return Math.min(1.0, Math.max(fraction(floor), kept));
	}

	private static double compute(int soulLevel) {
		return 1.0 - Math.exp(-Math.max(0.0, BleachTuning.SL_CURVE_K) * (soulLevel - 1));
	}

	/**
	 * Holds a config value to 0..1. A cap above 1 would turn a reduction into healing and a negative
	 * one would turn it into a penalty; neither is a thing anybody means to type.
	 */
	private static double fraction(double value) {
		return Math.min(1.0, Math.max(0.0, value));
	}
}
