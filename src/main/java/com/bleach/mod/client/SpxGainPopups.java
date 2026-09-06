package com.bleach.mod.client;

import java.util.ArrayList;
import java.util.List;

import com.bleach.mod.tuning.BleachTuning;

/**
 * The floating {@code +30 SPX} that rises off the bar when a kill pays out.
 *
 * <p>Nothing is inferred here. The server sends the award as it makes it · {@code SpxGainPayload},
 * because SPX is a bank that is filled and spent in the same tick — a kill worth 30 that buys a
 * 40-point level leaves the bank ten <em>lower</em> than it started, so watching the synced figure
 * would report a loss on exactly the occasion worth celebrating. This class only queues what it was
 * told and ages it out.
 *
 * <p>Awards that land within {@link BleachTuning#HUD_SPX_GAIN_MERGE_MILLIS} of each other are merged
 * into the newest popup rather than stacked. Two kills in the same breath are one event to the player
 * — a bow through a crowd, an AoE, a mob stack falling at once — and three figures fighting for the
 * same few pixels is less readable than one that counts them.
 */
public final class SpxGainPopups {
	private SpxGainPopups() {
	}

	/** One figure, rising and fading from the moment it was born. Mutable so awards can merge in. */
	public static final class Popup {
		private int amount;
		private long bornMillis;

		private Popup(int amount, long bornMillis) {
			this.amount = amount;
			this.bornMillis = bornMillis;
		}

		public int amount() {
			return amount;
		}

		public long bornMillis() {
			return bornMillis;
		}
	}

	private static final List<Popup> POPUPS = new ArrayList<>();

	/** Never let a backlog build — the newest figures are the ones the player is looking for. */
	private static final int MAX_POPUPS = 5;

	/** Queue an award the server just reported. */
	public static void add(int amount) {
		if (amount <= 0) {
			return;
		}

		long now = System.currentTimeMillis();
		long merge = (long) Math.max(0.0, BleachTuning.HUD_SPX_GAIN_MERGE_MILLIS);

		if (!POPUPS.isEmpty()) {
			Popup newest = POPUPS.get(POPUPS.size() - 1);
			if (now - newest.bornMillis <= merge) {
				// Merged, and the clock restarts: the figure has just changed, so it needs its full
				// life to be read at its new value rather than fading out mid-count.
				newest.amount += amount;
				newest.bornMillis = now;
				return;
			}
		}

		POPUPS.add(new Popup(amount, now));
		while (POPUPS.size() > MAX_POPUPS) {
			POPUPS.remove(0);
		}
	}

	/** Live popups, oldest first, with the expired ones dropped. */
	public static List<Popup> active(long now) {
		if (POPUPS.isEmpty()) {
			return List.of();
		}
		long life = (long) Math.max(1.0, BleachTuning.HUD_SPX_GAIN_DURATION_MILLIS);
		POPUPS.removeIf(popup -> now - popup.bornMillis >= life);
		return POPUPS;
	}

	/** Leaving a world. Nothing here should outlive the session that earned it. */
	public static void reset() {
		POPUPS.clear();
	}
}
