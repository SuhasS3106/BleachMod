package com.bleach.mod.client;

import com.bleach.mod.tuning.BleachTuning;
import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.util.Mth;

/**
 * One soul's fire: a live particle system that persists between frames.
 *
 * <h2>Why this is a simulation and not a shape</h2>
 *
 * <p>Every earlier attempt at this drew a flame — a teardrop, then a cluster of tapering chains —
 * and every one of them read as a glowing decal. The reason is that <b>fire is not a shape, it is a
 * population</b>. Particles are born hot and white at the base, rise, cool through the aura's own
 * colour, spread, and die. The taper, the flicker, the wisps tearing off the tip: all of it falls out
 * of that, and none of it has to be drawn on purpose. Nothing drawn per-frame from scratch can
 * produce it, because the look lives in the history of each particle rather than in the outline.
 *
 * <p>That history is why this class exists at all instead of being a method on the overlay: the pool
 * has to survive from one frame to the next, keyed on the soul it belongs to.
 *
 * <h2>The colour ramp does most of the work</h2>
 *
 * <p>A flame drawn in one colour reads as a blob no matter how good the motion is, because real fire
 * is a temperature gradient before it is anything else. Each particle walks white → the aura colour →
 * a deep cooled version of it over its own life · {@link #ramp}. The white phase is deliberately
 * brief; blending is additive, so a wide white band stacks into a featureless white pill.
 *
 * <h2>Space and cost</h2>
 *
 * <p>Particles live in a local 3D space normalised to the reading's own radius — {@code y} is world
 * up, {@code x} and {@code z} are world horizontal — so the simulation is identical for a bonfire at
 * ten blocks and a spark at three hundred, and the drawn pixel radius is applied only at projection.
 * That also means a soul swelling into Bankai grows its fire smoothly instead of teleporting every
 * particle. Because the frame is world-aligned rather than screen-aligned, the fire has real volume:
 * turning your head orbits it, and looking down shows you the top of it.
 *
 * <p>State is a flat {@code float[]}, compacted in place, rather than a list of objects. At a
 * thousand-odd particles respawning several times a second, an object per particle is a few hundred
 * thousand allocations a minute inside the render loop, which is a stutter rather than a cost.
 * Drawing is loose triangles into the overlay's single shared buffer, so the whole sense — every soul
 * on screen — is still one upload.
 */
final class AuraFlame {
	/** Floats per particle: x y z vx vy vz age life size spin. */
	private static final int STRIDE = 10;

	private float[] pool = new float[STRIDE * 64];
	private int count;

	/** Fractional particles carried between frames, so a low spawn rate is not rounded to zero. */
	private double spawnDebt;

	/** Per-soul phase, so two souls standing together never surge in step. */
	private final float phase;

	private int rng;

	AuraFlame(int id) {
		// Seeded on the entity id rather than on wall time: a soul that walks out of reach and back
		// should be recognisably the same fire, not a new one with a different rhythm.
		this.rng = id * 374761393 + 668265263;
		this.phase = (hash(id, 17) * 2.0f - 1.0f) * 100.0f;
	}

	boolean isEmpty() {
		return count == 0;
	}

	int size() {
		return count;
	}

	// --- Simulation ----------------------------------------------------------------------------

	/**
	 * Advance by {@code dt} seconds.
	 *
	 * @param radiusPx    the reading's drawn radius, used only to scale the spawn rate — a distant
	 *                    soul is a small fire rather than a lonely dot
	 * @param budgetScale global throttle, 0..1, applied when the screen is crowded
	 */
	void update(float dt, float burn, float radiusPx, float time, float budgetScale) {
		// The surge, shared by everything in this fire this frame: it spawns harder, throws harder
		// and shakes harder at once, which is what makes a flare read as one event and not three.
		float gust = gust(time);

		double rate = BleachTuning.AURA_PARTICLE_RATE
				* (0.35 + burn * 0.14)
				* (0.35 + Math.min(radiusPx, 90.0f) / 90.0)
				* Math.max(0.25f, gust)
				* budgetScale;

		spawnDebt += rate * dt;
		int born = (int) spawnDebt;
		spawnDebt -= born;

		int cap = Math.max(0, BleachTuning.AURA_PARTICLE_MAX_PER_SOUL);
		for (int i = 0; i < born && count < cap; i++) {
			spawn(gust);
		}

		float buoy = (float) BleachTuning.AURA_PARTICLE_BUOYANCY;
		float turb = (float) BleachTuning.AURA_PARTICLE_TURBULENCE;
		float swirl = (float) BleachTuning.AURA_PARTICLE_SWIRL;
		float taper = (float) BleachTuning.AURA_PARTICLE_TAPER;
		float drag = Math.max(0.0f, 1.0f - (float) BleachTuning.AURA_PARTICLE_DRAG * dt);
		float noiseTime = time + phase;

		int kept = 0;
		for (int i = 0; i < count; i++) {
			int p = i * STRIDE;
			float age = pool[p + 6] + dt;
			float life = pool[p + 7];
			if (age >= life) {
				continue;
			}

			float t = age / life;
			float x = pool[p];
			float y = pool[p + 1];
			float z = pool[p + 2];
			float vx = pool[p + 3];
			float vy = pool[p + 4];
			float vz = pool[p + 5];

			// Buoyancy — hot gas rises, and it rises harder while it is still hot. Fading it out
			// with age is what makes the top of the fire stall and break up instead of climbing
			// forever.
			vy += buoy * (1.0f - t * 0.7f) * dt;

			// Turbulence, ramped with age so the base stays coherent and only the top tears apart.
			// Applied flat it out-competes buoyancy immediately and the whole fire lies down.
			float tf = turb * dt * (0.18f + t * 1.4f) * Math.max(0.4f, gust);
			vx += turbX(x, y, z, noiseTime) * tf;
			vz += turbZ(x, y, z, noiseTime) * tf;

			// Swirl about the axis. Real fire entrains air and rotates; without it the turbulence
			// reads as jitter rather than as motion.
			float sw = swirl * dt * pool[p + 9] * 0.05f;
			vx += -z * sw;
			vz += x * sw;

			// Taper — a pull toward the axis that grows with age. This is what gives a flame its
			// point; buoyancy plus turbulence alone makes a smoke column, which widens forever.
			float pull = taper * dt * t;
			vx -= x * pull;
			vz -= z * pull;

			vx *= drag;
			vy *= drag;
			vz *= drag;

			int q = kept * STRIDE;
			pool[q] = x + vx * dt;
			pool[q + 1] = y + vy * dt;
			pool[q + 2] = z + vz * dt;
			pool[q + 3] = vx;
			pool[q + 4] = vy;
			pool[q + 5] = vz;
			pool[q + 6] = age;
			pool[q + 7] = life;
			pool[q + 8] = pool[p + 8];
			pool[q + 9] = pool[p + 9];
			kept++;
		}
		count = kept;
	}

	/**
	 * Birth one particle through a volume rather than off a point or a flat disc.
	 *
	 * <p>{@code DOME} lifts the footprint into a squashed half-sphere sitting on the soul, so the
	 * fire wraps it and reads round from any angle — a flat disc collapses to a line the moment the
	 * camera is level with it, which is a column standing on a plate. {@code BLOOM} against
	 * {@code BUOYANCY} is then the whole shape control: bloom wins and it is a ball of fire, buoyancy
	 * wins and it is a jet.
	 */
	private void spawn(float gust) {
		ensure(count + 1);

		float a = rand() * Mth.TWO_PI;
		float ca = Mth.cos(a);
		float sa = Mth.sin(a);

		// Cube root, so the volume fills evenly instead of shelling onto its own surface.
		float rr = (float) Math.cbrt(rand());
		float elev = rand() * (Mth.PI * 0.5f) * (float) BleachTuning.AURA_PARTICLE_DOME;
		float ce = Mth.cos(elev);
		float se = Mth.sin(elev);

		float disc = (float) BleachTuning.AURA_PARTICLE_DISC;
		float bloom = (float) BleachTuning.AURA_PARTICLE_BLOOM;

		int p = count * STRIDE;
		pool[p] = ca * ce * rr * disc;
		pool[p + 1] = se * rr * disc * 0.75f + (rand() - 0.5f) * 0.08f;   // squashed: wide, not tall
		pool[p + 2] = sa * ce * rr * disc;
		pool[p + 3] = ca * ce * bloom * (0.4f + rand());
		pool[p + 4] = (se * bloom * 0.6f + 0.35f + rand() * 1.6f) * Math.max(0.3f, gust);
		pool[p + 5] = sa * ce * bloom * (0.4f + rand());
		pool[p + 6] = 0.0f;
		pool[p + 7] = (float) BleachTuning.AURA_PARTICLE_LIFE * (0.55f + rand() * 0.9f);
		// Small and many, never big and few: additive light builds a gradient out of overlap, while
		// a handful of fat blobs simply clips to white wherever two of them cross.
		pool[p + 8] = (float) BleachTuning.AURA_PARTICLE_GRAIN * (0.6f + rand() * 0.9f);
		pool[p + 9] = (rand() - 0.5f) * 2.0f;
		count++;
	}

	// --- Drawing -------------------------------------------------------------------------------

	/**
	 * Project and emit every particle into the shared buffer.
	 *
	 * <p>The local frame is world-aligned, so a particle is resolved onto the camera basis exactly
	 * the way the reading's own anchor was. The perspective divide is not repeated per particle: the
	 * plume is tiny beside its distance, so the anchor's scale — which is precisely the drawn radius
	 * — carries the whole projection.
	 */
	void draw(BufferBuilder buffer, Matrix4f matrix, Vector3f left, Vector3f up, float anchorX,
			float anchorY, float radiusPx, int color, float alpha, float zDepth) {
		float baseRed = ((color >> 16) & 0xFF) / 255.0f;
		float baseGreen = ((color >> 8) & 0xFF) / 255.0f;
		float baseBlue = (color & 0xFF) / 255.0f;

		float grow = (float) BleachTuning.AURA_PARTICLE_GROW;
		float opacity = (float) BleachTuning.AURA_PARTICLE_OPACITY;
		float stretch = (float) BleachTuning.AURA_PARTICLE_STRETCH;
		float minSize = (float) BleachTuning.AURA_PARTICLE_MIN_SIZE_PX;

		for (int i = 0; i < count; i++) {
			int p = i * STRIDE;
			float t = pool[p + 6] / pool[p + 7];

			// Alpha swells in fast and falls off squared, which is what keeps the tips wispy. Kept
			// low on purpose: the brightness of the fire comes from how many particles overlap, not
			// from how solid any one of them is, and that is the only way the core stays a gradient
			// instead of clipping to a white pill.
			float a = Math.min(1.0f, t * 5.0f) * (1.0f - t) * (1.0f - t) * opacity * alpha;
			if (a <= 0.004f) {
				continue;
			}

			// Grow as it rises and cools — expanding gas — then shrink into nothing at the very end
			// so particles do not blink out at full size.
			float size = pool[p + 8] * (1.0f + t * grow) * (1.0f - t * t * 0.35f) * radiusPx;
			if (size < minSize) {
				continue;
			}

			float x = pool[p];
			float y = pool[p + 1];
			float z = pool[p + 2];
			float sx = anchorX - radiusPx * (x * left.x() + y * left.y() + z * left.z());
			float sy = anchorY - radiusPx * (x * up.x() + y * up.y() + z * up.z());

			// Stretch along the direction of travel. This is the single thing that turns a column of
			// round dots into fire: a fast particle is a streak, a slow one at the top is a puff, and
			// every streak leaning along its own velocity is what reads as flame. Area is held
			// roughly constant — widen by s, thin by 1/√s — so stretching does not also brighten.
			float vx = pool[p + 3];
			float vy = pool[p + 4];
			float vz = pool[p + 5];
			float vsx = -(vx * left.x() + vy * left.y() + vz * left.z());
			float vsy = -(vx * up.x() + vy * up.y() + vz * up.z());
			float speed = Mth.sqrt(vsx * vsx + vsy * vsy);

			float s = 1.0f + stretch * Math.min(2.2f, speed * 0.42f);
			float tilt = s > 1.03f ? (float) Math.atan2(vsy, vsx) : 0.0f;
			float minor = s > 1.03f ? size / Mth.sqrt(s) : size;
			float major = s > 1.03f ? size * s : size;

			blob(buffer, matrix, sx, sy, major, minor, tilt, zDepth,
					baseRed, baseGreen, baseBlue, t, a);
		}
	}

	/**
	 * Colour at age {@code t}: white-hot, through the aura's own colour, into a deep cooled version
	 * of it. Written straight into {@code out} to keep the draw loop allocation-free.
	 *
	 * <p>The white phase runs to {@code t = 0.05} and no further. Additive blending stacks it, so a
	 * wide white band saturates the middle of every fire into a featureless blob; held brief, the same
	 * ramp reads as a hot core.
	 */
	private static void ramp(float[] out, float red, float green, float blue, float t) {
		float hotRed = Mth.lerp(0.72f, red, 1.0f);
		float hotGreen = Mth.lerp(0.72f, green, 1.0f);
		float hotBlue = Mth.lerp(0.72f, blue, 1.0f);

		if (t < 0.05f) {
			float k = t / 0.05f;
			out[0] = Mth.lerp(k, 1.0f, hotRed);
			out[1] = Mth.lerp(k, 1.0f, hotGreen);
			out[2] = Mth.lerp(k, 1.0f, hotBlue);
		} else if (t < 0.22f) {
			float k = (t - 0.05f) / 0.17f;
			out[0] = Mth.lerp(k, hotRed, red);
			out[1] = Mth.lerp(k, hotGreen, green);
			out[2] = Mth.lerp(k, hotBlue, blue);
		} else {
			// Cooling runs toward the blue end, the way a real flame's smoke does against a dark sky.
			float k = (t - 0.22f) / 0.78f;
			out[0] = Mth.lerp(k, red, red * 0.42f);
			out[1] = Mth.lerp(k, green, green * 0.16f);
			out[2] = Mth.lerp(k, blue, blue * 0.62f);
		}
	}

	/** Scratch for {@link #ramp}. The overlay is single-threaded — the render pass is the only caller. */
	private static final float[] RAMP = new float[3];

	/**
	 * One particle: a lit centre with a rim that fades to nothing, as a fan of loose triangles.
	 *
	 * <p>Five segments, not twenty. A particle is a handful of pixels across and there are thousands
	 * of them; at that size a pentagon and a circle are the same picture, and the softness comes from
	 * the rim alpha rather than from the silhouette.
	 */
	private static void blob(BufferBuilder buffer, Matrix4f matrix, float cx, float cy, float rx,
			float ry, float tilt, float zDepth, float red, float green, float blue, float t,
			float alpha) {
		ramp(RAMP, red, green, blue, t);
		float r = RAMP[0];
		float g = RAMP[1];
		float b = RAMP[2];

		int segments = Math.max(3, BleachTuning.AURA_PARTICLE_SEGMENTS);
		float cos = Mth.cos(tilt);
		float sin = Mth.sin(tilt);

		float prevX = 0.0f;
		float prevY = 0.0f;
		for (int i = 0; i <= segments; i++) {
			float angle = (Mth.TWO_PI * i) / segments;
			float ox = Mth.cos(angle) * rx;
			float oy = Mth.sin(angle) * ry;
			float x = cx + ox * cos - oy * sin;
			float y = cy + ox * sin + oy * cos;

			if (i > 0) {
				buffer.addVertex(matrix, cx, cy, zDepth).setColor(r, g, b, alpha);
				buffer.addVertex(matrix, prevX, prevY, zDepth).setColor(r, g, b, 0.0f);
				buffer.addVertex(matrix, x, y, zDepth).setColor(r, g, b, 0.0f);
			}

			prevX = x;
			prevY = y;
		}
	}

	// --- Noise ---------------------------------------------------------------------------------

	/*
	 * Deliberately the cheapest thing that still looks like turbulence: three sine waves at unrelated
	 * frequencies. Real value noise is barely dearer, but this needs no table and at flame scale
	 * nobody can tell them apart.
	 *
	 * CHURN multiplies only the TIME terms, so raising it boils the same field faster without
	 * changing the size of the structures in it. Speeding the field up by shortening its wavelength
	 * instead would only make the fire finer-grained, not more violent.
	 */

	private static float turbX(float x, float y, float z, float time) {
		float c = time * (float) BleachTuning.AURA_PARTICLE_CHURN;
		return Mth.sin(y * 3.5f + c * 1.7f) * Mth.cos(z * 2.6f - c * 1.1f)
				+ Mth.sin(x * 5.3f - c * 2.3f) * 0.5f
				+ Mth.sin(y * 1.1f + c * 0.6f) * 0.8f;   // long wavelength, leans whole sheets over
	}

	private static float turbZ(float x, float y, float z, float time) {
		float c = time * (float) BleachTuning.AURA_PARTICLE_CHURN;
		return Mth.cos(y * 3.9f - c * 1.4f) * Mth.sin(x * 2.4f + c * 1.3f)
				+ Mth.cos(z * 5.1f + c * 2.1f) * 0.5f
				+ Mth.cos(y * 1.2f - c * 0.7f) * 0.8f;
	}

	/**
	 * Gusting. A fire burning at a constant rate reads as a machine — what makes real fire look
	 * violent is that it surges: it flares, falls back, and flares again. Two sines at unrelated
	 * periods give a pulse that never visibly repeats.
	 */
	private float gust(float time) {
		float g = Mth.sin(time * 2.3f + phase) * 0.6f + Mth.sin(time * 3.9f + phase * 1.7f) * 0.4f;
		return 1.0f + (float) BleachTuning.AURA_PARTICLE_GUST * g;
	}

	// --- Plumbing ------------------------------------------------------------------------------

	private void ensure(int particles) {
		int needed = particles * STRIDE;
		if (needed <= pool.length) {
			return;
		}
		float[] grown = new float[Math.max(needed, pool.length * 2)];
		System.arraycopy(pool, 0, grown, 0, count * STRIDE);
		pool = grown;
	}

	/** Xorshift, 0..1. Cheap and per-flame, so no two fires draw the same sequence. */
	private float rand() {
		rng ^= rng << 13;
		rng ^= rng >>> 17;
		rng ^= rng << 5;
		return (rng >>> 8) / (float) (1 << 24);
	}

	private static float hash(int seed, int index) {
		int h = seed * 374761393 + index * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) >>> 8) / (float) (1 << 24);
	}
}
