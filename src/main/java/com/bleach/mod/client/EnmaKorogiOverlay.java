package com.bleach.mod.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Total sensory blackout overlay for Tōsen's Bankai (Enma Kōrogi).
 *
 * <p>Renders a 100% opaque black rectangle covering the entire viewport whenever the player
 * is inside an active Enma Kōrogi dome.
 *
 * <p><b>Why the pose is pushed forward.</b> {@code HudRenderCallback} already fires at the tail of
 * {@code Gui#render}, so this draws after the hotbar and the chat — but "after" is not "in front
 * of". GUI item stacks are rendered at a depth of roughly 150 and the chat lines sit above zero
 * too, so a plain {@code fill} at the default depth loses the depth test to both and you read chat
 * straight through a blackout that is supposedly total. Translating past everything the vanilla HUD
 * draws is what makes the deprivation actually complete.
 */
public final class EnmaKorogiOverlay {
	/**
	 * Far enough forward to cover every layer the vanilla HUD draws — GUI items sit near 150 and
	 * tooltips near 400 — while staying well inside the GUI projection's usable depth range.
	 */
	private static final float BLACKOUT_Z_DEPTH = 500.0f;

	private EnmaKorogiOverlay() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(EnmaKorogiOverlay::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		if (ClientEnmaKorogiState.isAffected()) {
			graphics.pose().pushPose();
			graphics.pose().translate(0.0f, 0.0f, BLACKOUT_Z_DEPTH);
			graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000);
			graphics.pose().popPose();
		}
	}
}
