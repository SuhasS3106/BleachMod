package com.bleach.mod.progression;

import com.bleach.mod.tuning.BleachTuning;

/**
 * The level curve, {@code spxToNext(L) = round(SPX_CURVE_COEFF × L ^ SPX_CURVE_EXPONENT)}.
 *
 * <p>Precomputed at load time. {@code Math.pow} must never run in a kill handler or a sync tick.
 * Phase 5 builds the award logic on top of this; Phase 1 needs only the lookup so the HUD payload
 * can report progress honestly.
 */
public final class SpxTable {
	private SpxTable() {
	}

	/** Indexed by level: {@code TO_NEXT[L]} is the SPX needed to go from L to L+1. */
	private static int[] toNext = new int[0];

	/** Recompute from the current tuning values. Registered as a {@link BleachTuning} reload hook. */
	public static void rebuild() {
		int max = Math.max(1, BleachTuning.SL_MAX);
		int[] table = new int[max + 1];
		for (int level = 1; level < max; level++) {
			table[level] = (int) Math.round(
					BleachTuning.SPX_CURVE_COEFF * Math.pow(level, BleachTuning.SPX_CURVE_EXPONENT));
		}
		// The cap has no next level; leaving it zero makes "is capped" a value check, not a branch.
		toNext = table;
	}

	/** SPX required to advance from {@code level} to {@code level + 1}, or 0 at the cap. */
	public static int toNext(int level) {
		int[] table = toNext;
		if (level < 1 || level >= table.length) {
			return 0;
		}
		return table[level];
	}
}
