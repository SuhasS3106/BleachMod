package com.bleach.mod.network;

import com.bleach.mod.ModToggle;
import com.bleach.mod.ability.AbilityDispatcher;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Payload registration and the server-side receivers. Types must be registered on both sides, so
 * this runs from the common initializer; the client-side receivers live in {@code BleachModClient}.
 *
 * <p>Fabric runs play-phase receivers on the server thread, so the handlers below can touch world
 * state directly without an {@code execute} hop.
 */
public final class BleachNetworking {
	private BleachNetworking() {
	}

	/** Operator level required to throw the master switch. */
	private static final int TOGGLE_PERMISSION_LEVEL = 2;

	public static void register() {
		PayloadTypeRegistry.playS2C().register(SpiritualSyncPayload.TYPE, SpiritualSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(ModTogglePayload.State.TYPE, ModTogglePayload.State.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(EnmaKorogiSyncPayload.TYPE, EnmaKorogiSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(GinBeamStatePayload.TYPE, GinBeamStatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(AuraSensePayload.TYPE, AuraSensePayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(SpxGainPayload.TYPE, SpxGainPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(KaromatsuSyncPayload.TYPE, KaromatsuSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(MiracleSyncPayload.TYPE, MiracleSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(DomeTintPayload.TYPE, DomeTintPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(FlexStatePayload.TYPE, FlexStatePayload.STREAM_CODEC);

		PayloadTypeRegistry.playC2S().register(AbilityActivatePayload.TYPE, AbilityActivatePayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(ModTogglePayload.Request.TYPE, ModTogglePayload.Request.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(GinBeamPayload.TYPE, GinBeamPayload.STREAM_CODEC);

		ServerPlayNetworking.registerGlobalReceiver(AbilityActivatePayload.TYPE,
				(payload, context) -> AbilityDispatcher.handle(context.player(), payload.action()));

		ServerPlayNetworking.registerGlobalReceiver(ModTogglePayload.Request.TYPE,
				(payload, context) -> onToggleRequest(context.player()));

		ServerPlayNetworking.registerGlobalReceiver(GinBeamPayload.TYPE,
				(payload, context) -> com.bleach.mod.ability.kits.GinTransform.handleBeam(context.player(), payload));
	}

	private static void onToggleRequest(ServerPlayer player) {
		if (!player.hasPermissions(TOGGLE_PERMISSION_LEVEL)) {
			// Told rather than ignored: a key that does nothing and says nothing reads as a bug.
			player.displayClientMessage(
					Component.literal("You do not have permission to toggle the Bleach mod."), true);
			return;
		}

		ModToggle.toggle(player.server);
		player.displayClientMessage(ModToggle.statusMessage(), true);
	}

	public static void sendSync(ServerPlayer player, SpiritualSyncPayload payload) {
		ServerPlayNetworking.send(player, payload);
	}

	/** Tell one player what they just earned, for the HUD popup · {@code SpxGainPayload}. */
	public static void sendSpxGain(ServerPlayer player, int amount) {
		if (amount <= 0) {
			return;
		}
		ServerPlayNetworking.send(player, new SpxGainPayload(amount));
	}
}
