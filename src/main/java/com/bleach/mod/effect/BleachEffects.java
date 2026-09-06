package com.bleach.mod.effect;

import com.bleach.mod.BleachMod;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;

/**
 * Every {@link MobEffect} the mod adds. One so far — Phase 9's Freeze and Phase 11's Sakanade land
 * here beside it.
 *
 * <p>Registered from {@code BleachMod#onInitialize}, <b>after</b> {@code BleachTuning.load()}:
 * {@link ReiatsuEffect} takes its icon colour in the constructor, which makes
 * {@code REIATSU_EFFECT_COLOR} the second constant after §I.1 that {@code /bleach reload} cannot
 * move. Every other Reiatsu value is read at apply time and does reload.
 */
public final class BleachEffects {
	private BleachEffects() {
	}

	/**
	 * PRD §5.1 · the mod's one debuff. Null until {@link #register()} runs — callers reachable from a
	 * mixin check for that rather than assuming, since a mixin can be loaded before mod init is done.
	 */
	public static Holder<MobEffect> REIATSU;
	public static Holder<MobEffect> FREEZE;
	public static Holder<MobEffect> SAKANADE;

	public static void register() {
		REIATSU = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
				BleachMod.id("reiatsu"), new ReiatsuEffect());
		FREEZE = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
				BleachMod.id("freeze"), new FreezeEffect());
		SAKANADE = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT,
				BleachMod.id("sakanade"), new SakanadeEffect());
	}
}
