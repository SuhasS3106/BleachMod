package com.bleach.mod;

import com.bleach.mod.client.BleachKeybinds;
import com.bleach.mod.client.ClientSpiritualState;
import com.bleach.mod.client.FreezeOverlay;
import com.bleach.mod.client.InvisibleEntityRenderer;
import com.bleach.mod.client.ScreenShake;
import com.bleach.mod.client.SpiritualHud;
import com.bleach.mod.client.particle.PressureParticle;
import com.bleach.mod.entity.BleachEntities;
import com.bleach.mod.network.ModTogglePayload;
import com.bleach.mod.network.SpiritualSyncPayload;
import com.bleach.mod.particle.BleachParticles;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

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

		ClientPlayNetworking.registerGlobalReceiver(com.bleach.mod.network.SpxGainPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> com.bleach.mod.client.SpxGainPopups.add(payload.amount())));

		ClientPlayNetworking.registerGlobalReceiver(com.bleach.mod.network.MiracleSyncPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> com.bleach.mod.client.ClientMiracleState.update(payload)));

		ClientPlayNetworking.registerGlobalReceiver(com.bleach.mod.network.KaromatsuSyncPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> com.bleach.mod.client.ClientKaromatsuState.update(payload)));

		ClientPlayNetworking.registerGlobalReceiver(com.bleach.mod.network.DomeTintPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> com.bleach.mod.client.ClientDomeState.setInside(payload.inside())));

		ClientPlayNetworking.registerGlobalReceiver(com.bleach.mod.network.FlexStatePayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> com.bleach.mod.client.ClientFlexState.update(payload)));

		// Otherwise the bar from the last world flashes up before the first sync of the next one.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientSpiritualState.clear();
			com.bleach.mod.client.SpxGainPopups.reset();
			com.bleach.mod.client.ClientAuraSenseState.clear();
			com.bleach.mod.client.ClientEnmaKorogiState.clear();
			com.bleach.mod.client.ClientGinBeamState.clear();
			com.bleach.mod.client.ClientKaromatsuState.clear();
			com.bleach.mod.client.ClientMiracleState.clear();
			com.bleach.mod.client.ClientDomeState.clear();
			com.bleach.mod.client.ClientFlexState.clear();
			// Both halves: the state is who is flexing, the renderer is the particles already in the
			// air for them. Dropping only the first leaves pools keyed on entity ids that the next
			// world will hand to somebody else.
			com.bleach.mod.client.FlexRenderer.clear();
		});

		ParticleFactoryRegistry.getInstance().register(BleachParticles.PRESSURE, PressureParticle.Provider::new);

		EntityRendererRegistry.register(BleachEntities.REISHI_ARROW, InvisibleEntityRenderer::new);

		com.bleach.mod.client.ZanpakutoModels.register();

		BleachKeybinds.register();

		// Before the bar, so the overlays sit under it rather than over the numbers it is
		// meant to make harder to act on.
		ScreenShake.register();
		FreezeOverlay.register();
		com.bleach.mod.client.EnmaKorogiOverlay.register();
		com.bleach.mod.client.KaromatsuOverlay.register();
		com.bleach.mod.client.MiracleOverlay.register();
		com.bleach.mod.client.GinBeamClient.register();

		// World space rather than the HUD: the field is a thing standing in the world, so it is drawn
		// into the scene after the translucent pass · FlexRenderer.
		com.bleach.mod.client.FlexRenderer.register();

		// After the other overlays and before the bar: the eyelid has to cover the blackout's own
		// layer as well as everything under it, and the auras are drawn in front of the lid.
		com.bleach.mod.client.AuraSenseOverlay.register();
		com.bleach.mod.client.DomeTintOverlay.register();
		com.bleach.mod.client.HeiligBogenModels.register();
		SpiritualHud.register();
	}
}
