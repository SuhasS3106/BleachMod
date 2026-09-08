package com.bleach.mod.ability.kits;

import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.tuning.BleachTuning;

import org.joml.Vector3f;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The shared half of every Schrift · design §5.1.
 *
 * <p>Tier 1 is the letter's own power and this base holds almost nothing for it. Tier 2 is
 * Vollständig, and this base holds <b>all</b> of it — the stat package, the Hirenkyaku bonus and the
 * wings — leaving a subclass to supply only an amplified form of its tier-1 power.
 *
 * <p>That split is what makes four Schrifts affordable: a Schrift is one power written at two
 * intensities, not two unrelated kits.
 *
 * <p><b>Per-player state must never be an instance field.</b> One kit object is shared by every
 * player who picked that Schrift; a field would put all of them on one cooldown. Use a
 * {@code Map<UUID, …>} keyed by the player, as {@code RukiaTransform} does.
 */
public abstract class QuincyTransform implements TransformAbility {

	private static final ResourceLocation VOLL_SPEED_ID =
			ResourceLocation.fromNamespaceAndPath("bleach_mod", "vollstandig_speed");

	private final ResourceLocation id;

	protected QuincyTransform(ResourceLocation id) {
		this.id = id;
	}

	@Override
	public ResourceLocation id() {
		return id;
	}

	/** The Schrift's own entry work. */
	protected abstract void onTierEnter(ServerPlayer player, SpiritualData data);

	/** The Schrift's own per-tick work. */
	protected abstract void onTierTick(ServerPlayer player, SpiritualData data);

	/** The Schrift's own teardown. Must be idempotent — see {@link TransformAbility#onRevert}. */
	protected abstract void onTierRevert(ServerPlayer player, SpiritualData data);

	/** Whether this tier is Vollständig and should carry the shared release package. */
	protected boolean isVollstandig() {
		return state() == SpiritualData.STATE_BANKAI;
	}

	@Override
	public void onEnter(ServerPlayer player, SpiritualData data) {
		if (isVollstandig()) {
			applySpeed(player);
			ringVollstandig(player);
		}
		onTierEnter(player, data);
	}

	/**
	 * The bell. Vollständig announces itself with a struck bell in both the manga and the anime, and
	 * it is the one audio cue the release has — so it is broadcast from the player's position rather
	 * than sent to them alone, and everyone nearby hears a Quincy go up.
	 *
	 * <p>Pitched below vanilla's bell by default: a full-height block bell reads as a village, and a
	 * slower, heavier toll reads as a release.
	 */
	private static void ringVollstandig(ServerPlayer player) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.BELL_BLOCK, SoundSource.PLAYERS,
				(float) BleachTuning.VOLL_BELL_VOLUME, (float) BleachTuning.VOLL_BELL_PITCH);
	}

	@Override
	public void onTick(ServerPlayer player, SpiritualData data) {
		if (isVollstandig()) {
			drawAura(player);
			if (isStill(player)) {
				drawWings(player);
			}
		}
		onTierTick(player, data);
	}

	/**
	 * Whether the player has effectively not moved this tick. The wings only unfurl when they are
	 * standing — moving, they furl and leave the aura alone, which is both how Vollständig reads on
	 * screen and a large saving on particle count while a player is running around.
	 *
	 * <p>Measured from the entity's own previous position rather than {@code getDeltaMovement},
	 * which on a server-side player is frequently near zero while walking.
	 */
	private static boolean isStill(ServerPlayer player) {
		double dx = player.getX() - player.xo;
		double dy = player.getY() - player.yo;
		double dz = player.getZ() - player.zo;
		return dx * dx + dy * dy + dz * dz <= BleachTuning.VOLL_WING_STILL_THRESHOLD;
	}

	/**
	 * The aura — a loose column of the kit's colour boiling around the player. Always on while in
	 * Vollständig, so a Quincy in flight still reads as released even with the wings furled.
	 */
	private void drawAura(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		DustParticleOptions dust = new DustParticleOptions(
				colourVector(wingColour()), (float) BleachTuning.VOLL_AURA_PARTICLE_SCALE);

		int count = Math.max(0, BleachTuning.VOLL_AURA_PARTICLES);
		double radius = BleachTuning.VOLL_AURA_RADIUS;
		double height = BleachTuning.VOLL_AURA_HEIGHT;

		for (int i = 0; i < count; i++) {
			double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
			double r = radius * Math.sqrt(player.getRandom().nextDouble());
			level.sendParticles(dust,
					player.getX() + Math.cos(angle) * r,
					player.getY() + player.getRandom().nextDouble() * height,
					player.getZ() + Math.sin(angle) * r,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * Teardown. The speed modifier is removed unconditionally rather than under
	 * {@link #isVollstandig()}: a tier-1 object can never have applied it, and removing an absent
	 * modifier is a no-op, so the unconditional call is the one that stays correct if a revert ever
	 * arrives through an object whose tier does not match what actually entered.
	 */
	@Override
	public void onRevert(ServerPlayer player, SpiritualData data) {
		removeSpeed(player);
		onTierRevert(player, data);
	}

	@Override
	public double flashStepRangeMult() {
		return isVollstandig() ? BleachTuning.VOLL_FS_RANGE_MULT : 1.0;
	}

	@Override
	public double meleeDamageBonus() {
		return isVollstandig() ? BleachTuning.VOLL_DMG : 0.0;
	}

	private static void applySpeed(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		speed.removeModifier(VOLL_SPEED_ID);
		speed.addPermanentModifier(new AttributeModifier(VOLL_SPEED_ID,
				BleachTuning.VOLL_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	/** Idempotent, and safe on a dead or unloaded player — every revert path reaches it. */
	protected static void removeSpeed(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(VOLL_SPEED_ID);
		}
	}

	/**
	 * The wings — two fans of feathers swept back from the shoulders, tinted by the kit colour. No
	 * model and no texture; this is the one visual that reads at range as "in Vollständig".
	 *
	 * <h2>Why this does not use the mod's own pressure particle</h2>
	 *
	 * <p>It did, and it did not work. {@code PressureParticle} is a needle that <em>rises</em> —
	 * {@code RISE_ACCEL} climbing to {@code RISE_MAX} across a 10–22 tick life — because its job
	 * everywhere else is to be a column of pressure venting off a player. Any static shape drawn
	 * with it smears upward within a few ticks, so the wings read as a vertical sprinkle rather than
	 * as wings. That is a property of the particle, not of the geometry: no amount of redrawing
	 * fixes a mark that leaves as soon as it is placed.
	 *
	 * <p>Vanilla's dust particle is the right tool instead. It takes an arbitrary RGB tint, so the
	 * per-kit colour survives, and it essentially stays where it is put — which is the whole
	 * requirement for a shape redrawn every few ticks.
	 *
	 * <h2>The shape</h2>
	 *
	 * <p>Each wing is {@code VOLL_WING_FEATHERS} feathers fanned from near-horizontal to steeply
	 * raised, each drawn as {@code VOLL_WING_SEGMENTS} points along its length, and each swept
	 * backwards in proportion to how far out it reaches — which is what stops the fan reading as a
	 * flat disc. Both wings are drawn from the same shoulder line, mirrored through {@code side}.
	 */
	private void drawWings(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		int interval = Math.max(1, BleachTuning.VOLL_WING_INTERVAL);
		if (player.tickCount % interval != 0) {
			return;
		}

		double yaw = Math.toRadians(player.getYRot());
		// Minecraft yaw 0 faces +Z, so forward is (-sin, cos); right and back follow from it.
		double rightX = Math.cos(yaw);
		double rightZ = Math.sin(yaw);
		double backX = Math.sin(yaw);
		double backZ = -Math.cos(yaw);

		DustParticleOptions dust = wingDust();
		int feathers = Math.max(1, BleachTuning.VOLL_WING_FEATHERS);
		int segments = Math.max(1, BleachTuning.VOLL_WING_SEGMENTS);
		double radius = BleachTuning.VOLL_WING_RADIUS;
		double shoulder = BleachTuning.VOLL_WING_OFFSET;

		// The beat. Tips travel further than roots, which is what a real wing does and what stops
		// the motion reading as the whole shape sliding up and down.
		double beat = Math.sin(player.tickCount * WING_FLAP_SPEED) * WING_FLAP_DEGREES;

		for (int side = -1; side <= 1; side += 2) {
			for (int f = 0; f < feathers; f++) {
				// 0 is the innermost feather, 1 the outermost; the fan sweeps up and back.
				double u = feathers == 1 ? 0.5 : f / (double) (feathers - 1);

				double degrees = WING_MIN_ANGLE + u * (WING_MAX_ANGLE - WING_MIN_ANGLE)
						+ beat * (WING_FLAP_ROOT + (1.0 - WING_FLAP_ROOT) * u);
				double angle = Math.toRadians(degrees);

				// Longest through the middle of the fan, shorter at root and tip — a wing profile
				// rather than a quarter-disc. The old form peaked at u=0, which is the flattest
				// feather, and that is precisely what made the wings splay out sideways.
				double length = radius * (WING_MIN_LENGTH
						+ (1.0 - WING_MIN_LENGTH) * Math.sin(Math.PI * u));

				// Perpendicular to the feather, within the wing plane — the axis a jagged wing
				// zigzags along.
				double perpOut = -Math.sin(angle);
				double perpUp = Math.cos(angle);
				double zigzag = wingJagged() ? BleachTuning.VOLL_WING_ZIGZAG : 0.0;

				for (int s = 1; s <= segments; s++) {
					double t = s / (double) segments;

					// Alternating kick, tapering toward the tip so the bolt narrows as it goes out
					// rather than staying a constant-width ribbon.
					double kick = zigzag * ((s % 2 == 0) ? 1.0 : -1.0) * (1.0 - t * 0.35);

					double out = (Math.cos(angle) * length * t + perpOut * kick) * WING_SPREAD;
					double up = Math.sin(angle) * length * t + perpUp * kick;
					double back = shoulder + out * WING_SWEEP;

					level.sendParticles(dust,
							player.getX() + rightX * out * side + backX * back,
							player.getY() + WING_SHOULDER_HEIGHT + up,
							player.getZ() + rightZ * out * side + backZ * back,
							1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}
	}

	/** Innermost feather's angle above horizontal, degrees. Kept well off flat. */
	private static final double WING_MIN_ANGLE = 4.0;
	/** Outermost feather's angle above horizontal, degrees. */
	private static final double WING_MAX_ANGLE = 82.0;
	/** Length of the root and tip feathers as a fraction of the longest, mid-fan one. */
	private static final double WING_MIN_LENGTH = 0.5;
	/** Horizontal compression. Below 1 makes the wing taller than it is wide. */
	private static final double WING_SPREAD = 1.05;
	/** How far back a feather is swept per block it reaches outward. */
	private static final double WING_SWEEP = 0.55;
	/** Height of the shoulder line above the player's feet, blocks. */
	private static final double WING_SHOULDER_HEIGHT = 1.15;
	/** Radians of beat phase per tick. About a 1.6-second cycle. */
	private static final double WING_FLAP_SPEED = 0.16;
	/** Peak swing of the beat, degrees, applied at the tip. */
	private static final double WING_FLAP_DEGREES = 14.0;
	/** Share of the beat the root feather gets; the tip gets all of it. */
	private static final double WING_FLAP_ROOT = 0.35;

	/**
	 * Whether this Schrift's wings are jagged lightning rather than smooth feathers. Defaulted off,
	 * so a Schrift gets the smooth shape unless it asks otherwise — the seam exists so the four
	 * letters can differ in silhouette, not only in colour.
	 */
	protected boolean wingJagged() {
		return false;
	}

	/** Packed RGB to the float vector the dust particle wants. */
	private static Vector3f colourVector(int rgb) {
		return new Vector3f(((rgb >> 16) & 0xFF) / 255.0f,
				((rgb >> 8) & 0xFF) / 255.0f,
				(rgb & 0xFF) / 255.0f);
	}

	/** The kit-tinted dust the wings are drawn in. */
	private DustParticleOptions wingDust() {
		int rgb = wingColour();
		return new DustParticleOptions(
				new Vector3f(((rgb >> 16) & 0xFF) / 255.0f,
						((rgb >> 8) & 0xFF) / 255.0f,
						(rgb & 0xFF) / 255.0f),
				(float) BleachTuning.VOLL_WING_PARTICLE_SCALE);
	}

	/** Overridden by a Schrift that wants its own wing colour; defaults to white. */
	protected int wingColour() {
		return 0xFFFFFF;
	}

	/** Release 1 — the Schrift. Sits in the Shikai slot and inherits its gate and drain. */
	public abstract static class Tier1 extends QuincyTransform {
		protected Tier1(ResourceLocation id) {
			super(id);
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_SHIKAI;
		}

		@Override
		public double drainPerSecond() {
			return BleachTuning.DRAIN_SHIKAI;
		}

		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_SHIKAI, soulLevel);
		}
	}

	/** Release 2 — Vollständig. Sits in the Bankai slot, loan and claw-back included. */
	public abstract static class Tier2 extends QuincyTransform {
		protected Tier2(ResourceLocation id) {
			super(id);
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_BANKAI;
		}

		@Override
		public double drainPerSecond() {
			return BleachTuning.DRAIN_BANKAI;
		}

		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_BANKAI, soulLevel);
		}
	}
}
