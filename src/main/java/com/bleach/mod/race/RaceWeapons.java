package com.bleach.mod.race;

import java.util.function.Function;

import com.bleach.mod.item.HeiligBogenItem;
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

	/** The item constructor for a race's spirit weapon. */
	public static Function<ResourceLocation, Item> factoryFor(Race race) {
		if (race.id() == Races.QUINCY.id()) {
			return HeiligBogenItem::new;
		}
		return ZanpakutoItem::new;
	}
}
