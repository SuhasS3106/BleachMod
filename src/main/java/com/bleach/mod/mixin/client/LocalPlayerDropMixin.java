package com.bleach.mod.mixin.client;

import com.bleach.mod.client.ClientHoverState;
import com.bleach.mod.item.Zanpakuto;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts the Q drop key client-side on LocalPlayer.
 *
 * <p>Prevents client inventory desynchronization and stops the client from ever predicting or sending
 * an item drop packet when holding an undroppable Zanpakutō or Asauchi.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerDropMixin {

	@Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
	private void bleach$clientKeepZanpakuto(boolean dropStack, CallbackInfoReturnable<Boolean> cir) {
		LocalPlayer self = (LocalPlayer) (Object) this;
		if (Zanpakuto.isUndroppable(self.getInventory().getSelected()) || Zanpakuto.isUndroppable(self.getMainHandItem())) {
			cir.setReturnValue(false);
		}

		// Hover shares this key · ClientHoverState. In the air the press is a hover and dropping
		// whatever happened to be in hand alongside it would be nobody's intent; on the ground it is
		// a drop and still behaves like one.
		if (ClientHoverState.isKeyHeld() && !self.onGround()) {
			cir.setReturnValue(false);
		}
	}
}
