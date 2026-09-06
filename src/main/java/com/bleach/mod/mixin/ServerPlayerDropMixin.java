package com.bleach.mod.mixin;

import com.bleach.mod.item.Zanpakuto;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents dropping Zanpakutō or Asauchi on the server when receiving drop actions (e.g. Q key).
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {

	@Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
	private void bleach$serverKeepZanpakuto(boolean dropStack, CallbackInfoReturnable<Boolean> cir) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (Zanpakuto.isUndroppable(self.getInventory().getSelected()) || Zanpakuto.isUndroppable(self.getMainHandItem())) {
			cir.setReturnValue(false);
		}
	}
}
