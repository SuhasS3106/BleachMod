package com.bleach.mod.client;

import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.effect.ReiatsuEffect;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * What it feels like to stand inside someone else's pressure · PRD §5.3.
 *
 * <p><b>This is the substitute, not the real thing.</b> The PRD asks for screen shake; real shake
 * means a mixin on the camera setup, and the implementation plan says explicitly to ship the cheap
 * version first and only pay for the renderer hook if this reads flat. So it is a HUD-layer
 * overlay: bands of colour crushing in from the screen edges, wobbling on a short period. Nothing
 * here touches the camera, so nothing here can fight a shader, an optimisation mod, or a player's
 * motion sickness settings.
 *
 * <p>It needs no networking of its own. The local player's own {@code MobEffectInstance} is synced
 * by vanilla, so the amplifier is already on the client — which is the same reason PRD §5 chose a
 * real effect over a custom packet in the first place.
 */
public final class ScreenShake {
	private ScreenShake() {
	}

	/** Vertical slices used to fake a horizontal gradient, which {@code fillGradient} cannot do. */
	private static final int SIDE_STRIPS = 8;
	private static final int ALPHA_MAX = 255;
	private static final float PHASE_OFFSET = 0.37f;

	public static void register() {
		HudRenderCallback.EVENT.register(ScreenShake::render);
	}

	private static void render(GuiGraphics graphics, net.minecraft.client.DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.options.hideGui || client.player.isSpectator()) {
			return;
		}

		if (!ClientSpiritualState.isEnabled() || BleachEffects.REIATSU == null) {
			return;
		}

		MobEffectInstance reiatsu = client.player.getEffect(BleachEffects.REIATSU);
		if (reiatsu == null) {
			return;
		}

		float intensity = (float) ReiatsuEffect.strength(
				BleachTuning.REIATSU_VIGNETTE_ALPHA, reiatsu.getAmplifier());
		if (intensity <= 0.0f) {
			return;
		}

		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		int depth = (int) (height * BleachTuning.REIATSU_VIGNETTE_DEPTH_PCT);
		if (depth <= 0) {
			return;
		}

		// The wobble is the whole "shake": the bands breathe in and out of the frame on opposite
		// phases, which reads as the screen moving without the camera having moved at all.
		float amplitude = (float) BleachTuning.REIATSU_SHAKE_AMPLITUDE_PX * intensity;
		int wobble = Math.round(wave(0.0f) * amplitude);
		int counterWobble = Math.round(wave(PHASE_OFFSET) * amplitude);

		int edge = argb(intensity);
		int clear = BleachTuning.REIATSU_VIGNETTE_COLOR;

		graphics.fillGradient(0, 0, width, depth + wobble, edge, clear);
		graphics.fillGradient(0, height - depth - counterWobble, width, height, clear, edge);

		sideBands(graphics, height, depth + wobble, edge, width);
	}

	/**
	 * Left and right bands, built from {@link #SIDE_STRIPS} flat slices of falling alpha.
	 * {@code fillGradient} interpolates vertically only, so a horizontal fade has to be stepped.
	 */
	private static void sideBands(GuiGraphics graphics, int height, int depth, int edge, int width) {
		int strip = Math.max(1, depth / SIDE_STRIPS);

		for (int i = 0; i < SIDE_STRIPS; i++) {
			int alpha = (edge >>> 24) * (SIDE_STRIPS - i) / SIDE_STRIPS;
			int color = (alpha << 24) | (edge & 0x00FFFFFF);

			graphics.fill(i * strip, 0, (i + 1) * strip, height, color);
			graphics.fill(width - (i + 1) * strip, 0, width - i * strip, height, color);
		}
	}

	private static float wave(float phaseOffset) {
		double period = Math.max(1.0, BleachTuning.REIATSU_SHAKE_PERIOD_MILLIS);
		float phase = (float) ((System.currentTimeMillis() % (long) period) / period);
		return Mth.sin((phase + phaseOffset) * Mth.TWO_PI);
	}

	private static int argb(float intensity) {
		int alpha = Mth.clamp(Math.round(intensity * ALPHA_MAX), 0, ALPHA_MAX);
		return (alpha << 24) | BleachTuning.REIATSU_VIGNETTE_COLOR;
	}
}
