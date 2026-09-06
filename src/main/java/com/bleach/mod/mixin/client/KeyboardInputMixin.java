package com.bleach.mod.mixin.client;

import com.bleach.mod.effect.BleachEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;

/**
 * Client-side input inversion for Shinji Hirako's Sakanade · PRD §6.5 · {@code BALANCE.md} §J.5.
 *
 * <p>Flips {@code forwardImpulse} and {@code leftImpulse} alongside the key state booleans
 * ({@code up <-> down}, {@code left <-> right}) while {@link BleachEffects#SAKANADE} is active.
 *
 * <p><b>WASD only. Never touches mouse look.</b>
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

	@Inject(method = "tick", at = @At("TAIL"))
	private void bleach$invertInputs(boolean slowDown, float sneakMultiplier, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && BleachEffects.SAKANADE != null && mc.player.hasEffect(BleachEffects.SAKANADE)) {
			Input self = (Input) (Object) this;
			self.forwardImpulse = -self.forwardImpulse;
			self.leftImpulse = -self.leftImpulse;

			boolean prevUp = self.up;
			self.up = self.down;
			self.down = prevUp;

			boolean prevLeft = self.left;
			self.left = self.right;
			self.right = prevLeft;
		}
	}
}
