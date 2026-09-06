package com.bleach.mod.client;

import com.bleach.mod.BleachMod;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.item.BleachComponents;
import com.bleach.mod.item.BleachItems;
import com.bleach.mod.item.ZanpakutoItem;

import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;

/**
 * The {@code bleach_mod:released} model predicate · one number, read off the blade, that lets a
 * zanpakutō model change with the state of the person holding it.
 *
 * <p>Every blade shares the Asauchi's shape in the base state — the released forms are the whole of
 * a character's visual identity, and handing it out before the release throws the reveal away. A
 * model that wants to differ adds an {@code overrides} entry; one that does not needs no file
 * changes at all, which is why nine of the ten model files are a single {@code parent} line.
 *
 * <h2>The 0 / 0.5 / 1 encoding</h2>
 *
 * <p>Predicate values look like they should be the raw state — 0, 1, 2 — and cannot be.
 * {@code ClampedItemPropertyFunction} clamps whatever is returned into {@code [0, 1]} before the
 * model ever sees it, so a Shikai and a Bankai both reporting {@code >= 1} would be indistinguishable
 * and every override would fire on both. Dividing by the number of released states keeps them
 * separate inside the range the clamp allows.
 *
 * <p>The Fabric registry below compiles with a deprecation warning and there is nothing to be done
 * about it on this version: vanilla's own {@code ItemProperties.register} is {@code private} in
 * 1.21.1, so an accessor is the only way in. The deprecation points at 1.21.4's replacement of the
 * whole overrides system, which is a port, not a fix.
 */
public final class ZanpakutoModels {
	private ZanpakutoModels() {
	}

	/** {@link SpiritualData#STATE_BANKAI}, the highest state — so the divisor that maps it to 1.0. */
	private static final float STATES = SpiritualData.STATE_BANKAI;

	public static void register() {
		for (ZanpakutoItem blade : BleachItems.zanpakuto()) {
			FabricModelPredicateProviderRegistry.register(blade, BleachMod.id("released"),
					(stack, level, entity, seed) ->
							stack.getOrDefault(BleachComponents.RELEASED, 0) / STATES);
		}
	}
}
