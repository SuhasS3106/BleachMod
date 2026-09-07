package com.bleach.mod.race;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReishiDensityTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.REISHI_MULT_MIN = 0.45;
		BleachTuning.REISHI_MULT_MAX = 1.35;
		BleachTuning.REISHI_SKYLIGHT_WEIGHT = 0.6;
		BleachTuning.REISHI_SKY_ACCESS_WEIGHT = 0.4;
		BleachTuning.REISHI_SUBMERGED_PENALTY = 0.7;
		BleachTuning.REISHI_NO_SKY_DIMENSION_PENALTY = 0.6;
	}

	@Test
	void zeroSensitivityIsAlwaysExactlyOne() {
		// The Shinigami guarantee. Any environment, any weather, always 1.0.
		assertEquals(1.0, ReishiDensity.multiplier(0.0, 0, false, true, false));
		assertEquals(1.0, ReishiDensity.multiplier(0.0, 15, true, false, true));
	}

	@Test
	void openSkyInFullDaylightReachesTheCeiling() {
		assertEquals(1.35, ReishiDensity.multiplier(1.0, 15, true, false, true), 1e-9);
	}

	@Test
	void sealedUndergroundReachesTheFloor() {
		assertEquals(0.45, ReishiDensity.multiplier(1.0, 0, false, false, true), 1e-9);
	}

	@Test
	void skyAccessAtNightStillBeatsACave() {
		double night = ReishiDensity.multiplier(1.0, 0, true, false, true);
		double cave = ReishiDensity.multiplier(1.0, 0, false, false, true);
		assertTrue(night > cave, "standing outside at midnight must beat a cave");
	}

	@Test
	void submergedAppliesItsPenaltyOnTop() {
		double dry = ReishiDensity.multiplier(1.0, 15, true, false, true);
		double wet = ReishiDensity.multiplier(1.0, 15, true, true, true);
		assertEquals(dry * 0.7, wet, 1e-9);
	}

	@Test
	void aDimensionWithNoSkyAppliesItsPenaltyOnTop() {
		double overworld = ReishiDensity.multiplier(1.0, 15, true, false, true);
		double nether = ReishiDensity.multiplier(1.0, 15, true, false, false);
		assertEquals(overworld * 0.6, nether, 1e-9);
	}

	@Test
	void penaltiesCompound() {
		double best = ReishiDensity.multiplier(1.0, 15, true, false, true);
		double worst = ReishiDensity.multiplier(1.0, 15, true, true, false);
		assertEquals(best * 0.7 * 0.6, worst, 1e-9);
	}

	@Test
	void partialSensitivityInterpolatesTowardOne() {
		double full = ReishiDensity.multiplier(1.0, 0, false, false, true);
		double half = ReishiDensity.multiplier(0.5, 0, false, false, true);
		assertEquals(1.0 + (full - 1.0) * 0.5, half, 1e-9);
	}

	@Test
	void neverReturnsANegativeOrZeroMultiplier() {
		BleachTuning.REISHI_SUBMERGED_PENALTY = -5.0;   // a config file can hold anything
		assertTrue(ReishiDensity.multiplier(1.0, 0, false, true, false) > 0.0);
	}

	@Test
	void ceilingHoldsEvenWhenExposureWeightsSumAboveOne() {
		// The two weights are meant to sum to 1.0 (BALANCE.md), but they are public static non-final
		// knobs a balance pass is free to retune independently. If their sum climbs above 1.0, the
		// documented ceiling must still hold rather than being blown past by luck of the current
		// defaults.
		BleachTuning.REISHI_SKYLIGHT_WEIGHT = 0.9;
		BleachTuning.REISHI_SKY_ACCESS_WEIGHT = 0.9;
		double result = ReishiDensity.multiplier(1.0, 15, true, false, true);
		assertEquals(BleachTuning.REISHI_MULT_MAX, result, 1e-9);
	}
}
