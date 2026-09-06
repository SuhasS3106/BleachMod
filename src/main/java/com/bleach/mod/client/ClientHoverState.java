package com.bleach.mod.client;

import com.bleach.mod.network.SpiritualSyncPayload;

/**
 * The client half of the hover handshake · {@code Hover}.
 *
 * <p>Two booleans have to agree before {@code LocalPlayerHoverMixin} takes the player's movement:
 *
 * <ul>
 *   <li><b>the key is down</b>, which only the client can see, and</li>
 *   <li><b>the server said yes</b>, which arrives on the sync payload and is re-asserted with every
 *       packet of the hold.</li>
 * </ul>
 *
 * <p>Requiring both is what keeps the client from holding itself up for free. The server drops the
 * channel when the pool runs out, when the player lands, when the mod is switched off or when they
 * die — and in every one of those cases the next payload says {@code hovering = false} and the
 * client falls, whatever the key is doing. The key half matters for the other direction: a release
 * has to stop the hover on the frame it happens rather than a round trip later, because a hover the
 * player has let go of is a hover they are already trying to fall out of.
 */
public final class ClientHoverState {
	private ClientHoverState() {
	}

	private static volatile boolean keyHeld;

	public static void setKeyHeld(boolean held) {
		keyHeld = held;
	}

	/** Whether the local player should be holding themselves up this tick. */
	public static boolean isHovering() {
		if (!keyHeld || !ClientSpiritualState.isEnabled()) {
			return false;
		}
		SpiritualSyncPayload payload = ClientSpiritualState.get();
		return payload != null && payload.hovering();
	}

	/**
	 * Whether the hover key is down at all, server permission or not.
	 *
	 * <p>Read by {@code LocalPlayerDropMixin} and nothing else. Hover shares its key with vanilla's
	 * drop-item, so a press in mid-air has to mean one thing or the other: airborne it is a hover and
	 * the drop is swallowed, on the ground it is still a drop. That decision cannot wait for the
	 * server's answer — the drop fires on the same frame as the press, and a round trip later the
	 * item is already on the floor — so this half is deliberately the unvalidated one. The cost of
	 * being wrong is an item that stays in your hand.
	 */
	public static boolean isKeyHeld() {
		return keyHeld;
	}

	public static void clear() {
		keyHeld = false;
	}
}
