package com.bleach.mod.effect;

import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Shinji Hirako's Sakanade inversion debuff · PRD §6.5 · {@code BALANCE.md} §J.5.
 *
 * <p>Applied to targets by Shikai on melee hit and continuously in Bankai's proximity aura:
 * <ul>
 *   <li><b>Players:</b> inverts WASD movement inputs via {@code KeyboardInputMixin} and pairs with
 *       hidden vanilla Nausea ({@link MobEffects#CONFUSION}). At
 *       {@link BleachTuning#SAKANADE_CAMERA_FLIP_AMPLIFIER} and above — the tier Shinji's Bankai
 *       applies — mouse look is inverted as well via {@code EntityTurnMixin}.</li>
 *   <li><b>Mobs:</b> inverts horizontal movement vectors via {@code LivingEntityTravelMixin}
 *       with a 70% inverted / 30% true roll rerolled every {@link BleachTuning#SHINJI_MOB_REROLL_TICKS} ticks,
 *       causing them to drunkenly stagger backwards while pathing toward their target.</li>
 * </ul>
 */
public final class SakanadeEffect extends MobEffect {

	private static final int PARTICLE_INTERVAL = 5;
	private static final int NAUSEA_DURATION_TICKS = 60;
	private static final int NAUSEA_AMPLIFIER = 0;
	private static final float PARTICLE_SCALE = 0.8f;

	public SakanadeEffect() {
		super(MobEffectCategory.HARMFUL, BleachTuning.SHINJI_EFFECT_COLOR);
	}

	/**
	 * Whether this entity's Sakanade is strong enough to invert the camera as well as the feet.
	 *
	 * <p>Lives here rather than in the client mixin so the tier rule has one home: the mixin is
	 * loaded before mod init can finish, so it must also tolerate {@link BleachEffects#SAKANADE}
	 * still being null.
	 */
	public static boolean flipsCamera(LivingEntity entity) {
		if (BleachEffects.SAKANADE == null) {
			return false;
		}
		MobEffectInstance instance = entity.getEffect(BleachEffects.SAKANADE);
		return instance != null && instance.getAmplifier() >= BleachTuning.SAKANADE_CAMERA_FLIP_AMPLIFIER;
	}

	@Override
	public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
		return true;
	}

	@Override
	public boolean applyEffectTick(LivingEntity entity, int amplifier) {
		if (entity.level().isClientSide()) {
			return true;
		}

		// Pair with hidden Nausea for players (PRD §6.5: "WASD only — never mouse-look — paired with Nausea")
		if (entity instanceof Player player) {
			player.addEffect(new MobEffectInstance(
					MobEffects.CONFUSION, NAUSEA_DURATION_TICKS, NAUSEA_AMPLIFIER, true, false, false));
		}

		// Ambient inverted pressure particles
		if (entity.level() instanceof ServerLevel serverLevel && entity.tickCount % PARTICLE_INTERVAL == 0) {
			serverLevel.sendParticles(
					new PressureParticleOptions(BleachTuning.KIT_SHINJI_PARTICLE_COLOR, PARTICLE_SCALE),
					entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
					2, 0.25, 0.25, 0.25, 0.02);
		}

		return true;
	}
}
