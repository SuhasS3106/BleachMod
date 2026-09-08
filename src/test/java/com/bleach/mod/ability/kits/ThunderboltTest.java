package com.bleach.mod.ability.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind Schrift T. Only the pure halves are reachable here — the strike itself
 * needs a {@code ServerLevel}, so the invulnerability-frame reset and the chain search are covered
 * by the manual pass rather than by these tests.
 */
class ThunderboltTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.THUNDER_BOLT_DAMAGE = 6.0;
		BleachTuning.THUNDER_COOLDOWN_TICKS = 60;
		BleachTuning.THUNDER_VOLL_COOLDOWN_TICKS = 20;
		BleachTuning.THUNDER_CHAIN_COUNT = 3;
		BleachTuning.THUNDER_CHAIN_FALLOFF = 0.5;
	}

	@Test
	void theStruckTargetTakesTheFullBolt() {
		assertEquals(6.0, ThunderboltTransform.chainDamage(6.0, 0, 0.5), 1e-9);
	}

	@Test
	void eachChainLinkKeepsTheFalloffOfTheOneBefore() {
		assertEquals(3.0, ThunderboltTransform.chainDamage(6.0, 1, 0.5), 1e-9);
		assertEquals(1.5, ThunderboltTransform.chainDamage(6.0, 2, 0.5), 1e-9);
		assertEquals(0.75, ThunderboltTransform.chainDamage(6.0, 3, 0.5), 1e-9);
	}

	@Test
	void aNegativeLinkIsTreatedAsThePrimaryRatherThanAmplifying() {
		assertEquals(6.0, ThunderboltTransform.chainDamage(6.0, -1, 0.5), 1e-9);
	}

	@Test
	void aZeroFalloffMakesChainLinksHarmlessInsteadOfThrowing() {
		assertEquals(0.0, ThunderboltTransform.chainDamage(6.0, 1, 0.0), 1e-9);
		assertEquals(6.0, ThunderboltTransform.chainDamage(6.0, 0, 0.0), 1e-9);
	}

	@Test
	void aNegativeFalloffIsClampedRatherThanFlippingTheSign() {
		assertEquals(0.0, ThunderboltTransform.chainDamage(6.0, 1, -0.5), 1e-9);
	}

	@Test
	void aPlayerWithNoRecordedBoltIsReady() {
		assertTrue(ThunderboltTransform.isReady(Integer.MIN_VALUE, 0, 60));
	}

	@Test
	void theCooldownBlocksUntilItHasFullyElapsed() {
		assertFalse(ThunderboltTransform.isReady(100, 159, 60));
		assertTrue(ThunderboltTransform.isReady(100, 160, 60));
		assertTrue(ThunderboltTransform.isReady(100, 161, 60));
	}

	@Test
	void vollstandigFiresThreeTimesAsOftenAsTheSchrift() {
		assertFalse(ThunderboltTransform.isReady(0, 19, BleachTuning.THUNDER_VOLL_COOLDOWN_TICKS));
		assertTrue(ThunderboltTransform.isReady(0, 20, BleachTuning.THUNDER_VOLL_COOLDOWN_TICKS));
		assertFalse(ThunderboltTransform.isReady(0, 20, BleachTuning.THUNDER_COOLDOWN_TICKS));
	}

	/**
	 * {@code /bleach test reishi} moves the world clock, and a player's tick counter can be reset by
	 * a dimension change. Either can leave {@code now} behind {@code last}; the player must come out
	 * ready rather than locked out until the counter catches up.
	 */
	@Test
	void aClockThatMovedBackwardsDoesNotLockThePlayerOut() {
		assertTrue(ThunderboltTransform.isReady(1000, 5, 60));
	}
}
