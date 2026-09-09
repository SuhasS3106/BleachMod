package com.bleach.mod.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The release-drain taper — Adil's item 7, built against the §E curve rather than beside it.
 *
 * <p>He asked for a Shikai drain that "becomes 0" around SL 60–70. Taken literally that is the
 * same defect {@link SoulLevelCurve} exists to prevent: a line that reaches zero makes the stance
 * permanently free the moment the cap moves past the crossing. So the drain tapers onto a floor it
 * cannot pass, and the floor is low enough that a high-level Shikai <em>reads</em> as free without
 * ever being free.
 */
class DrainTaperTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.SL_MAX = 100;
		BleachTuning.SL_CURVE_K = 0.035;
		BleachTuning.DRAIN_SHIKAI = 1.5;
		BleachTuning.DRAIN_BANKAI = 5.0;
		BleachTuning.SHIKAI_DRAIN_TAPER_CAP = 0.99;
		BleachTuning.SHIKAI_DRAIN_FLOOR = 0.10;
		BleachTuning.BANKAI_DRAIN_TAPER_CAP = 0.54;
		BleachTuning.BANKAI_DRAIN_FLOOR = 0.50;
		SoulLevelCurve.rebuild();
	}

	@Test
	void levelOnePaysTheFullPostedRate() {
		assertEquals(1.5, SoulLevelCurve.shikaiDrain(1), 1e-9);
		assertEquals(5.0, SoulLevelCurve.bankaiDrain(1), 1e-9);
	}

	@Test
	void shikaiReadsAsFreeByTheLevelAdilAskedFor() {
		// 0.17/s against a 740 SP pool is 71 minutes of Shikai. Free enough.
		assertEquals(0.1731, SoulLevelCurve.shikaiDrain(65), 1e-4);
	}

	@Test
	void shikaiNeverActuallyReachesZero() {
		double floor = BleachTuning.DRAIN_SHIKAI * BleachTuning.SHIKAI_DRAIN_FLOOR;
		assertEquals(0.15, SoulLevelCurve.shikaiDrain(100), 1e-9);
		for (int level = 1; level <= BleachTuning.SL_MAX; level++) {
			assertTrue(SoulLevelCurve.shikaiDrain(level) >= floor,
					"shikai drain at SL " + level + " fell through the floor");
		}
		assertTrue(SoulLevelCurve.shikaiDrain(10_000) > 0.0);
	}

	@Test
	void bankaiStaysACommitmentAtEveryLevel() {
		// Half the posted rate is the most the curve may ever give back. 2.5/s against 1090 SP is
		// 7 minutes at the cap, against 20 seconds at SL 1 — longer, never unlimited.
		assertEquals(3.6885, SoulLevelCurve.bankaiDrain(20), 1e-4);
		assertEquals(2.5874, SoulLevelCurve.bankaiDrain(65), 1e-4);
		assertEquals(2.5, SoulLevelCurve.bankaiDrain(100), 1e-9);

		double floor = BleachTuning.DRAIN_BANKAI * BleachTuning.BANKAI_DRAIN_FLOOR;
		for (int level = 1; level <= BleachTuning.SL_MAX; level++) {
			assertTrue(SoulLevelCurve.bankaiDrain(level) >= floor,
					"bankai drain at SL " + level + " fell through the floor");
		}
	}

	@Test
	void bankaiAlwaysCostsMoreThanShikai() {
		for (int level = 1; level <= BleachTuning.SL_MAX; level++) {
			assertTrue(SoulLevelCurve.bankaiDrain(level) > SoulLevelCurve.shikaiDrain(level),
					"bankai must stay the expensive stance at SL " + level);
		}
	}

	@Test
	void drainOnlyEverFalls() {
		for (int level = 2; level <= BleachTuning.SL_MAX; level++) {
			assertTrue(SoulLevelCurve.shikaiDrain(level) <= SoulLevelCurve.shikaiDrain(level - 1),
					"shikai drain rose at SL " + level);
			assertTrue(SoulLevelCurve.bankaiDrain(level) <= SoulLevelCurve.bankaiDrain(level - 1),
					"bankai drain rose at SL " + level);
		}
	}

	@Test
	void aTaperCapOfOneStillCannotZeroTheDrain() {
		// The literal reading of item 7, defended against: even told to remove all of it, the floor
		// is what answers.
		BleachTuning.SHIKAI_DRAIN_TAPER_CAP = 1.0;
		BleachTuning.BANKAI_DRAIN_TAPER_CAP = 1.0;
		assertEquals(0.15, SoulLevelCurve.shikaiDrain(100), 1e-9);
		assertEquals(2.5, SoulLevelCurve.bankaiDrain(100), 1e-9);
	}

	@Test
	void aNonsenseCapCannotTurnDrainIntoRegen() {
		BleachTuning.SHIKAI_DRAIN_TAPER_CAP = 4.0;
		BleachTuning.BANKAI_DRAIN_TAPER_CAP = -2.0;
		assertTrue(SoulLevelCurve.shikaiDrain(100) > 0.0);
		// A negative cap would otherwise make levelling *raise* the bill above the posted rate.
		assertTrue(SoulLevelCurve.bankaiDrain(100) <= BleachTuning.DRAIN_BANKAI);
	}

	@Test
	void theBankaiMultiplierIsExposedForDrainsThatReplaceIt() {
		// Shunsui's Act 3 drain substitutes for DRAIN_BANKAI rather than adding to it, so it has to
		// taper on the same curve or Act 3 quietly becomes the expensive way to be in Bankai.
		assertEquals(1.0, SoulLevelCurve.bankaiDrainMultiplier(1), 1e-9);
		assertEquals(0.5175, SoulLevelCurve.bankaiDrainMultiplier(65), 1e-4);
		assertEquals(0.50, SoulLevelCurve.bankaiDrainMultiplier(100), 1e-9);
		assertEquals(BleachTuning.DRAIN_BANKAI * SoulLevelCurve.bankaiDrainMultiplier(65),
				SoulLevelCurve.bankaiDrain(65), 1e-9);
	}

	@Test
	void raisingTheCapCannotMakeAnyLevelFree() {
		// The §12.3 lesson as a test: the shape has to hold at a cap nobody has tried yet.
		BleachTuning.SL_MAX = 1000;
		SoulLevelCurve.rebuild();
		assertEquals(0.15, SoulLevelCurve.shikaiDrain(1000), 1e-9);
		assertEquals(2.5, SoulLevelCurve.bankaiDrain(1000), 1e-9);
	}
}
