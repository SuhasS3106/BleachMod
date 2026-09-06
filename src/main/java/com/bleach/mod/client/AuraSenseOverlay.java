package com.bleach.mod.client;

import java.util.Collection;

import com.bleach.mod.tuning.BleachTuning;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Aura Sense, as the sensor sees it: an eyelid falling from the top of the screen, and then souls
 * burning through it.
 *
 * <p>The lid is a black rectangle grown downward from {@code y = 0}, which is the whole of the
 * "closing your eyes" animation — it is drawn at {@link #BLACKOUT_Z_DEPTH} for the reason spelled
 * out in {@code EnmaKorogiOverlay}: {@code HudRenderCallback} fires after the hotbar and the chat,
 * but GUI items sit at a depth of roughly 150 and would otherwise win the depth test and show
 * through a blackout that is supposed to be total.
 *
 * <h2>Projecting an aura the client cannot see</h2>
 *
 * <p>There is no entity to hang a nameplate off — most of these are far outside entity tracking
 * range, which is the entire reason the server sends coordinates. So each aura is projected by hand:
 * the offset from the camera is resolved onto the camera's own basis, and the perspective divide is
 * done against a focal length in pixels derived from the FOV setting. Anything at or behind the near
 * plane is dropped rather than mirrored to the wrong side of the screen.
 *
 * <p>The blob is a triangle fan — opaque at the centre, transparent at the rim — rather than a stack
 * of {@code fill} rectangles. One draw call per aura instead of one per row, and a real radial
 * falloff instead of a staircase.
 *
 * <p><b>Size is the projection's, not a formula's.</b> Each aura is given a radius in world units
 * that grows with Soul Level, and the divide by depth is what makes it shrink with distance — so a
 * capped soul at three hundred blocks is a spark and one at ten is a bonfire, without either case
 * being special. The clamps at both ends only stop a far aura rounding to nothing and a neighbour
 * whiting out the screen.
 */
public final class AuraSenseOverlay {
	private AuraSenseOverlay() {
	}

	/** Far enough forward to cover every layer the vanilla HUD draws · {@code EnmaKorogiOverlay}. */
	private static final float BLACKOUT_Z_DEPTH = 500.0f;
	/** One layer in front of the lid. Higher GUI z is nearer the camera in the GUI projection. */
	private static final float AURA_Z_DEPTH = 501.0f;

	/** Depth below which an aura is behind the eye and has no screen position at all. */
	private static final double NEAR_CLIP = 0.05;

	private static final double TWO_PI = Math.PI * 2.0;

	public static void register() {
		HudRenderCallback.EVENT.register(AuraSenseOverlay::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return;
		}

		float lid = ClientAuraSenseState.eyelidProgress();
		if (lid <= 0.0f) {
			return;
		}

		int width = graphics.guiWidth();
		int height = graphics.guiHeight();

		graphics.pose().pushPose();
		graphics.pose().translate(0.0f, 0.0f, BLACKOUT_Z_DEPTH);
		graphics.fill(0, 0, width, Mth.ceil(height * lid), 0xFF000000);
		graphics.pose().popPose();

		// Always advanced, even with the lid still rising: the smoothing and the fade are per-frame
		// and skipping them would make every aura jump on the frame the sense becomes visible.
		Collection<ClientAuraSenseState.Reading> readings = ClientAuraSenseState.advance();

		float threshold = (float) Mth.clamp(BleachTuning.AURA_VISION_THRESHOLD, 0.0, 0.999);
		if (lid < threshold) {
			return;
		}

		// Enma Kōrogi is already enforced on the server, which stops the channel and stops sending
		// readings · AuraSense#canSense. This is the belt to that braces: readings already in hand
		// keep fading for a third of a second after the last packet, and not one frame of that may
		// show through a dome that has taken every sense the player has.
		if (ClientEnmaKorogiState.isAffected()) {
			return;
		}
		float vision = (lid - threshold) / (1.0f - threshold);

		// GuiGraphics batches its fills and flushes them at the end of the frame, while the fans below
		// upload immediately. Without this the lid would be drawn after the auras it is meant to sit
		// behind, and would only lose to them on the depth test — which is a thinner guarantee than
		// simply putting the lid on the screen first.
		graphics.flush();

		drawAuras(client, graphics.pose().last().pose(), readings, width, height, vision);
	}

	private static void drawAuras(Minecraft client, Matrix4f matrix,
			Collection<ClientAuraSenseState.Reading> readings, int width, int height, float vision) {
		if (readings.isEmpty()) {
			return;
		}

		Camera camera = client.gameRenderer.getMainCamera();
		Vec3 eye = camera.getPosition();
		Vector3f forward = camera.getLookVector();
		Vector3f up = camera.getUpVector();
		Vector3f left = camera.getLeftVector();

		// The FOV setting rather than the renderer's effective value, which is private in 1.21.1 and
		// only ever differs by the sprint/speed zoom. On a screen that is black apart from the blobs
		// themselves, a couple of degrees of drift has nothing to be measured against.
		double fov = client.options.fov().get();
		double focalPixels = (height / 2.0) / Math.tan(Math.toRadians(fov) / 2.0);

		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableCull();

		for (ClientAuraSenseState.Reading reading : readings) {
			Vec3 offset = reading.position().subtract(eye);

			double depth = offset.x * forward.x() + offset.y * forward.y() + offset.z * forward.z();
			if (depth <= NEAR_CLIP) {
				continue;
			}

			// getLeftVector is the camera's left, so the screen's x axis is its negation.
			double lateral = -(offset.x * left.x() + offset.y * left.y() + offset.z * left.z());
			double vertical = offset.x * up.x() + offset.y * up.y() + offset.z * up.z();

			float screenX = (float) (width / 2.0 + (lateral / depth) * focalPixels);
			float screenY = (float) (height / 2.0 - (vertical / depth) * focalPixels);

			double worldRadius = BleachTuning.AURA_SIZE_BASE
					+ BleachTuning.AURA_SIZE_PER_LEVEL * reading.soulLevel;
			float radius = (float) Mth.clamp((worldRadius / depth) * focalPixels,
					BleachTuning.AURA_MIN_RADIUS_PX, BleachTuning.AURA_MAX_RADIUS_PX);

			if (screenX < -radius || screenX > width + radius
					|| screenY < -radius || screenY > height + radius) {
				continue;
			}

			float alpha = (float) (BleachTuning.AURA_CORE_ALPHA * vision * reading.alpha());
			if (alpha <= 0.0f) {
				continue;
			}

			fan(matrix, screenX, screenY, radius, reading.color, alpha);
		}

		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	/** One aura: a centre vertex at full alpha and a rim of transparent ones around it. */
	private static void fan(Matrix4f matrix, float centreX, float centreY, float radius,
			int color, float alpha) {
		float red = ((color >> 16) & 0xFF) / 255.0f;
		float green = ((color >> 8) & 0xFF) / 255.0f;
		float blue = (color & 0xFF) / 255.0f;

		int segments = Math.max(3, BleachTuning.AURA_SEGMENTS);

		BufferBuilder buffer = Tesselator.getInstance()
				.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);

		buffer.addVertex(matrix, centreX, centreY, AURA_Z_DEPTH).setColor(red, green, blue, alpha);
		for (int i = 0; i <= segments; i++) {
			double angle = (TWO_PI * i) / segments;
			buffer.addVertex(matrix,
							centreX + (float) (Math.cos(angle) * radius),
							centreY + (float) (Math.sin(angle) * radius),
							AURA_Z_DEPTH)
					.setColor(red, green, blue, 0.0f);
		}

		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}
}
