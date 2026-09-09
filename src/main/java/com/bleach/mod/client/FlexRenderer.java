package com.bleach.mod.client;

import java.util.HashMap;
import java.util.Iterator;
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

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Draws every Spiritual Flex field in the world · PRD §5.3.
 *
 * <h2>Why this is in the world and not on the HUD</h2>
 *
 * <p>{@code AuraSenseOverlay} draws its souls onto the screen, which is right for it: an aura seen
 * with your eyes shut is a reading, not an object, and it has to show through walls. A flex field is
 * the opposite — it is a thing standing in the world at a known radius, and the whole point of
 * seeing one is judging whether you are inside it. So it renders in world space, after the
 * translucent pass, where perspective and the depth buffer do that work for free: the field goes
 * behind the wall it is behind, and a target can tell how far away its edge is.
 *
 * <h2>What the server sends, and what this makes of it</h2>
 *
 * <p>One small packet per hold plus a keepalive · {@code FlexStatePayload}. Everything else — where
 * the field is, how it moves, how dense it is on this machine — is derived here from the flexer's
 * own interpolated position, which means the field tracks a sprinting player smoothly instead of
 * stepping once per server tick, and a weak client can draw fewer particles without the server ever
 * knowing.
 *
 * <p>Blending is additive and the depth <em>write</em> is off while the depth <em>test</em> stays
 * on. Additive is what makes overlapping particles brighter rather than one occluding another, which
 * in turn means the order they are emitted in does not matter — and not writing depth is what stops
 * the front of the column from clipping away the back of it.
 */
public final class FlexRenderer {
	private FlexRenderer() {
	}

	/** One live particle system per flexer, keyed on entity id · {@link FlexAura}. */
	private static final Map<Integer, FlexAura> AURAS = new HashMap<>();

	/** Total particles alive last frame, which is what throttles this frame · budget. */
	private static int lastParticleCount;

	private static long lastFrameMillis;

	public static void register() {
		WorldRenderEvents.AFTER_TRANSLUCENT.register(FlexRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			AURAS.clear();
			return;
		}

		ClientFlexState.expire();
		Map<Integer, ClientFlexState.Field> fields = ClientFlexState.fields();
		prune(fields);

		if (fields.isEmpty() && AURAS.isEmpty()) {
			// Still resets the frame clock, or the first frame of the next field advances by however
			// long it has been since anybody last flexed.
			lastFrameMillis = 0L;
			return;
		}

		Camera camera = context.camera();
		Vec3 eye = camera.getPosition();
		Vector3f left = camera.getLeftVector();
		Vector3f up = camera.getUpVector();
		Matrix4f matrix = context.matrixStack().last().pose();
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);

		float dt = frameSeconds();
		float budget = budgetScale();
		// One clock for the whole frame, so every field boils against the same time base and two
		// flexers standing together do not drift out of phase with each other.
		float seconds = (System.currentTimeMillis() % 3_600_000L) / 1000.0f;

		double renderDistance = BleachTuning.FLEX_AURA_RENDER_DISTANCE;
		double renderDistanceSq = renderDistance * renderDistance;

		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(
				GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
				GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();

		BufferBuilder buffer = Tesselator.getInstance()
				.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

		int alive = 0;
		boolean drewAnything = false;

		for (Map.Entry<Integer, ClientFlexState.Field> entry : fields.entrySet()) {
			ClientFlexState.Field field = entry.getValue();
			Entity flexer = client.level.getEntity(entry.getKey());
			if (flexer == null) {
				continue;
			}

			Vec3 feet = flexer.getPosition(partialTick);
			if (feet.distanceToSqr(eye) > renderDistanceSq) {
				// Not drawn and not simulated: a field nobody can see still costs a couple of
				// thousand particles a frame if it is left ticking.
				continue;
			}

			FlexAura aura = AURAS.computeIfAbsent(entry.getKey(), FlexAura::new);
			if (field.onsetPending) {
				field.onsetPending = false;
				aura.kick();
			}

			aura.update(dt, feet.x, feet.y, feet.z, field.radius, field.tier, seconds, budget);
			aura.draw(buffer, matrix, left, up,
					(float) (feet.x - eye.x), (float) (feet.y - eye.y), (float) (feet.z - eye.z),
					field.radius, field.color);

			alive += aura.size();
			drewAnything = true;
		}

		lastParticleCount = alive;

		if (drewAnything) {
			BufferUploader.drawWithShader(buffer.buildOrThrow());
		} else {
			// Nothing was emitted, so the buffer holds no vertices and building it would throw. The
			// state below still has to come back off.
			buffer.build();
		}

		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
	}

	/**
	 * Drop the pool of any field that has ended.
	 *
	 * <p>Deliberately not the moment the packet arrives: a field whose particles are still in the air
	 * has to burn down rather than vanish, so the pool outlives its {@link ClientFlexState.Field} by
	 * however long its last particle had left. Once it is empty and the field is gone, so is this.
	 */
	private static void prune(Map<Integer, ClientFlexState.Field> fields) {
		if (AURAS.isEmpty()) {
			return;
		}

		for (Iterator<Map.Entry<Integer, FlexAura>> it = AURAS.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Integer, FlexAura> entry = it.next();
			if (!fields.containsKey(entry.getKey()) && entry.getValue().isEmpty()) {
				it.remove();
			}
		}
	}

	/**
	 * Seconds since the last frame, clamped.
	 *
	 * <p>Wall time rather than tick delta, because the simulation runs per frame and has to look the
	 * same at 30 fps as at 240. The clamp is what stops an alt-tab or a chunk load from resolving a
	 * particle's entire life in the first frame back.
	 */
	private static float frameSeconds() {
		long now = System.currentTimeMillis();
		if (lastFrameMillis == 0L) {
			lastFrameMillis = now;
			return 1.0f / 60.0f;
		}

		float dt = (now - lastFrameMillis) / 1000.0f;
		lastFrameMillis = now;
		return Mth.clamp(dt, 0.0f, 0.1f);
	}

	/**
	 * How hard to throttle spawning, 0..1 · {@code FLEX_AURA_BUDGET}.
	 *
	 * <p>Measured on last frame's population rather than on the number of fields, because that is the
	 * quantity that actually costs anything — one enormous field is the same load as six small ones,
	 * and the ceiling should be reached at the same place either way. Fields are throttled together
	 * rather than first-come-first-served, so a crowd dims uniformly instead of the last flexer in
	 * getting nothing.
	 */
	private static float budgetScale() {
		int budget = BleachTuning.FLEX_AURA_BUDGET;
		if (budget <= 0) {
			return 1.0f;
		}
		return Mth.clamp(1.0f - (float) lastParticleCount / budget, 0.15f, 1.0f);
	}

	/** Dropped on disconnect, so a field cannot survive into the next world. */
	public static void clear() {
		AURAS.clear();
		lastParticleCount = 0;
		lastFrameMillis = 0L;
	}
}
