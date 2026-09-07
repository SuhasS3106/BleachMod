package com.bleach.mod.entity;

import com.bleach.mod.BleachMod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Every entity the mod adds. Called from {@code BleachMod#onInitialize}. */
public final class BleachEntities {
	private BleachEntities() {
	}

	public static EntityType<ReishiArrow> REISHI_ARROW;

	public static void register() {
		REISHI_ARROW = Registry.register(BuiltInRegistries.ENTITY_TYPE,
				BleachMod.id("reishi_arrow"),
				EntityType.Builder.<ReishiArrow>of(ReishiArrow::new, MobCategory.MISC)
						// Hitbox size, tracking range and update interval below mirror vanilla's own
						// EntityType.ARROW registration exactly (checked against the mapped jar) — they
						// are engine/networking parity for a fast projectile, not tunable balance, so
						// they stay literals rather than BleachTuning fields.
						.sized(0.5f, 0.5f)
						.clientTrackingRange(4)
						.updateInterval(20)
						.build("reishi_arrow"));
	}
}
