package com.bleach.mod.client;

import com.bleach.mod.ability.kits.BleachKits;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.item.ZanpakutoItem;
import com.bleach.mod.network.GinBeamPayload;
import com.bleach.mod.network.SpiritualSyncPayload;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * Client controller for Gin Ichimaru's Kamishini no Yari continuous beam in Bankai.
 *
 * <p>Tracks left-click held state and calculates angular look velocity per tick to report swipes.
 */
public final class GinBeamClient {
	private GinBeamClient() {
	}

	private static float prevYaw;
	private static float prevPitch;
	private static boolean wasHeld;

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(GinBeamClient::onClientTick);
	}

	private static void onClientTick(Minecraft client) {
		// Every beam in view, including other players', is drawn from the caster entity itself.
		ClientGinBeamState.tick(client);

		if (client.player == null) {
			wasHeld = false;
			return;
		}

		ItemStack held = client.player.getMainHandItem();
		boolean isGinBlade = held.getItem() instanceof ZanpakutoItem zanpakuto
				&& BleachKits.GIN.equals(zanpakuto.kitId());

		SpiritualSyncPayload sync = ClientSpiritualState.get();
		boolean isBankai = sync != null && sync.state() == SpiritualData.STATE_BANKAI;

		float curYaw = client.player.getYRot();
		float curPitch = client.player.getXRot();
		float dYaw = Math.abs(curYaw - prevYaw);
		float dPitch = Math.abs(curPitch - prevPitch);
		// Wrap yaw delta
		if (dYaw > 180.0f) {
			dYaw = 360.0f - dYaw;
		}
		float angularSpeed = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);

		prevYaw = curYaw;
		prevPitch = curPitch;

		boolean shouldBeam = isGinBlade && isBankai && client.options.keyAttack.isDown() && client.screen == null;

		if (shouldBeam) {
			wasHeld = true;
			ClientPlayNetworking.send(new GinBeamPayload(true, angularSpeed));

			// No local particles. The beam leaves the eye along the exact look vector, so drawing it
			// for its own caster paints a solid line over the crosshair and hides the target. Gin
			// feels the blade; everyone else sees it. The server sends it to every other player.
		} else if (wasHeld) {
			wasHeld = false;
			ClientPlayNetworking.send(new GinBeamPayload(false, 0.0f));
		}
	}
}
