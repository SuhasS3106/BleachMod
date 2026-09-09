package com.bleach.mod.ability.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.ability.kits.KatenShikaiManager.CastOutcome;
import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Irooni's two testable halves — Adil's item 3, fixes A and C.
 *
 * <p>The reported symptom was "Shikai doesn't work". Three things could produce that and only two
 * are bugs: every failure path returned in silence (A), and the jump detector sampled a window a
 * real jump barely occupies (C). The third is that a cast needs a swing that <em>misses</em>, which
 * is working as designed and needs telling Adil rather than fixing.
 */
class KatenShikaiTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.SHUNSUI_IROONI_CAST_SP_COST = 15.0;
		BleachTuning.SHUNSUI_IROONI_CAST_COOLDOWN_TICKS = 60;
		BleachTuning.SHUNSUI_IROONI_CAST_RADIUS = 20.0;
	}

	// ---- A: no failure path may be silent ----------------------------------------

	@Test
	void aCastThatCanBePaidForAndHasTargetsGoesThrough() {
		assertEquals(CastOutcome.CAST, KatenShikaiManager.evaluateCast(60, 60, 100.0, 15.0, 3));
	}

	@Test
	void theCooldownIsReportedRatherThanSwallowed() {
		assertEquals(CastOutcome.COOLDOWN, KatenShikaiManager.evaluateCast(59, 60, 100.0, 15.0, 3));
	}

	@Test
	void anEmptyPoolIsReportedRatherThanSwallowed() {
		assertEquals(CastOutcome.NOT_ENOUGH_SP, KatenShikaiManager.evaluateCast(60, 60, 14.9, 15.0, 3));
	}

	@Test
	void swingingAtNobodyIsReportedRatherThanSwallowed() {
		assertEquals(CastOutcome.NO_TARGETS, KatenShikaiManager.evaluateCast(60, 60, 100.0, 15.0, 0));
	}

	@Test
	void theCooldownIsCheckedBeforeThePoolSoTheMessageMatchesTheRealBlocker() {
		// Both wrong at once: he should be told the thing that will clear first.
		assertEquals(CastOutcome.COOLDOWN, KatenShikaiManager.evaluateCast(0, 60, 0.0, 15.0, 0));
	}

	@Test
	void aFirstCastIsNotBlockedByACooldownThatNeverRan() {
		// No previous cast is passed as a huge elapsed time; it must not read as "on cooldown".
		assertEquals(CastOutcome.CAST, KatenShikaiManager.evaluateCast(Integer.MAX_VALUE, 60, 100.0, 15.0, 1));
	}

	// ---- C: the jump edge --------------------------------------------------------

	@Test
	void leavingTheGroundUpwardIsAJump() {
		assertTrue(KatenShikaiManager.isJumpEdge(true, false, 0.42));
	}

	@Test
	void aSlowJumpStillCounts() {
		// The whole point of the rewrite: the old check needed y > 0.4 in the one tick it sampled.
		assertTrue(KatenShikaiManager.isJumpEdge(true, false, 0.01));
	}

	@Test
	void steppingOffALedgeIsNotAJump() {
		assertFalse(KatenShikaiManager.isJumpEdge(true, false, -0.08));
		assertFalse(KatenShikaiManager.isJumpEdge(true, false, 0.0));
	}

	@Test
	void beingAlreadyAirborneIsNotAFreshJump() {
		// Only the edge counts, or one jump would break the rule twenty times over.
		assertFalse(KatenShikaiManager.isJumpEdge(false, false, 0.33));
	}

	@Test
	void standingStillIsNotAJump() {
		assertFalse(KatenShikaiManager.isJumpEdge(true, true, 0.0));
		assertFalse(KatenShikaiManager.isJumpEdge(true, true, 0.42));
	}

	@Test
	void landingIsNotAJump() {
		assertFalse(KatenShikaiManager.isJumpEdge(false, true, -0.5));
	}
}
