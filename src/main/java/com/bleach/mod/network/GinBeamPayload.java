package com.bleach.mod.network;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-Server payload for Gin Ichimaru's Kamishini no Yari continuous beam in Bankai.
 *
 * <p>Transmits whether the attack key is held down and the client's current angular mouse swipe speed
 * in degrees per tick.
 */
public record GinBeamPayload(boolean active, float angularSpeed) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<GinBeamPayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("gin_beam"));

	public static final StreamCodec<FriendlyByteBuf, GinBeamPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeBoolean(payload.active);
				buf.writeFloat(payload.angularSpeed);
			},
			buf -> new GinBeamPayload(buf.readBoolean(), buf.readFloat()));

	@Override
	public CustomPacketPayload.Type<GinBeamPayload> type() {
		return TYPE;
	}
}
