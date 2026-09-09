package com.bleach.mod.ability.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Jakuhō Raikōben's blast — the two decisions the detonation makes per target.
 *
 * <p>The core is a <em>mechanic</em> and the falloff is <em>damage</em>. That split is stated in
 * {@code BleachDamage}'s class note and in PRD §2.4, and the implementation had never honoured it:
 * both rings fired {@code SPIRIT_PRESSURE}, which is mitigable, so 60 raw damage arrived as about
 * 11 against Protection IV netherite — a fifth of its value against exactly the people it is
 * aimed at, while an unarmoured mob took the whole thing.
 */
class SuiFengBankaiTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.SUI_BANKAI_LETHAL_RADIUS = 12.0;
		BleachTuning.SUI_BANKAI_FALLOFF_RADIUS = 24.0;
		BleachTuning.SUI_BANKAI_DMG_INNER = 60.0;
		BleachTuning.SUI_BANKAI_DMG_OUTER = 12.0;
	}

	// ---- which ring a target is in -----------------------------------------------

	@Test
	void groundZeroIsTheCore() {
		assertTrue(SuiFengTransform.isCoreHit(0.0, 12.0));
	}

	@Test
	void theCoreIncludesItsOwnEdge() {
		assertTrue(SuiFengTransform.isCoreHit(12.0, 12.0));
	}

	@Test
	void oneBlockPastTheCoreIsFalloff() {
		assertFalse(SuiFengTransform.isCoreHit(12.5, 12.0));
		assertFalse(SuiFengTransform.isCoreHit(24.0, 12.0));
	}

	// ---- how much the blast deals ------------------------------------------------

	@Test
	void theWholeCoreTakesTheFullBlast() {
		assertEquals(60.0, SuiFengTransform.blastDamage(0.0, 12.0, 24.0, 60.0, 12.0), 1e-6);
		assertEquals(60.0, SuiFengTransform.blastDamage(12.0, 12.0, 24.0, 60.0, 12.0), 1e-6);
	}

	@Test
	void theOuterEdgeTakesTheOuterValue() {
		assertEquals(12.0, SuiFengTransform.blastDamage(24.0, 12.0, 24.0, 60.0, 12.0), 1e-6);
	}

	@Test
	void halfwayOutIsHalfwayBetween() {
		// (18 - 12) / (24 - 12) = 0.5 → lerp(0.5, 60, 12) = 36
		assertEquals(36.0, SuiFengTransform.blastDamage(18.0, 12.0, 24.0, 60.0, 12.0), 1e-6);
	}

	@Test
	void fallingOffNeverGoesBelowTheOuterValue() {
		// Nothing past the falloff radius is collected today, but a tuning pass that shrinks the
		// radius under a target already being iterated must not produce negative damage.
		assertEquals(12.0, SuiFengTransform.blastDamage(40.0, 12.0, 24.0, 60.0, 12.0), 1e-6);
	}

	@Test
	void damageOnlyEverFallsWithDistance() {
		double previous = Double.MAX_VALUE;
		for (double d = 0.0; d <= 24.0; d += 0.5) {
			double dmg = SuiFengTransform.blastDamage(d, 12.0, 24.0, 60.0, 12.0);
			assertTrue(dmg <= previous, "blast damage rose at " + d);
			previous = dmg;
		}
	}

	@Test
	void aDegenerateRadiusPairCannotDivideByZero() {
		// lethal == falloff would be 0/0 in the lerp. Everything inside is simply core.
		double dmg = SuiFengTransform.blastDamage(12.0, 12.0, 12.0, 60.0, 12.0);
		assertEquals(60.0, dmg, 1e-6);
	}
}
