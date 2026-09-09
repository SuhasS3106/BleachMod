package com.bleach.mod.ability.kits;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.Ability;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Heilig Pfeil — Gerard's Vollständig active.
 *
 * <p>Canon: <i>"Gerard can fire energy beams from the tip of Hoffnung that are powerful enough to
 * level entire city blocks"</i>, and the Heilig Pfeil itself is <i>"a pure torrent of power"</i>.
 *
 * <p>A line, not a projectile. Everything within {@link BleachTuning#HP_WIDTH} of the beam is hit,
 * and the beam does not stop at the first body — a torrent that the first person absorbed would be
 * an arrow, and Gerard already has one of those.
 */
public final class HeiligPfeil implements Ability {

	public static final ResourceLocation ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "miracle/heilig_pfeil");

	@Override
	public ResourceLocation id() {
		return ID;
	}

	@Override
	public double spCost(SpiritualData data) {
		return BleachTuning.HP_SP_COST;
	}

	@Override
	public int cooldownTicks(SpiritualData data) {
		return BleachTuning.HP_COOLDOWN_TICKS;
	}

	@Override
	public void onActivate(ServerPlayer player, SpiritualData data) {
		ServerLevel level = player.serverLevel();
		Vec3 look = player.getLookAngle().normalize();
		Vec3 origin = player.getEyePosition();
		double range = BleachTuning.HP_RANGE;
		double width = BleachTuning.HP_WIDTH;

		Vec3 end = origin.add(look.scale(range));
		AABB sweep = new AABB(origin, end).inflate(width + 1.0);

		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, sweep,
				e -> e != player && e.isAlive())) {

			Vec3 centre = target.getBoundingBox().getCenter().subtract(origin);
			double offset = MiracleAbilities.distanceToRay(
					centre.x, centre.y, centre.z, look.x * range, look.y * range, look.z * range);
			if (offset > width + target.getBbWidth() * 0.5) {
				continue;
			}
			if (centre.length() > range) {
				continue;
			}

			target.invulnerableTime = 0;
			target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, player),
					(float) BleachTuning.HP_DAMAGE);
		}

		// Drawn as a solid line so the beam is readable as terrain to stand out of.
		int steps = (int) Math.ceil(range * 2);
		for (int i = 1; i <= steps; i++) {
			double t = (i / (double) steps) * range;
			level.sendParticles(
					new PressureParticleOptions(BleachTuning.KIT_MIRACLE_PARTICLE_COLOR, 2.0f),
					origin.x + look.x * t, origin.y + look.y * t, origin.z + look.z * t,
					3, 0.12, 0.12, 0.12, 0.0);
		}

		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 2.0f, 0.4f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.0f, 0.9f);
	}
}
