package com.bleach.mod.mixin.client;

import com.bleach.mod.client.ClientEnmaKorogiState;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tōsen cannot sprint while Enma Kōrogi is up.
 *
 * <p>The Bankai blinds and deafens everyone else inside the dome for as long as it holds, which is
 * close to unanswerable; the balance is that the caster gives up his own mobility to maintain it.
 *
 * <p>Blocked at {@code canStartSprinting} rather than by clearing the flag server-side every tick.
 * The client owns sprint prediction, so a server that merely un-sets it fights the client once per
 * tick and the player stutters between sprinting and not. Refusing to start is the only place the
 * decision is made once. {@code TosenTransform} still clears the flag on the server as the
 * authority backstop, for a client that does not have this mixin.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintMixin {

	@Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
	private void bleach$noSprintWhileDeafening(CallbackInfoReturnable<Boolean> cir) {
		if (ClientEnmaKorogiState.isCaster()) {
			cir.setReturnValue(false);
		}
	}
}
