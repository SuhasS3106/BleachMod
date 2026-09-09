package com.bleach.mod.ability.kits;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.Ability;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Hoffnung's Wrath — Gerard's Schrift active.
 *
 * <p>One swing of the sword. Canon: <i>"with a single swing of his sword, Gerard effortlessly blew a
 * hole through the Vestibule Road"</i>, and later damaged the Tree of Life with the force of a swing
 * alone. So this is not a punch — it is the ground coming apart in a cone in front of him.
 *
 * <p>It launches rather than shoves. Immense Strength reads as people leaving the floor.
 */
public final class HoffnungsWrath implements Ability {

	public static final ResourceLocation ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "miracle/hoffnungs_wrath");

	@Override
	public ResourceLocation id() {
		return ID;
	}

	@Override
	public double spCost(SpiritualData data) {
		return BleachTuning.HW_SP_COST;
	}

	@Override
	public int cooldownTicks(SpiritualData data) {
		return BleachTuning.HW_COOLDOWN_TICKS;
	}

	@Override
	public void onActivate(ServerPlayer player, SpiritualData data) {
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle().normalize();
		Vec3 eye = player.getEyePosition();
		double range = BleachTuning.HW_RANGE;

		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(range),
				e -> e != player && e.isAlive() && e.distanceToSqr(player) <= range * range)) {

			Vec3 toTarget = target.getBoundingBox().getCenter().subtract(eye);
			if (toTarget.lengthSqr() < 1.0e-6) {
				continue;
			}
			if (!MiracleAbilities.inArc(look.dot(toTarget.normalize()), BleachTuning.HW_ARC_DEGREES)) {
				continue;
			}

			target.invulnerableTime = 0;
			target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, player),
					(float) BleachTuning.HW_DAMAGE);

			// setDeltaMovement plus hurtMarked is the vanilla knockback path — hurtMarked is what
			// makes the server push a motion packet. This is not the PoisonDome defect, which was
			// about holding a player in place every tick against their own movement packets.
			Vec3 away = toTarget.normalize();
			double kb = BleachTuning.HW_KNOCKBACK;
			target.setDeltaMovement(away.x * kb, BleachTuning.HW_LIFT, away.z * kb);
			target.hurtMarked = true;
		}

		// The ground coming apart, drawn along the swing rather than around the caster.
		BlockParticleOption debris =
				new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState());
		for (int i = 1; i <= 12; i++) {
			double t = (i / 12.0) * range;
			double x = player.getX() + look.x * t;
			double z = player.getZ() + look.z * t;
			level.sendParticles(debris, x, player.getY() + 0.1, z, 12, 0.5, 0.1, 0.5, 0.15);
			level.sendParticles(
					new PressureParticleOptions(BleachTuning.KIT_MIRACLE_PARTICLE_COLOR, 1.5f),
					x, player.getY() + 0.6, z, 4, 0.35, 0.3, 0.35, 0.02);
		}

		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.0f, 0.8f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.2f, 0.5f);
	}
}
