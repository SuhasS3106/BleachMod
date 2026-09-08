package com.bleach.mod.client;

import com.bleach.mod.ModToggle;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The purple wash over the screen of anyone standing in a Gift Bereich.
 *
 * <p>It is the only thing that tells a trapped player <em>why</em> they cannot leave. The shell's
 * particles are visible from outside and at a distance, but from the inside of a 36-block dome the
 * nearest wall is eighteen blocks away and easy to miss entirely — so without this, being contained
 * reads as the game being broken rather than as someone having caught you.
 *
 * <p>Drawn as a flat translucent fill rather than a vignette on purpose: a vignette says "you are
 * hurt", which the game already uses it for, and a full wash says "you are somewhere". Kept well
 * under half opacity so a fight inside one is still playable.
 */
public final class DomeTintOverlay {
	private DomeTintOverlay() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(DomeTintOverlay::render);
	}

	private static void render(GuiGraphics graphics, Object tickCounter) {
		if (!ModToggle.isEnabled()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui) {
			return;
		}

		float tint = ClientDomeState.advance();
		if (tint <= 0.0f) {
			return;
		}

		int alpha = Mth.clamp(
				Mth.floor(tint * BleachTuning.DOME_TINT_MAX_ALPHA), 0, 255);
		if (alpha <= 0) {
			return;
		}

		int colour = (alpha << 24) | (BleachTuning.DOME_COLOR & 0x00FFFFFF);
		graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), colour);
	}
}
