package com.bleach.mod.client;

import org.jetbrains.annotations.Nullable;

import com.bleach.mod.network.SpiritualSyncPayload;

/**
 * The last {@link SpiritualSyncPayload} the server sent, and the only source the HUD and the stats
 * screen read from. The client never computes a spiritual value of its own.
 */
public final class ClientSpiritualState {
	private ClientSpiritualState() {
	}

	@Nullable
	private static volatile SpiritualSyncPayload current;

	/**
	 * Server-reported master switch. Defaults to false so that the HUD cannot flash up in the gap
	 * between joining and the first {@code ModTogglePayload.State} arriving — on a vanilla server,
	 * or one where the mod is off, that packet never arrives at all and false is the right answer.
	 */
	private static volatile boolean enabled;

	public static void set(SpiritualSyncPayload payload) {
		current = payload;
	}

	/** Null until the first sync arrives, and again after leaving a world. */
	@Nullable
	public static SpiritualSyncPayload get() {
		return current;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void clear() {
		current = null;
		enabled = false;
	}
}
