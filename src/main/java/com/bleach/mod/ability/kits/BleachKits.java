package com.bleach.mod.ability.kits;

import java.util.List;
import java.util.Map;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.race.Race;
import com.bleach.mod.race.Races;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;

/**
 * The five playable characters · PRD §3.3 · {@code BALANCE.md} §I.
 *
 * <p>Registration order is menu order — {@link AbilityRegistry} keeps a {@link java.util.LinkedHashMap}
 * for exactly this, and {@code ZanpakutoSelectMenu} lays the slots out by iterating it.
 *
 * <p>The ids are constants rather than locals because {@code BleachItems} needs them at item
 * registration time, which happens before any {@link Kit} exists. That indirection is deliberate:
 * an item knows only which kit it grants, never the kit object, so the two can be built in either
 * order and a kit can be replaced without touching its sword.
 */
public final class BleachKits {
	private BleachKits() {
	}

	public static final ResourceLocation ICHIGO = BleachMod.id("ichigo");
	public static final ResourceLocation YAMAMOTO = BleachMod.id("yamamoto");
	public static final ResourceLocation SUIFENG = BleachMod.id("suifeng");
	public static final ResourceLocation RUKIA = BleachMod.id("rukia");
	public static final ResourceLocation SHINJI = BleachMod.id("shinji");
	public static final ResourceLocation AIZEN = BleachMod.id("aizen");
	public static final ResourceLocation TOSEN = BleachMod.id("tosen");
	public static final ResourceLocation GIN = BleachMod.id("gin");

	/** Every kit id, in menu order. Read by {@code BleachItems} to mint one sword per kit. */
	public static final List<ResourceLocation> IDS = List.of(
			ICHIGO, YAMAMOTO, SUIFENG, RUKIA, SHINJI, AIZEN, TOSEN, GIN);

	/**
	 * Which race each kit belongs to. Read by {@code BleachItems} before any Kit object exists.
	 *
	 * <p><b>Consistency risk:</b> this and the {@code Races.X} argument passed to each
	 * {@code new Kit(...)} below are two statements of the same fact and can drift. {@code /bleach
	 * test race} (Task 16) asserts they agree for every kit; this map is not unified with
	 * {@link Kit#race()} here.
	 */
	private static final Map<ResourceLocation, Race> RACE_OF = Map.of(
			ICHIGO, Races.SHINIGAMI, YAMAMOTO, Races.SHINIGAMI, SUIFENG, Races.SHINIGAMI,
			RUKIA, Races.SHINIGAMI, SHINJI, Races.SHINIGAMI, AIZEN, Races.SHINIGAMI,
			TOSEN, Races.SHINIGAMI, GIN, Races.SHINIGAMI);

	/** Never null — an unlisted kit is treated as Shinigami, which is the pre-race behaviour. */
	public static Race raceOf(ResourceLocation kitId) {
		return RACE_OF.getOrDefault(kitId, Races.SHINIGAMI);
	}

	/**
	 * Called once from {@code AbilityRegistry#registerDefaults}. Registers all eight playable kits
	 * with their respective Shikai and Bankai transformations.
	 */
	public static void register() {
		AbilityRegistry.registerKit(new Kit(ICHIGO, "Ichigo Kurosaki",
				IchigoTransform.shikai(), IchigoTransform.bankai(),
				BleachTuning.KIT_ICHIGO_FS_RANGE_MULT, BleachTuning.KIT_ICHIGO_FS_COOLDOWN_MULT,
				BleachTuning.KIT_ICHIGO_PARTICLE_COLOR, Races.SHINIGAMI));

		AbilityRegistry.registerKit(new Kit(YAMAMOTO, "Genryūsai Yamamoto",
				YamamotoTransform.shikai(), YamamotoTransform.bankai(),
				BleachTuning.KIT_YAMAMOTO_FS_RANGE_MULT, BleachTuning.KIT_YAMAMOTO_FS_COOLDOWN_MULT,
				BleachTuning.KIT_YAMAMOTO_PARTICLE_COLOR, Races.SHINIGAMI));

		AbilityRegistry.registerKit(new Kit(SUIFENG, "Suì-Fēng",
				SuiFengTransform.shikai(), SuiFengTransform.bankai(),
				BleachTuning.KIT_SUIFENG_FS_RANGE_MULT, BleachTuning.KIT_SUIFENG_FS_COOLDOWN_MULT,
				BleachTuning.KIT_SUIFENG_PARTICLE_COLOR, Races.SHINIGAMI));

		AbilityRegistry.registerKit(new Kit(RUKIA, "Rukia Kuchiki",
				RukiaTransform.shikai(), RukiaTransform.bankai(),
				BleachTuning.KIT_RUKIA_FS_RANGE_MULT, BleachTuning.KIT_RUKIA_FS_COOLDOWN_MULT,
				BleachTuning.KIT_RUKIA_PARTICLE_COLOR, Races.SHINIGAMI));

		AbilityRegistry.registerKit(new Kit(SHINJI, "Shinji Hirako",
				ShinjiTransform.shikai(), ShinjiTransform.bankai(),
				BleachTuning.KIT_SHINJI_FS_RANGE_MULT, BleachTuning.KIT_SHINJI_FS_COOLDOWN_MULT,
				BleachTuning.KIT_SHINJI_PARTICLE_COLOR, Races.SHINIGAMI));

		AbilityRegistry.registerKit(new Kit(AIZEN, "Sōsuke Aizen",
				AizenTransform.shikai(), AizenTransform.bankai(),
				BleachTuning.KIT_AIZEN_FS_RANGE_MULT, BleachTuning.KIT_AIZEN_FS_COOLDOWN_MULT,
				BleachTuning.KIT_AIZEN_PARTICLE_COLOR, Races.SHINIGAMI));

		AbilityRegistry.registerKit(new Kit(TOSEN, "Kaname Tōsen",
				TosenTransform.shikai(), TosenTransform.bankai(),
				BleachTuning.KIT_TOSEN_FS_RANGE_MULT, BleachTuning.KIT_TOSEN_FS_COOLDOWN_MULT,
				BleachTuning.KIT_TOSEN_PARTICLE_COLOR, Races.SHINIGAMI));

		AbilityRegistry.registerKit(new Kit(GIN, "Gin Ichimaru",
				GinTransform.shikai(), GinTransform.bankai(),
				BleachTuning.KIT_GIN_FS_RANGE_MULT, BleachTuning.KIT_GIN_FS_COOLDOWN_MULT,
				BleachTuning.KIT_GIN_PARTICLE_COLOR, Races.SHINIGAMI));
	}
}
