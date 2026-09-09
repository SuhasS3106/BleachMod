package com.bleach.mod.ability.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind Gerard's canon kit — reflection, probability, and the beam's geometry.
 *
 * <p>Two of these are guards rather than numbers. Reflection that could exceed the damage that
 * caused it, or a negate chance that could reach 1.0, both turn a bruiser into something nobody can
 * fight; and neither failure is visible until someone is standing in front of it.
 */
class MiracleAbilitiesTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.M_STACK_MAX = 20;
		BleachTuning.M_REFLECT_BASE = 0.30;
		BleachTuning.M_REFLECT_PER_STACK = 0.02;
		BleachTuning.M_PROB_BASE = 0.05;
		BleachTuning.M_PROB_PER_MISSING_HP = 0.30;
		BleachTuning.M_PROB_PER_ENEMY = 0.05;
		BleachTuning.M_PROB_MAX = 0.35;
	}

	// ---- Hoffnung's Reflection ---------------------------------------------------

	@Test
	void reflectionStartsAtTheBaseWithNoStacks() {
		assertEquals(0.30, MiracleAbilities.reflectFraction(0), 1e-9);
	}

	@Test
	void afullStackReflectsSeventyPercent() {
		assertEquals(0.70, MiracleAbilities.reflectFraction(20), 1e-9);
	}

	@Test
	void reflectionGrowsWithEveryStack() {
		double previous = -1.0;
		for (int s = 0; s <= 20; s++) {
			double r = MiracleAbilities.reflectFraction(s);
			assertTrue(r > previous, "reflection did not rise at " + s);
			previous = r;
		}
	}

	@Test
	void reflectionCanNeverExceedTheHitThatCausedIt() {
		// Otherwise two Gerards hitting each other is an amplifying loop, and one Gerard is
		// unapproachable in melee whatever weapon you brought.
		BleachTuning.M_REFLECT_BASE = 5.0;
		BleachTuning.M_REFLECT_PER_STACK = 5.0;
		assertTrue(MiracleAbilities.reflectFraction(20) <= 1.0);
	}

	@Test
	void aNegativeTuningCannotHealTheAttacker() {
		BleachTuning.M_REFLECT_BASE = -2.0;
		BleachTuning.M_REFLECT_PER_STACK = -1.0;
		assertTrue(MiracleAbilities.reflectFraction(10) >= 0.0);
	}

	// ---- The Miracle -------------------------------------------------------------

	@Test
	void atFullHealthAloneTheMiracleIsRare() {
		assertEquals(0.05, MiracleAbilities.miracleChance(1.0, 1), 1e-9);
	}

	@Test
	void theWorseTheOddsTheMoreLikelyItIs() {
		// Half health, alone: 0.05 + 0.30 x 0.5 = 0.20
		assertEquals(0.20, MiracleAbilities.miracleChance(0.5, 1), 1e-9);
	}

	@Test
	void beingOutnumberedCountsAsBadOdds() {
		// Full health, three enemies: 0.05 + 0.05 x 2 = 0.15
		assertEquals(0.15, MiracleAbilities.miracleChance(1.0, 3), 1e-9);
	}

	@Test
	void theChanceIsCappedSoTheKitStaysFightable() {
		assertEquals(0.35, MiracleAbilities.miracleChance(0.0, 20), 1e-9);
	}

	@Test
	void theChanceIsNeverNegativeAndNeverCertain() {
		BleachTuning.M_PROB_MAX = 5.0;
		assertTrue(MiracleAbilities.miracleChance(0.0, 99) < 1.0);
		BleachTuning.M_PROB_BASE = -1.0;
		assertTrue(MiracleAbilities.miracleChance(1.0, 1) >= 0.0);
	}

	@Test
	void aLoneDefenderIsNotTreatedAsOutnumbered() {
		// enemies of 0 or 1 must both mean "nobody extra", not a negative contribution.
		assertEquals(MiracleAbilities.miracleChance(1.0, 1), MiracleAbilities.miracleChance(1.0, 0), 1e-9);
	}

	// ---- Heilig Pfeil geometry ---------------------------------------------------

	@Test
	void somethingOnTheBeamIsHit() {
		// Beam from origin along +X; target 10 blocks along it, dead centre.
		assertEquals(0.0, MiracleAbilities.distanceToRay(10, 0, 0, 1, 0, 0), 1e-9);
	}

	@Test
	void somethingBesideTheBeamIsMeasuredByItsOffset() {
		assertEquals(3.0, MiracleAbilities.distanceToRay(10, 3, 0, 1, 0, 0), 1e-9);
	}

	@Test
	void somethingBehindTheFiringPointIsNotOnTheBeam() {
		// Clamped at the origin rather than projecting backwards, so a target behind Gerard is
		// measured from him and not from an imaginary beam out of his back.
		assertEquals(10.0, MiracleAbilities.distanceToRay(-10, 0, 0, 1, 0, 0), 1e-9);
	}

	@Test
	void aZeroLengthDirectionDoesNotProduceNaN() {
		double d = MiracleAbilities.distanceToRay(5, 0, 0, 0, 0, 0);
		assertFalse(Double.isNaN(d), "degenerate direction produced NaN");
	}

	// ---- the swing arc, kept from the previous moveset ----------------------------

	@Test
	void deadAheadIsInTheArcAndBehindIsNot() {
		assertTrue(MiracleAbilities.inArc(1.0, 60.0));
		assertFalse(MiracleAbilities.inArc(-1.0, 60.0));
	}
}
