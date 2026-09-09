package com.bleach.mod.client;

import com.bleach.mod.ModToggle;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * The gloom over the screen of anyone on Shunsui's stage — Karamatsu Shinjū's tell that you are
 * inside it and which act is running.
 *
 * <h2>What this used to get wrong, and why it mattered</h2>
 *
 * <p><b>It pushed itself in front of the HUD.</b> The fill was drawn after a
 * {@code pose().translate(0, 0, 500)}, which put it past every HUD layer including
 * {@link SpiritualHud} at {@code HUD_Z_DEPTH = 0}. Registration order could not save it: depth wins.
 * That is the whole of "players affected by the Bankai cannot see their SP" — the bar was drawn, and
 * then painted over. The translate is gone; the wash now sits under the HUD like every other tint.
 *
 * <p><b>And it was opaque enough to matter.</b> Up to {@code 0x60} — 96/255 — across the entire
 * viewport, snapped on and off with no fade. It is now capped at
 * {@link BleachTuning#SHUNSUI_ZONE_TINT_MAX_ALPHA} and faded by {@link ClientKaromatsuState}, the
 * same treatment {@link DomeTintOverlay} already got: a fight inside one has to stay playable.
 *
 * <p>Act escalation now reads through <b>colour</b> rather than through opacity — bruise purple,
 * then blood, then rot, then the blue of Dangyo no Fuchi's water, then the thread's cold white.
 */
public final class KaromatsuOverlay {
	private KaromatsuOverlay() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(KaromatsuOverlay::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		if (!ModToggle.isEnabled()) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui) {
			return;
		}

		float fade = ClientKaromatsuState.advance();
		if (fade <= 0.0f) {
			return;
		}

		int alpha = Mth.clamp(
				Mth.floor(fade * BleachTuning.SHUNSUI_ZONE_TINT_MAX_ALPHA), 0, 255);
		if (alpha <= 0) {
			return;
		}

		int colour = (alpha << 24) | (colourForAct(ClientKaromatsuState.currentAct()) & 0x00FFFFFF);
		graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), colour);
	}

	private static int colourForAct(byte act) {
		return switch (act) {
			case 1 -> BleachTuning.SHUNSUI_TINT_ACT_1;
			case 2 -> BleachTuning.SHUNSUI_TINT_ACT_2;
			case 3 -> BleachTuning.SHUNSUI_TINT_ACT_3;
			case 4 -> BleachTuning.SHUNSUI_TINT_FINAL_ACT;
			// PRE_ACT, and the fade-out after the stage has cleared its act back to -1.
			default -> BleachTuning.SHUNSUI_TINT_PRE_ACT;
		};
	}
}
