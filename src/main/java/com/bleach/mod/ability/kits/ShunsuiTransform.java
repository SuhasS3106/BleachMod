package com.bleach.mod.ability.kits;

import java.util.List;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shunsui Kyōraku's released states · Katen Kyōkotsu & Karamatsu Shinjū · BALANCE.md §J.9.
 */
public final class ShunsuiTransform {
	private ShunsuiTransform() {
	}

	public static final ResourceLocation SHIKAI_ID = BleachMod.id("shunsui/shikai");
	public static final ResourceLocation BANKAI_ID = BleachMod.id("shunsui/bankai");

	private static final double VECTOR_EPSILON = 1.0e-4;

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	// --- Shikai: Katen Kyōkotsu (Irooni) --------------------------------------------

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
		public void onEnter(ServerPlayer player, SpiritualData data) {
			KatenShikaiManager.init(player);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			KatenShikaiManager.tickRuleCheck(player);
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			KatenShikaiManager.clearAll(player);
		}

		@Override
		public void onMeleeHit(ServerPlayer player, LivingEntity target, float damage) {
			if (damage <= 0.0f) {
				return;
			}

			// 1. Dual-blade cleave arc across secondary targets
			AttributeInstance reachAttr = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
			double reach = reachAttr != null ? reachAttr.getValue() : 3.0;

			Vec3 eyePos = player.getEyePosition();
			Vec3 look = player.getLookAngle().normalize();
			double cosThreshold = Math.cos(Math.toRadians(BleachTuning.SHUNSUI_SHIKAI_CLEAVE_ARC / 2.0));

			AABB searchBox = player.getBoundingBox().inflate(reach);
			List<LivingEntity> candidates = player.level().getEntitiesOfClass(
					LivingEntity.class, searchBox,
					e -> e != player && e != target && e.isAlive() && !e.isAlliedTo(player));

			float cleaveDamage = (float) (damage * BleachTuning.SHUNSUI_SHIKAI_CLEAVE_PCT);
			if (cleaveDamage > 0.0f) {
				for (LivingEntity secondary : candidates) {
					Vec3 toSecondary = secondary.getEyePosition().subtract(eyePos);
					double dist = toSecondary.length();
					if (dist > reach || dist < VECTOR_EPSILON) {
						continue;
					}

					Vec3 dir = toSecondary.normalize();
					if (look.dot(dir) < cosThreshold) {
						continue;
					}

					secondary.invulnerableTime = 0;
					secondary.hurt(BleachDamage.source(player.serverLevel(), BleachDamage.SPIRIT_PRESSURE, player), cleaveDamage);
				}
			}

			// 2. Dual-blade second strike chance on the primary target
			if (player.getRandom().nextDouble() < BleachTuning.SHUNSUI_SHIKAI_DUAL_HIT_CHANCE) {
				target.invulnerableTime = 0;
				target.hurt(BleachDamage.source(player.serverLevel(), BleachDamage.SPIRIT_PRESSURE, player), cleaveDamage);

				Vec3 center = target.getBoundingBox().getCenter();
				player.serverLevel().sendParticles(
						new PressureParticleOptions(BleachTuning.KIT_SHUNSUI_PARTICLE_COLOR, 0.5f),
						center.x, center.y, center.z,
						8, 0.2, 0.2, 0.2, 0.02);
			}
		}
	}

	// --- Bankai: Karamatsu Shinjū ---------------------------------------------------

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
		public void onEnter(ServerPlayer player, SpiritualData data) {
			KaromatsuManager.startBankai(player);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			KaromatsuManager.tickBankai(player);
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			KaromatsuManager.endBankai(player);
		}

		@Override
		public void onMeleeHit(ServerPlayer player, LivingEntity target, float damage) {
			if (damage <= 0.0f) {
				return;
			}

			// Second blade bonus active from Act 2 onwards
			double bonus = KaromatsuManager.getCurrentMeleeDmgBonus(player);
			if (bonus > 0.0) {
				float extraDamage = (float) (damage * bonus);
				target.invulnerableTime = 0;
				target.hurt(BleachDamage.source(player.serverLevel(), BleachDamage.SPIRIT_PRESSURE, player), extraDamage);

				Vec3 center = target.getBoundingBox().getCenter();
				player.serverLevel().sendParticles(
						new PressureParticleOptions(0x202020, 0.6f),
						center.x, center.y, center.z,
						10, 0.25, 0.25, 0.25, 0.02);
			}
		}
	}
}
