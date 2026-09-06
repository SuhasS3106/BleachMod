package com.bleach.mod.mixin;

import com.bleach.mod.ability.kits.AizenHypnosisManager;

import net.minecraft.world.entity.Mob;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Aizen's illusions do not burn in daylight.
 *
 * <p>They are conjured from a zombie because a zombie already walks and swings, not because they
 * are undead — so vanilla's sun rule is an implementation detail leaking into the fiction. Left
 * alone it is also a tell: a hypnosis cast at dawn announces itself when the victim's attackers
 * catch fire and drop dead on their own a few seconds later.
 *
 * <p>Cancelling {@code isSunBurnTick} rather than making the mob fire-immune keeps every other
 * source of fire working on them normally.
 */
@Mixin(Mob.class)
public abstract class MobSunBurnMixin {

	@Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
	private void bleach$illusionsIgnoreSunlight(CallbackInfoReturnable<Boolean> cir) {
		if (AizenHypnosisManager.isIllusionMob((Mob) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
