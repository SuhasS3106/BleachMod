package com.bleach.mod.item;

import java.util.LinkedHashMap;
import java.util.Map;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.kits.BleachKits;
import org.jetbrains.annotations.Nullable;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Every item the mod adds: the two Asauchi and one zanpakutō per kit.
 *
 * <p>Registered from {@code BleachMod#onInitialize}, <b>after</b> {@code BleachTuning.load()} —
 * {@link ZanpakutoItem} bakes its attack attributes into the item's default components, and those
 * are read exactly once, here. That makes §I.1 the only tuning group {@code /bleach reload} cannot
 * move; it is called out in {@code BALANCE.md} for the same reason.
 *
 * <p>The kit swords are minted from {@link BleachKits#IDS} rather than from registered
 * {@link com.bleach.mod.ability.Kit}s, because items must exist before kits do. Adding a sixth
 * character stays a one-line change in two files rather than one.
 */
public final class BleachItems {
	private BleachItems() {
	}

	private static final ResourceKey<CreativeModeTab> COMBAT_TAB =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.withDefaultNamespace("combat"));

	public static final AsauchiItem ASAUCHI = new AsauchiItem();
	public static final ReforgedAsauchiItem REFORGED_ASAUCHI = new ReforgedAsauchiItem();

	/** One blade per kit, in {@link BleachKits#IDS} order. */
	private static final Map<ResourceLocation, ZanpakutoItem> ZANPAKUTO = new LinkedHashMap<>();

	public static void register() {
		register("asauchi", ASAUCHI);
		register("reforged_asauchi", REFORGED_ASAUCHI);

		for (ResourceLocation kitId : BleachKits.IDS) {
			ZanpakutoItem blade = new ZanpakutoItem(kitId);
			ZANPAKUTO.put(kitId, blade);
			register("zanpakuto_" + kitId.getPath(), blade);
		}

		// Combat rather than a tab of our own: seven items do not fill a tab, and an operator looking
		// for a sword looks where the swords are.
		ItemGroupEvents.modifyEntriesEvent(COMBAT_TAB).register(entries -> {
			entries.accept(ASAUCHI);
			entries.accept(REFORGED_ASAUCHI);
			for (ZanpakutoItem blade : ZANPAKUTO.values()) {
				entries.accept(blade);
			}
		});
	}

	private static void register(String path, Item item) {
		Registry.register(BuiltInRegistries.ITEM, BleachMod.id(path), item);
	}

	/** Every registered blade. Used client-side to hang the released-state model predicate on each. */
	public static Iterable<ZanpakutoItem> zanpakuto() {
		return ZANPAKUTO.values();
	}

	/** The blade for a kit id, or null if that kit has none. */
	@Nullable
	public static ZanpakutoItem zanpakutoFor(ResourceLocation kitId) {
		return ZANPAKUTO.get(kitId);
	}

	/** Either flavour of Asauchi — the tokens that open the picker. */
	public static boolean isSelector(ItemStack stack) {
		return stack.getItem() instanceof AsauchiItem;
	}
}
