package com.bleach.mod.mixin.client;

import com.bleach.mod.client.ClientEnmaKorogiState;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.SubtitleOverlay;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses all subtitles and captions when inside Tōsen's Enma Kōrogi Bankai dome.
 */
@Mixin(SubtitleOverlay.class)
public abstract class SubtitleOverlayMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void bleach$hideCaptionsInEnmaKorogi(GuiGraphics guiGraphics, CallbackInfo ci) {
		if (ClientEnmaKorogiState.isAffected()) {
			ci.cancel();
		}
	}
}
