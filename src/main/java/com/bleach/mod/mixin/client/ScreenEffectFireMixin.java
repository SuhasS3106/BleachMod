package com.bleach.mod.mixin.client;

import com.bleach.mod.ability.kits.BleachKits;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.client.ClientSpiritualState;
import com.bleach.mod.item.ZanpakutoItem;
import com.bleach.mod.network.SpiritualSyncPayload;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Yamamoto does not see his own flames.
 *
 * <p>Ryūjin Jakka's wielder is fire-immune in both released states and the server clears his fire
 * ticks every tick, but the client still paints the full-screen fire overlay for the frames in
 * between — so walking through his own scorch trail blinds him with an effect that is doing him no
 * harm. This cancels the fire overlay only, leaving the water overlay and every block-view effect
 * untouched, and only while a Yamamoto blade is drawn and released.
 *
 * <p>Decided from the held item and the synced state rather than a new packet: the client already
 * knows both, and the kit is readable straight off the stack in hand.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectFireMixin {

	@Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
	private static void bleach$hideOwnFlames(Minecraft minecraft, PoseStack poseStack, CallbackInfo ci) {
		if (!ClientSpiritualState.isEnabled() || minecraft.player == null) {
			return;
		}

		SpiritualSyncPayload state = ClientSpiritualState.get();
		if (state == null || state.state() == SpiritualData.STATE_BASE) {
			return;
		}

		if (minecraft.player.getMainHandItem().getItem() instanceof ZanpakutoItem blade
				&& BleachKits.YAMAMOTO.equals(blade.kitId())) {
			ci.cancel();
		}
	}
}
