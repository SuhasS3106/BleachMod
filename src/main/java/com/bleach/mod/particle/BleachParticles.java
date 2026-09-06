package com.bleach.mod.particle;

import com.bleach.mod.BleachMod;

import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The mod's only particle type · PRD §5.3.
 *
 * <p>One 8×8 PNG and one definition file, tinted per kit at spawn time. Flash Step, Spiritual Flex
 * and every later ability draw from the same asset rather than shipping one each — the colour is
 * what tells them apart.
 *
 * <p>Registered on <b>both</b> sides: the type is common (the server sends it in a particle packet),
 * the factory that turns it into pixels is client-only and lives in
 * {@code client.particle.PressureParticle}.
 */
public final class BleachParticles {
	private BleachParticles() {
	}

	public static final ParticleType<PressureParticleOptions> PRESSURE = FabricParticleTypes.complex(
			PressureParticleOptions.CODEC, PressureParticleOptions.STREAM_CODEC);

	public static void register() {
		Registry.register(BuiltInRegistries.PARTICLE_TYPE, BleachMod.id("pressure"), PRESSURE);
	}
}
