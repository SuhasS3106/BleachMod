package com.bleach.mod;

import com.bleach.mod.ability.AbilityCooldowns;
import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.MeleeHooks;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.command.BleachCommands;
import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.entity.BleachEntities;
import com.bleach.mod.item.BleachItems;
import com.bleach.mod.particle.BleachParticles;
import com.bleach.mod.item.SpiritWeapon;
import com.bleach.mod.network.BleachNetworking;
import com.bleach.mod.progression.SoulLevel;
import com.bleach.mod.progression.SoulLevelCurve;
import com.bleach.mod.progression.SpxTable;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BleachMod implements ModInitializer {
	public static final String MOD_ID = "bleach_mod";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Tuning first, always. Everything downstream reads it, and the reload hooks must be
		// registered before the initial load so they fire for it too.
		BleachTuning.onReload(SpxTable::rebuild);
		BleachTuning.onReload(SoulLevelCurve::rebuild);
		BleachTuning.load();

		// After tuning (nothing here reads it yet, but every registration call in this method does)
		// and before the items, which is a convenient anchor point rather than a hard requirement.
		BleachEntities.register();

		// Items before kits: ZanpakutoItem bakes BALANCE.md §I.1 into its default components at
		// construction, so this call has to sit after BleachTuning.load() and cannot be a static
		// initialiser somewhere.
		// Before the items, because a blade's model predicate is worthless if the component it reads
		// is not in the registry by the time a stack carrying it is decoded.
		com.bleach.mod.item.BleachComponents.register();
		BleachItems.register();

		// Same ordering rule as the items above: ReiatsuEffect takes its icon colour in the
		// constructor, so it has to be built after the tuning file has been read.
		BleachEffects.register();
		BleachParticles.register();

		BleachAttachments.register();
		BleachNetworking.register();
		ServerFlightFix.register();
		AbilityCooldowns.register();
		AbilityRegistry.registerDefaults();
		MeleeHooks.register();
		com.bleach.mod.ability.kits.MiracleTransform.register();
		SpiritWeapon.register();
		SoulLevel.register();
		ModToggle.register();
		SpiritualTicker.register();
		com.bleach.mod.ability.kits.AizenHypnosisManager.register();
		BleachCommands.register();

		LOGGER.info("Bleach mod initialized");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
