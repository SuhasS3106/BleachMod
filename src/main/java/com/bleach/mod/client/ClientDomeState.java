package com.bleach.mod.client;

import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.util.Mth;

/**
 * Whether this client is standing in a Gift Bereich, and how far its tint has faded in.
 *
 * <p>The server sends only the edge — in, or out — and the fade lives here, so the wall reads as
 * closing around you rather than as a colour appearing. Client thread only: the network handler hops
 * through {@code client.execute} and the overlay runs in the render pass.
 */
public final class ClientDomeState {
	private ClientDomeState() {
	}

	private static boolean inside;
	private static float tint;
	private static long lastFrameMillis;

	public static void setInside(boolean value) {
		inside = value;
	}

	/** Dropped on disconnect, so a tint cannot survive into the next world. */
	public static void clear() {
		inside = false;
		tint = 0.0f;
		lastFrameMillis = 0L;
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

		double perSecond = Math.max(0.001, BleachTuning.DOME_TINT_FADE_PER_SECOND);
		float step = (float) (delta * perSecond);

		tint = inside
				? Math.min(1.0f, tint + step)
				: Math.max(0.0f, tint - step);
		return tint;
	}
}
