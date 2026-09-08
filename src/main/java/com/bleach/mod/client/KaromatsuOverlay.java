package com.bleach.mod.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Gloom / underwater tint overlay for Shunsui Kyōraku's Bankai (Karamatsu Shinjū).
 *
 * <p>Renders a translucent coloured fill over the entire viewport for players who are
 * inside the zone but are <em>not</em> Shunsui. The tint shifts through four acts to
 * communicate dread, despair, and the underwater sensation of Act 3 — without any new
 * rendering system or actual water physics.
 *
 * <h2>Tint table (packed ARGB)</h2>
 * <ul>
 *   <li>PRE_ACT (0): {@code 0x26301828} — barely-there gloom (~15% alpha purple-brown)</li>
 *   <li>ACT_1   (1): {@code 0x40301828} — heavier gloom (~25%)</li>
 *   <li>ACT_2   (2): {@code 0x50201020} — deeper/darker (~31%)</li>
 *   <li>ACT_3   (3): {@code 0x503040A0} — blue-grey underwater (~31%)</li>
 *   <li>FINAL_ACT(4): {@code 0x60FFFFFF} — cold white charge flash (~38%)</li>
 * </ul>
 *
 * <h2>Why the pose is pushed forward</h2>
 *
 * <p>Same reason as {@link EnmaKorogiOverlay}: GUI item stacks are rendered at ~depth 150 and
 * chat lines are above zero. Translating past them is what makes the overlay sit in front of
 * the HUD rather than behind it.
 */
public final class KaromatsuOverlay {
	/** Far enough forward to cover every HUD layer — same constant as EnmaKorogiOverlay. */
	private static final float OVERLAY_Z = 500.0f;

	private static final int[] ACT_TINTS = {
			0x26301828, // PRE_ACT (index 0)
			0x40301828, // ACT_1   (index 1)
			0x50201020, // ACT_2   (index 2)
			0x503040A0, // ACT_3   (index 3)
			0x60FFFFFF, // FINAL_ACT (index 4)
	};

	private KaromatsuOverlay() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(KaromatsuOverlay::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		if (!ClientKaromatsuState.isAffected()) {
			return;
		}

		byte act = ClientKaromatsuState.currentAct();
		int tint = tintForAct(act);

		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, 0.0f, OVERLAY_Z);
		graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), tint);
		graphics.pose().popPose();
	}

	private static int tintForAct(byte act) {
		if (act >= 0 && act < ACT_TINTS.length) {
			return ACT_TINTS[act];
		}
		// Unknown act — fall back to faint pre-act tint rather than nothing.
		return ACT_TINTS[0];
	}
}
