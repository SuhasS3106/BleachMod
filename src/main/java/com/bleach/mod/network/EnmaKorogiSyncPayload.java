package com.bleach.mod.network;

import java.util.UUID;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-Client synchronization payload for Kaname Tōsen's Bankai dome (Enma Kōrogi).
 *
 * <p>Notifies clients of the dome's center coordinates, radius, and whether it is being created or removed.
 */
public record EnmaKorogiSyncPayload(
		UUID casterId,
		double x,
		double y,
		double z,
		float radius,
		boolean active
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<EnmaKorogiSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("enma_korogi_sync"));

	public static final StreamCodec<FriendlyByteBuf, EnmaKorogiSyncPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeUUID(payload.casterId);
				buf.writeDouble(payload.x);
				buf.writeDouble(payload.y);
				buf.writeDouble(payload.z);
				buf.writeFloat(payload.radius);
				buf.writeBoolean(payload.active);
			},
			buf -> new EnmaKorogiSyncPayload(
					buf.readUUID(),
					buf.readDouble(),
					buf.readDouble(),
					buf.readDouble(),
					buf.readFloat(),
					buf.readBoolean()));

	@Override
	public CustomPacketPayload.Type<EnmaKorogiSyncPayload> type() {
		return TYPE;
	}
}
