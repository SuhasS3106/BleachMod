package com.bleach.mod;

import com.bleach.mod.ability.AbilityCooldowns;
import com.bleach.mod.ability.common.AuraSense;
import com.bleach.mod.ability.common.Hover;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.network.ModTogglePayload;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The master on/off switch, and the one place that owns it.
 *
 * <p>Existing to answer a question that comes up constantly during development: <em>is the mod
 * doing this, or is Minecraft?</em> Switching it off has to leave the game genuinely untouched, so
 * "off" means all of:
 *
 * <ul>
 *   <li>{@code SpiritualTicker} does nothing at all — no regen, no drain, no exertion, no playtime
 *       accumulation, no packets</li>
 *   <li>{@code AbilityDispatcher} drops every keypress</li>
 *   <li>the HUD does not render</li>
 * </ul>
 *
 * <p>Anyone mid-transformation when the switch is thrown is reverted first, through the normal
 * revert path. Leaving a player in Bankai with the ticker asleep would strand them there — and,
 * once Phase 6 attaches attribute modifiers to that state, would leave those modifiers applied with
 * nothing running that could ever take them off.
 *
 * <p>Persistence is free: the flag is a {@link BleachTuning} field, so it round-trips through
 * {@code tuning.json} like every other tunable. That does mean {@code /bleach reload} restores the
 * value from disk — which is why toggling writes the file rather than only flipping the field.
 */
public final class ModToggle {
	private ModToggle() {
	}

	public static void register() {
		// The client cannot draw the bar correctly until it knows the flag, and it has no default it
		// could safely assume, so this is sent unconditionally on join.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				ServerPlayNetworking.send(handler.player, new ModTogglePayload.State(isEnabled())));
	}

	public static boolean isEnabled() {
		return BleachTuning.MOD_ENABLED;
	}

	/**
	 * Flip the flag, tear down anything that was running under it, tell every client, and persist.
	 * Safe to call with the value it already has — it re-broadcasts and returns.
	 */
	public static void set(MinecraftServer server, boolean enabled) {
		if (BleachTuning.MOD_ENABLED == enabled) {
			broadcast(server);
			return;
		}

		BleachTuning.MOD_ENABLED = enabled;
		apply(server, enabled);
		BleachTuning.save();

		BleachMod.LOGGER.info("Bleach mod {}", enabled ? "enabled" : "disabled");
	}

	/**
	 * Re-assert the current flag without writing it back out. Used after {@code /bleach reload},
	 * which can change {@code MOD_ENABLED} from the config file without anyone having toggled it.
	 */
	public static void reapply(MinecraftServer server) {
		apply(server, isEnabled());
	}

	private static void apply(MinecraftServer server, boolean enabled) {
		if (!enabled) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				SpiritualData data = BleachAttachments.get(player);
				if (data.isTransformed()) {
					SpiritualTicker.forceRevert(player, data);
				}
				data.flexing = false;

				// Through stop() rather than by clearing the flag: switching the mod off must not
				// leave a sensor sitting behind an eyelid that only an inactive payload can lift.
				AuraSense.stop(player, data);

				// And the same for a hover, which owes the player their gravity back — switching the
				// mod off must not leave anyone floating with nothing running that could put them down.
				Hover.stop(player, data);
			}
			AbilityCooldowns.clearAll();
		}

		broadcast(server);

		if (enabled) {
			// The clients have been rendering nothing; give them a current bar rather than whatever
			// they were last told before the switch went off.
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				SpiritualTicker.sync(player, true);
			}
		}
	}

	public static void toggle(MinecraftServer server) {
		set(server, !isEnabled());
	}

	private static void broadcast(MinecraftServer server) {
		ModTogglePayload.State payload = new ModTogglePayload.State(isEnabled());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** Shared wording, so the keybind and the command say the same thing. */
	public static Component statusMessage() {
		return Component.literal(isEnabled() ? "Bleach mod enabled." : "Bleach mod disabled.");
	}
}
