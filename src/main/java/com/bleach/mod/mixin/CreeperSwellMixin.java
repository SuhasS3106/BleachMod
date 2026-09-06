package com.bleach.mod.mixin;

import com.bleach.mod.effect.ReiatsuEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.monster.Creeper;

/**
 * A creeper under pressure cannot finish its fuse · {@code BALANCE.md} §H.4.
 *
 * <p>Two injections, because the swell and the explosion are two different failures. The fuse itself
 * is walked back every tick, so a creeper that was already hissing visibly winds <em>down</em> as it
 * enters the field rather than freezing mid-hiss — the tell is the point, since a stuck fuse looks
 * exactly like a creeper about to go off. The explosion is then blocked outright as the backstop,
 * for the tick where the fuse was already full when the pressure landed.
 *
 * <p>It is a reprieve, not a disarm: walk out of the field, or let the flexer's pool run out, and the
 * creeper starts counting again from wherever the walk-back left it.
 */
@Mixin(Creeper.class)
public abstract class CreeperSwellMixin {

	@Inject(method = "tick", at = @At("HEAD"))
	private void bleach_mod$defuse(CallbackInfo ci) {
		Creeper self = (Creeper) (Object) this;
		if (self.getSwellDir() > 0 && ReiatsuEffect.isSilenced(self)) {
			self.setSwellDir(-1);
		}
	}

	@Inject(method = "explodeCreeper", at = @At("HEAD"), cancellable = true)
	private void bleach_mod$noExplosion(CallbackInfo ci) {
		if (ReiatsuEffect.isSilenced((Creeper) (Object) this)) {
			ci.cancel();
		}
	}
}
