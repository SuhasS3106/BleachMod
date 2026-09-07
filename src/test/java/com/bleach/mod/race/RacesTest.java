package com.bleach.mod.race;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RacesTest {

	@Test
	void shinigamiIsIdZeroSoExistingSavesLoadUnchanged() {
		assertEquals(0, Races.SHINIGAMI.id());
	}

	@Test
	void shinigamiIsBehaviourallyInert() {
		assertEquals(0.0, Races.SHINIGAMI.reishiSensitivity());
		assertFalse(Races.SHINIGAMI.hasBlut());
	}

	@Test
	void quincyHasBlutAndReadsTheEnvironment() {
		assertTrue(Races.QUINCY.hasBlut());
		assertTrue(Races.QUINCY.reishiSensitivity() > 0.0);
	}

	@Test
	void unknownIdFallsBackToShinigamiRatherThanThrowing() {
		assertSame(Races.SHINIGAMI, Races.byId((byte) 0));
		assertSame(Races.QUINCY, Races.byId((byte) 1));
		assertSame(Races.SHINIGAMI, Races.byId((byte) 99));
		assertSame(Races.SHINIGAMI, Races.byId((byte) -1));
	}

	@Test
	void tierNamesAreRaceSpecific() {
		assertEquals("Shikai", Races.SHINIGAMI.tierName((byte) 1));
		assertEquals("Bankai", Races.SHINIGAMI.tierName((byte) 2));
		assertEquals("Schrift", Races.QUINCY.tierName((byte) 1));
		assertEquals("Vollständig", Races.QUINCY.tierName((byte) 2));
	}

	@Test
	void baseStateHasNoTierName() {
		assertEquals("", Races.SHINIGAMI.tierName((byte) 0));
		assertEquals("", Races.QUINCY.tierName((byte) 0));
	}

	@Test
	void aRaceMayDeclareFewerTiersThanTheStateMachineAllows() {
		// The hedge for Adil: Arrancar resurrección is one release tier, not two.
		Race oneTier = new Race((byte) 7, "Arrancar", java.util.List.of("Resurrección"),
				"zanpakuto", 0.0, false);
		assertEquals("Resurrección", oneTier.tierName((byte) 1));
		assertEquals("", oneTier.tierName((byte) 2));
	}
}
