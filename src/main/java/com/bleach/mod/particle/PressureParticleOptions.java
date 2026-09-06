package com.bleach.mod.particle;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * The spawn-time payload of {@link BleachParticles#PRESSURE}: a packed RGB tint and a scale.
 *
 * <p>A {@code SimpleParticleType} with the colour baked into the client factory would have been
 * roughly forty fewer lines, and would have given every kit the same colour. The tint is
 * {@code Kit.particleColor}, which is the mod's whole visual identity for one 8×8 asset (PRD §5.3),
 * so it has to travel with each spawn rather than with the type.
 *
 * @param color packed RGB, from {@code Kit#particleColor}
 * @param scale quad size multiplier
 */
public record PressureParticleOptions(int color, float scale) implements ParticleOptions {

	public static final MapCodec<PressureParticleOptions> CODEC = RecordCodecBuilder.mapCodec(
			instance -> instance.group(
					com.mojang.serialization.Codec.INT.fieldOf("color")
							.forGetter(PressureParticleOptions::color),
					com.mojang.serialization.Codec.FLOAT.fieldOf("scale")
							.forGetter(PressureParticleOptions::scale)
			).apply(instance, PressureParticleOptions::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, PressureParticleOptions> STREAM_CODEC =
			StreamCodec.of(
					(buf, options) -> {
						buf.writeInt(options.color);
						buf.writeFloat(options.scale);
					},
					buf -> new PressureParticleOptions(buf.readInt(), buf.readFloat()));

	@Override
	public ParticleType<?> getType() {
		return BleachParticles.PRESSURE;
	}
}
