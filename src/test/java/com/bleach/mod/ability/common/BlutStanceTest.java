package com.bleach.mod.ability.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlutStanceTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.BLUT_VENE_REDUCTION = 0.25;
		BleachTuning.BLUT_ARTERIE_BONUS = 0.30;
	}

	@Test
	void cycleGoesOffVeneArterieOff() {
		assertEquals(Blut.VENE, Blut.cycle(Blut.OFF));
		assertEquals(Blut.ARTERIE, Blut.cycle(Blut.VENE));
		assertEquals(Blut.OFF, Blut.cycle(Blut.ARTERIE));
	}

	@Test
	void anUnknownStanceCyclesBackToOff() {
		assertEquals(Blut.OFF, Blut.cycle((byte) 42));
	}

	@Test
	void veneReducesDamageTakenAndArterieDoesNot() {
		assertEquals(0.75, Blut.damageTakenMultiplier(Blut.VENE), 1e-9);
		assertEquals(1.0, Blut.damageTakenMultiplier(Blut.ARTERIE), 1e-9);
		assertEquals(1.0, Blut.damageTakenMultiplier(Blut.OFF), 1e-9);
	}

	@Test
	void arterieRaisesDamageDealtAndVeneDoesNot() {
		assertEquals(1.30, Blut.damageDealtMultiplier(Blut.ARTERIE), 1e-9);
		assertEquals(1.0, Blut.damageDealtMultiplier(Blut.VENE), 1e-9);
		assertEquals(1.0, Blut.damageDealtMultiplier(Blut.OFF), 1e-9);
	}

	@Test
	void theTwoStancesAreMutuallyExclusiveByConstruction() {
		// There is no stance for which both multipliers move.
		for (byte stance = 0; stance <= 2; stance++) {
			boolean defensive = Blut.damageTakenMultiplier(stance) != 1.0;
			boolean offensive = Blut.damageDealtMultiplier(stance) != 1.0;
			assertEquals(false, defensive && offensive, "stance " + stance + " is both");
		}
	}

	@Test
	void reductionIsFlooredSoAConfigCannotTurnDamageIntoHealing() {
		BleachTuning.BLUT_VENE_REDUCTION = 5.0;
		assertEquals(0.0, Blut.damageTakenMultiplier(Blut.VENE), 1e-9);
	}

	@Test
	void drainFallsWithSoulLevelAndIsFlooredAtZero() {
		BleachTuning.BLUT_DRAIN_BASE = 2.0;
		BleachTuning.BLUT_DRAIN_PER_LEVEL = 0.06;
		assertEquals(2.0 / BleachTuning.TICKS_PER_SECOND, Blut.tickCost(1), 1e-9);
		assertEquals(1.4 / BleachTuning.TICKS_PER_SECOND, Blut.tickCost(11), 1e-9);
		// A high enough level must never pay the player SP back.
		assertEquals(0.0, Blut.tickCost(1000), 1e-9);
	}
}
