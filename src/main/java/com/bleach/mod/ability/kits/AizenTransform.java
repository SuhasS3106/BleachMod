package com.bleach.mod.ability.kits;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Sōsuke Aizen's released states · Kyōka Suigetsu (Kanzen Saimin / Complete Hypnosis).
 */
public final class AizenTransform {
	private AizenTransform() {
	}

	public static final ResourceLocation SHIKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "aizen/shikai");
	public static final ResourceLocation BANKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "aizen/bankai");

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	// --- Shikai: Kyōka Suigetsu --------------------------------------------------------

	private static final class Shikai implements TransformAbility {
		@Override
		public ResourceLocation id() {
			return SHIKAI_ID;
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

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			AizenHypnosisManager.unleashShikai(player);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			AizenHypnosisManager.tickIllusions(player);
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			AizenHypnosisManager.endShikai(player);
		}
	}

	// --- Bankai: Kyōka Suigetsu (Transcendent) -----------------------------------------

	private static final class Bankai implements TransformAbility {
		@Override
		public ResourceLocation id() {
			return BANKAI_ID;
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

		@Override
		public double meleeDamageBonus() {
			return BleachTuning.AIZEN_BANKAI_DMG_BONUS;
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			AizenHypnosisManager.unleashShikai(player);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			AizenHypnosisManager.tickIllusions(player);
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			AizenHypnosisManager.endShikai(player);
		}
	}
}
