package com.bleach.mod.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bleach.mod.network.AuraSensePayload;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The last reading the server sent, the eyelid animation, and nothing else. Everything here is
 * touched from the client thread only — the network handler hops through {@code client.execute} and
 * the overlay runs in the render pass, both on that thread.
 *
 * <h2>Why the eyelid is driven by two flags rather than one</h2>
 *
 * <p>The lid falls on the <em>server's</em> word ({@link #setActive}) and lifts on <em>either</em>
 * the server's word or the key coming up ({@link #setKeyHeld}). Closing on the packet is what keeps
 * a refused sense — an empty pool, a disabled mod, a vanilla server — from blinding a player who
 * would then be waiting for a reading that is never coming. Opening on the local key is what keeps
 * the release from costing a round trip, which on any real connection is the difference between a
 * sense you are willing to use in a fight and one you are not.
 *
 * <h2>Smoothing</h2>
 *
 * <p>Readings arrive ten times a second and are drawn sixty or more. Snapping each aura to the
 * newest packet makes every blob stutter, so each one is instead pulled toward its reported position
 * by a fixed fraction of the remaining gap per second — frame-rate independent, no extrapolation,
 * and it cannot invent a position the server never sent. An aura that stops being reported fades
 * over {@link BleachTuning#AURA_FADE_SECONDS} instead of vanishing mid-frame.
 *
 * <p>Burn — how hard a soul is pushing · {@code AuraSense#burn} — rides the same blend, so a release
 * swells over a few frames rather than cutting between two sizes. That swell is the only warning a
 * sensor gets that someone across the map just went Bankai, and a hard cut would read as a glitch.
 */
public final class ClientAuraSenseState {
	private ClientAuraSenseState() {
	}

	/** One aura, as the renderer needs it: where it is being drawn, and how solid it still is. */
	public static final class Reading {
		/** Entity id. The renderer uses it only as a seed, so one soul's plume leans the same way. */
		public final int id;
		public final int color;
		public final int soulLevel;

		/** Newest position the server reported, in world space. */
		private Vec3 target;
		/** Where it is actually being drawn, chasing {@link #target}. */
		private Vec3 drawn;
		/** Newest burn multiplier the server reported. */
		private float targetBurn;
		/** The burn actually being drawn, chasing {@link #targetBurn}. */
		private float drawnBurn;
		/** Seconds since this aura was last in a packet. Drives the fade-out. */
		private double staleSeconds;

		private Reading(int id, int color, int soulLevel, Vec3 position, float burn) {
			this.id = id;
			this.color = color;
			this.soulLevel = soulLevel;
			this.target = position;
			this.drawn = position;
			this.targetBurn = burn;
			this.drawnBurn = burn;
		}

		public Vec3 position() {
			return drawn;
		}

		/**
		 * How hard this soul is pushing, chasing the reported value on the same blend as position.
		 *
		 * <p>Smoothed rather than snapped because a release is a swell, not a cut: a Bankai coming
		 * out should be a fire growing over a few frames, which is also the only warning a sensor
		 * gets. A new reading starts at its reported burn, so a soul that walks into reach already
		 * released arrives at full size instead of blooming out of nothing.
		 */
		public float burn() {
			return drawnBurn;
		}

		/** 1.0 while the aura is still being reported, ramping to 0 over the fade window. */
		public float alpha() {
			double window = Math.max(0.001, BleachTuning.AURA_FADE_SECONDS);
			return (float) Mth.clamp(1.0 - staleSeconds / window, 0.0, 1.0);
		}
	}

	private static final Map<Integer, Reading> READINGS = new LinkedHashMap<>();

	/** Ids carried by the newest packet, so the render pass knows which readings to age. */
	private static final Set<Integer> REPORTED = new HashSet<>();

	/** The server says the channel is up. */
	private static boolean serverActive;
	/** The key is down here. */
	private static boolean keyHeld;

	/** Eyelid travel, 0 open .. 1 shut, at {@link #animationStartMillis}. */
	private static float lidAtStart;
	private static long animationStartMillis;
	private static boolean closing;

	private static long lastFrameMillis;

	// --- Inputs ---------------------------------------------------------------------------

	public static void update(AuraSensePayload payload) {
		setActive(payload.active());

		if (!payload.active()) {
			READINGS.clear();
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		// The packet's deltas are from the sensor's eye, so the sensor's eye is what rebuilds them.
		// The client's own position is up to a tick out of step with the server's copy of it, which
		// on a blob whose radius is measured in whole blocks is not a distance anyone can see.
		Vec3 origin = client.player.getEyePosition();

		Set<Integer> seen = new HashSet<>(payload.auras().size());
		for (AuraSensePayload.Aura aura : payload.auras()) {
			Vec3 position = origin.add(aura.dx(), aura.dy(), aura.dz());
			seen.add(aura.entityId());

			Reading existing = READINGS.get(aura.entityId());
			if (existing == null) {
				READINGS.put(aura.entityId(), new Reading(aura.entityId(), aura.color(),
						aura.soulLevel(), position, aura.burn()));
			} else {
				existing.target = position;
				existing.targetBurn = aura.burn();
				existing.staleSeconds = 0.0;
			}
		}

		// Anything the sweep did not report is out of reach now. It is not dropped here — the render
		// pass ages it instead, so an aura sitting on the edge of a reach fades rather than
		// strobing in and out with every other packet.
		REPORTED.clear();
		REPORTED.addAll(seen);
	}

	public static void setActive(boolean active) {
		if (serverActive != active) {
			serverActive = active;
			retarget();
		}
	}

	public static void setKeyHeld(boolean held) {
		if (keyHeld != held) {
			keyHeld = held;
			retarget();
		}
	}

	public static void clear() {
		READINGS.clear();
		REPORTED.clear();
		serverActive = false;
		keyHeld = false;
		closing = false;
		lidAtStart = 0.0f;
		animationStartMillis = 0L;
	}

	// --- The eyelid -----------------------------------------------------------------------

	/**
	 * Restart the animation from wherever the lid currently is, rather than from 0 or 1. Tapping the
	 * key twice quickly otherwise makes the lid jump to fully open before falling again.
	 */
	private static void retarget() {
		boolean shut = serverActive && keyHeld;
		if (shut == closing) {
			return;
		}

		lidAtStart = eyelidProgress();
		closing = shut;
		animationStartMillis = System.currentTimeMillis();
	}

	/** 0 fully open, 1 fully shut. */
	public static float eyelidProgress() {
		double duration = Math.max(1.0, closing
				? BleachTuning.AURA_EYELID_CLOSE_MILLIS
				: BleachTuning.AURA_EYELID_OPEN_MILLIS);

		double elapsed = (System.currentTimeMillis() - animationStartMillis) / duration;
		float target = closing ? 1.0f : 0.0f;
		if (elapsed >= 1.0) {
			return target;
		}

		return Mth.lerp((float) elapsed, lidAtStart, target);
	}

	// --- The readings ----------------------------------------------------------------------

	/**
	 * Advance the smoothing and the fade by one frame and hand back what is worth drawing. Called
	 * once per frame from the overlay, which is the only thing that reads any of this.
	 */
	public static Collection<Reading> advance() {
		long now = System.currentTimeMillis();
		double deltaSeconds = lastFrameMillis == 0L ? 0.0 : (now - lastFrameMillis) / 1000.0;
		lastFrameMillis = now;

		// A pause, an alt-tab or a world load can leave an arbitrarily large gap. Clamped so the
		// first frame back does not age every aura straight out of existence before it is drawn.
		deltaSeconds = Mth.clamp(deltaSeconds, 0.0, 0.25);

		double keep = Mth.clamp(1.0 - BleachTuning.AURA_SMOOTHING_PER_SECOND, 0.0, 1.0);
		double blend = 1.0 - Math.pow(keep, deltaSeconds);

		List<Reading> visible = new ArrayList<>(READINGS.size());
		for (Iterator<Map.Entry<Integer, Reading>> it = READINGS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, Reading> entry = it.next();
			Reading reading = entry.getValue();

			if (!REPORTED.contains(entry.getKey())) {
				reading.staleSeconds += deltaSeconds;
				if (reading.alpha() <= 0.0f) {
					it.remove();
					continue;
				}
			}

			// A Flash Step, a teleport or a dimension change moves a soul further in one packet than
			// anything can travel. Smoothing that would drag the blob across the whole screen, so a
			// jump past the snap distance is taken as a jump and drawn where it landed.
			double snap = BleachTuning.AURA_SNAP_DISTANCE;
			reading.drawn = reading.drawn.distanceToSqr(reading.target) > snap * snap
					? reading.target
					: reading.drawn.lerp(reading.target, blend);

			reading.drawnBurn = Mth.lerp((float) blend, reading.drawnBurn, reading.targetBurn);
			visible.add(reading);
		}
		return visible;
	}
}
