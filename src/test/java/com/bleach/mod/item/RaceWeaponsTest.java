package com.bleach.mod.item;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.race.RaceWeapons;
import com.bleach.mod.race.Races;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Which kit's weapon is which.
 *
 * <p>Race decided this for everyone until Gerard. He is a swordsman on a race of archers, so the
 * mapping grew a per-kit escape hatch — and the risk of an escape hatch is that it leaks. These
 * assert it applies to exactly one kit, which is the whole of what this change could break in
 * Suhas's and Adil's work.
 *
 * <p>Asserted on the decision rather than on constructed items: building an {@link net.minecraft.world.item.Item}
 * touches {@code DataComponents} and needs a bootstrapped game, which no test here has.
 */
class RaceWeaponsTest {

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("bleach_mod", path);
	}

	@Test
	void gerardIsTheOneKitThatCarriesASword() {
		assertTrue(RaceWeapons.carriesHoffnung(id("miracle")));
	}

	@Test
	void noOtherQuincyDoes() {
		assertFalse(RaceWeapons.carriesHoffnung(id("thunderbolt")));
		assertFalse(RaceWeapons.carriesHoffnung(id("deathdealing")));
	}

	@Test
	void noShinigamiDoes() {
		for (String kit : new String[] {
				"ichigo", "yamamoto", "suifeng", "rukia", "shinji",
				"aizen", "tosen", "gin", "shunsui" }) {
			assertFalse(RaceWeapons.carriesHoffnung(id(kit)),
					"shinigami kit " + kit + " must not have been handed Hoffnung");
		}
	}

	@Test
	void theHatchIsKeyedOnTheKitAndNotTheNamespace() {
		assertFalse(RaceWeapons.carriesHoffnung(
				ResourceLocation.fromNamespaceAndPath("minecraft", "miracle")));
	}

	@Test
	void raceStillDecidesForEveryoneElse() {
		assertTrue(RaceWeapons.carriesBow(Races.QUINCY));
		assertFalse(RaceWeapons.carriesBow(Races.SHINIGAMI));
	}
}
