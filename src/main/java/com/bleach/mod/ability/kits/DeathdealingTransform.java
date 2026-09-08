package com.bleach.mod.ability.kits;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

	/** Release 1 — the Schrift. Arrows dose; nothing else changes. */
	private static final class Schrift extends QuincyTransform.Tier1 {
		private Schrift() {
			super(SCHRIFT_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onProjectileHit(ServerPlayer player, LivingEntity target, float damage) {
			Doses.add(target, BleachTuning.DOSE_PER_ARROW, player.server.getTickCount());
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
			Doses.add(target, BleachTuning.DOSE_PER_ARROW_VOLL, player.server.getTickCount());
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_DEATHDEALING_PARTICLE_COLOR;
		}
	}
}
