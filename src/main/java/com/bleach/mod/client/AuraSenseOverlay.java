package com.bleach.mod.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.bleach.mod.tuning.BleachTuning;
import com.mojang.blaze3d.platform.GlStateManager;
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
 * <p><b>Size is the projection's, not a formula's.</b> Each aura is given a radius in world units
 * that grows with Soul Level and is then multiplied by burn — what the soul is doing right now —
 * and the divide by depth is what makes it shrink with distance. So a capped soul at three hundred
 * blocks is a spark and one at ten is a bonfire, without either case being special, and a released
 * Bankai is unmistakable at either range without ever ceasing to be proportional to the Soul Level
 * behind it. The clamps at both ends only stop a far aura rounding to nothing and a neighbour
 * whiting out the screen.
 *
 * <p>A creature has no Soul Level, so its world radius comes from how much of the world it takes up
 * instead — a measure the server sends because at these ranges there is no entity here to measure.
 * That keeps every soul on screen on one continuous scale: a bee is a spark, a ghast is a bonfire,
 * and nothing is an anonymous dot for want of a number to size it by.
 *
 * <h2>A soul burns, and fire is a population</h2>
 *
 * <p>The drawn radius is not a shape to fill; it is the size of a <b>fire</b> · {@link AuraFlame}.
 * Each reading owns a particle system that lives between frames — particles born hot and white at
 * the base, rising, cooling through the aura's colour, tearing apart at the top and dying. Every
 * earlier version of this drew a flame instead of simulating one, and every one of them read as a
 * glowing decal, because the look of fire lives in the history of each particle rather than in the
 * outline.
 *
 * <p>Nothing about that changes with distance. There is no threshold below which a reading becomes a
 * simpler shape, which is what made far-off souls render as plain round dots before: the same
 * simulation runs at every size and only the spawn rate scales down, so a soul three hundred blocks
 * out is a small fire.
 *
 * <p>All of it is still one draw call. Particles are emitted as loose triangles into a single batched
 * buffer, and blending is additive, so overlapping fires stack into light on a black field and draw
 * order stops mattering — no sorting, at any depth.
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

	/**
	 * How far a fire can reach past its own radius, as a multiple of it. Used for the off-screen cull
	 * only, and deliberately generous: a reading culled a frame early loses its whole particle pool
	 * and has to build the fire again from nothing, which pops far worse than a few wasted triangles.
	 */
	private static final float CULL_EXTENT = 5.5f;

	/** How far the centre of the haze is pulled toward white. The core of a fire is not its colour. */
	private static final float CORE_WHITENING = 0.45f;

	/**
	 * Soul Level the server sends for anything that has none · {@code AuraSense}. Players start at 1,
	 * so this is an unambiguous "size this one by its body, not by its soul" and not a sentinel that
	 * a real reading could ever collide with.
	 */
	private static final int UNRANKED_SOUL_LEVEL = 0;

	/**
	 * One live fire per soul, keyed on entity id. Kept here rather than on the reading because a
	 * reading is a value the network layer rebuilds; a fire is a simulation that has to survive.
	 */
	private static final Map<Integer, AuraFlame> FLAMES = new HashMap<>();

	private static long lastFrameNanos;

	/** Particles alive last frame, which is what the spawn throttle steers against. */
	private static int lastParticleCount;

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
			// Eyes open. Drop every fire rather than leave them smouldering in memory: the next time
			// the sense is used the world will have moved, and a pool of stale particles would flash
			// the previous sweep's fires for a frame before the new readings arrived.
			FLAMES.clear();
			lastFrameNanos = 0L;
			lastParticleCount = 0;
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

		// GuiGraphics batches its fills and flushes them at the end of the frame, while the buffer
		// below uploads immediately. Without this the lid would be drawn after the auras it is meant
		// to sit behind, and would only lose to them on the depth test — which is a thinner
		// guarantee than simply putting the lid on the screen first.
		graphics.flush();

		drawAuras(client, graphics.pose().last().pose(), readings, width, height, vision);
	}

	// --- Projection ---------------------------------------------------------------------------

	/** One reading resolved onto the screen: where it landed, how big it burns, in what colour. */
	private record Plume(int id, float x, float y, float radius, float burn, int color,
			float alpha) {
	}

	private static void drawAuras(Minecraft client, Matrix4f matrix,
			Collection<ClientAuraSenseState.Reading> readings, int width, int height, float vision) {
		if (readings.isEmpty()) {
			FLAMES.clear();
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

		List<Plume> plumes = new ArrayList<>(readings.size());
		Plume strongest = null;

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

			// Soul Level sets the resting size and burn multiplies it, so the two stay proportional
			// all the way up: the same release reads bigger on a bigger soul, which is the point.
			// A creature has no Soul Level, so it is sized by its body instead — the one thing it
			// does have — and burns at a flat ×1, which leaves the multiply harmless either way.
			float burn = Math.max(0.0f, reading.burn());
			double worldRadius = (reading.soulLevel > UNRANKED_SOUL_LEVEL
					? BleachTuning.AURA_SIZE_BASE
							+ BleachTuning.AURA_SIZE_PER_LEVEL * reading.soulLevel
					: BleachTuning.AURA_MOB_SIZE_BASE + BleachTuning.AURA_MOB_SIZE_PER_BLOCK
							* Math.min(reading.body, BleachTuning.AURA_MOB_SIZE_CAP)) * burn;

			// The resting ceiling is what stops a neighbour whiting out the screen; a soul that is
			// actually burning is allowed past it, up to a ceiling of its own, or the clamp would
			// quietly erase every difference this feature exists to draw.
			double ceiling = Math.min(
					BleachTuning.AURA_MAX_RADIUS_PX * Math.max(1.0f, burn),
					BleachTuning.AURA_MAX_BURN_RADIUS_PX);
			float radius = (float) Mth.clamp((worldRadius / depth) * focalPixels,
					BleachTuning.AURA_MIN_RADIUS_PX, ceiling);

			float extent = radius * CULL_EXTENT;
			if (screenX < -extent || screenX > width + extent
					|| screenY < -extent || screenY > height + extent) {
				continue;
			}

			float alpha = (float) (BleachTuning.AURA_CORE_ALPHA * vision * reading.alpha());
			if (alpha <= 0.0f) {
				continue;
			}

			Plume plume = new Plume(reading.id, screenX, screenY, radius, burn, reading.color, alpha);
			plumes.add(plume);
			if (strongest == null || plume.radius() > strongest.radius()) {
				strongest = plume;
			}
		}

		// Fires whose souls did not survive the pass above — out of reach, behind the camera, culled
		// off the edge — are dropped rather than simulated where nobody can see them.
		prune(plumes);

		if (plumes.isEmpty()) {
			return;
		}

		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.enableBlend();
		// Additive. Two auras overlapping on a black field are brighter, not one occluding the other,
		// and with nothing occluding anything the order these are emitted in stops mattering.
		RenderSystem.blendFuncSeparate(
				GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
				GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
		RenderSystem.disableCull();

		BufferBuilder buffer = Tesselator.getInstance()
				.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

		// One clock for the whole frame, so every fire on screen boils against the same time base and
		// two souls standing together do not drift out of phase with each other.
		float seconds = (System.currentTimeMillis() % 3_600_000L) / 1000.0f;
		float dt = frameSeconds();
		float budget = budgetScale();

		haze(buffer, matrix, strongest);

		int alive = 0;
		for (Plume plume : plumes) {
			AuraFlame flame = FLAMES.computeIfAbsent(plume.id(), AuraFlame::new);
			flame.update(dt, plume.burn(), plume.radius(), seconds, budget);
			flame.draw(buffer, matrix, left, up, plume.x(), plume.y(), plume.radius(),
					plume.color(), plume.alpha(), AURA_Z_DEPTH);
			alive += flame.size();
		}
		lastParticleCount = alive;

		BufferUploader.drawWithShader(buffer.buildOrThrow());

		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
	}

	// --- Frame bookkeeping ---------------------------------------------------------------------

	/**
	 * Seconds since the last frame that drew auras.
	 *
	 * <p>Wall time rather than the tick delta, because fire is not on the tick clock and a
	 * twenty-hertz step would judder visibly at any real frame rate. Clamped hard: an alt-tab, a
	 * chunk load or a pause can leave a gap of seconds, and integrating that in one step would fling
	 * every particle off the screen and leave a hole where the fire was.
	 */
	private static float frameSeconds() {
		long now = System.nanoTime();
		if (lastFrameNanos == 0L) {
			lastFrameNanos = now;
			return 1.0f / 60.0f;
		}
		float dt = (now - lastFrameNanos) / 1_000_000_000.0f;
		lastFrameNanos = now;
		return Mth.clamp(dt, 0.0f, 0.05f);
	}

	/**
	 * Spawn throttle for a crowded screen, 0.2..1.
	 *
	 * <p>Forty-eight souls at close range is forty-eight full fires, which is far more geometry than
	 * the sense is worth. Rather than cut some souls off entirely — which would make a reading
	 * disappear because of what other readings were doing — every fire is thinned in proportion, so a
	 * packed room reads as a dimmer, sparser version of the same picture. Steering off last frame's
	 * count makes this a feedback loop that settles rather than a hard cap that oscillates.
	 */
	private static float budgetScale() {
		int budget = Math.max(1, BleachTuning.AURA_PARTICLE_BUDGET);
		if (lastParticleCount <= budget) {
			return 1.0f;
		}
		return Mth.clamp(budget / (float) lastParticleCount, 0.2f, 1.0f);
	}

	/** Forget the fires of souls that are not on screen this frame. */
	private static void prune(List<Plume> plumes) {
		if (FLAMES.isEmpty()) {
			return;
		}
		for (Iterator<Map.Entry<Integer, AuraFlame>> it = FLAMES.entrySet().iterator();
				it.hasNext(); ) {
			int id = it.next().getKey();
			boolean present = false;
			for (Plume plume : plumes) {
				if (plume.id() == id) {
					present = true;
					break;
				}
			}
			if (!present) {
				it.remove();
			}
		}
	}

	// --- The haze -----------------------------------------------------------------------------

	/**
	 * A wash of colour behind the strongest reading on screen. One reading only — hazing every soul
	 * would fill a black screen with grey and cost the sense the contrast it reads by.
	 */
	private static void haze(BufferBuilder buffer, Matrix4f matrix, Plume p) {
		if (p == null || p.radius() < BleachTuning.AURA_EMBER_RADIUS_PX) {
			return;
		}

		float scale = (float) BleachTuning.AURA_HAZE_SCALE;
		ellipse(buffer, matrix, p.x(), p.y() - p.radius() * 0.8f,
				p.radius() * scale, p.radius() * scale * 1.3f, p.color(),
				(float) (BleachTuning.AURA_HAZE_ALPHA * p.alpha()));
	}

	/**
	 * One soft ellipse: a centre vertex at full alpha and a rim of transparent ones, emitted as loose
	 * triangles so it shares the frame's single buffer and upload with every particle.
	 */
	private static void ellipse(BufferBuilder buffer, Matrix4f matrix, float cx, float cy,
			float rx, float ry, int color, float alpha) {
		if (alpha <= 0.0f || rx <= 0.0f || ry <= 0.0f) {
			return;
		}

		float red = ((color >> 16) & 0xFF) / 255.0f;
		float green = ((color >> 8) & 0xFF) / 255.0f;
		float blue = (color & 0xFF) / 255.0f;

		// The centre of a fire's glow reads white and takes its colour on the way out; a flat disc of
		// the aura colour reads as a sticker instead.
		float coreRed = Mth.lerp(CORE_WHITENING, red, 1.0f);
		float coreGreen = Mth.lerp(CORE_WHITENING, green, 1.0f);
		float coreBlue = Mth.lerp(CORE_WHITENING, blue, 1.0f);

		int segments = (int) Mth.clamp(Math.round(6 + Math.max(rx, ry) * 0.55f),
				6, Math.max(6, BleachTuning.AURA_SEGMENTS));
		float prevX = 0.0f;
		float prevY = 0.0f;

		for (int i = 0; i <= segments; i++) {
			double angle = (TWO_PI * i) / segments;
			float x = cx + (float) (Math.cos(angle) * rx);
			float y = cy + (float) (Math.sin(angle) * ry);

			if (i > 0) {
				buffer.addVertex(matrix, cx, cy, AURA_Z_DEPTH)
						.setColor(coreRed, coreGreen, coreBlue, alpha);
				buffer.addVertex(matrix, prevX, prevY, AURA_Z_DEPTH).setColor(red, green, blue, 0.0f);
				buffer.addVertex(matrix, x, y, AURA_Z_DEPTH).setColor(red, green, blue, 0.0f);
			}

			prevX = x;
			prevY = y;
		}
	}
}
