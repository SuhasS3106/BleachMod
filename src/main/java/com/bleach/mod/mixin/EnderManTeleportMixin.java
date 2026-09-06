package com.bleach.mod.mixin;

import com.bleach.mod.effect.ReiatsuEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.monster.EnderMan;

/**
 * An enderman under pressure is pinned where it stands · {@code BALANCE.md} §H.4.
 *
 * <p>Every blink an enderman has — the idle wander, the escape from daylight and water, the dodge on
 * being hit, and the lunge toward whoever is staring at it — funnels through the private
 * {@code teleport(double, double, double)}. Refusing that one method is the whole silence, and
 * refusing it by <em>return value</em> rather than by cancelling the caller matters: the callers all
 * read the boolean and behave sensibly when a teleport simply did not find anywhere to go, which is
 * a state vanilla already produces on its own.
 */
@Mixin(EnderMan.class)
public abstract class EnderManTeleportMixin {

	@Inject(method = "teleport(DDD)Z", at = @At("HEAD"), cancellable = true)
	private void bleach_mod$noBlink(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
		if (ReiatsuEffect.isSilenced((EnderMan) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
