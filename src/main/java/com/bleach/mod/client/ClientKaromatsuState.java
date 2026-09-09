package com.bleach.mod.client;

import com.bleach.mod.network.KaromatsuSyncPayload;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.util.Mth;

/**
 * Whether this client is on Shunsui's stage, which act it is in, and how far the gloom has faded in.
 *
 * <p>Membership is decided entirely by the server and arrives as an edge — see
 * {@link KaromatsuSyncPayload}. The fade lives here, so the stage reads as closing around you rather
 * than as a colour appearing, and so a missed frame cannot leave a hard-edged wash on screen.
 *
 * <p>Client thread only: the network handler hops through {@code client.execute} and
 * {@link KaromatsuOverlay} runs in the render pass.
 */
public final class ClientKaromatsuState {
	private ClientKaromatsuState() {
	}

	/** Act index of the last "you are on the stage" the server sent, or -1 when off it. */
	private static byte act = -1;
	private static boolean affected;
	private static float tint;
	private static long lastFrameMillis;

	public static void update(KaromatsuSyncPayload payload) {
		affected = payload.active();
		// The act is kept through the falling edge so the wash fades out in the colour it was in,
		// rather than snapping to the pre-act purple on its way to nothing.
		if (payload.active()) {
			act = payload.actIndex();
		}
	}

	/** Dropped on disconnect, so a tint cannot survive into the next world. */
	public static void clear() {
		affected = false;
		act = -1;
		tint = 0.0f;
		lastFrameMillis = 0L;
	}

	/** Whether the local player is currently a participant in an active zone. */
	public static boolean isAffected() {
		return affected;
	}

	/** The act being played, or {@code -1} if the local player is not on the stage. */
	public static byte currentAct() {
		return affected ? act : -1;
	}

	/**
	 * Advances the fade and returns its current strength, 0..1. Called once per frame by the overlay.
	 *
	 * <p>Frame-rate independent, and the delta is clamped so an alt-tab or a world load does not
	 * resolve the whole fade in the first frame back.
	 */
	public static float advance() {
		long now = System.currentTimeMillis();
		double delta = lastFrameMillis == 0L ? 0.0 : (now - lastFrameMillis) / 1000.0;
		lastFrameMillis = now;
		delta = Mth.clamp(delta, 0.0, 0.25);

		double perSecond = Math.max(0.001, BleachTuning.SHUNSUI_ZONE_TINT_FADE_PER_SECOND);
		float step = (float) (delta * perSecond);

		tint = affected
				? Math.min(1.0f, tint + step)
				: Math.max(0.0f, tint - step);
		return tint;
	}
}
