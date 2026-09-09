package com.bleach.mod.client;

import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Gerard's stack meter — vertical, down the left edge.
 *
 * <p>It was a horizontal bar above the hotbar and it collided with everything already living there:
 * the hotbar, the SP bar, the health and hunger rows, and the status-effect icons. The centre of the
 * screen bottom is the busiest real estate in Minecraft and this is the wrong thing to put in it.
 *
 * <p>Vertical also suits what it measures. Stacks are an accumulation — a column that fills upward
 * reads as "how much punishment is in him" more directly than a bar that grows sideways, and it
 * leaves the fill visible in peripheral vision while you are looking at whoever is hitting you.
 *
 * <p><b>Position is config, not code.</b> {@link BleachTuning#M_HUD_X} and
 * {@link BleachTuning#M_HUD_Y_PCT} move it, and {@code /bleach reload} applies them without a
 * restart — so it can be dragged out of the way of whatever else a given setup draws.
 *
 * <p>Three states, carried by colour, because this has to be legible while something is hitting you:
 * <b>red</b> filling for ordinary stacks, <b>gold</b> once Godly Size has fired, and
 * <b>flashing</b> while the core is exposed and damage is doubled.
 */
public final class MiracleOverlay {

	private MiracleOverlay() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(MiracleOverlay::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui || !ClientMiracleState.isActive()) {
			return;
		}

		int width = Math.max(2, BleachTuning.M_HUD_WIDTH);
		int height = Math.max(8, BleachTuning.M_HUD_HEIGHT);

		// Held on screen whatever the config says, so a bad offset cannot hide the meter entirely.
		int x = clamp(BleachTuning.M_HUD_X, 0, Math.max(0, graphics.guiWidth() - width - 1));
		int top = clamp((int) Math.round(graphics.guiHeight() * BleachTuning.M_HUD_Y_PCT),
				10, Math.max(10, graphics.guiHeight() - height - 12));
		int bottom = top + height;

		float fill = ClientMiracleState.fill(deltaTracker.getGameTimeDeltaTicks());
		int filled = Math.round(height * fill);

		// Frame, so an empty meter still reads as a meter rather than as nothing.
		graphics.fill(x - 1, top - 1, x + width + 1, bottom + 1, 0xC0000000);

		// Fills upward from the bottom: an accumulation rising, not a resource draining.
		if (filled > 0) {
			graphics.fill(x, bottom - filled, x + width, bottom, barColour());
		}

		String label = ClientMiracleState.stacks() + "/" + ClientMiracleState.maxStacks();
		graphics.drawString(client.font, Component.literal(label),
				x, bottom + 3, textColour(), true);

		if (ClientMiracleState.isCoreExposed()) {
			graphics.drawString(client.font, Component.literal("CORE"),
					x, top - 11, flash() ? 0xFFFFFFFF : 0xFFFF4444, true);
		} else if (ClientMiracleState.isGodlySize()) {
			graphics.drawString(client.font, Component.literal("GODLY"),
					x, top - 11, 0xFFFBBF24, true);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int barColour() {
		if (ClientMiracleState.isCoreExposed()) {
			return flash() ? 0xFFFFFFFF : 0xFFFF4444;
		}
		if (ClientMiracleState.isGodlySize()) {
			return 0xFFFBBF24;
		}
		return 0xFF000000 | BleachTuning.KIT_MIRACLE_PARTICLE_COLOR;
	}

	private static int textColour() {
		if (ClientMiracleState.isCoreExposed()) {
			return flash() ? 0xFFFFFFFF : 0xFFFF6666;
		}
		return 0xFFFFFFFF;
	}

	/**
	 * Two beats a second, off wall clock rather than game time — a warning that stops warning while
	 * the game is paused is not doing its job.
	 */
	private static boolean flash() {
		return (System.currentTimeMillis() / 250L) % 2L == 0L;
	}
}
