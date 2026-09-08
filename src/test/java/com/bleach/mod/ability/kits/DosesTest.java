package com.bleach.mod.ability.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dose arithmetic behind Schrift D. Only the pure halves are reachable here — the ledger, the
 * decay pass and the dome all need a server, so they are covered by the manual pass.
 */
class DosesTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.DOSE_DAMAGE_PER = 0.09;
		BleachTuning.DOSE_MAX = 10;
		BleachTuning.DOSE_DECAY_TICKS = 60;
	}

	@Test
	void anUndosedTargetTakesNormalDamage() {
		assertEquals(1.0, Doses.damageTakenMultiplier(0), 1e-9);
	}

	@Test
	void eachDoseAddsItsShare() {
		assertEquals(1.09, Doses.damageTakenMultiplier(1), 1e-9);
		assertEquals(1.45, Doses.damageTakenMultiplier(5), 1e-9);
		assertEquals(1.90, Doses.damageTakenMultiplier(10), 1e-9);
	}

	@Test
	void theMultiplierIsCappedAtTheDoseMaximum() {
		assertEquals(Doses.damageTakenMultiplier(10), Doses.damageTakenMultiplier(50), 1e-9);
	}

	@Test
	void aNegativeCountIsTreatedAsUndosedRatherThanAsProtection() {
		assertEquals(1.0, Doses.damageTakenMultiplier(-3), 1e-9);
	}

	/**
	 * A config file can hold any number somebody types into it. A negative per-dose value must not
	 * turn a dose into damage reduction — that would make The Deathdealing heal what it hits.
	 */
	@Test
	void aNegativePerDoseConfigCannotTurnDosesIntoProtection() {
		BleachTuning.DOSE_DAMAGE_PER = -0.5;
		assertTrue(Doses.damageTakenMultiplier(5) >= 1.0);
	}

	@Test
	void stackingIsCappedAtTheMaximum() {
		assertEquals(3, Doses.stack(1, 2));
		assertEquals(10, Doses.stack(9, 5));
		assertEquals(10, Doses.stack(10, 1));
	}

	@Test
	void stackingIgnoresNegativeInputsInsteadOfSubtracting() {
		assertEquals(2, Doses.stack(2, -4));
		assertEquals(2, Doses.stack(-4, 2));
	}

	@Test
	void aZeroDoseMaximumDisablesTheMechanicRatherThanInverting() {
		BleachTuning.DOSE_MAX = 0;
		assertEquals(0, Doses.stack(0, 5));
		assertEquals(1.0, Doses.damageTakenMultiplier(5), 1e-9);
	}
}
