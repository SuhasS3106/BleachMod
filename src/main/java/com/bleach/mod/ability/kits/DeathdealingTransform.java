package com.bleach.mod.ability.kits;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.tuning.BleachTuning;

import org.joml.Vector3f;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Schrift <b>D — The Deathdealing</b>, Askin Nakk Le Vaar · {@code BALANCE.md} §P.6.
 *
 * <p>Askin's power is how much of a thing a body can take. Here that is {@link Doses}: every landed
 * arrow leaves a dose, and every dose raises the damage that target takes from <em>everything</em>,
 * from anyone. Doses bleed off if nobody keeps applying them, so D is a clock — commit to a target
 * and finish it while the stack is up.
 *
 * <p>The two tiers are the same power at two intensities, as {@link QuincyTransform} requires:
 *
 * <ul>
 *   <li><b>Schrift (tier 1):</b> {@link BleachTuning#DOSE_PER_ARROW} doses per landed arrow. One
 *       target at a time, at the speed you can hit it.</li>
 *   <li><b>Vollständig (tier 2):</b> <i>Gift Bad Sonnenschein</i> — a standing dome of poison
 *       anchored where you released, dosing and damaging everything inside it on a clock. The same
 *       mechanic applied to a volume instead of to one arrow at a time. Arrows still dose, harder.</li>
 * </ul>
 *
 * <p><b>It is deliberately not an outright kill</b> — see {@link Doses}. The dose stack is a
 * vulnerability multiplier, not a death threshold.
 *
 * <p>Unlike every other tier in this mod the dome does <b>not</b> follow the player. That is the
 * point of the letter, and it is why {@link #onTierRevert} has real work to do: a dome outlives the
 * intent that made it unless something explicitly takes it down.
 */
public final class DeathdealingTransform {
	private DeathdealingTransform() {
	}

	public static final ResourceLocation SCHRIFT_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "deathdealing/schrift");
	public static final ResourceLocation VOLLSTANDIG_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "deathdealing/vollstandig");

	public static TransformAbility schrift() {
		return new Schrift();
	}

	public static TransformAbility vollstandig() {
		return new Vollstandig();
	}

	/**
	 * Applies doses and <b>shows them</b>.
	 *
	 * <p>The showing is not decoration. Without it the Schrift tier is invisible: the player presses
	 * the key, shoots, and nothing on screen changes — the whole power is a number on an entity
	 * nobody can see. A stacking mechanic the player cannot read is a mechanic they cannot use, so
	 * the ring on the target and the count on the action bar are part of the feature rather than
	 * polish on top of it.
	 */
	private static void dose(ServerPlayer player, LivingEntity target, int amount) {
		Doses.add(target, amount, player.server.getTickCount());
		int stack = Doses.count(target);

		if (player.level() instanceof ServerLevel level) {
			ring(level, target, stack);
		}

		player.displayClientMessage(Component.literal(String.format(
				"Dose %d/%d · ×%.2f", stack, BleachTuning.DOSE_MAX,
				Doses.damageTakenMultiplier(stack))), true);
	}

	/** A ring of Askin's purple around the target, one point per dose, so the stack is countable. */
	private static void ring(ServerLevel level, LivingEntity target, int stack) {
		int rgb = BleachTuning.KIT_DEATHDEALING_PARTICLE_COLOR;
		DustParticleOptions dust = new DustParticleOptions(
				new Vector3f(((rgb >> 16) & 0xFF) / 255.0f,
						((rgb >> 8) & 0xFF) / 255.0f,
						(rgb & 0xFF) / 255.0f),
				(float) BleachTuning.DOSE_PARTICLE_SCALE);

		double radius = target.getBbWidth() * 0.5 + BleachTuning.DOSE_RING_MARGIN;
		double height = target.getBbHeight() * 0.6;

		for (int i = 0; i < stack; i++) {
			double angle = (i / (double) Math.max(1, stack)) * Math.PI * 2.0;
			level.sendParticles(dust,
					target.getX() + Math.cos(angle) * radius,
					target.getY() + height,
					target.getZ() + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** Release 1 — the Schrift. Arrows dose; nothing else changes. */
	private static final class Schrift extends QuincyTransform.Tier1 {
		private Schrift() {
			super(SCHRIFT_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
			player.displayClientMessage(
					Component.literal("The Deathdealing — your arrows leave a dose."), true);
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onProjectileHit(ServerPlayer player, LivingEntity target, float damage) {
			dose(player, target, BleachTuning.DOSE_PER_ARROW);
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_DEATHDEALING_PARTICLE_COLOR;
		}
	}

	/** Release 2 — Gift Bad Sonnenschein. The dome, plus heavier arrows. */
	private static final class Vollstandig extends QuincyTransform.Tier2 {
		private Vollstandig() {
			super(VOLLSTANDIG_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
			PoisonDome.place(player);
			player.displayClientMessage(
					Component.literal("Gift Bad Sonnenschein."), true);
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
		}

		/**
		 * Takes the dome down. Reached from every revert path — deliberate toggle-off, SP exhaustion,
		 * death, logout, sheathing and the master switch — which is exactly what a field anchored away
		 * from its owner needs, since nothing else in the world holds a reference to it.
		 */
		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
			PoisonDome.remove(player);
		}

		@Override
		public void onProjectileHit(ServerPlayer player, LivingEntity target, float damage) {
			dose(player, target, BleachTuning.DOSE_PER_ARROW_VOLL);
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_DEATHDEALING_PARTICLE_COLOR;
		}
	}
}
