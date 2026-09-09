package com.bleach.mod.client;

import com.bleach.mod.tuning.BleachTuning;
import com.mojang.blaze3d.vertex.BufferBuilder;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.util.Mth;

/**
 * One flexer's field: a live particle system that persists between frames, in world space.
 *
 * <h2>Three layers, one envelope</h2>
 *
 * <p>A field is not one effect. It is a <b>column</b> boiling up off the flexer, a wall of thin
 * <b>lances</b> climbing the radius, and a few big slow <b>sheets</b> for silhouette-scale mass —
 * and a shockwave ring on the floor underneath all of it. They are separate populations because they
 * answer different questions: the column says where the player is, the lances say how far the field
 * reaches, the sheets keep it from reading as a spray of dots, and the ring draws the edge a target
 * has to get out of.
 *
 * <p>What makes them one effect rather than four is that every one of them surges on the same pulse
 * · {@code FLEX_AURA_PULSE_PERIOD}. Spawn rates, ring births and brightness all ride that single
 * envelope, so a beat reads as the field breathing rather than as four systems that happen to be
 * near each other.
 *
 * <h2>Why this is a simulation and not a shape</h2>
 *
 * <p>The same reason {@link AuraFlame} is, and this borrows its machinery wholesale: fire is a
 * population, not an outline, and the look lives in each particle's history. That history is why
 * this class exists instead of being a method on the renderer — the pool has to survive from frame
 * to frame, keyed on the flexer it belongs to.
 *
 * <p>Positions are local: {@code y} up, {@code x}/{@code z} horizontal, all in blocks, all relative
 * to the flexer's feet. World space is applied once at draw. That frame is <em>moving</em>, though,
 * so it is not a free ride: each frame's displacement is subtracted back out of the pool, which is
 * what leaves the field standing in the world instead of worn by the player · {@link #update}.
 *
 * <p>State is a flat {@code float[]} compacted in place. At a couple of thousand particles
 * respawning several times a second, an object per particle is a few hundred thousand allocations a
 * minute inside the render loop.
 */
final class FlexAura {
	/** Floats per particle: x y z vx vy vz age life size spin kind. */
	private static final int STRIDE = 11;

	private static final int COLUMN = 0;
	private static final int LANCE = 1;
	private static final int SHEET = 2;

	/**
	 * How fast the noise field boils, independent of how big its structures are. Not a tuning dial:
	 * the constants that shape the field are, this is the one number that just has to feel like air.
	 */
	private static final float CHURN = 1.7f;

	private float[] pool = new float[STRIDE * 256];
	private int count;

	/** Fractional particles carried between frames, so a low spawn rate is not rounded to zero. */
	private double columnDebt;
	private double lanceDebt;
	private double sheetDebt;

	/** Floats per ring: age, and the local x/y/z it was born at. */
	private static final int RING_STRIDE = 4;

	/** Live shockwave rings. Four is already more than the pulse can produce. */
	private final float[] rings = new float[4 * RING_STRIDE];
	private int ringCount;

	/** Seconds until the next ring, counted down against the pulse period. */
	private float ringTimer;

	/** Seconds left of the activation burst · {@code FLEX_AURA_ONSET_SECONDS}. */
	private float onset;

	/** Per-field phase, so two flexers standing together never surge in step. */
	private final float phase;

	/**
	 * Where the flexer stood last frame, world coordinates, and whether that has been seen yet.
	 *
	 * <p>The pool is in the flexer's local frame, so <em>doing nothing</em> here nails every particle
	 * to the player and the whole field slides along with them like a costume. Air does not do that:
	 * what has already left the body stays where it was left. The difference between the two is this
	 * one displacement, applied to the pool every frame · {@link #update}.
	 */
	private double prevX;
	private double prevY;
	private double prevZ;
	private boolean tracking;

	/**
	 * World velocity handed to particles born this frame, blocks/s · {@code FLEX_AURA_INHERIT}.
	 *
	 * <p>A field on a sprinting player should lean, not just get left behind: what is thrown off the
	 * body carries some of the body's motion with it, and then drag takes it away. Set once per
	 * frame in {@link #update} and read by the three spawners.
	 */
	private float inheritX;
	private float inheritY;
	private float inheritZ;

	private int rng;

	FlexAura(int id) {
		// Seeded on the entity id rather than on wall time: a flexer who walks out of range and back
		// should be recognisably the same field, not a new one with a different rhythm.
		this.rng = id * 374761393 + 668265263;
		this.phase = (hash(id, 17) * 2.0f - 1.0f) * 100.0f;
	}

	boolean isEmpty() {
		return count == 0 && ringCount == 0;
	}

	int size() {
		return count;
	}

	/** Start the activation burst. Taken once per hold — see {@code FlexStatePayload#onset}. */
	void kick() {
		onset = (float) BleachTuning.FLEX_AURA_ONSET_SECONDS;
	}

	// --- Simulation ----------------------------------------------------------------------------

	/**
	 * Advance by {@code dt} seconds.
	 *
	 * <h2>The field is anchored to the world, not to the player</h2>
	 *
	 * <p>Positions are stored in the flexer's local frame, which is what lets the whole field be
	 * drawn by planting one origin — but it also means an untouched pool rides the player exactly,
	 * and a column that keeps its shape while its owner sprints out from under it is the single thing
	 * that gave the effect away as particles. So the frame's displacement is subtracted back out of
	 * every particle: what has already left the body stays in the world, the flexer walks out of
	 * their own plume, and it trails behind them.
	 *
	 * <p>Three dials, because "leave it all behind" alone is not right either. {@code FLEX_AURA_CARRY}
	 * is how much of the motion the existing field still inherits — a little, since the body drags
	 * some air with it. {@code FLEX_AURA_INHERIT} gives newly born particles the player's velocity,
	 * so the column <em>leans</em> into a run rather than snapping backwards a frame after birth.
	 * {@code FLEX_AURA_TELEPORT_SNAP} catches the case this must not apply to at all: a Flash Step is
	 * not movement through the intervening air, so past that threshold the field goes with the player
	 * rather than being stranded across half a chunk.
	 *
	 * @param feetX       the flexer's feet in world space; the frame-to-frame delta is what anchors
	 * @param radius      the field's radius in blocks — the lances and the ring are drawn on it
	 * @param tier        Reiatsu amplifier 0..3, indexing {@code FLEX_AURA_TIER_GAIN}
	 * @param time        the shared frame clock, seconds, so every field boils against one time base
	 * @param budgetScale global throttle, 0..1, applied when there are too many fields on screen
	 */
	void update(float dt, double feetX, double feetY, double feetZ,
			float radius, int tier, float time, float budgetScale) {
		float dx = 0.0f;
		float dy = 0.0f;
		float dz = 0.0f;
		if (tracking) {
			dx = (float) (feetX - prevX);
			dy = (float) (feetY - prevY);
			dz = (float) (feetZ - prevZ);
		}
		tracking = true;
		prevX = feetX;
		prevY = feetY;
		prevZ = feetZ;

		float carry = (float) Mth.clamp(BleachTuning.FLEX_AURA_CARRY, 0.0, 1.0);
		float snap = (float) BleachTuning.FLEX_AURA_TELEPORT_SNAP;
		boolean teleported = snap > 0.0f && (dx * dx + dy * dy + dz * dz) > snap * snap;
		if (teleported) {
			// Not travel. Nothing was left in the air between here and there, so the field arrives
			// with its owner instead of being smeared across the gap.
			carry = 1.0f;
		}

		// What gets subtracted from a local position this frame: the part of the move that layer does
		// not follow.
		float shift = 1.0f - carry;
		float shiftX = dx * shift;
		float shiftY = dy * shift;
		float shiftZ = dz * shift;

		// Lances are the exception, and the reason is what they are for. The column and the sheets
		// are exhaust — thrown off the body, and then the body's business no longer. A lance is
		// structure: it stands on the radius to make the radius legible (§H.6), and that radius is
		// measured from the player every tick on the server. Anchoring one to the world sets the wall
		// adrift from the field it is drawing, and since a lance feels no drag it never catches up —
		// a sprinter leaves the entire wall a stride behind them and the streaks read as gone.
		float lanceCarry = teleported
				? 1.0f
				: (float) Mth.clamp(BleachTuning.FLEX_AURA_LANCE_CARRY, 0.0, 1.0);
		float lanceShift = 1.0f - lanceCarry;
		float lanceShiftX = dx * lanceShift;
		float lanceShiftY = dy * lanceShift;
		float lanceShiftZ = dz * lanceShift;

		if (teleported || dt <= 1.0e-4f) {
			inheritX = 0.0f;
			inheritY = 0.0f;
			inheritZ = 0.0f;
		} else {
			float inherit = (float) BleachTuning.FLEX_AURA_INHERIT / dt;
			inheritX = dx * inherit;
			// Halved, and only ever downward-damped: a jump should tug the field up a little, but a
			// fall must not fire the whole column at the sky.
			inheritY = dy * inherit * 0.5f;
			inheritZ = dz * inherit;
		}

		float period = (float) Math.max(0.05, BleachTuning.FLEX_AURA_PULSE_PERIOD);

		// The beat, shared by everything in this field this frame. Everything surging at once is what
		// makes a pulse read as one event instead of as three systems that happen to be in phase.
		float pulse = pulse(time + phase);

		// The activation burst, decaying over its own length rather than cutting off: the hold has to
		// arrive as a shove and settle into a channel, and a step down reads as a dropped frame.
		float burst = 1.0f;
		if (onset > 0.0f) {
			float k = onset / (float) Math.max(0.01, BleachTuning.FLEX_AURA_ONSET_SECONDS);
			burst = 1.0f + (float) (BleachTuning.FLEX_AURA_ONSET_GAIN - 1.0) * k * k;
			onset = Math.max(0.0f, onset - dt);
		}

		float gain = tierGain(tier) * pulse * burst * budgetScale;

		spawnRings(dt, period, pulse, shiftX, shiftY, shiftZ);

		int cap = Math.max(0, BleachTuning.FLEX_AURA_MAX_PARTICLES);
		columnDebt += BleachTuning.FLEX_AURA_COLUMN_RATE * gain * dt;
		lanceDebt += BleachTuning.FLEX_AURA_LANCE_RATE * gain * dt;
		sheetDebt += BleachTuning.FLEX_AURA_SHEET_RATE * gain * dt;

		int columns = (int) columnDebt;
		columnDebt -= columns;
		int lances = (int) lanceDebt;
		lanceDebt -= lances;
		int sheets = (int) sheetDebt;
		sheetDebt -= sheets;

		// Share the remaining room out in proportion to what each layer asked for, rather than
		// spawning them in a fixed order until the cap runs out. Three loops each racing the same
		// ceiling means whoever goes last starves first — and since the column goes first and
		// refills itself every frame, an onset burst or a high tier could take the whole pool and
		// leave the lances and the sheets with nothing at exactly the moment the field is loudest.
		// The layers are a mix, not a priority list, so under pressure the mix is what survives.
		int room = cap - count;
		int asked = columns + lances + sheets;
		if (asked > room) {
			columns = share(columns, asked, room);
			lances = share(lances, asked, room);
			sheets = share(sheets, asked, room);
		}

		for (int i = 0; i < columns; i++) {
			spawnColumn(burst);
		}
		for (int i = 0; i < lances; i++) {
			spawnLance(radius);
		}
		for (int i = 0; i < sheets; i++) {
			spawnSheet(radius);
		}

		advance(dt, time, shiftX, shiftY, shiftZ, lanceShiftX, lanceShiftY, lanceShiftZ);
	}

	/**
	 * Move every live particle and drop the dead ones, compacting in place.
	 *
	 * <p>Lances are deliberately exempt from almost all of it. They are the layer that has to read as
	 * a <em>wall</em> standing on the radius, and a lance that swirls or tapers is a column particle
	 * that happens to have spawned a long way out.
	 */
	private void advance(float dt, float time, float shiftX, float shiftY, float shiftZ,
			float lanceShiftX, float lanceShiftY, float lanceShiftZ) {
		float buoy = (float) BleachTuning.FLEX_AURA_BUOYANCY;
		float turb = (float) BleachTuning.FLEX_AURA_TURBULENCE;
		float swirl = (float) BleachTuning.FLEX_AURA_SWIRL;
		float taper = (float) BleachTuning.FLEX_AURA_TAPER;
		float drag = Math.max(0.0f, 1.0f - (float) BleachTuning.FLEX_AURA_DRAG * dt);
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
			int kind = (int) pool[p + 10];

			if (kind == LANCE) {
				// A lance only climbs and thins. The one force it feels is a light drag, so the tip
				// slows and the streak stretches out behind it.
				vy *= 1.0f - 0.25f * dt;
			} else {
				// Buoyancy — hot gas rises, and harder while it is still hot. Fading it with age is
				// what makes the top of the column stall and break up instead of climbing forever.
				vy += buoy * (1.0f - t * 0.7f) * dt;

				// Turbulence, ramped with age so the base stays coherent and only the top tears up.
				// Sheets take half of it: they are mass, and mass that boils reads as more spray.
				float tf = turb * dt * (0.18f + t * 1.4f) * (kind == SHEET ? 0.5f : 1.0f);
				vx += turbX(x, y, z, noiseTime) * tf;
				vz += turbZ(x, y, z, noiseTime) * tf;

				// Swirl about the flexer's own axis. Without it the turbulence reads as jitter rather
				// than as motion, and the field stops looking like it belongs to a person.
				float sw = swirl * dt * pool[p + 9] * 0.05f;
				vx += -z * sw;
				vz += x * sw;

				// Taper — a pull toward the axis that grows with age. This is what gives the column
				// its point; buoyancy and turbulence alone make a smoke plume, which widens forever.
				float pull = taper * dt * t;
				vx -= x * pull;
				vz -= z * pull;

				vx *= drag;
				vy *= drag;
				vz *= drag;
			}

			// The shift is what keeps a particle in the world while the local frame moves under it.
			// Lances take their own, because they follow the player rather than the air · update.
			float sx = kind == LANCE ? lanceShiftX : shiftX;
			float sy = kind == LANCE ? lanceShiftY : shiftY;
			float sz = kind == LANCE ? lanceShiftZ : shiftZ;

			int q = kept * STRIDE;
			pool[q] = x + vx * dt - sx;
			pool[q + 1] = y + vy * dt - sy;
			pool[q + 2] = z + vz * dt - sz;
			pool[q + 3] = vx;
			pool[q + 4] = vy;
			pool[q + 5] = vz;
			pool[q + 6] = age;
			pool[q + 7] = life;
			pool[q + 8] = pool[p + 8];
			pool[q + 9] = pool[p + 9];
			pool[q + 10] = pool[p + 10];
			kept++;
		}
		count = kept;
	}

	/**
	 * One layer's cut of the room left in the pool, floored at zero.
	 *
	 * <p>Rounding down every share can leave a particle or two of the cap unspent on a frame. That is
	 * the right way to miss: the alternative rounds up and overruns a ceiling that exists to stop a
	 * weak machine dropping frames.
	 */
	private static int share(int want, int asked, int room) {
		return asked <= 0 || room <= 0 ? 0 : (int) ((long) want * room / asked);
	}

	/**
	 * The field's envelope at a given moment, {@code 1 - depth}..1 · {@code FLEX_AURA_PULSE_PERIOD}.
	 *
	 * <p>Static and phase-free so that anything else which ought to breathe with a field can read the
	 * same curve without owning one — {@code ScreenShake} bills the vignette against it, so what a
	 * crushed player feels is in step with what they can see standing in front of them. A field's own
	 * simulation passes its {@link #phase} in, which is what keeps two flexers from surging together.
	 */
	static float pulse(float time) {
		float period = (float) Math.max(0.05, BleachTuning.FLEX_AURA_PULSE_PERIOD);
		float depth = (float) Mth.clamp(BleachTuning.FLEX_AURA_PULSE_DEPTH, 0.0, 1.0);
		float beat = 0.5f + 0.5f * Mth.cos(Mth.TWO_PI * time / period);
		return (1.0f - depth) + depth * beat;
	}

	/** Intensity of the whole field at this Reiatsu tier, clamped to the table's ends. */
	private static float tierGain(int tier) {
		double[] table = BleachTuning.FLEX_AURA_TIER_GAIN;
		if (table == null || table.length == 0) {
			return 1.0f;
		}
		return (float) table[Mth.clamp(tier, 0, table.length - 1)];
	}

	// --- Birth ---------------------------------------------------------------------------------

	/**
	 * The column: born through a small disc at the flexer's feet and thrown up hard.
	 *
	 * <p>Square-rooted radius so the disc fills evenly rather than crowding its own rim, and the
	 * onset burst is folded into the launch speed as well as the count — a shove that spawns more
	 * particles at the same speed reads as fog, not as force.
	 */
	private void spawnColumn(float burst) {
		ensure(count + 1);

		float a = rand() * Mth.TWO_PI;
		float rr = Mth.sqrt(rand()) * (float) BleachTuning.FLEX_AURA_COLUMN_DISC;

		int p = count * STRIDE;
		pool[p] = Mth.cos(a) * rr;
		pool[p + 1] = rand() * 0.35f;
		pool[p + 2] = Mth.sin(a) * rr;
		pool[p + 3] = Mth.cos(a) * (0.4f + rand()) * 0.6f + inheritX;
		pool[p + 4] = (1.2f + rand() * 2.6f) * Math.min(2.0f, burst) + inheritY;
		pool[p + 5] = Mth.sin(a) * (0.4f + rand()) * 0.6f + inheritZ;
		pool[p + 6] = 0.0f;
		pool[p + 7] = (float) BleachTuning.FLEX_AURA_LIFE * (0.55f + rand() * 0.9f);
		// Small and many, never big and few: additive light builds a gradient out of overlap, while
		// a handful of fat blobs clips to white wherever two of them cross.
		pool[p + 8] = (float) BleachTuning.FLEX_AURA_GRAIN * (0.6f + rand() * 0.9f);
		pool[p + 9] = (rand() - 0.5f) * 2.0f;
		pool[p + 10] = COLUMN;
		count++;
	}

	/**
	 * A lance: a thin fast streak climbing somewhere out in the field.
	 *
	 * <p>Spawned on an annulus rather than a disc — {@code FLEX_AURA_LANCE_INNER} keeps them out of
	 * the middle, where the column already is. Filling the disc instead would put most of them near
	 * the axis, which is the one place the field does not need help reading as tall.
	 */
	private void spawnLance(float radius) {
		ensure(count + 1);

		float a = rand() * Mth.TWO_PI;
		float inner = (float) Mth.clamp(BleachTuning.FLEX_AURA_LANCE_INNER, 0.0, 0.95);
		float rr = radius * (inner + (1.0f - inner) * Mth.sqrt(rand()));

		int p = count * STRIDE;
		pool[p] = Mth.cos(a) * rr;
		pool[p + 1] = rand() * 0.25f;
		pool[p + 2] = Mth.sin(a) * rr;
		// No inherited velocity, unlike the other two. A lance already follows the player through
		// FLEX_AURA_LANCE_CARRY, and it feels no drag — anything added here would never decay, and
		// the wall would shear itself apart over a lance's life.
		pool[p + 3] = 0.0f;
		pool[p + 4] = (float) BleachTuning.FLEX_AURA_LANCE_SPEED * (0.7f + rand() * 0.6f);
		pool[p + 5] = 0.0f;
		pool[p + 6] = 0.0f;
		pool[p + 7] = (float) BleachTuning.FLEX_AURA_LANCE_LIFE * (0.6f + rand() * 0.8f);
		pool[p + 8] = (float) (BleachTuning.FLEX_AURA_GRAIN * BleachTuning.FLEX_AURA_LANCE_GRAIN)
				* (0.7f + rand() * 0.6f);
		pool[p + 9] = 0.0f;
		pool[p + 10] = LANCE;
		count++;
	}

	/** A sheet: big, slow, dim. Nine a second of these is the difference between a field and a mist. */
	private void spawnSheet(float radius) {
		ensure(count + 1);

		float a = rand() * Mth.TWO_PI;
		float rr = radius * (0.15f + rand() * 0.55f);

		int p = count * STRIDE;
		pool[p] = Mth.cos(a) * rr;
		pool[p + 1] = rand() * 1.2f;
		pool[p + 2] = Mth.sin(a) * rr;
		pool[p + 3] = Mth.cos(a) * 0.35f + inheritX;
		pool[p + 4] = 0.8f + rand() * 1.4f + inheritY;
		pool[p + 5] = Mth.sin(a) * 0.35f + inheritZ;
		pool[p + 6] = 0.0f;
		pool[p + 7] = (float) BleachTuning.FLEX_AURA_LIFE * (1.4f + rand() * 1.2f);
		pool[p + 8] = (float) (BleachTuning.FLEX_AURA_GRAIN * BleachTuning.FLEX_AURA_SHEET_GRAIN)
				* (0.7f + rand() * 0.6f);
		pool[p + 9] = (rand() - 0.5f) * 2.0f;
		pool[p + 10] = SHEET;
		count++;
	}

	/**
	 * One ring per beat, and ages the live ones.
	 *
	 * <p>The timer runs off the pulse rather than off a rate of its own, because a shockwave that
	 * leaves on a different clock from the surge that threw it is two effects again.
	 */
	private void spawnRings(float dt, float period, float pulse,
			float shiftX, float shiftY, float shiftZ) {
		float life = period * (float) Math.max(0.05, BleachTuning.FLEX_AURA_RING_LIFE);

		int kept = 0;
		for (int i = 0; i < ringCount; i++) {
			int r = i * RING_STRIDE;
			float age = rings[r] + dt;
			if (age >= life) {
				continue;
			}

			int q = kept * RING_STRIDE;
			rings[q] = age;
			// A ring is the one part of the field that already belonged to the world rather than to
			// the player — §H.6 kept it for exactly that reason — so it is anchored like everything
			// else now. Before this it was drawn around wherever the flexer currently stood, which
			// dragged an expanding shockwave sideways whenever they walked.
			rings[q + 1] = rings[r + 1] - shiftX;
			rings[q + 2] = rings[r + 2] - shiftY;
			rings[q + 3] = rings[r + 3] - shiftZ;
			kept++;
		}
		ringCount = kept;

		ringTimer -= dt;
		if (ringTimer <= 0.0f) {
			ringTimer += period;
			if (ringCount < rings.length / RING_STRIDE && pulse > 0.5f) {
				int q = ringCount * RING_STRIDE;
				rings[q] = 0.0f;
				rings[q + 1] = 0.0f;
				rings[q + 2] = 0.0f;
				rings[q + 3] = 0.0f;
				ringCount++;
			}
		}
	}

	// --- Drawing -------------------------------------------------------------------------------

	/**
	 * Emit every particle and every ring into the shared buffer.
	 *
	 * <p>{@code left} and {@code up} are the camera's own basis, so a particle is a billboard: a flat
	 * disc that always faces the viewer, which is what a light source looks like and costs three
	 * vertices per segment to draw. The field's <em>volume</em> comes from where the particles are,
	 * not from any one of them having a shape.
	 *
	 * @param originX camera-relative position of the flexer's feet — the pose is already at the
	 *                camera, so this is where the whole local frame is planted
	 */
	void draw(BufferBuilder buffer, Matrix4f matrix, Vector3f left, Vector3f up,
			float originX, float originY, float originZ, float radius, int color) {
		float baseRed = ((color >> 16) & 0xFF) / 255.0f;
		float baseGreen = ((color >> 8) & 0xFF) / 255.0f;
		float baseBlue = (color & 0xFF) / 255.0f;

		float grow = (float) BleachTuning.FLEX_AURA_GROW;
		float opacity = (float) BleachTuning.FLEX_AURA_OPACITY;
		float sheetOpacity = opacity * (float) BleachTuning.FLEX_AURA_SHEET_OPACITY;
		float stretch = (float) BleachTuning.FLEX_AURA_STRETCH;
		float lanceStretch = (float) BleachTuning.FLEX_AURA_LANCE_STRETCH;
		float nearFade = (float) Math.max(0.0, BleachTuning.FLEX_AURA_NEAR_FADE);

		for (int i = 0; i < count; i++) {
			int p = i * STRIDE;
			float t = pool[p + 6] / pool[p + 7];
			int kind = (int) pool[p + 10];

			// Swells in fast, falls off squared — which is what keeps the tips wispy. Kept low on
			// purpose: brightness comes from how many particles overlap, not from how solid one is.
			float a = Math.min(1.0f, t * 5.0f) * (1.0f - t) * (1.0f - t)
					* (kind == SHEET ? sheetOpacity : opacity);
			if (a <= 0.004f) {
				continue;
			}

			float cx = originX + pool[p];
			float cy = originY + pool[p + 1];
			float cz = originZ + pool[p + 2];

			// Anything close enough to the eye to fill the screen fades out instead. Two cases, one
			// rule: your own column stands exactly where your head is in first person, and walking
			// through somebody else's field would otherwise white the screen out at the boundary.
			if (nearFade > 0.0f) {
				float distance = Mth.sqrt(cx * cx + cy * cy + cz * cz);
				if (distance < nearFade) {
					a *= distance / nearFade;
					if (a <= 0.004f) {
						continue;
					}
				}
			}

			// Grow as it rises and cools — expanding gas — then shrink into nothing at the very end,
			// so particles do not blink out at full size.
			float size = pool[p + 8] * (1.0f + t * grow) * (1.0f - t * t * 0.35f);

			// Stretch along the direction of travel. This is the single thing that turns a column of
			// round dots into fire: a fast particle is a streak, a slow one at the top is a puff.
			// Area is held roughly constant — widen by s, thin by 1/√s — so stretching does not also
			// brighten.
			float vx = pool[p + 3];
			float vy = pool[p + 4];
			float vz = pool[p + 5];
			float vsx = vx * left.x() + vy * left.y() + vz * left.z();
			float vsy = vx * up.x() + vy * up.y() + vz * up.z();
			float speed = Mth.sqrt(vsx * vsx + vsy * vsy);

			float k = kind == LANCE ? lanceStretch : stretch;
			float s = 1.0f + k * Math.min(2.2f, speed * 0.42f);
			float tilt = s > 1.03f ? (float) Math.atan2(vsy, vsx) : 0.0f;
			float minor = s > 1.03f ? size / Mth.sqrt(s) : size;
			float major = s > 1.03f ? size * s : size;

			blob(buffer, matrix, left, up, cx, cy, cz, major, minor, tilt,
					baseRed, baseGreen, baseBlue, t, a);
		}

		drawRings(buffer, matrix, originX, originY, originZ, radius, baseRed, baseGreen, baseBlue);
	}

	/**
	 * The shockwave: a flat band sweeping out to the field's edge, drawn on the ground.
	 *
	 * <p>Flat and horizontal rather than billboarded, because this one <em>is</em> a shape and the
	 * shape is the point — it is the only part of the field that answers "am I standing in it?", so
	 * it has to lie on the floor the target is standing on.
	 */
	private void drawRings(BufferBuilder buffer, Matrix4f matrix, float originX, float originY,
			float originZ, float radius, float red, float green, float blue) {
		if (ringCount == 0 || radius <= 0.0f) {
			return;
		}

		int segments = Math.max(8, BleachTuning.FLEX_AURA_RING_SEGMENTS);
		float width = (float) BleachTuning.FLEX_AURA_RING_WIDTH;
		float alpha = (float) BleachTuning.FLEX_AURA_RING_ALPHA;
		float period = (float) Math.max(0.05, BleachTuning.FLEX_AURA_PULSE_PERIOD);
		float life = period * (float) Math.max(0.05, BleachTuning.FLEX_AURA_RING_LIFE);
		float yOffset = (float) BleachTuning.FLEX_RING_Y_OFFSET;

		for (int i = 0; i < ringCount; i++) {
			int r = i * RING_STRIDE;
			float t = rings[r] / life;

			// Each ring carries the spot it was thrown from, not the spot its owner is standing on.
			float cx = originX + rings[r + 1];
			float y = originY + rings[r + 2] + yOffset;
			float cz = originZ + rings[r + 3];

			// Out fast and decelerating, not linear: a shockwave that expands at a constant rate
			// reads as a scanning circle rather than as something that was thrown.
			float ease = 1.0f - (1.0f - t) * (1.0f - t);
			float outer = radius * ease;
			float inner = Math.max(0.0f, outer - width);
			float a = alpha * (1.0f - t) * Math.min(1.0f, t * 6.0f);
			if (a <= 0.004f) {
				continue;
			}

			float prevCos = 1.0f;
			float prevSin = 0.0f;
			for (int seg = 1; seg <= segments; seg++) {
				float angle = (Mth.TWO_PI * seg) / segments;
				float cos = Mth.cos(angle);
				float sin = Mth.sin(angle);

				// Two triangles per segment, bright on the leading edge and transparent behind it,
				// so the band is a gradient rather than a hoop with two hard sides.
				buffer.addVertex(matrix, cx + prevCos * inner, y, cz + prevSin * inner)
						.setColor(red, green, blue, 0.0f);
				buffer.addVertex(matrix, cx + prevCos * outer, y, cz + prevSin * outer)
						.setColor(red, green, blue, a);
				buffer.addVertex(matrix, cx + cos * outer, y, cz + sin * outer)
						.setColor(red, green, blue, a);

				buffer.addVertex(matrix, cx + prevCos * inner, y, cz + prevSin * inner)
						.setColor(red, green, blue, 0.0f);
				buffer.addVertex(matrix, cx + cos * outer, y, cz + sin * outer)
						.setColor(red, green, blue, a);
				buffer.addVertex(matrix, cx + cos * inner, y, cz + sin * inner)
						.setColor(red, green, blue, 0.0f);

				prevCos = cos;
				prevSin = sin;
			}
		}
	}

	/**
	 * Colour at age {@code t}: white-hot, through the field's own colour, into a deep cooled version
	 * of it. Written straight into {@code out} to keep the draw loop allocation-free.
	 *
	 * <p>The white phase runs to {@code t = 0.06} and no further. Additive blending stacks it, so a
	 * wide white band saturates the middle of the column into a featureless pill; held brief, the
	 * same ramp reads as a hot core.
	 */
	private static void ramp(float[] out, float red, float green, float blue, float t) {
		float hotRed = Mth.lerp(0.72f, red, 1.0f);
		float hotGreen = Mth.lerp(0.72f, green, 1.0f);
		float hotBlue = Mth.lerp(0.72f, blue, 1.0f);

		if (t < 0.06f) {
			float k = t / 0.06f;
			out[0] = Mth.lerp(k, 1.0f, hotRed);
			out[1] = Mth.lerp(k, 1.0f, hotGreen);
			out[2] = Mth.lerp(k, 1.0f, hotBlue);
		} else if (t < 0.24f) {
			float k = (t - 0.06f) / 0.18f;
			out[0] = Mth.lerp(k, hotRed, red);
			out[1] = Mth.lerp(k, hotGreen, green);
			out[2] = Mth.lerp(k, hotBlue, blue);
		} else {
			float k = (t - 0.24f) / 0.76f;
			out[0] = Mth.lerp(k, red, red * 0.42f);
			out[1] = Mth.lerp(k, green, green * 0.16f);
			out[2] = Mth.lerp(k, blue, blue * 0.62f);
		}
	}

	/** Scratch for {@link #ramp}. The render pass is the only caller, and it is one thread. */
	private static final float[] RAMP = new float[3];

	/**
	 * One particle: a lit centre with a rim that fades to nothing, as a fan of loose triangles on the
	 * camera plane.
	 *
	 * <p>Five segments, not twenty · {@code FLEX_AURA_SEGMENTS}. A particle is a handful of pixels
	 * across and there are thousands of them; at that size a pentagon and a circle are the same
	 * picture, and the softness comes from the rim alpha rather than from the silhouette.
	 */
	private static void blob(BufferBuilder buffer, Matrix4f matrix, Vector3f left, Vector3f up,
			float cx, float cy, float cz, float rx, float ry, float tilt,
			float red, float green, float blue, float t, float alpha) {
		ramp(RAMP, red, green, blue, t);
		float r = RAMP[0];
		float g = RAMP[1];
		float b = RAMP[2];

		int segments = Math.max(3, BleachTuning.FLEX_AURA_SEGMENTS);
		float cos = Mth.cos(tilt);
		float sin = Mth.sin(tilt);

		float prevX = 0.0f;
		float prevY = 0.0f;
		float prevZ = 0.0f;
		for (int i = 0; i <= segments; i++) {
			float angle = (Mth.TWO_PI * i) / segments;
			float ox = Mth.cos(angle) * rx;
			float oy = Mth.sin(angle) * ry;

			// Rotate the offset in the camera plane, then resolve it onto the camera basis. Doing the
			// tilt before the basis is what keeps a streak pointing along its velocity from every
			// angle rather than only from the one the maths was written for.
			float u = ox * cos - oy * sin;
			float v = ox * sin + oy * cos;
			float x = cx + left.x() * u + up.x() * v;
			float y = cy + left.y() * u + up.y() * v;
			float z = cz + left.z() * u + up.z() * v;

			if (i > 0) {
				buffer.addVertex(matrix, cx, cy, cz).setColor(r, g, b, alpha);
				buffer.addVertex(matrix, prevX, prevY, prevZ).setColor(r, g, b, 0.0f);
				buffer.addVertex(matrix, x, y, z).setColor(r, g, b, 0.0f);
			}

			prevX = x;
			prevY = y;
			prevZ = z;
		}
	}

	// --- Noise ---------------------------------------------------------------------------------

	/*
	 * Deliberately the cheapest thing that still looks like turbulence: three sine waves at unrelated
	 * frequencies, exactly as AuraFlame does it. Real value noise is barely dearer, but this needs no
	 * table and at this scale nobody can tell them apart.
	 */

	private static float turbX(float x, float y, float z, float time) {
		float c = time * CHURN;
		return Mth.sin(y * 3.5f + c * 1.7f) * Mth.cos(z * 2.6f - c * 1.1f)
				+ Mth.sin(x * 5.3f - c * 2.3f) * 0.5f
				+ Mth.sin(y * 1.1f + c * 0.6f) * 0.8f;
	}

	private static float turbZ(float x, float y, float z, float time) {
		float c = time * CHURN;
		return Mth.cos(y * 3.9f - c * 1.4f) * Mth.sin(x * 2.4f + c * 1.3f)
				+ Mth.cos(z * 5.1f + c * 2.1f) * 0.5f
				+ Mth.cos(y * 1.2f - c * 0.7f) * 0.8f;
	}

	// --- Pool ----------------------------------------------------------------------------------

	private void ensure(int particles) {
		int needed = particles * STRIDE;
		if (needed <= pool.length) {
			return;
		}

		float[] grown = new float[Math.max(needed, pool.length * 2)];
		System.arraycopy(pool, 0, grown, 0, count * STRIDE);
		pool = grown;
	}

	/** xorshift, 0..1. The render loop wants a few thousand of these a frame and no allocation. */
	private float rand() {
		rng ^= rng << 13;
		rng ^= rng >>> 17;
		rng ^= rng << 5;
		return (rng >>> 8) / (float) (1 << 24);
	}

	private static float hash(int a, int b) {
		int h = a * 374761393 + b * 668265263;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) >>> 8) / (float) (1 << 24);
	}
}
