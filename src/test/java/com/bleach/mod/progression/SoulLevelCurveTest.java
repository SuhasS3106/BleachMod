package com.bleach.mod.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The §E curve. These are the tests the old linear version could not have passed: the whole point of
 * the rewrite is that raising {@code SL_MAX} can no longer produce an invulnerable player, and that
 * is a property of the shape rather than of any one number.
 */
class SoulLevelCurveTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.SL_MAX = 100;
		BleachTuning.SL_CURVE_K = 0.035;
		BleachTuning.SL_GENERAL_DMG_TAKEN_CAP = 0.25;
		BleachTuning.SL_BLEACH_DMG_TAKEN_CAP = 0.3333;
		BleachTuning.SL_BLEACH_DMG_DEALT_CAP = 0.60;
		BleachTuning.SL_DMG_TAKEN_FLOOR = 0.50;
		SoulLevelCurve.rebuild();
	}

	@Test
	void levelOneScalesNothing() {
		assertEquals(0.0, SoulLevelCurve.progress(1), 1e-9);
		assertEquals(1.0, SoulLevelCurve.damageTaken(1, true), 1e-9);
		assertEquals(1.0, SoulLevelCurve.damageTaken(1, false), 1e-9);
		assertEquals(1.0, SoulLevelCurve.damageDealt(1), 1e-9);
	}

	@Test
	void theCurveApproachesButNeverReachesItsCeiling() {
		// 1 − exp(−0.035 × 99) ≈ 0.9687 — close enough that the last levels still pay, far enough
		// that the ceiling is never actually touched.
		assertEquals(0.9687, SoulLevelCurve.progress(100), 1e-4);
		assertTrue(SoulLevelCurve.progress(100) < 1.0);

		// Far past any cap anyone will set, progress rounds to 1 in a double — exp(−350) is smaller
		// than the gap between 1.0 and the next representable value. That is fine, and is exactly why
		// the ceiling has to be a cap on the multiplier rather than a promise about the curve: the
		// reduction still stops at SL_GENERAL/BLEACH_DMG_TAKEN_CAP, never past it.
		assertTrue(SoulLevelCurve.progress(10_000) <= 1.0);
		// 0.500025, not 0.5: SL_BLEACH_DMG_TAKEN_CAP is 0.3333 rather than a third, so the two
		// caps multiply out a hair above the floor rather than exactly onto it.
		assertEquals(0.5, SoulLevelCurve.damageTaken(10_000, true), 1e-4);
		assertEquals(1.60, SoulLevelCurve.damageDealt(10_000), 1e-9);
	}

	@Test
	void aCappedPlayerStillTakesHalfDamage() {
		// The bug this whole class exists to prevent: linear scaling zeroed bleach damage at SL 68.
		assertEquals(0.5131, SoulLevelCurve.damageTaken(100, true), 1e-4);
		assertTrue(SoulLevelCurve.damageTaken(100, true) >= BleachTuning.SL_DMG_TAKEN_FLOOR);
	}

	@Test
	void noLevelAnywhereCrossesTheFloor() {
		for (int level = 1; level <= BleachTuning.SL_MAX; level++) {
			assertTrue(SoulLevelCurve.damageTaken(level, true) >= BleachTuning.SL_DMG_TAKEN_FLOOR,
					"bleach damage taken at SL " + level);
			assertTrue(SoulLevelCurve.damageTaken(level, false) >= BleachTuning.SL_DMG_TAKEN_FLOOR,
					"general damage taken at SL " + level);
		}
	}

	@Test
	void theFloorHoldsAgainstAConfigThatWouldOtherwiseZeroIt() {
		BleachTuning.SL_GENERAL_DMG_TAKEN_CAP = 1.0;
		BleachTuning.SL_BLEACH_DMG_TAKEN_CAP = 1.0;
		assertEquals(0.5, SoulLevelCurve.damageTaken(100, true), 1e-9);
		assertEquals(0.5, SoulLevelCurve.damageTaken(100, false), 1e-9);
	}

	@Test
	void aNonsenseCapCannotTurnDamageIntoHealing() {
		BleachTuning.SL_GENERAL_DMG_TAKEN_CAP = 4.0;
		BleachTuning.SL_BLEACH_DMG_TAKEN_CAP = -2.0;
		double taken = SoulLevelCurve.damageTaken(100, true);
		assertTrue(taken >= 0.5, "clamped to the floor, not negative: " + taken);
	}

	@Test
	void damageDealtStopsShortOfDoubling() {
		// The linear version reached +198% at SL 100. This one asymptotes on SL_BLEACH_DMG_DEALT_CAP.
		assertEquals(1.5812, SoulLevelCurve.damageDealt(100), 1e-4);
		assertTrue(SoulLevelCurve.damageDealt(100)
				< 1.0 + BleachTuning.SL_BLEACH_DMG_DEALT_CAP);
	}

	@Test
	void twentyIsWorthLessThanItUsedToBe() {
		// Deliberate: the same progression is spread over five times the levels, so −42% at the old
		// cap becomes about −26% at the same level.
		assertEquals(0.7364, SoulLevelCurve.damageTaken(20, true), 1e-4);
		assertEquals(1.2914, SoulLevelCurve.damageDealt(20), 1e-4);
	}

	@Test
	void scalingIsMonotonic() {
		for (int level = 2; level <= BleachTuning.SL_MAX; level++) {
			assertTrue(SoulLevelCurve.damageTaken(level, true) < SoulLevelCurve.damageTaken(level - 1, true),
					"taken must keep falling at SL " + level);
			assertTrue(SoulLevelCurve.damageDealt(level) > SoulLevelCurve.damageDealt(level - 1),
					"dealt must keep rising at SL " + level);
		}
	}

	@Test
	void aLevelPastTheTableIsComputedRatherThanThrowing() {
		BleachTuning.SL_MAX = 20;
		SoulLevelCurve.rebuild();
		// A save from before someone shrank the cap. It must still answer, and answer the same thing.
		assertEquals(0.9687, SoulLevelCurve.progress(100), 1e-4);
	}
}
