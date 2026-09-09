package com.bleach.mod.client;

import com.bleach.mod.ability.AbilityAction;
import com.bleach.mod.network.AbilityActivatePayload;
import com.bleach.mod.network.ModTogglePayload;
import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Every key the mod owns, and the only place a packet is sent from the client.
 *
 * <p>The client's entire job here is to report edges. It does not check SP, cooldowns, gates, the
 * player's kit or whether the sword is drawn — all of that is
 * {@code AbilityDispatcher}'s, and duplicating any of it would be a prediction that can desync.
 */
public final class BleachKeybinds {
	private BleachKeybinds() {
	}

	private static final String CATEGORY = "key.categories.bleach_mod";

	public static final KeyMapping FLASH_STEP = register("flash_step", GLFW.GLFW_KEY_V);
	public static final KeyMapping SHIKAI = register("shikai", GLFW.GLFW_KEY_R);
	public static final KeyMapping BANKAI = register("bankai", GLFW.GLFW_KEY_G);
	public static final KeyMapping FLEX = register("flex", GLFW.GLFW_KEY_LEFT_ALT);
	public static final KeyMapping AURA_SENSE = register("aura_sense", GLFW.GLFW_KEY_C);
	public static final KeyMapping HOVER = register("hover", GLFW.GLFW_KEY_Q);
	public static final KeyMapping DRAW_SHEATHE = register("draw_sheathe", GLFW.GLFW_KEY_X);
	public static final KeyMapping STATS = register("stats", GLFW.GLFW_KEY_K);
	public static final KeyMapping TOGGLE_MOD = register("toggle_mod", GLFW.GLFW_KEY_B);
	public static final KeyMapping BLUT = register("blut", GLFW.GLFW_KEY_Z);
	/**
	 * The kit's own move for whichever tier you are in. T was the only key left, and it is the one
	 * the reference moveset uses — its G, X, Z and C are all taken here by Vollständig,
	 * draw/sheathe, Blut and Aura Sense, none of which can move without breaking every kit.
	 */
	public static final KeyMapping KIT_ABILITY = register("kit_ability", GLFW.GLFW_KEY_T);

	/**
	 * Last reported state of the hold key. The client owns this because only the client can see the
	 * key; the server sees edges and nothing else.
	 */
	private static boolean flexing;

	/** The same, for the Aura Sense hold. Both edges are the client's to report and nobody else's. */
	private static boolean sensing;

	/** And for the Hover hold. The key it took back is vanilla's drop-item, which the mod already
	 * suppresses while the zanpakutō is out · {@code LocalPlayerDropMixin}. */
	private static boolean hovering;

	private static KeyMapping register(String name, int key) {
		return KeyBindingHelper.registerKeyBinding(
				new KeyMapping("key.bleach_mod." + name, InputConstants.Type.KEYSYM, key, CATEGORY));
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(BleachKeybinds::onEndTick);

		// A disconnect with the key held would otherwise leave the flag set on the client, so the
		// next world would open mid-flex with nothing having pressed anything.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			flexing = false;
			sensing = false;
			hovering = false;
			ClientHoverState.clear();
		});
	}

	private static void onEndTick(Minecraft client) {
		if (client.player == null) {
			flexing = false;
			sensing = false;
			hovering = false;
			ClientHoverState.clear();
			return;
		}

		// Press bindings: drain the queue rather than reading it, so a press that landed during a
		// lag spike still fires exactly once and cannot be double-counted.
		while (FLASH_STEP.consumeClick()) {
			send(AbilityAction.FLASH_STEP);
		}
		while (SHIKAI.consumeClick()) {
			send(AbilityAction.SHIKAI);
		}
		while (BANKAI.consumeClick()) {
			send(AbilityAction.BANKAI);
		}
		while (DRAW_SHEATHE.consumeClick()) {
			send(AbilityAction.DRAW_SHEATHE);
		}
		// Sent unconditionally; the server drops it silently for a race without Blut, so the client
		// never needs to know what race it is to decide whether a key is live.
		while (BLUT.consumeClick()) {
			send(AbilityAction.BLUT_CYCLE);
		}

		while (KIT_ABILITY.consumeClick()) {
			send(AbilityAction.KIT_ABILITY);
		}

		while (TOGGLE_MOD.consumeClick()) {
			ClientPlayNetworking.send(new ModTogglePayload.Request());
		}

		// Client-only; opens a screen and sends nothing. Every number on it is already here, in the
		// payload the HUD renders from.
		while (STATS.consumeClick()) {
			client.setScreen(new SoulStatsScreen());
		}

		// The hold binding. A screen being open counts as released: the key is captured by the GUI,
		// so the falling edge would never arrive and the channel would run until the screen closed.
		boolean down = FLEX.isDown() && client.screen == null;
		if (down != flexing) {
			flexing = down;
			send(down ? AbilityAction.FLEX_START : AbilityAction.FLEX_STOP);
		}

		// The other hold. Same screen rule, same reason.
		boolean sensingDown = AURA_SENSE.isDown() && client.screen == null;
		if (sensingDown != sensing) {
			sensing = sensingDown;
			send(sensing ? AbilityAction.SENSE_START : AbilityAction.SENSE_STOP);

			// The eyelid falls only once the server has answered with a reading, but it lifts the
			// instant the key comes up — a release that waited on a round trip is a release that
			// leaves the player blind for as long as their ping, which is exactly the moment they
			// wanted their eyes back.
			ClientAuraSenseState.setKeyHeld(sensing);
		}

		// The third hold, on the same screen rule. The local flag is set before the packet rather
		// than after the server answers: it is one half of the handshake in ClientHoverState, and
		// the half that has to fall on the frame the key comes up — a release that waited on a
		// round trip is a release that keeps the player up there after they asked to drop.
		boolean hoverDown = HOVER.isDown() && client.screen == null;
		if (hoverDown != hovering) {
			hovering = hoverDown;
			ClientHoverState.setKeyHeld(hovering);
			send(hovering ? AbilityAction.HOVER_START : AbilityAction.HOVER_STOP);
		}
	}

	private static void send(AbilityAction action) {
		ClientPlayNetworking.send(AbilityActivatePayload.of(action));
	}
}
