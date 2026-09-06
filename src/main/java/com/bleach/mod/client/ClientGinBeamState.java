package com.bleach.mod.client;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.network.GinBeamStatePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side rendering of every Kamishini no Yari beam currently extended nearby.
 *
 * <p>The server sends only "entity N is beaming" / "entity N stopped". Everything about the beam's
 * shape is derived here from the caster entity the client is already tracking, which is what makes
 * the whole thing cost two packets per hold instead of a hundred and fifteen per tick — see
 * {@link GinBeamStatePayload}.
 *
 * <p>Drawing locally is also strictly better looking. The client has the caster's interpolated
 * rotation, so a beam swung across a target sweeps smoothly rather than stepping once per server
 * tick, and the density can be tuned to the viewer's machine rather than to the network.
 */
public final class ClientGinBeamState {
	private ClientGinBeamState() {
	}

	/**
	 * How long a beam survives without being reasserted, in client ticks.
	 *
	 * <p>Longer than the server's keepalive interval so an ordinary hold never flickers, short
	 * enough that a missed stop — a caster who died, disconnected or fell out of range mid-swing —
	 * clears within about a second instead of leaving a beam hanging in the air forever.
	 */
	private static final int EXPIRY_TICKS = 30;

	/** Spacing between particles along the beam, in blocks. Purely a client-side quality dial. */
	private static final double STEP = 0.5;

	/** Maximum length drawn. Matches {@code GIN_BANKAI_BEAM_RANGE}; the server owns the real reach. */
	private static final double RANGE = 70.0;

	/** Caster entity id -> client tick at which the beam expires if not reasserted. */
	private static final Map<Integer, Integer> ACTIVE = new ConcurrentHashMap<>();

	public static void update(GinBeamStatePayload payload) {
		Minecraft client = Minecraft.getInstance();
		if (payload.active()) {
			ACTIVE.put(payload.entityId(), tickCount(client) + EXPIRY_TICKS);
		} else {
			ACTIVE.remove(payload.entityId());
		}
	}

	public static void clear() {
		ACTIVE.clear();
	}

	private static int tickCount(Minecraft client) {
		return client.player == null ? 0 : client.player.tickCount;
	}

	/** Called once per client tick. Draws every live beam and drops the stale ones. */
	public static void tick(Minecraft client) {
		if (ACTIVE.isEmpty() || client.level == null || client.player == null) {
			return;
		}

		int now = client.player.tickCount;
		ClientLevel level = client.level;

		Iterator<Map.Entry<Integer, Integer>> it = ACTIVE.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Integer> entry = it.next();
			if (entry.getValue() <= now) {
				it.remove();
				continue;
			}

			Entity caster = level.getEntity(entry.getKey());
			if (caster == null) {
				// Out of tracking range or already removed; nothing to draw from.
				it.remove();
				continue;
			}

			// The caster never sees their own blade. It leaves the eye along the exact look vector,
			// so drawn for its owner it is an opaque wall over the crosshair.
			if (caster == client.player) {
				continue;
			}

			draw(level, caster);
		}
	}

	private static void draw(ClientLevel level, Entity caster) {
		Vec3 eye = caster.getEyePosition();
		Vec3 look = caster.getLookAngle();

		for (double d = 1.0; d <= RANGE; d += STEP) {
			Vec3 point = eye.add(look.scale(d));
			level.addParticle(ParticleTypes.END_ROD, point.x, point.y, point.z, 0.0, 0.0, 0.0);
		}
	}
}
