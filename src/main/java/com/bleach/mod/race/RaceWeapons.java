package com.bleach.mod.race;

import java.util.function.Function;

import com.bleach.mod.item.HeiligBogenItem;
import com.bleach.mod.item.HoffnungItem;
import com.bleach.mod.item.ZanpakutoItem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * The one Minecraft-coupled half of the race seam: which item class a race's kits carry.
 *
 * <p>Separate from {@link Race} so that record stays free of Minecraft imports and unit testable.
 * This is the only place in the mod that maps a race to a concrete weapon class.
 */
public final class RaceWeapons {
	private RaceWeapons() {
	}

	/**
	 * The item constructor for a kit's spirit weapon.
	 *
	 * <p>Race decides it for everyone but Gerard. He is a swordsman on a race of archers — canon
	 * gives him Hoffnung the blade first and the Heilig Bogen second — so the mapping needed a
	 * per-kit escape hatch rather than a second race.
	 */
	public static Function<ResourceLocation, Item> factoryFor(Race race, ResourceLocation kitId) {
		if (carriesHoffnung(kitId)) {
			return HoffnungItem::new;
		}
		if (carriesBow(race)) {
			return HeiligBogenItem::new;
		}
		return ZanpakutoItem::new;
	}

	/**
	 * Whether this kit is the one exception to race-decides-the-weapon.
	 *
	 * <p>Split out from {@link #factoryFor} so the decision can be tested without constructing an
	 * {@link Item} — item construction touches {@code DataComponents}, which needs a bootstrapped
	 * game, which is why every test in this repo stops at pure statics.
	 */
	public static boolean carriesHoffnung(ResourceLocation kitId) {
		return MIRACLE.equals(kitId);
	}

	/** Whether this race's kits carry a bow rather than a blade. */
	public static boolean carriesBow(Race race) {
		return race.id() == Races.QUINCY.id();
	}

	/**
	 * Gerard's kit id, spelled out rather than imported from {@code BleachKits}.
	 *
	 * <p>{@code BleachKits} builds Kit objects, which build transformations, which read this class.
	 * Importing it back here closes that loop at class-initialisation time.
	 */
	private static final ResourceLocation MIRACLE =
			ResourceLocation.fromNamespaceAndPath("bleach_mod", "miracle");
}
