package com.bleach.mod.ability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.common.AuraSense;
import com.bleach.mod.ability.common.FlashStep;
import com.bleach.mod.ability.common.Hover;
import com.bleach.mod.ability.common.SpiritualFlex;
import com.bleach.mod.ability.kits.BleachKits;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.race.Race;
import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

/**
 * The Java registry behind PRD §3.3. Holds both the universal abilities (Flash Step, Flex — every
 * player has them regardless of kit) and the five kits.
 *
 * <p>The plan's file list names only {@code AbilityRegistry} while the PRD refers to a
 * {@code KitRegistry.register}; both live here rather than in two eight-line classes, since a kit
 * is nothing but a bundle of abilities and the two are always registered in the same breath.
 *
 * <p>Registration happens once at mod init from a single call site and is never mutated
 * afterwards, so the maps need no synchronisation.
 */
public final class AbilityRegistry {
	private AbilityRegistry() {
	}

	// --- Universal ability ids ---------------------------------------------------------
	// Not kit-owned: every player has these from Soul Level 1, drawn or sheathed.

	public static final ResourceLocation FLASH_STEP = BleachMod.id("flash_step");
	public static final ResourceLocation SPIRITUAL_FLEX = BleachMod.id("spiritual_flex");
	public static final ResourceLocation AURA_SENSE = BleachMod.id("aura_sense");
	public static final ResourceLocation HOVER = BleachMod.id("hover");

	private static final Map<ResourceLocation, Ability> ABILITIES = new LinkedHashMap<>();
	private static final Map<String, Kit> KITS = new LinkedHashMap<>();

	/**
	 * The abilities every player has regardless of kit. Called once from
	 * {@code BleachMod#onInitialize}; the kits register themselves from Phase 4 onward.
	 */
	public static void registerDefaults() {
		register(new FlashStep());
		register(new SpiritualFlex());
		register(new AuraSense());
		register(new Hover());
		BleachKits.register();
	}

	/** Register an ability. Returns its argument so registration can be a field initialiser. */
	public static <A extends Ability> A register(A ability) {
		Ability previous = ABILITIES.putIfAbsent(ability.id(), ability);
		if (previous != null) {
			throw new IllegalStateException("Duplicate ability id " + ability.id());
		}
		return ability;
	}

	/**
	 * Register a kit and both of its released states. Throws on a missing Shikai or Bankai — see
	 * {@link Kit} and PRD §3.3.
	 */
	public static Kit registerKit(Kit kit) {
		Kit previous = KITS.putIfAbsent(kit.storageId(), kit);
		if (previous != null) {
			throw new IllegalStateException("Duplicate kit id " + kit.id());
		}
		register(kit.shikai());
		register(kit.bankai());
		return kit;
	}

	@Nullable
	public static Ability get(ResourceLocation id) {
		return ABILITIES.get(id);
	}

	/** The kit a player has committed to, or null if they still carry an unused Asauchi. */
	@Nullable
	public static Kit kitFor(SpiritualData data) {
		return data.characterId == null ? null : KITS.get(data.characterId);
	}

	@Nullable
	public static Kit kit(String storageId) {
		return KITS.get(storageId);
	}

	/** Menu order is registration order — {@link LinkedHashMap} is load-bearing for the Asauchi UI. */
	public static Collection<Kit> kits() {
		return Collections.unmodifiableCollection(KITS.values());
	}

	/** The kits belonging to one race, in registration order. Drives the second picker screen. */
	public static List<Kit> kitsFor(Race race) {
		List<Kit> matching = new ArrayList<>();
		for (Kit kit : KITS.values()) {
			if (kit.race().id() == race.id()) {
				matching.add(kit);
			}
		}
		return matching;
	}
}
