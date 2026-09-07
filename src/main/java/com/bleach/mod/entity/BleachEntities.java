package com.bleach.mod.entity;

import com.bleach.mod.BleachMod;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * Every entity the mod adds. Called from {@code BleachMod#onInitialize}, after
 * {@link BleachTuning#load()} so the values below reflect {@code config/bleach_mod/tuning.json}
 * on first launch — but not after: {@code EntityType.Builder} bakes these into the built
 * {@code EntityType} once, here, so unlike most of {@code BleachTuning} a later
 * {@code /bleach reload} cannot move them. See {@code BALANCE.md} §P.1.
 */
public final class BleachEntities {
	private BleachEntities() {
	}

	public static EntityType<ReishiArrow> REISHI_ARROW;

	public static void register() {
		REISHI_ARROW = Registry.register(BuiltInRegistries.ENTITY_TYPE,
				BleachMod.id("reishi_arrow"),
				EntityType.Builder.<ReishiArrow>of(ReishiArrow::new, MobCategory.MISC)
						.sized((float) BleachTuning.REISHI_ARROW_WIDTH, (float) BleachTuning.REISHI_ARROW_HEIGHT)
						.clientTrackingRange(BleachTuning.REISHI_ARROW_TRACKING_RANGE)
						.updateInterval(BleachTuning.REISHI_ARROW_UPDATE_INTERVAL)
						.build("reishi_arrow"));
	}
}
