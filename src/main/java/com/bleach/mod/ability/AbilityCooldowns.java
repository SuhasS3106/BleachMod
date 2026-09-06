package com.bleach.mod.ability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player ability cooldowns, keyed off {@code MinecraftServer#getTickCount}.
 *
 * <p>Deliberately <b>not</b> persisted and not on {@code SpiritualData}. A cooldown is worth at most
 * a few seconds; carrying it across a logout would mean writing an absolute tick count into a save
 * file whose tick counter restarts, and the only exploit it would close — relogging to skip a
 * 1.5-second Flash Step cooldown — costs more than it saves.
 */
public final class AbilityCooldowns {
	private AbilityCooldowns() {
	}

	/** Player → ability → server tick at which it becomes ready again. */
	private static final Map<UUID, Map<ResourceLocation, Long>> EXPIRY = new HashMap<>();

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clear(handler.player));
	}

	public static boolean isReady(ServerPlayer player, ResourceLocation ability) {
		Map<ResourceLocation, Long> byAbility = EXPIRY.get(player.getUUID());
		if (byAbility == null) {
			return true;
		}
		Long expiry = byAbility.get(ability);
		return expiry == null || player.server.getTickCount() >= expiry;
	}

	/** Ticks left, for the HUD and for command feedback. Zero when ready. */
	public static int remaining(ServerPlayer player, ResourceLocation ability) {
		Map<ResourceLocation, Long> byAbility = EXPIRY.get(player.getUUID());
		if (byAbility == null) {
			return 0;
		}
		Long expiry = byAbility.get(ability);
		if (expiry == null) {
			return 0;
		}
		return (int) Math.max(0L, expiry - player.server.getTickCount());
	}

	public static void start(ServerPlayer player, ResourceLocation ability, int ticks) {
		if (ticks <= 0) {
			return;
		}
		EXPIRY.computeIfAbsent(player.getUUID(), uuid -> new HashMap<>())
				.put(ability, (long) player.server.getTickCount() + ticks);
	}

	/**
	 * Drop one cooldown. The refund path: an ability that aborts partway through
	 * {@link Ability#onActivate} has already had its cooldown started by the dispatcher, and
	 * charging a player for a Flash Step that never happened is the one thing worse than the step
	 * failing.
	 */
	public static void clear(ServerPlayer player, ResourceLocation ability) {
		Map<ResourceLocation, Long> byAbility = EXPIRY.get(player.getUUID());
		if (byAbility != null) {
			byAbility.remove(ability);
		}
	}

	/** Drop every cooldown for one player — disconnect, death, and the mod being switched off. */
	public static void clear(ServerPlayer player) {
		EXPIRY.remove(player.getUUID());
	}

	/** Drop every cooldown for everyone. Used when the mod is toggled back on. */
	public static void clearAll() {
		EXPIRY.clear();
	}
}
