package com.bleach.mod.client;

import com.bleach.mod.network.MiracleSyncPayload;

/**
 * The last stack count the server sent, plus the two flags that go with it.
 *
 * <p>Client thread only: the network handler hops through {@code client.execute} and
 * {@link MiracleOverlay} runs in the render pass. Nothing here is derived — the server owns the
 * number, and a client that recomputed it from damage it observed would drift the moment a hit was
 * negated by The Miracle.
 */
public final class ClientMiracleState {
	private ClientMiracleState() {
	}

	private static int stacks;
	private static int maxStacks;
	private static boolean godlySize;
	private static boolean coreExposed;

	/** Smoothed toward {@link #stacks} so the bar slides rather than snapping. */
	private static float displayed;

	public static void update(MiracleSyncPayload payload) {
		stacks = payload.stacks();
		maxStacks = payload.maxStacks();
		godlySize = payload.godlySize();
		coreExposed = payload.coreExposed();
	}

	/** Dropped on disconnect, so a stale bar cannot survive into the next world. */
	public static void clear() {
		stacks = 0;
		maxStacks = 0;
		godlySize = false;
		coreExposed = false;
		displayed = 0.0f;
	}

	/** True when there is anything worth drawing. */
	public static boolean isActive() {
		return maxStacks > 0;
	}

	public static int stacks() {
		return stacks;
	}

	public static int maxStacks() {
		return maxStacks;
	}

	public static boolean isGodlySize() {
		return godlySize;
	}

	public static boolean isCoreExposed() {
		return coreExposed;
	}

	/**
	 * Fill fraction, eased toward the real value.
	 *
	 * @param delta frame delta in partial ticks; the easing is frame-rate independent enough for a
	 *        bar that only ever moves a stack at a time
	 */
	public static float fill(float delta) {
		if (maxStacks <= 0) {
			displayed = 0.0f;
			return 0.0f;
		}
		float target = stacks / (float) maxStacks;
		displayed += (target - displayed) * Math.min(1.0f, delta * 0.35f);
		return Math.max(0.0f, Math.min(1.0f, displayed));
	}
}
