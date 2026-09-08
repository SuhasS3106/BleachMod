package com.bleach.mod.tuning;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bleach.mod.BleachMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/**
 * Every tunable number in the mod, one field per row of {@code BALANCE.md}, grouped by that
 * file's section letters.
 *
 * <p><b>Standing rule:</b> no numeric literal appears in game logic. If a number matters to
 * balance it lives here, it appears in {@code BALANCE.md}, and the two change in the same commit.
 *
 * <p>Fields are deliberately non-final: {@link #load()} overlays {@code config/bleach_mod/tuning.json}
 * over these defaults at startup, and {@code /bleach reload} re-reads it without a restart.
 *
 * <p>Deviation from {@code BALANCE.md} rule 1, noted deliberately: values that are counts, tick
 * durations, packed RGB colours or flags are declared {@code int}/{@code boolean} rather than
 * {@code double}. They are still one-field-per-row, still config-overridable, and still the only
 * place the number exists — but declaring a tick count as a double would mean a cast at every
 * call site, which is exactly the kind of noise the rule exists to prevent.
 */
public final class BleachTuning {
	private BleachTuning() {
	}

	/** Engine constant, not a balance value — Minecraft's fixed tick rate. */
	public static final double TICKS_PER_SECOND = 20.0;

	/** Engine constant, not a balance value — the length of a Minecraft day in ticks. */
	public static final long TICKS_PER_MC_DAY = 24000L;

	// ================================================================================
	// A. Spiritual Pressure — pool and regen · PRD §1.1–1.2 · BALANCE.md §A
	// ================================================================================

	/** Max SP at SL 1. */
	public static double SP_BASE_MAX = 100.0;
	/** Added max SP per level above 1. */
	public static double SP_MAX_PER_LEVEL = 10.0;
	/** Regen at SL 1, as a fraction of max per second. */
	public static double SP_REGEN_BASE_PCT = 0.020;
	/** Regen growth per level, as a fraction of max per second. */
	public static double SP_REGEN_PCT_PER_LEVEL = 0.0011;
	/** Regen freeze after any spend or damage, in ticks. */
	public static int SP_REGEN_PAUSE_TICKS = 60;

	// ================================================================================
	// B. Exertion · PRD §1.3 · BALANCE.md §B
	// ================================================================================

	/** Exertion accrued per second while in Bankai. */
	public static double EXERTION_RATE_BANKAI = 1.00;
	/** Exertion accrued per second while in Shikai. */
	public static double EXERTION_RATE_SHIKAI = 0.35;
	/** Penalty coefficient at SL 1. */
	public static double EXERTION_K_BASE = 0.060;
	/** Coefficient reduction per level. */
	public static double EXERTION_K_PER_LEVEL = 0.0024;
	/** Hard floor on the regen multiplier. */
	public static double EXERTION_MULT_FLOOR = 0.20;

	// ================================================================================
	// C. Transformation gates and drains · PRD §1.4–1.5 · BALANCE.md §C
	// ================================================================================

	/** Bankai entry threshold at SL 1, as a fraction of max. */
	public static double GATE_BANKAI_BASE = 0.95;
	/** Shikai entry threshold at SL 1, as a fraction of max. */
	public static double GATE_SHIKAI_BASE = 0.65;
	/** Threshold reduction per level, applied to both gates. */
	public static double GATE_REDUCTION_PER_LEVEL = 0.015;
	/** Bankai drain, SP per second. */
	public static double DRAIN_BANKAI = 5.0;
	/** Shikai drain, SP per second. */
	public static double DRAIN_SHIKAI = 1.5;

	// ================================================================================
	// D. Soul Points · PRD §2.1–2.3 · BALANCE.md §D
	// ================================================================================

	/** Base daily SPX cap before scalars. */
	public static int SPX_DAILY_CAP_BASE = 200;
	/** Catch-up bonus per level below WSL. */
	public static double CATCHUP_PER_LEVEL_GAP = 0.15;
	/** Catch-up multiplier ceiling. */
	public static double CATCHUP_MAX = 3.0;
	/** World scalar growth per WSL point. */
	public static double WORLD_SCALAR_PER_LEVEL = 0.08;
	/** Base SPX value of a player kill. */
	public static int SPX_PLAYER_BASE = 25;
	/** Bonus SPX per level the victim is above you. */
	public static int SPX_PLAYER_PER_LEVEL_GAP = 5;
	/** Level curve coefficient. */
	public static double SPX_CURVE_COEFF = 12.0;
	/** Level curve exponent. */
	public static double SPX_CURVE_EXPONENT = 1.6;
	/** Level cap. */
	public static int SL_MAX = 20;
	/** SPX awarded for an entity type absent from {@link #MOB_SPX}. */
	public static int SPX_DEFAULT_MOB = 1;

	// ================================================================================
	// E. Soul Level scaling · PRD §2.4 · BALANCE.md §E
	// ================================================================================

	/** Bleach damage dealt bonus, fraction per level. */
	public static double SL_BLEACH_DMG_DEALT_PER_LEVEL = 0.020;
	/** Bleach damage taken reduction, fraction per level. */
	public static double SL_BLEACH_DMG_TAKEN_PER_LEVEL = 0.015;
	/** All-source damage taken reduction, fraction per level. */
	public static double SL_GENERAL_DMG_TAKEN_PER_LEVEL = 0.010;
	/** Bonus max health per two levels. */
	public static double SL_HP_PER_TWO_LEVELS = 1.0;
	/** Level-up sound volume. */
	public static double SL_LEVELUP_SOUND_VOLUME = 0.7;
	/** Level-up sound pitch. Below 1.0 — deeper than the vanilla XP chime it borrows. */
	public static double SL_LEVELUP_SOUND_PITCH = 0.6;

	// ================================================================================
	// F. World scaling of mobs · PRD §2.5 · BALANCE.md §F
	// ================================================================================

	/** Mob outgoing damage growth per effective level. */
	public static double MOB_SCALE_PER_LEVEL = 0.08;
	/** Max levels a mob may out-scale its victim. Load-bearing; see PRD §2.5. */
	public static int MOB_SCALE_LEVEL_HEADROOM = 5;
	/** Also scale mob max health. Off by default. */
	public static boolean MOB_SCALE_HEALTH = false;

	// ================================================================================
	// G. Flash Step · PRD §4 · BALANCE.md §G
	// ================================================================================

	/** Range floor, blocks. */
	public static double FS_RANGE_BASE = 10.0;
	/** Range contribution per Soul Level, blocks. */
	public static double FS_RANGE_PER_LEVEL = 0.55;
	/** Range contribution at 100% SP, blocks. */
	public static double FS_RANGE_SP_TERM = 20.0;
	/** SP cost, fraction of max. */
	public static double FS_COST_PCT = 0.08;
	/** Base cooldown, ticks. */
	public static int FS_COOLDOWN_TICKS = 8;
	/** How far below the arrival box Flash Step probes for ground, blocks. */
	public static double FS_GROUND_PROBE = 0.2;
	/** Sky branch teleports like the ground branch instead of applying an impulse. */
	public static boolean FS_SKY_TELEPORT = true;
	/** Momentum kept along the look vector after a sky teleport, blocks per tick. */
	public static double FS_SKY_CARRY_MOMENTUM = 0.35;
	/** Sky-branch range as a fraction of ground range. */
	public static double FS_SKY_RANGE_PCT = 1.0;
	/** Distance held back from the hit point, blocks. */
	public static double FS_STOP_SHORT = 0.5;
	/** Step size when walking a blocked destination back toward the eye, blocks. */
	public static double FS_CLEARANCE_STEP = 0.25;
	/** Sky branch: Levitation I duration, ticks. */
	public static int FS_SKY_LEVITATION_TICKS = 0;
	/** Sky branch: Slow Falling duration, ticks. */
	public static int FS_SKY_SLOW_FALLING_TICKS = 40;

	/**
	 * Whether a Flash Step cancels fall damage until the player next touches the ground.
	 *
	 * <p>A Shinigami crossing thirty blocks of open air is moving, not falling — and without this the
	 * sky step is a trap: it costs SP to use and then bills the player again on landing.
	 */
	public static boolean FS_NO_FALL_DAMAGE = true;
	/** Sky branch: initial velocity per block of sky range, blocks per tick. */
	public static double FS_SKY_IMPULSE_PER_BLOCK = 0.05;
	/** Ticks the arrival burst is held back so it never precedes the player · see FlashStep. */
	public static int FS_ARRIVAL_BURST_DELAY_TICKS = 2;
	/** Particles spawned at each endpoint. */
	public static int FS_PARTICLE_COUNT = 45;
	/** Particle scatter around each endpoint, blocks. */
	public static double FS_PARTICLE_SPREAD = 1.1;
	/** Particle scale. */
	public static double FS_PARTICLE_SCALE = 1.2;
	/** Teleport sound volume. */
	public static double FS_SOUND_VOLUME = 0.8;
	/** Teleport sound pitch. Above 1.0 — the blink is lighter than an enderman's. */
	public static double FS_SOUND_PITCH = 1.4;

	// ================================================================================
	// H. Spiritual Flex · PRD §5 · BALANCE.md §H
	// ================================================================================

	/** Radius floor, blocks. */
	public static double FLEX_RADIUS_BASE = 8.0;
	/** Radius growth per level, blocks. */
	public static double FLEX_RADIUS_PER_LEVEL = 0.40;
	/** Flexer drain at SL 1, SP per second. */
	public static double FLEX_DRAIN_BASE = 3.0;
	/** Flexer drain reduction per level. */
	public static double FLEX_DRAIN_PER_LEVEL = 0.10;
	/** Counterer drain at SL 1, SP per second. */
	public static double FLEX_COUNTER_DRAIN_BASE = 4.0;
	/** Counterer drain reduction per level. */
	public static double FLEX_COUNTER_DRAIN_PER_LEVEL = 0.13;
	/** Gap reduction floor when countering, levels. */
	public static int FLEX_COUNTER_GAP_BASE = 2;
	/** {@code floor(SL / divisor)} is added to the counter gap reduction. */
	public static int FLEX_COUNTER_GAP_DIVISOR = 4;
	/** Reiatsu refresh duration, ticks. */
	public static int FLEX_EFFECT_DURATION_TICKS = 60;

	/**
	 * Minimum level gap for a flex to register on a target at all. {@code 0} means a peer feels the
	 * field; raise to 1 to restore the old "strictly above you" rule.
	 *
	 * <p>A negative value would let a weaker player pressure someone above them, which inverts the
	 * whole mechanic — the gap is what buys the tier.
	 */
	public static int FLEX_BASELINE_MIN_GAP = 0;

	/**
	 * Duration of the baseline pressure applied when the level gap buys no tier of its own, in ticks.
	 *
	 * <p>Deliberately far shorter than {@link #FLEX_EFFECT_DURATION_TICKS}. The baseline is the
	 * weight of standing in a peer's reiatsu, not a debuff you carry away from it: it is refreshed
	 * every tick while inside the field and gone within a breath of leaving, so it can be answered
	 * by stepping back as well as by pushing back. Set to 0 to disable the baseline entirely.
	 */
	public static int FLEX_BASELINE_DURATION_TICKS = 15;

	// --- H.3 Flex presentation -------------------------------------------------------

	/** Ticks between particle rings. Above 1 to keep a held channel from flooding the client. */
	public static int FLEX_PARTICLE_INTERVAL_TICKS = 2;
	/** Particles in a ring before the spend term. */
	public static int FLEX_PARTICLE_BASE = 10;
	/** Extra particles per SP/s the flex is costing · PRD §5.3 "density scales with SP spent". */
	public static double FLEX_PARTICLE_PER_SP = 3.0;
	/** Upward velocity of a pressure particle, blocks per tick. */
	public static double FLEX_PARTICLE_RISE = 1.20;
	/** Random in/out scatter on the ring, blocks. */
	public static double FLEX_PARTICLE_RING_JITTER = 0.35;
	/** How often the flexer's action bar reports what the field is hitting, ticks. */
	public static int FLEX_FEEDBACK_TICKS = 20;
	/** Ring particles drawn per block of field radius. */
	public static double FLEX_RING_POINTS_PER_BLOCK = 3.5;
	/** Height of the ring above the flexer's feet, blocks. */
	public static double FLEX_RING_Y_OFFSET = 0.2;
	/** Pressure particle scale. */
	public static double FLEX_PARTICLE_SCALE = 0.6;

	// --- H.1 Reiatsu tier thresholds -------------------------------------------------

	/** Level gap at which Reiatsu amplifier 0 applies. */
	public static int REIATSU_TIER_1_GAP = 1;
	/** Level gap at which Reiatsu amplifier 1 applies. */
	public static int REIATSU_TIER_2_GAP = 4;
	/** Level gap at which Reiatsu amplifier 2 applies. */
	public static int REIATSU_TIER_3_GAP = 8;
	/** Level gap at which Reiatsu amplifier 3 applies. */
	public static int REIATSU_TIER_4_GAP = 13;

	// --- H.2 Reiatsu effect strength per amplifier -----------------------------------

	/** {@code MOVEMENT_SPEED} multiplier by amplifier. −1.00 at amp 3 is the rooted tier. */
	public static double[] REIATSU_SPEED_MULT = { -0.15, -0.30, -0.45, -1.00 };
	/** {@code ATTACK_SPEED} multiplier by amplifier. */
	public static double[] REIATSU_ATK_SPEED_MULT = { 0.0, -0.20, -0.35, -0.35 };
	/** {@code ATTACK_DAMAGE} multiplier by amplifier. */
	public static double[] REIATSU_ATK_DMG_MULT = { 0.0, 0.0, -0.25, -0.40 };
	/**
	 * Amplifier of the vanilla Nausea applied alongside the crushing tier. Nausea has no attribute
	 * equivalent, so it is the one vanilla effect Reiatsu reaches for — hidden, but see PRD §11.2.
	 */
	public static int REIATSU_NAUSEA_AMPLIFIER = 0;
	/**
	 * Effect colour (packed RGB), shown on the inventory icon.
	 *
	 * <p>Read <b>once</b>, when {@code BleachEffects} constructs the effect at registration — a
	 * {@code MobEffect} takes its colour in the constructor. Like §I.1, this one needs a restart
	 * rather than a {@code /bleach reload}.
	 */
	public static int REIATSU_EFFECT_COLOR = 0x7C3AED;

	// --- H.4 Silence · BALANCE.md §H.4 ------------------------------------------------

	/**
	 * Whether a mob under Reiatsu loses every special action it has — arrows, potions, fireballs,
	 * wind charges, fangs, the creeper's fuse, the enderman's blink, the warden's shout.
	 *
	 * <p>A mob at Soul Level 0 is not a peer under pressure that can flatten a hillside; it is a
	 * thing that should be struggling to stand. Melee is deliberately still allowed — a silenced mob
	 * is helpless at range and dangerous in your face, which is a fight rather than an execution.
	 */
	public static boolean REIATSU_SILENCE_MOBS = true;

	/**
	 * Lowest Reiatsu amplifier that silences. {@code 0} is any tier at all — the default, and what
	 * makes a field the answer to a firing line. Raise it to make the silence a reward for outranking
	 * something by more than a level.
	 */
	public static int REIATSU_SILENCE_MIN_AMP = 0;

	/**
	 * Whether the silence also applies to players.
	 *
	 * <p><b>Off.</b> A player under a field is already slowed, weakened and slow to swing by §H.2,
	 * and taking their bow away on top of that turns any level gap into a lockout with no counterplay
	 * — while pushing back, the thing PvP counterplay <em>is</em>, would still leave them disarmed.
	 * Mobs have no such answer and are not owed one.
	 *
	 * <p><b>Turning it on breaks Sui-Feng's Bankai.</b> Her wind-up roots the caster by applying
	 * Reiatsu to <em>herself</em> ({@code SuiFengTransform}), so a silence that reaches players would
	 * refuse the missile she just paid half her health for. Anyone flipping this has to deal with
	 * that first.
	 */
	public static boolean REIATSU_SILENCE_PLAYERS = false;

	// --- H.5 Pressure damage · BALANCE.md §H.5 ----------------------------------------

	/** Ticks between damage ticks while a target stands in a field. 20 is once a second. */
	public static int REIATSU_DAMAGE_INTERVAL_TICKS = 20;
	/** Level gap below which standing in a field costs no health at all. */
	public static int REIATSU_DAMAGE_MIN_GAP = 1;
	/** Damage at exactly {@link #REIATSU_DAMAGE_MIN_GAP}, before the per-level term. */
	public static double REIATSU_DAMAGE_BASE = 0.5;
	/** Damage added per level of gap beyond the minimum. */
	public static double REIATSU_DAMAGE_PER_GAP = 0.5;
	/** Ceiling on one damage tick, whatever the gap. */
	public static double REIATSU_DAMAGE_MAX = 8.0;

	/**
	 * Whether the bleed spares anything that is not hostile or a player.
	 *
	 * <p><b>Off by default:</b> pressure is pressure, and a field that politely stepped around the
	 * livestock would be a strange kind of crushing weight. Worth knowing what that means before
	 * flexing at home, though — cows, horses, villagers and a tamed wolf are all inside the radius
	 * and all outranked, so a held field is a slow cull of everything you own. Set this true to
	 * limit the bleed to hostiles and players; the tier effects still land on everything either way.
	 */
	public static boolean REIATSU_DAMAGE_HOSTILE_ONLY = false;

	// ================================================================================
	// I. Kit multipliers · PRD §6 · BALANCE.md §I
	// ================================================================================

	public static double KIT_ICHIGO_FS_RANGE_MULT = 1.0;
	public static double KIT_ICHIGO_FS_COOLDOWN_MULT = 1.0;
	public static int KIT_ICHIGO_PARTICLE_COLOR = 0x1E3A8A;

	public static double KIT_YAMAMOTO_FS_RANGE_MULT = 1.0;
	public static double KIT_YAMAMOTO_FS_COOLDOWN_MULT = 1.0;
	public static int KIT_YAMAMOTO_PARTICLE_COLOR = 0xB91C1C;

	public static double KIT_SUIFENG_FS_RANGE_MULT = 1.5;
	public static double KIT_SUIFENG_FS_COOLDOWN_MULT = 0.5;
	public static int KIT_SUIFENG_PARTICLE_COLOR = 0xFACC15;

	public static double KIT_RUKIA_FS_RANGE_MULT = 1.0;
	public static double KIT_RUKIA_FS_COOLDOWN_MULT = 1.0;
	public static int KIT_RUKIA_PARTICLE_COLOR = 0xBFDBFE;

	public static double KIT_SHINJI_FS_RANGE_MULT = 1.0;
	public static double KIT_SHINJI_FS_COOLDOWN_MULT = 1.0;
	public static int KIT_SHINJI_PARTICLE_COLOR = 0x7E22CE;

	public static double KIT_AIZEN_FS_RANGE_MULT = 1.0;
	public static double KIT_AIZEN_FS_COOLDOWN_MULT = 1.0;
	public static int KIT_AIZEN_PARTICLE_COLOR = 0x6366F1;

	public static double KIT_TOSEN_FS_RANGE_MULT = 1.0;
	public static double KIT_TOSEN_FS_COOLDOWN_MULT = 1.0;
	public static int KIT_TOSEN_PARTICLE_COLOR = 0x8B5CF6;

	public static double KIT_GIN_FS_RANGE_MULT = 1.2;
	public static double KIT_GIN_FS_COOLDOWN_MULT = 0.8;
	public static int KIT_GIN_PARTICLE_COLOR = 0xE2E8F0;

	// --- I.1 The zanpakutō item ------------------------------------------------------
	//
	// Read once, when BleachItems builds the Item.Properties at registration. Item attribute
	// modifiers are baked into the item's default component map, so unlike every other constant
	// here these two do NOT respond to /bleach reload — they need a restart.

	/** Attack damage added on top of the tier bonus. 3 over {@code Tiers.IRON} is an iron sword. */
	public static int ZANPAKUTO_ATTACK_DAMAGE = 3;
	/** Attack speed modifier. −2.4 is the vanilla sword swing rate. */
	public static double ZANPAKUTO_ATTACK_SPEED = -2.4;

	// ================================================================================
	// J.1 Ichigo · PRD §6.1 · BALANCE.md §J.1
	// ================================================================================

	/** {@code ENTITY_INTERACTION_RANGE} add-value — the big cleaver. */
	public static double ICHIGO_SHIKAI_REACH = 1.5;
	/** Bleach melee bonus, fraction. */
	public static double ICHIGO_SHIKAI_DMG = 0.25;
	/** Arc of the widened sweep, degrees. */
	public static double ICHIGO_SHIKAI_CLEAVE_ARC = 90.0;
	/** Fraction of primary damage dealt to secondary cleave targets. */
	public static double ICHIGO_SHIKAI_CLEAVE_PCT = 0.50;
	/** {@code MOVEMENT_SPEED} multiplied-total. */
	public static double ICHIGO_BANKAI_SPEED = 0.60;
	/** {@code ATTACK_SPEED} multiplied-total. */
	public static double ICHIGO_BANKAI_ATK_SPEED = 0.50;
	/** Bleach melee bonus, fraction. */
	public static double ICHIGO_BANKAI_DMG = 0.75;

	// ================================================================================
	// J.2 Yamamoto · PRD §6.2 · BALANCE.md §J.2
	// ================================================================================

	/** Ignite radius, blocks. */
	public static double YAMA_SHIKAI_RADIUS = 30.0;
	/** Fire ticks applied by the Shikai burst. */
	public static int YAMA_SHIKAI_BURN_TICKS = 200;
	/** Extinguish radius, blocks. */
	public static double YAMA_BANKAI_RADIUS = 36.0;
	/** Melee bonus while Bankai is active, fraction. */
	public static double YAMA_BANKAI_DMG = 0.30;
	/** Fire-Aspect-equivalent burn applied on hit, ticks. */
	public static int YAMA_BANKAI_ONHIT_BURN_TICKS = 100;
	/** Scorch sweep budget, columns per tick. */
	public static int YAMA_SCORCH_COLUMNS_PER_TICK = 60;
	/** Scorch sweep hard cap on modified blocks. */
	public static int YAMA_SCORCH_BLOCK_CAP = 12000;
	/** How far below the surface water, ice and snow are evaporated. */
	public static int YAMA_EVAPORATE_DEPTH = 4;
	/** Radius of each follow-up disc scorched as Shikai's flame is carried around, blocks. */
	public static double YAMA_TRAIL_RADIUS = 8.0;
	/** How far the caster must move before Shikai lays more fire, blocks. */
	public static double YAMA_TRAIL_STEP = 3.0;
	/** Positions checked per tick by Bankai's 3D spherical extinguish sweep. */
	public static int YAMA_BLOCK_SWEEP_BUDGET = 2500;
	/** Whether Bankai's fire-absorbing sweep extinguishes lit campfires and fireplaces. */
	public static boolean YAMA_EXTINGUISH_CAMPFIRES = true;
	/** Whether Bankai's fire-absorbing sweep snuffs out torches. */
	public static boolean YAMA_EXTINGUISH_TORCHES = true;
	/** Flames drawn collapsing inward when Bankai pulls the fire into the blade. */
	public static int YAMA_BANKAI_INHALE_PARTICLES = 200;
	/** Chance a scorched column is also set alight. */
	public static double YAMA_FIRE_CHANCE = 0.55;
	/** Particles spawned along the perimeter ring on Shikai entry. */
	public static int YAMA_SHIKAI_RING_PARTICLES = 120;
	/** Height offset above caster for the Shikai perimeter flame ring, blocks. */
	public static double YAMA_RING_PARTICLE_Y_OFFSET = 0.20;
	/** SP cost to unleash the Bankai raven conical destruction on swing miss. */
	public static double YAMA_BANKAI_CONE_SP_COST = 30.0;
	/** Base reach of the Bankai conical destruction, blocks. */
	public static double YAMA_BANKAI_CONE_RANGE_BASE = 15.0;
	/** Additional reach per Soul Level for the Bankai conical destruction, blocks/level. */
	public static double YAMA_BANKAI_CONE_RANGE_PER_SL = 1.25;
	/** Half-angle of the Bankai destruction cone, degrees. */
	public static double YAMA_BANKAI_CONE_ANGLE_DEG = 35.0;
	/** Base damage of the Bankai conical destruction blast. */
	public static double YAMA_BANKAI_CONE_DMG_BASE = 27.0;
	/** Additional damage per Soul Level for the Bankai conical destruction blast. */
	public static double YAMA_BANKAI_CONE_DMG_PER_SL = 2.0;
	/** Hard cap on blocks obliterated by one raven conical destruction. */
	public static int YAMA_BANKAI_CONE_BLOCK_CAP = 8000;
	/** Tick-slice budget for Bankai conical destruction. */
	public static int YAMA_BANKAI_CONE_BLOCKS_PER_TICK = 400;
	/** Cooldown between Bankai raven swings, ticks. */
	public static int YAMA_BANKAI_SWING_COOLDOWN_TICKS = 15;

	/**
	 * Fraction of the strike reach that the cone actually excavates.
	 *
	 * <p>The blast and the hole it leaves are deliberately different shapes. The raven has to reach
	 * far enough to be a threat across a fight, but a crater that long turns every miss into terrain
	 * vandalism — so the flame carries and the digging stops short.
	 */
	public static double YAMA_BANKAI_CONE_BLOCK_RANGE_MULT = 0.55;

	/** Half-angle of the excavated cone. Narrower than the strike cone for the same reason. */
	public static double YAMA_BANKAI_CONE_BLOCK_ANGLE_DEG = 20.0;

	/**
	 * Whether the Bankai raven cone abandons its terrain destruction when the blast strikes a living
	 * entity. The swing is then a strike <em>or</em> an excavation, never both.
	 */
	public static boolean YAMA_BANKAI_CONE_CANCEL_ON_HIT = true;

	// ================================================================================
	// J.3 Sui-Feng · PRD §6.3 · BALANCE.md §J.3
	// ================================================================================

	/**
	 * Second-strike match radius, in <b>body-frame units</b>, where the whole body is a unit cube:
	 * 1.0 is the full width or the full height. Fraction, not blocks — a fixed distance is a third of
	 * a player and most of a bee. 0.30 reads as roughly a limb, and is a little kinder than the
	 * geometry alone would suggest because the target you are re-striking is a body part that moves
	 * as its owner turns.
	 */
	public static double SUI_MARK_TOLERANCE = 0.35;
	/** Bankai telegraph, ticks. */
	public static int SUI_BANKAI_WINDUP_TICKS = 60;
	/** Guaranteed-kill radius, blocks. */
	public static double SUI_BANKAI_LETHAL_RADIUS = 12.0;
	/** Outer falloff edge, blocks. */
	public static double SUI_BANKAI_FALLOFF_RADIUS = 24.0;
	/** Damage at the lethal edge. */
	public static double SUI_BANKAI_DMG_INNER = 60.0;
	/** Damage at the outer edge. */
	public static double SUI_BANKAI_DMG_OUTER = 12.0;
	/**
	 * Exertion seconds dumped on the caster when a Nigeki Kessatsu second strike lands.
	 *
	 * <p>The cost of Suì-Fēng's Shikai sits here rather than on her Bankai. Jakuhō Raikōben is a
	 * telegraphed, rooted, three-second wind-up that can miss outright — emptying the pool is
	 * already the whole of its price, and it recovers at the ordinary rate afterwards. The homonka
	 * kill is the opposite: instant, unconditional and armour-blind, so <em>that</em> is what earns
	 * the throttled regeneration. At SL 1 this pins the multiplier near
	 * {@link #EXERTION_MULT_FLOOR} and takes minutes of visible bar to shed.
	 */
	public static double SUI_SHIKAI_KILL_EXERTION = 60.0;
	/** Missile flight speed, blocks per tick. */
	public static double SUI_MISSILE_SPEED = 2.5;
	/** Collision slices per tick. A projectile that tests only its endpoints flies through walls. */
	public static int SUI_MISSILE_SUBSTEPS = 5;
	/** Maximum flight distance before it detonates in the air, blocks. */
	public static double SUI_MISSILE_RANGE = 120.0;
	/** Hitbox inflation when testing what the missile has struck, blocks. */
	public static double SUI_MISSILE_HIT_RADIUS = 0.5;
	/** Self damage as a fraction of <em>current</em> health, never lethal. */
	public static double SUI_BANKAI_SELF_DMG_PCT = 0.50;
	/** Spherical crater radius, blocks. */
	public static double SUI_CRATER_RADIUS = 18.0;
	/** Hard cap on blocks changed by one crater. Full r=18 sphere is ~24,400 blocks plus outer rim shell. */
	public static int SUI_CRATER_BLOCK_CAP = 28000;
	/** Crater tick-slice budget. */
	public static int SUI_CRATER_BLOCKS_PER_TICK = 400;
	/** Mark butterfly particle color (packed RGB black). */
	public static int SUI_MARK_PARTICLE_COLOR = 0x050505;
	/** Mark particle scale. */
	public static double SUI_MARK_PARTICLE_SCALE = 0.80;
	/** Particles spawned along the expanding Bankai warning ring each tick. */
	public static int SUI_BANKAI_RING_PARTICLES = 100;
	/** Raycast reach for mark detection, blocks. */
	public static double SUI_MARK_RAYCAST_REACH = 5.0;

	// ================================================================================
	// J.4 Rukia · PRD §6.4 · BALANCE.md §J.4
	// ================================================================================

	/** Bankai field radius, blocks. */
	public static double RUKIA_BANKAI_RADIUS = 16.0;
	/** Freeze tick damage per second. */
	public static double RUKIA_FREEZE_DMG_PER_SEC = 2.0;
	/** Movement speed multiplier while afflicted by the freeze effect. */
	public static double RUKIA_FREEZE_SPEED_MULT = -0.50;
	/** Freeze effect icon color (packed RGB). */
	public static int RUKIA_FREEZE_EFFECT_COLOR = 0x93C5FD;
	/** Freeze effect refresh duration, ticks. */
	public static int RUKIA_FREEZE_DURATION_TICKS = 60;
	/** Frost vignette border color (packed RGB). */
	public static int RUKIA_VIGNETTE_COLOR = 0x93C5FD;
	/** Frost vignette peak border opacity. */
	public static double RUKIA_VIGNETTE_ALPHA = 0.25;
	/** Frost vignette depth as a fraction of screen height. */
	public static double RUKIA_VIGNETTE_DEPTH_PCT = 0.20;
	/** Snow-layer placement budget per tick. */
	public static int RUKIA_SNOW_BLOCKS_PER_TICK = 40;
	/** Snow layer height cap. */
	public static int RUKIA_SNOW_MAX_LAYERS = 4;
	/** Total blocks touched per activation. */
	public static int RUKIA_SNOW_BLOCK_CAP = 3000;
	/** Particles per tick faking local weather. */
	public static int RUKIA_SNOWFALL_PARTICLES = 60;
	/** Height above caster for snowfall particle spawns, blocks. */
	public static double RUKIA_SNOWFALL_HEIGHT = 12.0;
	/** Downward velocity of falling snowflake particles, blocks per tick. */
	public static double RUKIA_SNOWFALL_SPEED = 0.20;
	/** Localized burst radius on melee hit, blocks. */
	public static double RUKIA_SHIKAI_ONHIT_RADIUS = 6.0;
	/** Radius of each follow-up disc laid as Bankai's field is carried around, blocks. */
	public static double RUKIA_TRAIL_RADIUS = 7.0;
	/** How far the caster must move before Bankai lays another disc, blocks. */
	public static double RUKIA_TRAIL_STEP = 3.0;
	/** Ticks between on-hit bursts. */
	public static int RUKIA_SHIKAI_ONHIT_COOLDOWN = 20;

	// ================================================================================
	// J.5 Shinji · PRD §6.5 · BALANCE.md §J.5
	// ================================================================================

	/** Single-target inversion on melee hit, ticks. */
	public static int SHINJI_SHIKAI_ONHIT_DURATION = 100;
	/** Ticks between on-hit applications. */
	public static int SHINJI_SHIKAI_ONHIT_COOLDOWN = 40;
	/** Aura radius floor, blocks. */
	public static double SHINJI_RADIUS_BASE = 16.0;
	/** Aura radius growth per level, blocks. */
	public static double SHINJI_RADIUS_PER_LEVEL = 0.80;
	/** Sakanade refresh duration, ticks. */
	public static int SHINJI_EFFECT_DURATION_TICKS = 60;
	/** Fraction of mob rolls that invert. */
	public static double SHINJI_MOB_INVERT_CHANCE = 0.70;
	/** Ticks between mob inversion rolls. */
	public static int SHINJI_MOB_REROLL_TICKS = 10;
	/** How often Bankai re-points afflicted mobs at each other, ticks. */
	public static int SHINJI_BANKAI_RETARGET_TICKS = 20;
	/** Sakanade amplifier applied by Shinji's Shikai — movement inversion only. */
	public static int SHINJI_SHIKAI_AMPLIFIER = 0;
	/** Sakanade amplifier applied by Shinji's Bankai — movement inversion plus a flipped camera. */
	public static int SHINJI_BANKAI_AMPLIFIER = 1;
	/** Amplifier at and above which Sakanade also inverts mouse look. */
	public static int SAKANADE_CAMERA_FLIP_AMPLIFIER = 1;
	/** Sakanade effect icon color (packed RGB purple). */
	public static int SHINJI_EFFECT_COLOR = 0x7E22CE;
	/** Particles spawned along the Bankai aura perimeter ring each tick. */
	public static int SHINJI_BANKAI_RING_PARTICLES = 64;

	// ================================================================================
	// J.6 Aizen · Kyōka Suigetsu · BALANCE.md §J.6
	// ================================================================================

	/** Raycast line of sight check radius for Complete Hypnosis upon Shikai release, blocks. */
	public static double AIZEN_SHIKAI_RADIUS = 100.0;
	/**
	 * Full width of the view cone a victim must have Aizen inside of, at the instant of release, for
	 * Complete Hypnosis to take. Degrees.
	 *
	 * <p>Kyōka Suigetsu is <em>seen</em>, not broadcast. An unobstructed line between two eyes is not
	 * the same claim as "the victim was looking at him" — it hypnotised everyone standing in the open
	 * within 100 blocks, including players facing the other way. This is the half that was missing.
	 *
	 * <p>90° is a little narrower than the horizontal span a default 70-FOV client actually renders
	 * (~103°), so anything this catches was unambiguously on screen rather than clipped to the edge
	 * of it. Widen it toward 103 to match what the screen literally shows; drop it toward 40 to
	 * demand the victim was more or less looking straight at him.
	 */
	public static double AIZEN_SHIKAI_FOV_DEG = 90.0;
	/** Number of unkillable illusion decoy mobs spawned per hypnotized player. */
	public static int AIZEN_ILLUSION_MOB_COUNT = 3;

	/** Extra illusion mobs per Soul Level above 1, so a stronger Aizen crowds the victim harder. */
	public static double AIZEN_ILLUSION_MOB_PER_SL = 0.5;

	/** Ceiling on the scaled illusion count, so a capped Aizen does not spawn a mob farm per victim. */
	public static int AIZEN_ILLUSION_MOB_MAX = 12;
	/** Melee damage bonus in Bankai. */
	public static double AIZEN_BANKAI_DMG_BONUS = 0.35;

	// ================================================================================
	// J.7 Tōsen · Suzumushi & Enma Kōrogi · BALANCE.md §J.7
	// ================================================================================

	/** Shikai high-pitch resonance and SP drain radius, blocks. */
	public static double TOSEN_SHIKAI_RADIUS = 26.0;
	/** Interval between Shikai high-pitch audio chimes, ticks. */
	public static int TOSEN_SHIKAI_SOUND_INTERVAL_TICKS = 11;
	/** Pitch of the Shikai resonant chime. */
	public static double TOSEN_SHIKAI_SOUND_PITCH = 2.0;
	/** SP drained per tick from players inside Tōsen's Shikai radius. */
	public static double TOSEN_SHIKAI_SP_DRAIN_PER_TICK = 1.0;

	/**
	 * Nausea duration refreshed each tick on anyone inside Suzumushi's radius, in ticks.
	 *
	 * <p>Distinct from the Blindness and Freeze at zero SP, which stay strictly gated on an empty
	 * pool. This is the chime itself being unbearable to stand near — it disorients from the first
	 * second without blinding, and its long fade means walking out of range does not clear it
	 * instantly. Set to 0 to disable.
	 */
	public static int TOSEN_SHIKAI_NAUSEA_TICKS = 90;

	/**
	 * Duration the Blindness and Freeze of Suzumushi's second stage are refreshed for, in ticks.
	 *
	 * <p>Short on purpose, and re-applied every tick while the victim is still exposed. It is the
	 * lapse that lets someone who has broken out of the radius recover in about three seconds
	 * instead of carrying the deprivation with them.
	 */
	public static int TOSEN_SHIKAI_DEPRIVE_TICKS = 60;

	/**
	 * Ticks a mob must stand inside Suzumushi's radius before the chime breaks it, and the ceiling
	 * on the exposure counter that measures it.
	 *
	 * <p>A mob has no spiritual pressure for the chime to erode, so it cannot be staged off an empty
	 * pool the way a player is. This is the stand-in clock. 100 ticks is not arbitrary: at
	 * {@link #TOSEN_SHIKAI_SP_DRAIN_PER_TICK} it is exactly how long the same aura takes to empty a
	 * Soul Level 1 player's {@link #SP_BASE_MAX}, so both halves of the room break together.
	 *
	 * <p>It is also the ceiling, which is what bounds recovery: exposure drains a tick per tick out
	 * of range, so no amount of standing in the aura can make a mob take longer than five seconds to
	 * shake it off.
	 */
	public static int TOSEN_SHIKAI_MOB_RESIST_TICKS = 100;

	/** Radius of Enma Kōrogi sensory deprivation Bankai dome, blocks. */
	public static double TOSEN_BANKAI_RADIUS = 25.0;

	/**
	 * Duration the dome's Blindness is refreshed for on mobs caught inside it, in ticks.
	 *
	 * <p>Enma Kōrogi is instant and total for a player — the client simply stops rendering. A mob
	 * has no screen to black out, so its half of the deprivation is Blindness plus a target it can
	 * never keep hold of. Refreshed rather than long-lived, so walking out of the dome restores a
	 * mob's senses in about two seconds rather than leaving it stunned in open ground.
	 */
	public static int TOSEN_BANKAI_MOB_BLIND_TICKS = 40;

	// ================================================================================
	// J.8 Gin · Shinsō & Kamishini no Yari · BALANCE.md §J.8
	// ================================================================================

	/** Range of Gin's Shikai long-range single attack, blocks. */
	public static double GIN_SHIKAI_RANGE = 55.0;
	/** Damage dealt by Gin's Shikai single piercing strike. */
	public static double GIN_SHIKAI_DMG = 18.0;
	/** Cooldown between Gin Shikai long-range thrusts, ticks. */
	public static int GIN_SHIKAI_COOLDOWN_TICKS = 20;
	/**
	 * SP burned by each Shinsō thrust.
	 *
	 * <p>The thrust is a 55-block instant piercing hit off the ordinary attack key on a one-second
	 * cooldown, which is far too strong to be paid for by {@link #DRAIN_SHIKAI} alone — passive
	 * drain charges for <em>being</em> in Shikai, not for firing it.
	 *
	 * <p>Priced to be punishing rather than merely noticeable: at the SL 1 pool of
	 * {@link #SP_BASE_MAX} it is three thrusts from full, and every swing pays it — there is no
	 * cheaper melee mode to fall back on. Each one also pauses regeneration like any other spend,
	 * so a player who swings on reflex is out of Shikai entirely in a few seconds. Shinsō is a shot
	 * you line up, not a weapon you hold down.
	 */
	public static double GIN_SHIKAI_SP_COST = 30.0;
	/** Range of Gin's Bankai sweeping beam, blocks. */
	public static double GIN_BANKAI_BEAM_RANGE = 70.0;
	/** Damage dealt per slice of Gin's Bankai beam. */
	public static double GIN_BANKAI_BEAM_DMG = 15.0;
	/** Minimum mouse angular swipe velocity required to deal slice damage, degrees/tick. */
	public static double GIN_MIN_SWIPE_SPEED = 6.0;
	/** Cooldown before the same entity can be sliced again by the sweeping beam, ticks. */
	public static int GIN_BANKAI_ENTITY_SLICE_COOLDOWN_TICKS = 8;

	/** Spacing between beam particles, in blocks. Smaller is a more continuous stream. */
	public static double GIN_BANKAI_BEAM_PARTICLE_STEP = 0.6;

	// --- P.0 Quincy · shared race constants ------------------------------------------

	/** Floor on a Quincy's environment regen multiplier — deep underground, submerged, in the Nether. */
	public static double REISHI_MULT_MIN = 0.45;
	/** Ceiling — open sky, full daylight. */
	public static double REISHI_MULT_MAX = 1.35;
	/** How much of the span is bought by sky light level (0..15 normalised). */
	public static double REISHI_SKYLIGHT_WEIGHT = 0.6;
	/** How much of the span is bought by having a clear column to the sky at all. */
	public static double REISHI_SKY_ACCESS_WEIGHT = 0.4;
	/** Multiplier applied on top while the player is submerged in fluid. */
	public static double REISHI_SUBMERGED_PENALTY = 0.7;
	/** Multiplier applied on top in a dimension with no natural sky — the Nether. */
	public static double REISHI_NO_SKY_DIMENSION_PENALTY = 0.6;
	/** Absolute floor the final multiplier can never drop below, however harsh the penalties above. */
	public static double REISHI_MULT_ABSOLUTE_FLOOR = 0.01;

	// --- P.1 Quincy · Reishi Arrow ------------------------------------------------

	/** Quad size of {@code ReishiArrow}'s in-flight pressure-particle trail. */
	public static double REISHI_ARROW_PARTICLE_SCALE = 0.5;
	/**
	 * {@code ReishiArrow}'s hitbox width, in blocks. Read once by {@code BleachEntities#register}
	 * at entity-type registration — like §I.1, a config change here needs a restart.
	 */
	public static double REISHI_ARROW_WIDTH = 0.5;
	/** {@code ReishiArrow}'s hitbox height, in blocks. Same restart caveat as {@link #REISHI_ARROW_WIDTH}. */
	public static double REISHI_ARROW_HEIGHT = 0.5;
	/**
	 * Chunk radius at which a tracking client is sent the arrow at all. Same restart caveat as
	 * {@link #REISHI_ARROW_WIDTH}.
	 */
	public static int REISHI_ARROW_TRACKING_RANGE = 4;
	/** Ticks between position/velocity resyncs to tracking clients. Same restart caveat as {@link #REISHI_ARROW_WIDTH}. */
	public static int REISHI_ARROW_UPDATE_INTERVAL = 20;

	// --- P.2 Quincy · Heilig Bogen --------------------------------------------------

	/**
	 * Melee damage of the bow used as a club. Deliberately far under the zanpakutō's 3. Baked into
	 * the item's default attribute modifiers at registration — like §I.1, a config change here needs
	 * a restart.
	 */
	public static int BOW_MELEE_DAMAGE = 1;
	/** Melee attack speed modifier for the bow. Same restart caveat as {@link #BOW_MELEE_DAMAGE}. */
	public static double BOW_MELEE_SPEED = -2.8;
	/** SP charged per shot, whatever the draw. */
	public static double BOW_SHOT_SP_COST = 6.0;
	/** Ticks of draw for a full-power shot. */
	public static int BOW_FULL_DRAW_TICKS = 20;
	/** Arrow damage at a full draw, before Soul Level scaling. */
	public static double BOW_ARROW_DAMAGE = 7.0;
	/** Arrow launch velocity at a full draw, blocks per tick. */
	public static double BOW_ARROW_VELOCITY = 3.0;
	/** Minimum draw fraction below which the shot is refused outright. */
	public static double BOW_MIN_DRAW = 0.15;

	// --- P.3 Quincy · Vollstandig ----------------------------------------------------

	/** Vollstandig movement speed bonus, ADD_MULTIPLIED_TOTAL. */
	public static double VOLL_SPEED = 0.35;
	/** Vollstandig bleach melee and arrow damage bonus. */
	public static double VOLL_DMG = 0.45;
	/** Vollstandig Flash Step (Hirenkyaku) range multiplier while active. */
	public static double VOLL_FS_RANGE_MULT = 1.35;
	/** Wing particles emitted per tick behind the shoulders. */
	public static int VOLL_WING_PARTICLES = 6;
	/** How far behind the player the wing arc sits, blocks. */
	public static double VOLL_WING_OFFSET = 0.45;
	/** Radius of the wing arc, blocks. */
	public static double VOLL_WING_RADIUS = 1.1;
	/** Quad size of a single wing particle. */
	public static double VOLL_WING_PARTICLE_SCALE = 0.7;

	// ================================================================================
	// K. Networking and presentation · BALANCE.md §K
	// ================================================================================

	/** S2C stat sync heartbeat, ticks. */
	public static int SYNC_KEEPALIVE_TICKS = 20;
	/** HUD bar colour in the base state (packed RGB). */
	public static int HUD_COLOR_BASE = 0x3B82F6;
	/** HUD bar colour in Shikai (packed RGB). */
	public static int HUD_COLOR_SHIKAI = 0xF97316;
	/** HUD bar colour in Bankai (packed RGB). */
	public static int HUD_COLOR_BANKAI = 0xDC2626;
	/**
	 * Distance from the bottom of the screen to the top of the SP bar, in scaled pixels. 28 puts it
	 * lower on the HUD, cleanly beneath vanilla hearts and hunger rows.
	 */
	public static int HUD_BOTTOM_OFFSET = 0;
	/** Gap between the SP bar and the experience bar under it, pixels. */
	public static int HUD_BAR_GAP = 2;
	/**
	 * How far vanilla's health, food, armour and air rows are lifted to make room for the SP bar,
	 * in scaled pixels. Zero means one full vanilla row pitch, which is what fits — see
	 * {@code SpiritualHud#rowLift}. Raise it if another HUD mod wants that row too.
	 */
	public static int HUD_ROW_LIFT = 0;
	/** Gap between the SP figure and the top of the bar, pixels. */
	public static int HUD_TEXT_GAP = 1;
	/** Gap between the Soul Level figure and the left end of the bar, pixels. */
	public static int HUD_LEVEL_TEXT_GAP = 4;
	/** Depth the whole bar is lifted to. Kept at 0.0 to respect standard 2D render ordering and avoid clipping action messages. */
	public static double HUD_Z_DEPTH = 0.0;
	/** Soul Level figure colour, packed RGB. */
	public static int HUD_COLOR_LEVEL_TEXT = 0xFFFFFF;
	/*
	 * Gate markers — the notches on the bar showing where Shikai and Bankai become available. The
	 * thresholds are sent by the server · SpiritualSyncPayload, because they are read off tuning that
	 * is never synced.
	 */

	/** Width of a gate notch, px. */
	public static int HUD_GATE_WIDTH_PX = 1;
	/** How far a notch overhangs the bar above and below, px. Zero keeps it inside the frame. */
	public static int HUD_GATE_OVERHANG_PX = 1;
	/** Notch colour once the gate is met, packed RGB — this release is available right now. */
	public static int HUD_COLOR_GATE_OPEN = 0xFFFFFF;
	/** Notch colour while the gate is short, packed RGB. */
	public static int HUD_COLOR_GATE_SHUT = 0x64748B;
	/** Notch alpha while the gate is short, 0..255. Present but quiet — a target, not a warning. */
	public static int HUD_GATE_SHUT_ALPHA = 0x9A;

	/*
	 * The "+30 SPX" that rises off the bar on a kill. Sent by the server as it makes the award rather
	 * than differenced from the synced bank, which cannot work: SPX is filled and spent on levels in
	 * the same tick · SpxGainPayload.
	 */

	/** How long one figure lives, milliseconds — the full rise and fade. */
	public static double HUD_SPX_GAIN_DURATION_MILLIS = 1400.0;
	/** How far a figure rises over that life, scaled pixels. */
	public static int HUD_SPX_GAIN_RISE_PX = 13;
	/** Fraction of the life spent at full opacity before the fade starts, 0..1. */
	public static double HUD_SPX_GAIN_HOLD = 0.45;
	/** Awards landing within this of the last one merge into it instead of stacking, milliseconds. */
	public static double HUD_SPX_GAIN_MERGE_MILLIS = 350.0;
	/** Vertical pitch between two figures on screen at once, scaled pixels. */
	public static int HUD_SPX_GAIN_STACK_PX = 10;
	/**
	 * SPX gain figure colour, packed RGB. A light blue rather than a mid one — the figure is small,
	 * outlined, and lives for barely a second over whatever the world happens to be behind it.
	 */
	public static int HUD_COLOR_SPX_GAIN = 0x60A5FA;
	/**
	 * Reiatsu overlay alpha by amplifier, as a fraction. The PRD's "screen shake" ships first as this
	 * HUD-layer substitute — a real camera hook is a renderer mixin, and the plan says to try the
	 * cheap version before paying for it.
	 */
	public static double[] REIATSU_VIGNETTE_ALPHA = { 0.10, 0.22, 0.36, 0.55 };
	/** Depth of the overlay bands as a fraction of screen height. */
	public static double REIATSU_VIGNETTE_DEPTH_PCT = 0.22;
	/** Reiatsu overlay colour (packed RGB). */
	public static int REIATSU_VIGNETTE_COLOR = 0x2E1065;
	/** Peak band wobble, in scaled pixels. This is the "shake" of the substitute. */
	public static double REIATSU_SHAKE_AMPLITUDE_PX = 4.0;
	/** Wobble period, milliseconds. Short — a shudder, not a sway. */
	public static double REIATSU_SHAKE_PERIOD_MILLIS = 130.0;

	/** Stats screen panel background (packed RGB). */
	public static int STATS_COLOR_PANEL = 0x0F1420;
	/** Stats screen inset row background (packed RGB). */
	public static int STATS_COLOR_ROW = 0x1E2635;
	/** Stats screen accent — panel border and progress fill (packed RGB). */
	public static int STATS_COLOR_ACCENT = 0x3B82F6;
	/** Minecraft days between World Soul Level recomputes. */
	public static int WSL_RECOMPUTE_DAYS = 1;
	/** Minecraft days of playtime that count toward World Soul Level. */
	public static int WSL_PLAYTIME_WINDOW_DAYS = 7;

	/**
	 * Master on/off switch. Not a balance value — a diagnostic one, so that "is the mod doing this?"
	 * can be answered in one keypress. Lives here because {@link #load()}/{@link #save()} already
	 * give it persistence and a config key for free. Owned at runtime by
	 * {@code ModToggle}, which is what everything else should read; see that class for what "off"
	 * actually switches off.
	 */
	public static boolean MOD_ENABLED = true;

	// ================================================================================
	// N. Aura Sense · BALANCE.md §N
	// ================================================================================

	/** How far a Soul Level 1 player's aura carries to a sensor, blocks. */
	public static double AURA_RANGE_BASE = 80.0;
	/** Added reach per Soul Level above 1. At the SL 20 cap this is 349.8 blocks. */
	public static double AURA_RANGE_PER_LEVEL = 14.2;
	/** Reach of anything without a Soul Level — every mob, every animal, blocks. */
	public static double AURA_RANGE_UNRANKED = 64.0;
	/** Ticks between aura sweeps. Each sweep is one scan and one packet per sensor. */
	public static int AURA_SYNC_INTERVAL_TICKS = 2;
	/** Hard cap on auras in one packet. The nearest are kept; the rest are dropped. */
	public static int AURA_MAX_ENTRIES = 48;
	/** Aura colour for players — one colour for the whole species; the sense reads souls, not faces. */
	public static int AURA_COLOR_PLAYER = 0x8AE6FF;
	/** Saturation of the hue derived from a mob's type id, 0..1. */
	public static double AURA_MOB_SATURATION = 0.80;
	/** Value (brightness) of the hue derived from a mob's type id, 0..1. */
	public static double AURA_MOB_VALUE = 1.0;
	/** Ticks between the pressure particles that tell everyone else a sensor's eyes are shut. */
	public static int AURA_TELL_INTERVAL_TICKS = 6;

	// --- Aura Sense burn · how hard a soul is pushing · BALANCE.md §N.2 --------------

	/*
	 * Soul Level says how big a soul is; burn says how hard it is pushing right now. The two are
	 * multiplied rather than added, so the multiplier stays proportional to Soul Level — a released
	 * Bankai at SL 20 reads far larger than the same release at SL 3, exactly as the base radius
	 * already does. A sense that showed every soul at its resting size would make a released Bankai
	 * indistinguishable from a sheathed one, which is the one thing a pressure-sense is for.
	 */

	/** Burn multiplier while in Shikai. Applied on top of the Soul Level radius. */
	public static double AURA_BURN_SHIKAI = 1.8;
	/** Burn multiplier while in Bankai. */
	public static double AURA_BURN_BANKAI = 3.6;
	/** Further multiplier while exerting Spiritual Flex, on top of whatever state is held. */
	public static double AURA_BURN_FLEX = 2.1;

	// --- Aura Sense presentation · client-side · BALANCE.md §N.1 ---------------------

	/** Time the eyelid takes to fall, milliseconds. */
	public static double AURA_EYELID_CLOSE_MILLIS = 260.0;
	/** Time the eyelid takes to lift, milliseconds. Faster than the close — opening your eyes is. */
	public static double AURA_EYELID_OPEN_MILLIS = 170.0;
	/** How far the lid must have fallen before any aura is drawn, as a fraction of its travel. */
	public static double AURA_VISION_THRESHOLD = 0.86;
	/** Aura radius before Soul Level, world units — the floor for a player at SL 1. */
	public static double AURA_SIZE_BASE = 1.6;
	/** Added aura radius per Soul Level, in world units. */
	public static double AURA_SIZE_PER_LEVEL = 0.32;

	/*
	 * A creature has no Soul Level, so it is sized by the only thing it does have: how much of the
	 * world it takes up. The measure is the cube root of its bounding box's volume, sent over the
	 * wire because at these ranges there is no entity on the client to measure · AuraSense#body.
	 * Roughly: silverfish 0.36, chicken 0.48, bee 0.67, zombie 0.89, cow 1.04, spider 1.21,
	 * iron golem 1.74, ravager 2.03, ghast 4.0, ender dragon 12.7.
	 */

	/** Aura radius floor for any creature, world units. Even a bee is something. */
	public static double AURA_MOB_SIZE_BASE = 0.55;
	/** Added aura radius per block of measured body size, world units. */
	public static double AURA_MOB_SIZE_PER_BLOCK = 1.35;
	/**
	 * Ceiling on the measured size fed into the above, blocks. Holds the very largest creature alive
	 * just under a fully realised Soul Level 20 player, which is the line the sense is worth keeping:
	 * physical bulk is not spiritual weight, however much of it there is.
	 */
	public static double AURA_MOB_SIZE_CAP = 5.0;
	/** Floor on the drawn radius, scaled pixels — a far aura is a spark, never nothing. */
	public static double AURA_MIN_RADIUS_PX = 2.5;
	/** Ceiling on the drawn radius at rest, scaled pixels, so a neighbour does not white out. */
	public static double AURA_MAX_RADIUS_PX = 110.0;
	/**
	 * Ceiling on the drawn radius once burn is applied, scaled pixels. Separate from
	 * {@link #AURA_MAX_RADIUS_PX} on purpose: a released Bankai standing on top of you is meant to
	 * fill the view, and clamping it to the resting ceiling would erase the whole difference.
	 */
	public static double AURA_MAX_BURN_RADIUS_PX = 430.0;
	/**
	 * How far below a soul's centre of mass its fire is anchored, blocks.
	 *
	 * <p>A blob centred on the body reads as centred, but a fire rises from its anchor — so the same
	 * point that was right for a disc puts the flames above the soul and leaves the body sitting in
	 * the gap underneath. Dropping the base low on the body is what makes the soul look like the
	 * thing that is burning.
	 */
	public static double AURA_ANCHOR_DROP_BLOCKS = 0.5;
	/** Alpha at the centre of an aura, 0..1. The rim always fades to zero. */
	public static double AURA_CORE_ALPHA = 0.85;
	/** Triangle-fan segments per aura. Twenty is round enough at the maximum radius. */
	public static int AURA_SEGMENTS = 20;

	// --- Aura Sense flame · the particle fire · BALANCE.md §N.3 ---------------------

	/*
	 * Fire is not a shape, it is a population. Every value below feeds a live particle system —
	 * particles born hot and white at the base of a soul, rising, cooling through the aura's own
	 * colour, spreading and dying · AuraFlame. The taper, the flicker and the wisps tearing off the
	 * tip are consequences of that, not things drawn on purpose, which is why the earlier
	 * static-geometry version read as a decal no matter how it was shaped.
	 *
	 * The simulation is normalised to the reading's own radius, so ONE set of numbers describes a
	 * bonfire at ten blocks and a spark at three hundred alike, and a soul swelling into Bankai grows
	 * its fire smoothly instead of teleporting every particle in it. Only the spawn rate looks at the
	 * drawn pixel radius, so that a distant soul is a small fire rather than a lonely dot.
	 *
	 * These were settled in tools/flame-prototype.html, which is the same maths in a canvas with a
	 * slider per constant. Retune there before touching them here.
	 */

	/** Particles per second at the reference size, before burn, radius and gust scaling. */
	public static double AURA_PARTICLE_RATE = 620.0;
	/** Mean particle lifetime, seconds. Each varies around it. */
	public static double AURA_PARTICLE_LIFE = 0.95;
	/** Upward acceleration, in radii per second squared. Fades with age, so the top stalls. */
	public static double AURA_PARTICLE_BUOYANCY = 13.0;
	/** Turbulence strength. Ramped with age — applied flat, it lays the whole fire down. */
	public static double AURA_PARTICLE_TURBULENCE = 4.4;
	/** Rotation about the fire's axis. Without it, turbulence reads as jitter rather than motion. */
	public static double AURA_PARTICLE_SWIRL = 2.4;
	/** Velocity lost per second, 0..1. */
	public static double AURA_PARTICLE_DRAG = 0.45;
	/** Pull back toward the axis, growing with age. This is what gives a flame its point. */
	public static double AURA_PARTICLE_TAPER = 0.95;
	/** How much a particle expands over its life — cooling gas. */
	public static double AURA_PARTICLE_GROW = 0.7;
	/** Radius of the spawn volume, in reading radii. */
	public static double AURA_PARTICLE_DISC = 1.1;
	/**
	 * How far the spawn volume lifts off the flat, 0 a disc .. 1 a half-sphere. A flat disc collapses
	 * to a line the moment the camera is level with it, which reads as a column on a plate; the dome
	 * wraps the soul so the fire is round from every angle.
	 */
	public static double AURA_PARTICLE_DOME = 0.80;
	/**
	 * Outward speed off the dome. Against {@link #AURA_PARTICLE_BUOYANCY} this is the whole shape
	 * control: bloom wins and it is a ball of fire, buoyancy wins and it is a jet.
	 */
	public static double AURA_PARTICLE_BLOOM = 0.60;
	/**
	 * Alpha of one particle at its brightest. Deliberately low — the brightness of a fire comes from
	 * how many particles overlap, and a solid one clips the core to a white pill under additive
	 * blending.
	 */
	public static double AURA_PARTICLE_OPACITY = 0.30;
	/** Particle size as a fraction of the reading's radius. Small and many, never big and few. */
	public static double AURA_PARTICLE_GRAIN = 0.26;
	/**
	 * How far a particle stretches along its own velocity. The single thing that turns a column of
	 * round dots into fire: fast particles become streaks, slow ones at the top stay puffs.
	 */
	public static double AURA_PARTICLE_STRETCH = 1.75;
	/** Surge depth, 0 a steady burn .. 1 violent flaring. A constant fire reads as a machine. */
	public static double AURA_PARTICLE_GUST = 0.65;
	/** How fast the turbulence field boils. Raises violence without making the fire finer-grained. */
	public static double AURA_PARTICLE_CHURN = 1.9;

	/** Fan segments per particle. Five: at a few pixels across, a pentagon and a circle are one picture. */
	public static int AURA_PARTICLE_SEGMENTS = 5;
	/** Particles below this drawn size, px, are skipped rather than emitted as sub-pixel triangles. */
	public static double AURA_PARTICLE_MIN_SIZE_PX = 0.35;
	/** Hard cap on live particles for one soul. */
	public static int AURA_PARTICLE_MAX_PER_SOUL = 260;
	/**
	 * Live particles across every soul on screen. Exceeding it throttles the spawn rate of all fires
	 * in proportion rather than cutting some of them off, so a crowded room thins out evenly instead
	 * of picking winners.
	 */
	public static int AURA_PARTICLE_BUDGET = 4200;

	/** Drawn radius below which a reading is too small to bother hazing, px. */
	public static double AURA_EMBER_RADIUS_PX = 2.0;
	/** Alpha of the ambient haze around the strongest reading on screen, 0..1. */
	public static double AURA_HAZE_ALPHA = 0.12;
	/** Haze size as a multiple of the reading's drawn radius. */
	public static double AURA_HAZE_SCALE = 3.0;
	/** Fraction of the gap to the newest reported position an aura closes each second, 0..1. */
	public static double AURA_SMOOTHING_PER_SECOND = 0.9995;
	/** Seconds an aura keeps being drawn, fading, after it drops out of the packet. */
	public static double AURA_FADE_SECONDS = 0.35;
	/** Jump beyond which an aura is snapped rather than smoothed, blocks — a teleport is not motion. */
	public static double AURA_SNAP_DISTANCE = 8.0;

	// ================================================================================
	// O. Hover · BALANCE.md §O
	// ================================================================================

	/** Drain at SL 1, SP per second. */
	public static double HOVER_DRAIN_BASE = 4.0;
	/** Drain reduction per level above 1, SP per second. At the SL 20 cap this is 1.15/s. */
	public static double HOVER_DRAIN_PER_LEVEL = 0.15;

	/**
	 * Top speed while hovering, blocks per tick.
	 *
	 * <p>Deliberately under a sprint (0.28/tick): hovering is a way to survive the air a Flash Step
	 * left you in, not a faster way to cross the map than running.
	 */
	public static double HOVER_SPEED = 0.22;

	/**
	 * Fraction of the gap to the target velocity closed each tick, 0..1.
	 *
	 * <p>This is the whole feel of the ability. At 1.0 the player is a cursor — instant starts and
	 * dead stops; at 0.1 they are a balloon that drifts past everything they aim at. The value below
	 * settles a direction change in about five ticks, which reads as weight without reading as ice.
	 */
	public static double HOVER_RESPONSE = 0.45;

	/** Velocity below which the hover is snapped to a dead stop, blocks per tick. */
	public static double HOVER_REST_EPSILON = 0.002;

	/** Ticks between the pressure particles trailing a hovering player. */
	public static int HOVER_PARTICLE_INTERVAL_TICKS = 4;
	/** Particles per burst under a hovering player. */
	public static int HOVER_PARTICLE_COUNT = 3;
	/** Particle scale. */
	public static double HOVER_PARTICLE_SCALE = 0.5;
	/** Scatter around the player's feet, blocks. */
	public static double HOVER_PARTICLE_SPREAD = 0.35;
	/** Downward velocity of a hover particle, blocks per tick — the pressure holding them up. */
	public static double HOVER_PARTICLE_FALL = 0.35;

	// ================================================================================
	// D.1 Mob base SPX table
	// ================================================================================

	/** Per-entity-type base SPX, config-overridable. Contents mutable, reference is not. */
	public static final Map<ResourceLocation, Integer> MOB_SPX = new LinkedHashMap<>();

	private static final Map<ResourceLocation, Integer> MOB_SPX_DEFAULTS = defaultMobSpx();

	private static Map<ResourceLocation, Integer> defaultMobSpx() {
		Map<ResourceLocation, Integer> m = new LinkedHashMap<>();
		for (String id : new String[] { "zombie", "skeleton", "spider", "husk", "stray", "drowned" }) {
			m.put(ResourceLocation.withDefaultNamespace(id), 1);
		}
		for (String id : new String[] { "creeper", "enderman", "witch" }) {
			m.put(ResourceLocation.withDefaultNamespace(id), 2);
		}
		for (String id : new String[] { "wither_skeleton", "blaze", "piglin_brute", "evoker" }) {
			m.put(ResourceLocation.withDefaultNamespace(id), 5);
		}
		for (String id : new String[] { "iron_golem", "ravager", "elder_guardian" }) {
			m.put(ResourceLocation.withDefaultNamespace(id), 8);
		}
		for (String id : new String[] { "wither", "ender_dragon", "warden" }) {
			m.put(ResourceLocation.withDefaultNamespace(id), 100);
		}
		return m;
	}

	/** Base SPX for an entity type, falling back to {@link #SPX_DEFAULT_MOB}. */
	public static int mobSpx(EntityType<?> type) {
		return MOB_SPX.getOrDefault(BuiltInRegistries.ENTITY_TYPE.getKey(type), SPX_DEFAULT_MOB);
	}

	// ================================================================================
	// Config IO
	//
	// Plain Gson over the tunable fields by reflection. Deliberately not a codec layer:
	// this file is dev-facing, is never synced, and never reaches the client.
	// ================================================================================

	private static final String CONFIG_DIR = "bleach_mod";
	private static final String CONFIG_FILE = "tuning.json";
	private static final String DEFAULTS_FILE = "tuning-defaults.json";
	private static final String MOB_SPX_KEY = "MOB_SPX";
	private static final String CONFIG_VERSION_KEY = "CONFIG_VERSION";
	private static final String COMMENT_KEY = "_comment";

	private static final String FILE_COMMENT =
			"Only values you have changed are stored here. Anything absent follows the mod's own "
					+ "default and updates automatically when the mod does. See tuning-defaults.json "
					+ "for the full list of keys and their current defaults — that file is regenerated "
					+ "every launch and editing it does nothing.";

	/**
	 * Format stamp for {@code tuning.json}. <b>Bump this only when the shape of the file changes</b>
	 * — not when a default changes.
	 *
	 * <p>That distinction is the whole point of the v5 format, and it is worth being precise about
	 * why, because v1–v4 got it wrong in an expensive way. Those versions wrote a full dump of every
	 * key on every save, which made an override and an untouched default byte-identical on disk:
	 * both are just a number. So a file written by an older build overrode the entire new build,
	 * silently, on an install whose jar was completely up to date. That is how a client kept running
	 * {@code HUD_BOTTOM_OFFSET = 49} and {@code HUD_Z_DEPTH = 300} against a jar whose defaults were
	 * {@code 0} and {@code 0.0} — the bar pinned into the row the health hearts had just been lifted
	 * into, and the derived layout that would have prevented it never ran.
	 *
	 * <p>The only fix available then was to bump this number and wipe the file, which cost every
	 * player their tuning to deliver one changed default. From v5 the file stores <b>overrides
	 * only</b>: a key that is absent takes the compiled-in default, so a new default reaches every
	 * install that had not deliberately overridden it, and reaches it without touching the keys that
	 * had. Changing a default is now a code change and nothing else.
	 *
	 * <p>What still warrants a bump: renaming or re-typing a key, changing how a value is encoded,
	 * or anything else that makes an existing file mean something different than it says. A mismatch
	 * triggers the one-time conversion in {@link #convertLegacyFile}, not a reset.
	 */
	private static final int CURRENT_CONFIG_VERSION = 5;

	/**
	 * HTML escaping off deliberately. Gson defaults it on, which is right for a value going into a
	 * web page and wrong for a file a human opens in an editor — it turned the apostrophe in the
	 * header note into {@code '}. Nothing here is ever rendered as HTML.
	 */
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	/**
	 * Snapshot of the compiled-in defaults, captured before any config is read so a reload can
	 * restore a value whose key was deleted from the file. Declared last on purpose — static
	 * initialisation runs top to bottom, so every field above is already assigned.
	 */
	private static final Map<String, Object> DEFAULTS = captureDefaults();

	private static final List<Runnable> RELOAD_LISTENERS = new ArrayList<>();

	/** Register a callback fired after every successful load or reload. */
	public static void onReload(Runnable listener) {
		RELOAD_LISTENERS.add(listener);
	}

	private static List<Field> tunableFields() {
		List<Field> out = new ArrayList<>();
		for (Field f : BleachTuning.class.getDeclaredFields()) {
			int mods = f.getModifiers();
			if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
				continue;
			}
			Class<?> t = f.getType();
			if (t == double.class || t == int.class || t == boolean.class || t == double[].class) {
				out.add(f);
			}
		}
		return out;
	}

	private static Map<String, Object> captureDefaults() {
		Map<String, Object> out = new LinkedHashMap<>();
		try {
			for (Field f : tunableFields()) {
				Object v = f.get(null);
				out.put(f.getName(), v instanceof double[] arr ? arr.clone() : v);
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Failed to snapshot BleachTuning defaults", e);
		}
		return out;
	}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_DIR).resolve(CONFIG_FILE);
	}

	private static void resetToDefaults() {
		try {
			for (Field f : tunableFields()) {
				Object v = DEFAULTS.get(f.getName());
				f.set(null, v instanceof double[] arr ? arr.clone() : v);
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Failed to reset BleachTuning to defaults", e);
		}
		MOB_SPX.clear();
		MOB_SPX.putAll(MOB_SPX_DEFAULTS);
	}

	/**
	 * Reset to compiled-in defaults, then overlay whatever the config file overrides.
	 *
	 * <p>A missing file is created holding nothing but its version stamp and a note — an empty
	 * override set is the correct representation of "I have changed nothing", and it means a fresh
	 * install tracks the mod's defaults forever without anyone having to maintain it.
	 *
	 * <p>Unknown keys are warned about and dropped on the next write; absent keys silently keep
	 * their default, which is no longer a fallback but the ordinary case.
	 */
	public static void load() {
		resetToDefaults();

		Path path = configPath();
		if (!Files.exists(path)) {
			save();
			BleachMod.LOGGER.info("No tuning.json found — wrote a fresh one at {}", path);
		} else {
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				JsonElement root = JsonParser.parseReader(reader);
				if (root != null && root.isJsonObject()) {
					JsonObject json = root.getAsJsonObject();

					int fileVersion = json.has(CONFIG_VERSION_KEY) && json.get(CONFIG_VERSION_KEY).isJsonPrimitive()
							? json.get(CONFIG_VERSION_KEY).getAsInt()
							: 0;

					if (fileVersion != CURRENT_CONFIG_VERSION) {
						convertLegacyFile(path, json, fileVersion);
					} else {
						overlay(json);
						BleachMod.LOGGER.info("Loaded tuning from {}", path);

						// Rewrite when what is on disk is not what we would write now: an unknown key
						// to drop, or an override that has drifted back to matching the default and is
						// only noise. Comparing the canonical form covers both without a special case
						// for either, and a file that is already canonical is left untouched.
						JsonObject canonical = buildOverrides();
						if (sawUnknownKeys || !canonical.equals(json)) {
							save();
							BleachMod.LOGGER.info("Tidied {} to the current key set", path);
						}
					}
				} else {
					BleachMod.LOGGER.warn("tuning.json is not a JSON object — keeping defaults");
				}
			} catch (IOException | RuntimeException e) {
				BleachMod.LOGGER.error("Failed to read tuning.json — keeping defaults", e);
			}
		}

		saveDefaultsReference();

		for (Runnable listener : RELOAD_LISTENERS) {
			listener.run();
		}
	}

	/**
	 * One-time upgrade of a file written before the overrides-only format.
	 *
	 * <p>v1–v4 dumped every key, so the file cannot say which values were chosen and which were
	 * merely written down. The one signal available is comparison against the current defaults: a
	 * value that differs from the default is something somebody typed, and a value that matches it
	 * is the old dump. That reading is exact for every key whose default has not moved, which is
	 * nearly all of them, and it is what lets an upgrade keep a hand-tuned install intact instead of
	 * flattening it the way a version bump used to.
	 *
	 * <p>The one case it cannot resolve is a key whose default changed in the same build doing the
	 * conversion: the old dumped value differs from the new default, so it is preserved as though it
	 * had been chosen. That is the conservative direction — it keeps a value rather than discarding
	 * one — and it is why the original file is kept as {@code tuning.json.bak}. Deleting the key
	 * from the converted file picks the new default up. It happens once, and never again, because
	 * from here on the file only ever contains things somebody actually set.
	 */
	private static void convertLegacyFile(Path path, JsonObject json, int fileVersion) {
		overlay(json);

		// Counted off the canonical form rather than by walking the fields, so the number reported
		// is exactly what lands in the file — MOB_SPX entries included, which a field walk misses.
		JsonObject converted = buildOverrides();
		int kept = converted.size() - 2; // less CONFIG_VERSION and the header note
		if (converted.has(MOB_SPX_KEY)) {
			kept += converted.getAsJsonObject(MOB_SPX_KEY).size() - 1;
		}

		backUp(path);
		// save() writes only what now differs from the defaults, so the filtering is the write.
		save();

		BleachMod.LOGGER.warn(
				"tuning.json at {} used the old v{} full-dump format — converted to v{} "
						+ "(overrides only). Kept {} changed value(s); everything else now follows the "
						+ "mod's defaults and updates with it. Original kept as {}.",
				path, fileVersion, CURRENT_CONFIG_VERSION, kept, CONFIG_FILE + ".bak");
	}

	/** Set by {@link #overlay} when the file carried a key we no longer define. */
	private static boolean sawUnknownKeys;

	/**
	 * Move the existing config aside before overwriting it, so a version reset is recoverable.
	 * Failure to back up is not failure to reset — the reset is the point, the copy is a courtesy.
	 */
	private static void backUp(Path path) {
		try {
			Files.copy(path, path.resolveSibling(CONFIG_FILE + ".bak"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			BleachMod.LOGGER.warn("Could not back up {} before resetting it", path, e);
		}
	}

	/**
	 * Whether a field still holds the value it was compiled with.
	 *
	 * <p>The single question the whole overrides-only format rests on. {@code double[]} needs
	 * {@link Arrays#equals} rather than {@code equals} — the reference comparison an {@code Object}
	 * overload would do is false for every array, which would make every array-valued knob look
	 * permanently overridden and pin it to the value in the file forever.
	 */
	private static boolean isDefaultValue(Field f) {
		try {
			Object current = f.get(null);
			Object original = DEFAULTS.get(f.getName());
			if (current instanceof double[] a && original instanceof double[] b) {
				return Arrays.equals(a, b);
			}
			return current != null && current.equals(original);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Failed to read BleachTuning field " + f.getName(), e);
		}
	}

	private static void overlay(JsonObject json) {
		sawUnknownKeys = false;

		Map<String, Field> byName = new HashMap<>();
		for (Field f : tunableFields()) {
			byName.put(f.getName(), f);
		}

		for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
			String key = entry.getKey();
			JsonElement value = entry.getValue();

			if (MOB_SPX_KEY.equals(key)) {
				overlayMobSpx(value);
				continue;
			}

			if (CONFIG_VERSION_KEY.equals(key) || key.startsWith("_")) {
				// Bookkeeping and the human-readable note, not tunables. Skipped by prefix rather
				// than by name so a future annotation cannot start reporting itself as an unknown
				// key — and, worse, tripping the rewrite that drops unknown keys on every launch.
				continue;
			}

			Field field = byName.get(key);
			if (field == null) {
				BleachMod.LOGGER.warn("Unknown tuning key '{}' — ignored", key);
				sawUnknownKeys = true;
				continue;
			}

			try {
				applyValue(field, value);
			} catch (RuntimeException | IllegalAccessException e) {
				BleachMod.LOGGER.warn("Tuning key '{}' has an unusable value ({}) — keeping default {}",
						key, value, DEFAULTS.get(key));
			}
		}
	}

	private static void applyValue(Field field, JsonElement value) throws IllegalAccessException {
		Class<?> type = field.getType();
		if (type == double.class) {
			field.setDouble(null, value.getAsDouble());
		} else if (type == int.class) {
			field.setInt(null, value.getAsInt());
		} else if (type == boolean.class) {
			field.setBoolean(null, value.getAsBoolean());
		} else if (type == double[].class) {
			JsonArray array = value.getAsJsonArray();
			double[] out = new double[array.size()];
			for (int i = 0; i < out.length; i++) {
				out[i] = array.get(i).getAsDouble();
			}
			field.set(null, out);
		}
	}

	private static void overlayMobSpx(JsonElement value) {
		if (!value.isJsonObject()) {
			BleachMod.LOGGER.warn("Tuning key '{}' is not an object — keeping defaults", MOB_SPX_KEY);
			return;
		}
		for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
			ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
			if (id == null) {
				BleachMod.LOGGER.warn("MOB_SPX key '{}' is not a valid entity id — ignored", entry.getKey());
				continue;
			}
			try {
				MOB_SPX.put(id, entry.getValue().getAsInt());
			} catch (RuntimeException e) {
				BleachMod.LOGGER.warn("MOB_SPX value for '{}' is not an integer — ignored", entry.getKey());
			}
		}
	}

	/**
	 * Write the overrides back out, creating the directory if needed.
	 *
	 * <p>Only values that differ from the compiled-in defaults are written. That is what makes a
	 * default change propagate on its own: a key nobody set is not in the file, so there is nothing
	 * on disk to override it with next launch.
	 */
	public static void save() {
		JsonObject json = buildOverrides();

		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(json, writer);
			}
		} catch (IOException e) {
			BleachMod.LOGGER.error("Failed to write tuning.json to {}", path, e);
		}
	}

	/**
	 * The canonical on-disk form of the current values: the version stamp, the note, and every knob
	 * that differs from its default.
	 *
	 * <p>Built rather than written directly so {@link #load()} can compare it against the file it
	 * just read and rewrite only when the two actually disagree. Without that, every launch would
	 * rewrite the file whether or not anything had changed.
	 */
	private static JsonObject buildOverrides() {
		JsonObject json = new JsonObject();
		json.addProperty(CONFIG_VERSION_KEY, CURRENT_CONFIG_VERSION);
		json.addProperty(COMMENT_KEY, FILE_COMMENT);

		try {
			for (Field f : tunableFields()) {
				if (isDefaultValue(f)) {
					continue;
				}
				writeField(json, f);
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("Failed to serialise BleachTuning", e);
		}

		// Per-entry rather than all-or-nothing: overriding one mob's SPX should not freeze the
		// other eighteen against future changes, which is the same argument as for the scalars.
		JsonObject mobSpx = new JsonObject();
		for (Map.Entry<ResourceLocation, Integer> entry : MOB_SPX.entrySet()) {
			if (!entry.getValue().equals(MOB_SPX_DEFAULTS.get(entry.getKey()))) {
				mobSpx.addProperty(entry.getKey().toString(), entry.getValue());
			}
		}
		if (!mobSpx.isEmpty()) {
			json.add(MOB_SPX_KEY, mobSpx);
		}

		return json;
	}

	private static void writeField(JsonObject json, Field f) throws IllegalAccessException {
		Class<?> type = f.getType();
		if (type == double.class) {
			json.addProperty(f.getName(), f.getDouble(null));
		} else if (type == int.class) {
			json.addProperty(f.getName(), f.getInt(null));
		} else if (type == boolean.class) {
			json.addProperty(f.getName(), f.getBoolean(null));
		} else if (type == double[].class) {
			JsonArray array = new JsonArray();
			for (double d : (double[]) f.get(null)) {
				array.add(d);
			}
			json.add(f.getName(), array);
		}
	}

	/**
	 * Rewrite {@code tuning-defaults.json} — every key the mod defines, at the value this build
	 * ships, regenerated on every load.
	 *
	 * <p>The overrides-only format has one genuine cost: a knob nobody has touched is invisible, and
	 * a constant nobody can see is a constant nobody tunes. This is the answer to that. It is a
	 * reference listing, not an input — nothing ever reads it back, and the header says so, because
	 * a file that looks editable and silently is not would be worse than no file at all.
	 *
	 * <p>Written from {@link #DEFAULTS} rather than from the live fields, so it shows what the mod
	 * ships with even while the running values are overridden.
	 */
	private static void saveDefaultsReference() {
		JsonObject json = new JsonObject();
		json.addProperty(COMMENT_KEY,
				"Generated reference — every tuning key and the default this build ships with. "
						+ "Rewritten on every launch; edits here are ignored. To change a value, put "
						+ "it in " + CONFIG_FILE + " instead.");
		json.addProperty(CONFIG_VERSION_KEY, CURRENT_CONFIG_VERSION);

		for (Map.Entry<String, Object> entry : DEFAULTS.entrySet()) {
			Object v = entry.getValue();
			if (v instanceof double[] arr) {
				JsonArray array = new JsonArray();
				for (double d : arr) {
					array.add(d);
				}
				json.add(entry.getKey(), array);
			} else if (v instanceof Number n) {
				json.addProperty(entry.getKey(), n);
			} else if (v instanceof Boolean b) {
				json.addProperty(entry.getKey(), b);
			}
		}

		JsonObject mobSpx = new JsonObject();
		for (Map.Entry<ResourceLocation, Integer> entry : MOB_SPX_DEFAULTS.entrySet()) {
			mobSpx.addProperty(entry.getKey().toString(), entry.getValue());
		}
		json.add(MOB_SPX_KEY, mobSpx);

		Path path = configPath().resolveSibling(DEFAULTS_FILE);
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				GSON.toJson(json, writer);
			}
		} catch (IOException e) {
			BleachMod.LOGGER.warn("Could not write {} — the reference listing is a courtesy, not a "
					+ "requirement, so tuning is unaffected", path, e);
		}
	}
}
