package com.bleach.mod.ability.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Schrift M — the arithmetic behind Gerard Valkyrie's stacks.
 *
 * <p>M is the only "stronger as you lose" identity in the mod, which makes every one of these
 * numbers a potential exploit rather than merely a tuning value. The two that matter most are the
 * stack ceiling and the death-save gate: without the first, being hit is unboundedly good, and
 * without the second the miracle is a free life.
 */
class MiracleTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.M_STACK_PER_DAMAGE = 1.0;
		BleachTuning.M_STACK_MAX = 20;
		BleachTuning.M_DMG_PER_STACK = 0.025;
		BleachTuning.M_HP_PER_STACK = 0.5;
		BleachTuning.M_VOLL_STACK_MULT = 2.0;
		BleachTuning.M_DMG_REDUCTION_PER_STACK = 0.01;
		BleachTuning.M_BURST_HEAL_PER_STACK = 0.5;
		BleachTuning.M_MIRACLE_MIN_STACKS = 10;
		BleachTuning.M_SCALE_MAX = 1.4;
	}

	// ---- gaining stacks ----------------------------------------------------------

	@Test
	void damageBecomesStacksOneForOne() {
		assertEquals(6, MiracleTransform.stacksFrom(6.0, 1.0, 1.0));
	}

	@Test
	void vollstandigDoublesTheRate() {
		assertEquals(12, MiracleTransform.stacksFrom(6.0, 1.0, 2.0));
	}

	@Test
	void aGrazeStillCountsForSomething() {
		// Rounding down would make chip damage worth exactly nothing, and chip damage is most of
		// what a bruiser eats.
		assertEquals(1, MiracleTransform.stacksFrom(0.4, 1.0, 1.0));
	}

	@Test
	void zeroDamageGrantsNothing() {
		assertEquals(0, MiracleTransform.stacksFrom(0.0, 1.0, 1.0));
		assertEquals(0, MiracleTransform.stacksFrom(-3.0, 1.0, 1.0));
	}

	@Test
	void stacksAreHeldAtTheCeiling() {
		assertEquals(20, MiracleTransform.clampStacks(999));
		assertEquals(0, MiracleTransform.clampStacks(-5));
		assertEquals(13, MiracleTransform.clampStacks(13));
	}

	// ---- what stacks are worth ---------------------------------------------------

	@Test
	void aFullStackIsHalfAgainAsMuchMelee() {
		assertEquals(0.50, MiracleTransform.damageBonus(20), 1e-9);
		assertEquals(0.0, MiracleTransform.damageBonus(0), 1e-9);
	}

	@Test
	void aFullStackIsFiveEmptyHearts() {
		assertEquals(10.0, MiracleTransform.bonusHp(20), 1e-9);
		assertEquals(0.0, MiracleTransform.bonusHp(0), 1e-9);
	}

	@Test
	void reductionIsCappedSoTheStackedFloorStaysAtFortyPercent() {
		// 20% of its own, compounding with the Soul Level floor of 50% → 0.40 worst case.
		assertEquals(0.20, MiracleTransform.damageReduction(20), 1e-9);
		assertEquals(0.0, MiracleTransform.damageReduction(0), 1e-9);
	}

	@Test
	void reductionCannotReachOneEvenOnAbsurdTuning() {
		BleachTuning.M_DMG_REDUCTION_PER_STACK = 5.0;
		double r = MiracleTransform.damageReduction(20);
		assertTrue(r < 1.0, "reduction must never zero incoming damage: " + r);
	}

	// ---- the giant ---------------------------------------------------------------

	@Test
	void sizeGrowsWithStacksFromNormalToTheCap() {
		assertEquals(1.0, MiracleTransform.scaleFor(0), 1e-9);
		assertEquals(1.4, MiracleTransform.scaleFor(20), 1e-9);
		assertEquals(1.2, MiracleTransform.scaleFor(10), 1e-9);
	}

	@Test
	void sizeNeverShrinksYouBelowNormal() {
		BleachTuning.M_SCALE_MAX = 0.5;
		assertTrue(MiracleTransform.scaleFor(20) >= 1.0,
				"a scale cap under 1 must not make Gerard tiny");
	}

	// ---- the miracle -------------------------------------------------------------

	@Test
	void theMiracleNeedsItsMinimumStacks() {
		assertFalse(MiracleTransform.canMiracle(9, false));
		assertTrue(MiracleTransform.canMiracle(10, false));
		assertTrue(MiracleTransform.canMiracle(20, false));
	}

	@Test
	void theMiracleIsNotReEarnableWithinOneVollstandig() {
		// Decided 2026-09-09: one save per entry. Rebuilding to the gate must not grant another.
		assertFalse(MiracleTransform.canMiracle(20, true));
	}
}
