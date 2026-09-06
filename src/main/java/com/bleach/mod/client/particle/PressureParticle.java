package com.bleach.mod.client.particle;

import com.bleach.mod.particle.PressureParticleOptions;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;

/**
 * A thin vertical needle of spiritual pressure, rising fast. The mod's entire visual vocabulary,
 * tinted per kit.
 *
 * <p>No gravity and no physics: pressure rises through whatever is in the way.
 *
 * <h2>Why the needle is in the texture and not in the geometry</h2>
 *
 * <p>{@link net.minecraft.client.particle.SingleQuadParticle} draws one <em>square</em> billboard of
 * {@code quadSize × quadSize}, and the only hooks it exposes — {@code getQuadSize} and
 * {@code renderRotatedQuad} — are square on both axes. Getting a 4×16 quad out of it means
 * reimplementing {@code render} and its private {@code renderVertex} wholesale, which is fifty lines
 * of vertex maths that breaks on every version bump.
 *
 * <p>So the shape lives in the sprite instead: each texture is a 16×16 sheet carrying a 4-pixel-wide
 * tapered needle, at three heights (16, 12 and 8) for variety. Drawn on the square quad they read as
 * exactly the proportions they are, and {@code pickSprite} rolls one per particle for free — which
 * is also why the sheet stays square, because a non-square texture on a square quad would be
 * stretched back out into the fat blob this replaces.
 */
public final class PressureParticle extends TextureSheetParticle {

	/**
	 * Velocity retained per tick. Very close to 1 on purpose — the streak is buoyant, not thrown,
	 * and the old 0.94 killed the climb within a few ticks so every particle hung in the air.
	 */
	private static final float FRICTION = 0.985f;

	/** Upward acceleration per tick, in blocks. What makes the needle climb rather than drift. */
	private static final double RISE_ACCEL = 0.055;

	/** Ceiling on the climb so a long-lived needle does not outrun its own trail. */
	private static final double RISE_MAX = 0.55;

	/** Initial upward kick, so a particle spawned with no velocity still leaves immediately. */
	private static final double RISE_INITIAL = 0.12;

	/** Lifetime bounds, ticks. Presentation, not balance — the spend-facing dial is the ring size. */
	private static final int LIFETIME_MIN = 10;
	private static final int LIFETIME_MAX = 22;

	private static final float COLOR_MAX = 255.0f;

	private PressureParticle(ClientLevel level, double x, double y, double z,
			double xd, double yd, double zd, PressureParticleOptions options, SpriteSet sprites) {
		super(level, x, y, z);

		// Horizontal velocity is kept as given; the vertical component is only ever added to, so a
		// ring point spawned with zero velocity still rises instead of sitting where it was put.
		this.xd = xd;
		this.yd = yd + RISE_INITIAL;
		this.zd = zd;

		this.gravity = 0.0f;
		this.friction = FRICTION;
		this.hasPhysics = false;
		this.lifetime = LIFETIME_MIN + this.random.nextInt(LIFETIME_MAX - LIFETIME_MIN);
		this.quadSize *= options.scale();

		int rgb = options.color();
		setColor(((rgb >> 16) & 0xFF) / COLOR_MAX,
				((rgb >> 8) & 0xFF) / COLOR_MAX,
				(rgb & 0xFF) / COLOR_MAX);

		// One of the three needle heights, rolled per particle.
		pickSprite(sprites);
	}

	/**
	 * Accelerate upward, then move.
	 *
	 * <p>Applied before {@code super.tick()} so the kick lands in the same tick it is added rather
	 * than one behind, and clamped so the climb settles at a steady rise instead of accelerating
	 * without limit for the whole lifetime.
	 */
	@Override
	public void tick() {
		if (this.yd < RISE_MAX) {
			this.yd = Math.min(RISE_MAX, this.yd + RISE_ACCEL);
		}
		super.tick();
	}

	@Override
	public ParticleRenderType getRenderType() {
		// Translucent rather than opaque: the streak is meant to read as pressure in the air, and the
		// sprite's own alpha falloff is the only thing making it soft.
		return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
	}

	/** Client-side factory. Registered from {@code BleachModClient}. */
	public record Provider(SpriteSet sprites) implements ParticleProvider<PressureParticleOptions> {
		@Override
		public Particle createParticle(PressureParticleOptions options, ClientLevel level,
				double x, double y, double z, double xd, double yd, double zd) {
			return new PressureParticle(level, x, y, z, xd, yd, zd, options, sprites);
		}
	}
}
