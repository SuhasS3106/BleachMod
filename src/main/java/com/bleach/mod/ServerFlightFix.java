package com.bleach.mod;

import java.lang.reflect.Field;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;

/**
 * Hover exempts a player from the vanilla flight-kick check with {@code setNoGravity}, but the
 * packet listener's own gate on that path still requires {@code allow-flight=true} in
 * server.properties — a setting Hover has nothing to do with and a host can easily forget to flip.
 * Rather than make that a manual setup step, force it on every dedicated server start.
 */
final class ServerFlightFix {
	private ServerFlightFix() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(ServerFlightFix::apply);
	}

	private static void apply(MinecraftServer server) {
		if (!(server instanceof DedicatedServer dedicated) || dedicated.isFlightAllowed()) {
			return;
		}

		DedicatedServerProperties properties = dedicated.getProperties();
		try {
			Field allowFlight = DedicatedServerProperties.class.getField("allowFlight");
			allowFlight.setAccessible(true);
			allowFlight.set(properties, true);
			BleachMod.LOGGER.info(
					"[bleach_mod] allow-flight was false in server.properties; enabled it for this "
							+ "session so Hover does not get players flight-kicked.");
		} catch (ReflectiveOperationException e) {
			BleachMod.LOGGER.warn(
					"[bleach_mod] Could not force allow-flight on automatically; set "
							+ "allow-flight=true in server.properties manually or Hover will get "
							+ "players kicked.", e);
		}
	}
}
