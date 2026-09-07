package com.bleach.mod.race;

import com.bleach.mod.tuning.BleachTuning;

/**
 * How much ambient reishi a position offers, as a multiplier on SP regen · design §3.5.
 *
 * <p>Pure arithmetic with no Minecraft imports, so it is unit tested directly. The caller reads the
 * four environment facts off the world and passes them in; that split is what keeps the formula
 * testable and the world access in one place.
 */
public final class ReishiDensity {
	private ReishiDensity() {
	}

	private static final int MAX_SKY_LIGHT = 15;

	/**
	 * @param sensitivity     {@link Race#reishiSensitivity()} — 0 ignores the environment entirely
	 * @param skyLight        sky light at the player's position, 0..15
	 * @param hasSkyAccess    whether there is a clear column to the sky above the player
	 * @param submerged       whether the player's eyes are in a fluid
	 * @param dimensionHasSky whether this dimension has a natural sky at all
	 * @return a strictly positive multiplier. Exactly 1.0 when {@code sensitivity} is 0.
	 */
	public static double multiplier(double sensitivity, int skyLight, boolean hasSkyAccess,
			boolean submerged, boolean dimensionHasSky) {
		if (sensitivity <= 0.0) {
			return 1.0;
		}

		double light = Math.max(0, Math.min(MAX_SKY_LIGHT, skyLight)) / (double) MAX_SKY_LIGHT;
		double exposure = BleachTuning.REISHI_SKYLIGHT_WEIGHT * light
				+ BleachTuning.REISHI_SKY_ACCESS_WEIGHT * (hasSkyAccess ? 1.0 : 0.0);

		double raw = BleachTuning.REISHI_MULT_MIN
				+ (BleachTuning.REISHI_MULT_MAX - BleachTuning.REISHI_MULT_MIN) * exposure;

		if (submerged) {
			raw *= BleachTuning.REISHI_SUBMERGED_PENALTY;
		}
		if (!dimensionHasSky) {
			raw *= BleachTuning.REISHI_NO_SKY_DIMENSION_PENALTY;
		}

		// Interpolate from 1.0 by sensitivity, so a partially sensitive race is partially affected
		// and a zero-sensitivity race is exactly unaffected.
		double scaled = 1.0 + (raw - 1.0) * Math.max(0.0, Math.min(1.0, sensitivity));

		// A config file can hold any number somebody types into it, and a non-positive regen
		// multiplier would freeze the pool outright rather than merely slowing it.
		return Math.max(0.01, scaled);
	}
}
