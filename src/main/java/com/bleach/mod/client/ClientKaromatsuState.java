package com.bleach.mod.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.network.KaromatsuSyncPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side state tracking for Shunsui Kyōraku's Bankai (Karamatsu Shinjū) zones.
 *
 * <p>Populated and cleared by the {@link KaromatsuSyncPayload} handler registered in
 * {@code BleachModClient}. Queried by {@link KaromatsuOverlay} to determine what tint (if any)
 * to render, and from which act.
 */
public final class ClientKaromatsuState {
	private ClientKaromatsuState() {
	}

	public record ZoneData(UUID casterId, Vec3 center, float radius, byte actIndex) {}

	/** Active zones, keyed by caster UUID. */
	private static final Map<UUID, ZoneData> ZONES = new ConcurrentHashMap<>();

	public static void update(KaromatsuSyncPayload payload) {
		if (payload.active()) {
			ZONES.put(payload.casterId(), new ZoneData(
					payload.casterId(),
					new Vec3(payload.x(), payload.y(), payload.z()),
					payload.radius(),
					payload.actIndex()));
		} else {
			ZONES.remove(payload.casterId());
		}
	}

	public static void clear() {
		ZONES.clear();
	}

	/**
	 * Whether the local player is inside any active zone and is NOT the caster.
	 *
	 * <p>The gloom overlay renders only for affected players, never for the caster.
	 */
	public static boolean isAffected() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.player.isSpectator()) {
			return false;
		}

		UUID selfId = client.player.getUUID();
		Vec3 selfPos = client.player.position();

		for (ZoneData zone : ZONES.values()) {
			if (zone.casterId().equals(selfId)) {
				continue; // caster is exempt from the overlay
			}
			double radiusSq = (double) zone.radius() * zone.radius();
			if (selfPos.distanceToSqr(zone.center()) <= radiusSq) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The act index of the innermost (any) zone the local player is inside, or {@code -1} if none.
	 *
	 * <p>In practice there is at most one active zone per server, but the structure supports
	 * multiple without modification.
	 */
	public static byte currentAct() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return -1;
		}

		UUID selfId = client.player.getUUID();
		Vec3 selfPos = client.player.position();

		for (ZoneData zone : ZONES.values()) {
			if (zone.casterId().equals(selfId)) {
				continue;
			}
			double radiusSq = (double) zone.radius() * zone.radius();
			if (selfPos.distanceToSqr(zone.center()) <= radiusSq) {
				return zone.actIndex();
			}
		}
		return -1;
	}
}
