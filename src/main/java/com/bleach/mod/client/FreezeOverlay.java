package com.bleach.mod.client;

import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * Translucent icy vignette rendered when the local player has the freeze effect · PRD §6.4.
 */
public final class FreezeOverlay {
	private FreezeOverlay() {
	}

	private static final int SIDE_STRIPS = 8;
	private static final int ALPHA_MAX = 255;

	public static void register() {
		HudRenderCallback.EVENT.register(FreezeOverlay::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui || client.player.isSpectator()) {
			return;
		}

		if (!ClientSpiritualState.isEnabled() || BleachEffects.FREEZE == null) {
			return;
		}

		if (!client.player.hasEffect(BleachEffects.FREEZE)) {
			return;
		}

		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		int depth = (int) (height * BleachTuning.RUKIA_VIGNETTE_DEPTH_PCT);
		if (depth <= 0) {
			return;
		}

		int alpha = Mth.clamp((int) Math.round(BleachTuning.RUKIA_VIGNETTE_ALPHA * ALPHA_MAX), 0, ALPHA_MAX);
		int edge = (alpha << 24) | (BleachTuning.RUKIA_VIGNETTE_COLOR & 0x00FFFFFF);
		int clear = BleachTuning.RUKIA_VIGNETTE_COLOR & 0x00FFFFFF;

		graphics.fillGradient(0, 0, width, depth, edge, clear);
		graphics.fillGradient(0, height - depth, width, height, clear, edge);

		sideBands(graphics, height, depth, edge, width);
	}

	private static void sideBands(GuiGraphics graphics, int height, int depth, int edge, int width) {
		int strip = Math.max(1, depth / SIDE_STRIPS);

		for (int i = 0; i < SIDE_STRIPS; i++) {
			int alpha = (edge >>> 24) * (SIDE_STRIPS - i) / SIDE_STRIPS;
			int color = (alpha << 24) | (edge & 0x00FFFFFF);

			graphics.fill(i * strip, 0, (i + 1) * strip, height, color);
			graphics.fill(width - (i + 1) * strip, 0, width - i * strip, height, color);
		}
	}
}
