package com.bleach.mod.race;

import java.util.List;
import java.util.Objects;

/**
 * One playable race — the chassis a kit hangs off. A Shinigami's kit is a character; a Quincy's kit
 * is a Schrift. What differs between the two is entirely in this record.
 *
 * <p><b>Deliberately free of Minecraft imports.</b> The environment reading lives in
 * {@link ReishiDensity} and the weapon factory in {@code RaceWeapons}, so this type stays pure data
 * and can be unit tested without booting a game.
 *
 * @param id                 stored in {@code SpiritualData.race}. <b>Shinigami must be 0</b> so every
 *                           existing save loads as one with no migration.
 * @param displayName        shown in the picker and the guide
 * @param tierNames          release tier names, index 0 being release 1. A race may declare fewer
 *                           than the state machine allows — one entry is legal, and is what an
 *                           Arrancar's single resurrección needs.
 * @param weaponPrefix       item registry prefix: {@code zanpakuto} · {@code heilig_bogen}
 * @param reishiSensitivity  0 ignores the environment entirely; 1 is full exposure. Fed to
 *                           {@link ReishiDensity}.
 * @param hasBlut            whether the Blut stance key does anything for this race
 */
public record Race(byte id, String displayName, List<String> tierNames, String weaponPrefix,
		double reishiSensitivity, boolean hasBlut) {

	public Race {
		Objects.requireNonNull(displayName, "race displayName");
		Objects.requireNonNull(tierNames, "race tierNames");
		Objects.requireNonNull(weaponPrefix, "race weaponPrefix");
		tierNames = List.copyOf(tierNames);
	}

	/**
	 * The name of a release tier for this race, or the empty string for the base state and for any
	 * tier this race does not declare.
	 *
	 * <p>Returns empty rather than throwing because callers are display code: a guide line or a HUD
	 * label asking about a tier that does not exist should render nothing, not crash a client.
	 */
	public String tierName(byte state) {
		int index = state - 1;
		return index < 0 || index >= tierNames.size() ? "" : tierNames.get(index);
	}
}
