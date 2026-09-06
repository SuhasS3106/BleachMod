package com.bleach.mod.mixin;

import com.bleach.mod.ability.kits.MobInversion;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.effect.ReiatsuEffect;
import com.bleach.mod.tuning.BleachTuning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * The shared movement-input hook. <b>One mixin, two consumers:</b>
 * <ol>
 *   <li><b>Reiatsu rooting:</b> zeroes horizontal travel vector and delta movement for entities
 *       under crushing spiritual pressure.</li>
 *   <li><b>Sakanade mob inversion:</b> inverts the horizontal movement vectors of {@link Mob}s
 *       afflicted with {@link BleachEffects#SAKANADE} at the movement-vector layer. Rerolled every
 *       {@link BleachTuning#SHINJI_MOB_REROLL_TICKS} ticks at {@link BleachTuning#SHINJI_MOB_INVERT_CHANCE}
 *       chance, making mobs stumble drunkenly backward while their AI tries to path forward.</li>
 * </ol>
 *
 * <p>The roll lives on the mob as a {@link MobInversion} attachment rather than in a static map on
 * this class. A static map keyed by UUID cannot observe an entity going away, so it needs an
 * eviction hook per disappearance path and leaks one entry for every path that was missed —
 * chunk unload having no hook at all.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTravelMixin {

	@ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true)
	private Vec3 bleach_mod$modifyTravel(Vec3 travelVector) {
		LivingEntity self = (LivingEntity) (Object) this;

		// 1. Rooting (Reiatsu crushing tier or Sui-Feng Bankai windup)
		if (ReiatsuEffect.isRooted(self)) {
			Vec3 motion = self.getDeltaMovement();
			self.setDeltaMovement(0.0, motion.y, 0.0);
			return Vec3.ZERO;
		}

		// 2. Sakanade mob movement inversion (PRD §6.5). Server-side only: mob position on the
		// client is interpolated from packets, so inverting there would fight the server rather
		// than agree with it.
		if (self.level().isClientSide() || !(self instanceof Mob mob)) {
			return travelVector;
		}
		if (BleachEffects.SAKANADE == null || !mob.hasEffect(BleachEffects.SAKANADE)) {
			return travelVector;
		}

		MobInversion roll = mob.getAttachedOrCreate(BleachAttachments.MOB_INVERSION);
		int now = mob.tickCount;

		if (now - roll.lastRollTick >= BleachTuning.SHINJI_MOB_REROLL_TICKS) {
			roll.inverted = mob.getRandom().nextDouble() < BleachTuning.SHINJI_MOB_INVERT_CHANCE;
			roll.lastRollTick = now;
		}

		return roll.inverted
				? new Vec3(-travelVector.x, travelVector.y, -travelVector.z)
				: travelVector;
	}
}
