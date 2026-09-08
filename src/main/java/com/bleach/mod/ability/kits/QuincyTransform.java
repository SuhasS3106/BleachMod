package com.bleach.mod.ability.kits;

import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
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
		}
		onTierEnter(player, data);
	}

	@Override
	public void onTick(ServerPlayer player, SpiritualData data) {
		if (isVollstandig()) {
			drawWings(player);
		}
		onTierTick(player, data);
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
	 * The wings — an arc of pressure particles behind the shoulders, tinted by the kit colour. No
	 * model and no texture; this is the one visual that reads at range as "in Vollständig".
	 */
	private void drawWings(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		double yaw = Math.toRadians(player.getYRot());
		double backX = Math.sin(yaw) * BleachTuning.VOLL_WING_OFFSET;
		double backZ = -Math.cos(yaw) * BleachTuning.VOLL_WING_OFFSET;

		int count = Math.max(1, BleachTuning.VOLL_WING_PARTICLES);
		for (int i = 0; i < count; i++) {
			double t = (i / (double) count) * Math.PI;
			double spread = Math.cos(t) * BleachTuning.VOLL_WING_RADIUS;
			double lift = Math.sin(t) * BleachTuning.VOLL_WING_RADIUS;

			level.sendParticles(
					new PressureParticleOptions(wingColour(), (float) BleachTuning.VOLL_WING_PARTICLE_SCALE),
					player.getX() + backX + Math.cos(yaw) * spread,
					player.getY() + 1.0 + lift,
					player.getZ() + backZ + Math.sin(yaw) * spread,
					1, 0.0, 0.0, 0.0, 0.0);
		}
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
