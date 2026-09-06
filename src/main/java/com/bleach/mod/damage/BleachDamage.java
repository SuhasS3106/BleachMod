package com.bleach.mod.damage;

import org.jetbrains.annotations.Nullable;

import com.bleach.mod.BleachMod;
import com.bleach.mod.item.Zanpakuto;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * What counts as <em>bleach</em> damage · PRD §2.4.
 *
 * <p>The distinction is the whole reason Soul Level scaling does not trivialise the game: damage
 * <b>dealt</b> scales for bleach sources only, so a bow is a bow at every level, while damage
 * <b>taken</b> scales for everything because mobs scale with the world.
 *
 * <p>Two things are bleach damage:
 *
 * <ul>
 *   <li><b>Zanpakutō melee.</b> Not a custom damage type — a sword hit is {@code player_attack} and
 *       has to stay that way, or enchantments, advancements and death messages all stop matching
 *       vanilla. It is recognised structurally instead: the direct entity is a player and the blade
 *       is in their hand.</li>
 *   <li><b>Anything tagged {@link #BLEACH}.</b> Abilities that deal damage build a source from one
 *       of the mod's own damage types, and the tag is what the scaling checks — so Phases 6–11 add
 *       a JSON entry rather than a branch here.</li>
 * </ul>
 *
 * <p>{@link #SPIRIT_MECHANIC} is the opposite flag and deliberately narrow. Sui-Feng's two-strike
 * kill and her Bankai's inner radius are <em>mechanics</em>, not damage (PRD §2.4): letting a capped
 * player shrug them off with −42% bleach resistance deletes the character. Sources in that tag skip
 * the Soul Level reductions entirely. <b>Special-cased by source, never by magnitude</b> — a
 * threshold test on "damage this large must be a mechanic" would also catch a genuine Bankai combo.
 */
public final class BleachDamage {
	private BleachDamage() {
	}

	/** Sources that scale with Soul Level, both dealt and taken. */
	public static final TagKey<DamageType> BLEACH =
			TagKey.create(Registries.DAMAGE_TYPE, BleachMod.id("bleach"));

	/** Sources that bypass Soul Level damage reduction. See the class note. */
	public static final TagKey<DamageType> SPIRIT_MECHANIC =
			TagKey.create(Registries.DAMAGE_TYPE, BleachMod.id("spirit_mechanic"));

	/** The generic ability damage type. In {@link #BLEACH}. */
	public static final ResourceKey<DamageType> SPIRIT_PRESSURE =
			ResourceKey.create(Registries.DAMAGE_TYPE, BleachMod.id("spirit_pressure"));

	/**
	 * The Spiritual Flex bleed · {@code BALANCE.md} §H.5. Identical to {@link #SPIRIT_PRESSURE} —
	 * same tag, same scaling, same death message — except that it is in
	 * {@code minecraft:no_knockback}.
	 *
	 * <p>A separate type rather than a flag on the shove, because knockback lives in the damage
	 * type and vanilla reads it from inside {@code LivingEntity#hurt}. Pressure is a field you are
	 * standing in, not a blow: shoving the victim a little further away every second walked them
	 * out of the aura that was hurting them, so the field pushed away the very thing it was
	 * supposed to be pinning down.
	 *
	 * <p>Deliberately not applied to {@link #SPIRIT_PRESSURE} itself, which is shared by Suì-Fēng's
	 * blast, Yamamoto's cone, Ichigo's cleave and Gin's beam — all of them impacts, all of them
	 * wanting the shove they currently get.
	 */
	public static final ResourceKey<DamageType> SPIRIT_PRESSURE_BLEED =
			ResourceKey.create(Registries.DAMAGE_TYPE, BleachMod.id("spirit_pressure_bleed"));

	/** The unavoidable-kill type. In {@link #SPIRIT_MECHANIC}, and deliberately <em>not</em> in
	 * {@link #BLEACH} — it is not scaled up by the attacker's level either. */
	public static final ResourceKey<DamageType> SPIRIT_MECHANIC_KILL =
			ResourceKey.create(Registries.DAMAGE_TYPE, BleachMod.id("spirit_mechanic_kill"));

	/** Whether Soul Level scaling applies to this source at all. */
	public static boolean is(DamageSource source) {
		if (source.is(BLEACH)) {
			return true;
		}
		return source.getDirectEntity() instanceof Player player && Zanpakuto.isDrawn(player);
	}

	/** Whether this source ignores the victim's Soul Level reductions. */
	public static boolean isMechanic(DamageSource source) {
		return source.is(SPIRIT_MECHANIC);
	}

	/**
	 * Build one of the mod's damage sources. Damage types are registry data, so this needs a level
	 * to reach the registry — every caller has one.
	 */
	public static DamageSource source(Level level, ResourceKey<DamageType> type, @Nullable Entity attacker) {
		return new DamageSource(
				level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type),
				attacker);
	}
}
