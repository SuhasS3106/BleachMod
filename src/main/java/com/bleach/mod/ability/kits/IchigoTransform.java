package com.bleach.mod.ability.kits;

import java.util.List;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Ichigo Kurosaki's released states · PRD §6.1 · {@code BALANCE.md} §J.1.
 *
 * <p>The two states are a design choice, not a hierarchy:
 * <ul>
 *   <li><b>Shikai — the cleaver:</b> reach and weight. {@code +ICHIGO_SHIKAI_REACH} interaction
 *       range, {@code +ICHIGO_SHIKAI_DMG} bleach melee, and every landed sword swing cleaves all
 *       enemies within {@code ICHIGO_SHIKAI_CLEAVE_ARC / 2} of the look vector for
 *       {@code ICHIGO_SHIKAI_CLEAVE_PCT} of primary damage.</li>
 *   <li><b>Bankai — the speed flip:</b> mobility and tempo. {@code +ICHIGO_BANKAI_SPEED} movement
 *       speed, {@code +ICHIGO_BANKAI_ATK_SPEED} attack speed, and {@code +ICHIGO_BANKAI_DMG} bleach
 *       melee. <b>The reach bonus deliberately does not carry over</b> — Bankai's blade is the
 *       small one.</li>
 * </ul>
 *
 * <p>All attribute modifiers are {@link ResourceLocation}-keyed so removal is exact, and stripped
 * in {@code onRevert} (idempotent) and on login via {@link #stripModifiers}.
 */
public final class IchigoTransform {
	private IchigoTransform() {
	}

	public static final ResourceLocation SHIKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "ichigo/shikai");
	public static final ResourceLocation BANKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "ichigo/bankai");

	public static final ResourceLocation SHIKAI_REACH_MODIFIER = BleachMod.id("ichigo_shikai_reach");
	public static final ResourceLocation BANKAI_SPEED_MODIFIER = BleachMod.id("ichigo_bankai_speed");
	public static final ResourceLocation BANKAI_ATTACK_SPEED_MODIFIER = BleachMod.id("ichigo_bankai_attack_speed");

	/** Distance epsilon to avoid division by zero when normalizing vectors. */
	private static final double VECTOR_EPSILON = 1.0e-5;

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	/**
	 * Strip all bleach attribute modifiers pushed by either state. Called on revert and on player
	 * login to guarantee no orphaned modifiers survive a crash mid-transformation.
	 */
	public static void stripModifiers(ServerPlayer player) {
		stripShikaiModifiers(player);
		stripBankaiModifiers(player);
	}

	private static void stripShikaiModifiers(ServerPlayer player) {
		AttributeInstance reach = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
		if (reach != null) {
			reach.removeModifier(SHIKAI_REACH_MODIFIER);
		}
	}

	private static void stripBankaiModifiers(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(BANKAI_SPEED_MODIFIER);
		}
		AttributeInstance atkSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
		if (atkSpeed != null) {
			atkSpeed.removeModifier(BANKAI_ATTACK_SPEED_MODIFIER);
		}
	}

	// --- Shikai: Zangetsu -------------------------------------------------------------

	private static final class Shikai implements TransformAbility {
		@Override
		public ResourceLocation id() {
			return SHIKAI_ID;
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_SHIKAI;
		}


		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_SHIKAI, soulLevel);
		}

		@Override
		public double meleeDamageBonus() {
			return BleachTuning.ICHIGO_SHIKAI_DMG;
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			// Reach bonus: the big cleaver.
			AttributeInstance reach = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
			if (reach != null) {
				reach.removeModifier(SHIKAI_REACH_MODIFIER);
				reach.addPermanentModifier(new AttributeModifier(
						SHIKAI_REACH_MODIFIER,
						BleachTuning.ICHIGO_SHIKAI_REACH,
						AttributeModifier.Operation.ADD_VALUE));
			}
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			stripShikaiModifiers(player);
		}

		@Override
		public void onMeleeHit(ServerPlayer player, LivingEntity target, float damage) {
			if (damage <= 0.0f) {
				return;
			}

			// Modified reach from the player's entity interaction range attribute.
			AttributeInstance reachAttr = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
			double reach = reachAttr != null ? reachAttr.getValue() : BleachTuning.ICHIGO_SHIKAI_REACH;

			Vec3 eyePos = player.getEyePosition();
			Vec3 look = player.getLookAngle().normalize();
			double cosThreshold = Math.cos(Math.toRadians(BleachTuning.ICHIGO_SHIKAI_CLEAVE_ARC / 2.0));

			AABB searchBox = player.getBoundingBox().inflate(reach);
			List<LivingEntity> candidates = player.level().getEntitiesOfClass(
					LivingEntity.class, searchBox,
					e -> e != player && e != target && e.isAlive() && !e.isAlliedTo(player));

			float cleaveDamage = (float) (damage * BleachTuning.ICHIGO_SHIKAI_CLEAVE_PCT);
			if (cleaveDamage <= 0.0f) {
				return;
			}

			for (LivingEntity secondary : candidates) {
				Vec3 toSecondary = secondary.getEyePosition().subtract(eyePos);
				double dist = toSecondary.length();
				if (dist > reach || dist < VECTOR_EPSILON) {
					continue;
				}

				Vec3 dir = toSecondary.normalize();
				if (look.dot(dir) >= cosThreshold) {
					secondary.hurt(
							BleachDamage.source(player.serverLevel(), BleachDamage.SPIRIT_PRESSURE, player),
							cleaveDamage);
				}
			}
		}
	}

	// --- Bankai: Tensa Zangetsu -------------------------------------------------------

	private static final class Bankai implements TransformAbility {
		@Override
		public ResourceLocation id() {
			return BANKAI_ID;
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_BANKAI;
		}


		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_BANKAI, soulLevel);
		}

		@Override
		public double meleeDamageBonus() {
			return BleachTuning.ICHIGO_BANKAI_DMG;
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			// Speed flip: movement speed and attack speed multipliers.
			// The reach bonus deliberately does NOT carry over.
			stripShikaiModifiers(player);

			AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
			if (speed != null) {
				speed.removeModifier(BANKAI_SPEED_MODIFIER);
				speed.addPermanentModifier(new AttributeModifier(
						BANKAI_SPEED_MODIFIER,
						BleachTuning.ICHIGO_BANKAI_SPEED,
						AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}

			AttributeInstance atkSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
			if (atkSpeed != null) {
				atkSpeed.removeModifier(BANKAI_ATTACK_SPEED_MODIFIER);
				atkSpeed.addPermanentModifier(new AttributeModifier(
						BANKAI_ATTACK_SPEED_MODIFIER,
						BleachTuning.ICHIGO_BANKAI_ATK_SPEED,
						AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			}
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			stripBankaiModifiers(player);
		}
	}
}
