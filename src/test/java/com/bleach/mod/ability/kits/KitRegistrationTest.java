package com.bleach.mod.ability.kits;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * The check that game init does, done where a test can see it.
 *
 * <p>{@code AbilityRegistry.register} throws on a duplicate ability id, and a kit registers its two
 * tiers separately — so giving both tiers the kit's own id is a crash at
 * {@code Initializing game}, with every unit test green right up to the moment the client starts.
 * That is exactly what shipped for Schrift M on 2026-09-09.
 *
 * <p>One assertion per kit, rather than a loop over a registry, because building the registry is
 * what needs a running game in the first place.
 */
class KitRegistrationTest {

	@Test
	void miracleGivesEachTierItsOwnAbilityId() {
		assertNotEquals(MiracleTransform.SCHRIFT_ID, MiracleTransform.VOLLSTANDIG_ID);
	}

	@Test
	void thunderboltGivesEachTierItsOwnAbilityId() {
		assertNotEquals(ThunderboltTransform.SCHRIFT_ID, ThunderboltTransform.VOLLSTANDIG_ID);
	}

	@Test
	void noTierBorrowsItsKitId() {
		// The specific mistake: a tier constructed with BleachKits.MIRACLE rather than its own id.
		// Both tiers then collide with each other on the second registerKit call.
		assertNotEquals(BleachKits.MIRACLE, MiracleTransform.SCHRIFT_ID);
		assertNotEquals(BleachKits.MIRACLE, MiracleTransform.VOLLSTANDIG_ID);
		assertNotEquals(BleachKits.THUNDERBOLT, ThunderboltTransform.SCHRIFT_ID);
		assertNotEquals(BleachKits.THUNDERBOLT, ThunderboltTransform.VOLLSTANDIG_ID);
	}

	@Test
	void everyQuincyTierIdIsDistinctFromEveryOther() {
		assertNotEquals(MiracleTransform.SCHRIFT_ID, ThunderboltTransform.SCHRIFT_ID);
		assertNotEquals(MiracleTransform.VOLLSTANDIG_ID, ThunderboltTransform.VOLLSTANDIG_ID);
		assertNotEquals(MiracleTransform.SCHRIFT_ID, ThunderboltTransform.VOLLSTANDIG_ID);
		assertNotEquals(MiracleTransform.VOLLSTANDIG_ID, ThunderboltTransform.SCHRIFT_ID);
	}
}
