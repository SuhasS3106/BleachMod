package com.bleach.mod.effect;

import java.util.function.BiConsumer;

import com.bleach.mod.BleachMod;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * The mod's one debuff · PRD §5.1 · {@code BALANCE.md} §H.1–H.2.
 *
 * <p><b>No vanilla status effect name ever reaches the player.</b> There is exactly one registered
 * effect and its strength is its amplifier, so the inventory panel reads "Reiatsu III" and nothing
 * else. Stacking four vanilla debuffs would have been less code and would have told every player
 * exactly which ones.
 *
 * <p>The one exception is Nausea at the crushing tier, which has no attribute equivalent. It is
 * applied hidden and still lists by name in the inventory panel — accepted, and tracked as PRD §11
 * item 2.
 *
 * <h2>Why the modifiers are not registered with {@code addAttributeModifier}</h2>
 *
 * <p>The plan's sketch overrides {@code getAttributeModifierValue(int, AttributeModifier)}, which is
 * how this worked before 1.21. <b>That method does not exist in 1.21.1.</b> A registered modifier is
 * now a {@code MobEffect.AttributeTemplate} record whose amount is fixed at construction and scaled
 * linearly by {@code amplifier + 1} — which is exactly the linear scaling {@code BALANCE.md} §H.2
 * says not to use, and which would additionally bake a tuning value into a field that
 * {@code /bleach reload} could never move.
 *
 * <p>So this effect registers nothing and owns all three modifiers itself, overriding the three
 * methods vanilla routes through: {@link #createModifiers}, {@link #addAttributeModifiers} and
 * {@link #removeAttributeModifiers}. Values are read from {@link BleachTuning} at apply time, so a
 * reload lands on the next refresh — which, at a 3-second effect refreshed every tick, is instant.
 */
public final class ReiatsuEffect extends MobEffect {

	/** Returned by {@link #amplifierFor} when the level gap is below the first tier. */
	public static final int NO_TIER = -1;

	// ResourceLocation-keyed so removal is exact even when several sources touch the same attribute.
	private static final ResourceLocation MOVEMENT_MODIFIER = BleachMod.id("reiatsu_movement_speed");
	private static final ResourceLocation ATTACK_SPEED_MODIFIER = BleachMod.id("reiatsu_attack_speed");
	private static final ResourceLocation ATTACK_DAMAGE_MODIFIER = BleachMod.id("reiatsu_attack_damage");

	/**
	 * A movement multiplier at or below this is a root rather than a slow.
	 *
	 * <p>Derived rather than "amplifier 3" spelled out, so retuning
	 * {@link BleachTuning#REIATSU_SPEED_MULT} moves the rooted tier with it instead of leaving the
	 * table and the mixin disagreeing about which one it is.
	 */
	private static final double ROOT_MULTIPLIER = -1.0;

	public ReiatsuEffect() {
		super(MobEffectCategory.HARMFUL, BleachTuning.REIATSU_EFFECT_COLOR);
	}

	// --- Tiers -------------------------------------------------------------------------

	/**
	 * {@code BALANCE.md} §H.1. {@code gap = flexerSL − targetSL − counterReduction}; mobs count as
	 * Soul Level 0 and cannot counter.
	 *
	 * @return the amplifier 0–3, or {@link #NO_TIER} when the gap buys nothing
	 */
	public static int amplifierFor(int gap) {
		if (gap >= BleachTuning.REIATSU_TIER_4_GAP) {
			return 3;
		}
		if (gap >= BleachTuning.REIATSU_TIER_3_GAP) {
			return 2;
		}
		if (gap >= BleachTuning.REIATSU_TIER_2_GAP) {
			return 1;
		}
		if (gap >= BleachTuning.REIATSU_TIER_1_GAP) {
			return 0;
		}
		return NO_TIER;
	}

	/**
	 * The top tier: rooted, and the one that also carries hidden Nausea. Both extras hang off the
	 * same predicate so they can never come apart.
	 */
	public static boolean isCrushing(int amplifier) {
		return amplifier >= 0 && strength(BleachTuning.REIATSU_SPEED_MULT, amplifier) <= ROOT_MULTIPLIER;
	}

	/**
	 * Whether this entity is currently rooted by Reiatsu. Read by
	 * {@code LivingEntityTravelMixin} — a {@code −1.0} movement multiplier stops walking but still
	 * permits jumping and knockback drift, so the travel vector has to be zeroed as well.
	 */
	public static boolean isRooted(LivingEntity entity) {
		Holder<MobEffect> reiatsu = BleachEffects.REIATSU;
		if (reiatsu == null) {
			return false;
		}
		MobEffectInstance instance = entity.getEffect(reiatsu);
		return instance != null && isCrushing(instance.getAmplifier());
	}

	/**
	 * Whether this entity's special actions are shut off by the pressure it is standing in ·
	 * {@code BALANCE.md} §H.4.
	 *
	 * <p>"Special" means everything that is not a swing: every projectile and conjured entity it
	 * would spawn ({@code ConjuredEntityMixin}), the creeper's fuse ({@code CreeperSwellMixin}), the
	 * enderman's blink ({@code EnderManTeleportMixin}) and any damage it deals that is not a melee
	 * hit ({@code LivingEntityDamageMixin}). Four call sites, one predicate, so a mob cannot end up
	 * silenced in three of them and shooting in the fourth.
	 *
	 * <p>Players are excluded by default and the reason is in {@link BleachTuning#REIATSU_SILENCE_PLAYERS}:
	 * a field they can push back against is counterplay, and one that also disarms them is not.
	 */
	public static boolean isSilenced(LivingEntity entity) {
		if (!BleachTuning.REIATSU_SILENCE_MOBS) {
			return false;
		}
		if (entity instanceof Player && !BleachTuning.REIATSU_SILENCE_PLAYERS) {
			return false;
		}

		Holder<MobEffect> reiatsu = BleachEffects.REIATSU;
		if (reiatsu == null) {
			return false;
		}
		MobEffectInstance instance = entity.getEffect(reiatsu);
		return instance != null && instance.getAmplifier() >= BleachTuning.REIATSU_SILENCE_MIN_AMP;
	}

	/**
	 * Health lost per damage tick for a given level gap · {@code BALANCE.md} §H.5, or {@code 0} when
	 * the gap does not reach {@link BleachTuning#REIATSU_DAMAGE_MIN_GAP}.
	 *
	 * <p>The gap passed in is the one the tier was bought with — <em>after</em> any counter reduction
	 * — so pushing back lowers the bleed by the same levels it lowers the tier by, and a field two
	 * levels above you eventually kills you where a peer's never can.
	 */
	public static double damageFor(int gap) {
		if (gap < BleachTuning.REIATSU_DAMAGE_MIN_GAP) {
			return 0.0;
		}
		double damage = BleachTuning.REIATSU_DAMAGE_BASE
				+ BleachTuning.REIATSU_DAMAGE_PER_GAP * (gap - BleachTuning.REIATSU_DAMAGE_MIN_GAP);
		return Math.min(BleachTuning.REIATSU_DAMAGE_MAX, Math.max(0.0, damage));
	}

	/**
	 * Index a {@code BALANCE.md} §H.2 row by amplifier, clamped.
	 *
	 * <p>Indexed, never multiplied: the table is not linear — attack damage is untouched at the first
	 * two tiers and attack speed does not move between the third and the fourth.
	 */
	public static double strength(double[] byAmplifier, int amplifier) {
		if (byAmplifier.length == 0) {
			return 0.0;
		}
		return byAmplifier[Mth.clamp(amplifier, 0, byAmplifier.length - 1)];
	}

	// --- Attribute modifiers ------------------------------------------------------------

	@Override
	public void createModifiers(int amplifier, BiConsumer<Holder<Attribute>, AttributeModifier> output) {
		output.accept(Attributes.MOVEMENT_SPEED,
				modifier(MOVEMENT_MODIFIER, BleachTuning.REIATSU_SPEED_MULT, amplifier));
		output.accept(Attributes.ATTACK_SPEED,
				modifier(ATTACK_SPEED_MODIFIER, BleachTuning.REIATSU_ATK_SPEED_MULT, amplifier));
		output.accept(Attributes.ATTACK_DAMAGE,
				modifier(ATTACK_DAMAGE_MODIFIER, BleachTuning.REIATSU_ATK_DMG_MULT, amplifier));
	}

	@Override
	public void addAttributeModifiers(AttributeMap attributes, int amplifier) {
		createModifiers(amplifier, (attribute, modifier) -> {
			AttributeInstance instance = attributes.getInstance(attribute);
			if (instance != null) {
				// Remove first: the modifiers are permanent, so re-adding over a live one would
				// otherwise throw, and a tier change re-applies rather than replaces.
				instance.removeModifier(modifier.id());
				instance.addPermanentModifier(modifier);
			}
		});
	}

	@Override
	public void removeAttributeModifiers(AttributeMap attributes) {
		// The amplifier is irrelevant here — only the ids are, and those do not vary by tier.
		createModifiers(0, (attribute, modifier) -> {
			AttributeInstance instance = attributes.getInstance(attribute);
			if (instance != null) {
				instance.removeModifier(modifier.id());
			}
		});
	}

	private static AttributeModifier modifier(ResourceLocation id, double[] byAmplifier, int amplifier) {
		return new AttributeModifier(id, strength(byAmplifier, amplifier),
				AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
	}
}
