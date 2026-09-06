package com.bleach.mod.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.network.EnmaKorogiSyncPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side state tracking for Tōsen's Enma Kōrogi Bankai domes.
 */
public final class ClientEnmaKorogiState {
	private ClientEnmaKorogiState() {
	}

	public record DomeData(UUID casterId, Vec3 center, double radius) {}

	private static final Map<UUID, DomeData> DOMES = new ConcurrentHashMap<>();

	public static void update(EnmaKorogiSyncPayload payload) {
		if (payload.active()) {
			DOMES.put(payload.casterId(), new DomeData(
					payload.casterId(),
					new Vec3(payload.x(), payload.y(), payload.z()),
					payload.radius()));
		} else {
			DOMES.remove(payload.casterId());
		}
	}

	public static void clear() {
		DOMES.clear();
	}

	/**
	 * Whether the local client player is the caster of an active Enma Kōrogi dome.
	 *
	 * <p>Drives the sprint lock: Enma Kōrogi blinds and deafens everyone else outright, so the cost
	 * that keeps it honest is paid in the caster's own mobility while it is up.
	 */
	public static boolean isCaster() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) {
			return false;
		}

		UUID selfId = client.player.getUUID();
		for (DomeData dome : DOMES.values()) {
			if (dome.casterId().equals(selfId)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the local client player is inside any active Enma Kōrogi dome and is NOT the caster.
	 */
	public static boolean isAffected() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.player.isSpectator()) {
			return false;
		}

		UUID selfId = client.player.getUUID();
		Vec3 selfPos = client.player.position();

		for (DomeData dome : DOMES.values()) {
			if (dome.casterId().equals(selfId)) {
				continue;
			}
			if (selfPos.distanceToSqr(dome.center()) <= dome.radius() * dome.radius()) {
				return true;
			}
		}

		return false;
	}
}
