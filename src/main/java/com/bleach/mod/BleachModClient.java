package com.bleach.mod;

import com.bleach.mod.client.BleachKeybinds;
import com.bleach.mod.client.ClientSpiritualState;
import com.bleach.mod.client.FreezeOverlay;
import com.bleach.mod.client.ScreenShake;
import com.bleach.mod.client.SpiritualHud;
import com.bleach.mod.client.particle.PressureParticle;
import com.bleach.mod.network.ModTogglePayload;
import com.bleach.mod.network.SpiritualSyncPayload;
import com.bleach.mod.particle.BleachParticles;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;

public class BleachModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(SpiritualSyncPayload.TYPE,
				(payload, context) -> context.client().execute(() -> ClientSpiritualState.set(payload)));

		ClientPlayNetworking.registerGlobalReceiver(ModTogglePayload.State.TYPE,
				(payload, context) -> context.client().execute(
						() -> ClientSpiritualState.setEnabled(payload.enabled())));

		ClientPlayNetworking.registerGlobalReceiver(com.bleach.mod.network.EnmaKorogiSyncPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> com.bleach.mod.client.ClientEnmaKorogiState.update(payload)));

		ClientPlayNetworking.registerGlobalReceiver(com.bleach.mod.network.GinBeamStatePayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> com.bleach.mod.client.ClientGinBeamState.update(payload)));

		ClientPlayNetworking.registerGlobalReceiver(com.bleach.mod.network.AuraSensePayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> com.bleach.mod.client.ClientAuraSenseState.update(payload)));

		// Otherwise the bar from the last world flashes up before the first sync of the next one.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientSpiritualState.clear();
			com.bleach.mod.client.ClientAuraSenseState.clear();
			com.bleach.mod.client.ClientEnmaKorogiState.clear();
			com.bleach.mod.client.ClientGinBeamState.clear();
		});

		ParticleFactoryRegistry.getInstance().register(BleachParticles.PRESSURE, PressureParticle.Provider::new);

		com.bleach.mod.client.ZanpakutoModels.register();

		BleachKeybinds.register();

		// Before the bar, so the overlays sit under it rather than over the numbers it is
		// meant to make harder to act on.
		ScreenShake.register();
		FreezeOverlay.register();
		com.bleach.mod.client.EnmaKorogiOverlay.register();
		com.bleach.mod.client.GinBeamClient.register();

		// After the other overlays and before the bar: the eyelid has to cover the blackout's own
		// layer as well as everything under it, and the auras are drawn in front of the lid.
		com.bleach.mod.client.AuraSenseOverlay.register();
		SpiritualHud.register();
	}
}
