package com.bleach.mod.progression;

import com.bleach.mod.ability.AbilityDispatcher;
import com.bleach.mod.ability.kits.Doses;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.ability.common.Blut;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.item.SpiritWeapon;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * The four Soul Level damage multipliers · PRD §2.4–2.5, applied in the order below.
 *
 * <p>Applied <b>after</b> vanilla armour and resistance, never before. Folding a Soul Level bonus
 * in ahead of armour means armour eats it, and the documented "+38% bleach damage at SL 20" becomes
 * whatever is left after a diamond chestplate.
 */
public final class DamageScaling {
	private DamageScaling() {
	}

	/**
	 * @param victim the entity being hit
	 * @param source what is hitting it
	 * @param amount damage after armour and vanilla resistance
	 * @return damage after the Soul Level multipliers
	 */
	public static float apply(LivingEntity victim, DamageSource source, float amount) {
		if (amount <= 0.0f) {
			return amount;
		}

		double damage = amount;
		boolean bleach = BleachDamage.is(source);

		// Sui-Feng's two-strike kill and her Bankai's inner radius are mechanics, not damage. A
		// capped player shrugging them off with −42% deletes the character · PRD §2.4.
		boolean scalable = !BleachDamage.isMechanic(source);

		if (victim instanceof ServerPlayer victimPlayer && scalable) {
			int level = BleachAttachments.get(victimPlayer).soulLevel;

			// 1. General defense. Applies to everything, drawn or sheathed — mobs get stronger as the
			// world advances, so general defense has to advance with it.
			damage *= reduction(BleachTuning.SL_GENERAL_DMG_TAKEN_PER_LEVEL, level);

			// 2. Bleach resistance, on top of general. The two combine to −42% at SL 20.
			if (bleach) {
				damage *= reduction(BleachTuning.SL_BLEACH_DMG_TAKEN_PER_LEVEL, level);
			}

			// 2b. Blut Vene · design §5.4. 1.21.1 has no damage-taken attribute, so it lands here with
			// the Soul Level reductions rather than being scattered. It is deliberately not gated on
			// `bleach`: hardened blood stops a skeleton's arrow as well as a zanpakutō.
			damage *= Blut.damageTakenMultiplier(BleachAttachments.get(victimPlayer).blut);
		}

		// 2c. Schrift D's doses · design §P.6. A dosed target takes more from everything, from
		// anyone — the dose is a property of the victim, not of who is hitting it, which is what
		// makes The Deathdealing a setup power rather than a personal damage buff. It sits outside
		// the ServerPlayer block above because doses apply to mobs too, and mobs carry no
		// SpiritualData.
		damage *= Doses.damageTakenMultiplier(victim);

		// 3. Bleach damage dealt. Never applies to vanilla weapons — a bow is a bow at every level,
		// which is what keeps gear relevant · PRD §2.4.
		if (bleach) {
			ServerPlayer attacker = KillAttribution.attackerOf(source);
			if (attacker != null && SpiritWeapon.isDrawn(attacker)) {
				SpiritualData attackerData = BleachAttachments.get(attacker);
				int level = attackerData.soulLevel;
				damage *= 1.0 + BleachTuning.SL_BLEACH_DMG_DEALT_PER_LEVEL * (level - 1);

				// Transformation bleach melee bonus (Ichigo Shikai +25%, Bankai +40%, Yamamoto Bankai +30%).
				// Applies to direct melee strikes (source.getDirectEntity() == attacker), not projectiles.
				if (source.getDirectEntity() == attacker && attackerData.isTransformed()) {
					TransformAbility active = AbilityDispatcher.activeTransform(attackerData);
					if (active != null) {
						damage *= 1.0 + active.meleeDamageBonus();
					}
				}

				// Blut Arterie · design §5.4. Unlike the melee bonus above this covers projectiles too,
				// which is the whole point for a ranged race.
				damage *= Blut.damageDealtMultiplier(attackerData.blut);
			}
		}

		// 4. World scaling of mobs. Players are not Mobs, so PvP never lands here.
		if (victim instanceof ServerPlayer victimPlayer && source.getEntity() instanceof Mob) {
			double wsl = WorldSoulLevel.get(victimPlayer.server).value();
			damage *= SoulLevel.mobScalar(wsl, BleachAttachments.get(victimPlayer).soulLevel);
		}

		return (float) damage;
	}

	/**
	 * A per-level reduction, floored at zero.
	 *
	 * <p>The floor is not reachable at any shipped tuning — {@code SL_GENERAL_DMG_TAKEN_PER_LEVEL}
	 * would have to exceed 0.0526 to zero out at SL 20 — but the config file can hold any number
	 * somebody types into it, and a negative multiplier turns damage into healing.
	 */
	private static double reduction(double perLevel, int soulLevel) {
		return Math.max(0.0, 1.0 - perLevel * (soulLevel - 1));
	}
}
