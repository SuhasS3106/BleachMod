package com.bleach.mod.ability;

import org.jetbrains.annotations.Nullable;

/**
 * What a keypress asks the server to do. The wire carries the ordinal, so <b>order is protocol</b>:
 * append new actions at the end and never reorder or remove.
 *
 * <p>These are not abilities. Two of them (the flex edges) are channel bookkeeping and one is
 * inventory management; the mapping from action to {@link Ability} lives in
 * {@link AbilityDispatcher} because it depends on the player's kit.
 */
public enum AbilityAction {
	/** Universal blink. Requires no sword. */
	FLASH_STEP,
	/** Toggle the kit's Shikai on or off. */
	SHIKAI,
	/** Toggle the kit's Bankai on or off. */
	BANKAI,
	/** Rising edge of the hold-to-channel Flex key. */
	FLEX_START,
	/** Falling edge of the Flex key — also sent on screen open and disconnect. */
	FLEX_STOP,
	/** Swap the zanpakutō between the attachment and the main hand. */
	DRAW_SHEATHE,
	/** Rising edge of the hold-to-channel Aura Sense key — eyes closing. */
	SENSE_START,
	/** Falling edge of the Aura Sense key — also sent on screen open and disconnect. */
	SENSE_STOP,
	/** Rising edge of the hold-to-channel Hover key. */
	HOVER_START,
	/** Falling edge of the Hover key — also sent on screen open and disconnect. */
	HOVER_STOP;

	private static final AbilityAction[] BY_INDEX = values();

	/** Null for anything the client had no business sending. Never throws. */
	@Nullable
	public static AbilityAction byIndex(int index) {
		return index >= 0 && index < BY_INDEX.length ? BY_INDEX[index] : null;
	}
}
