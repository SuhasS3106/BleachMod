package com.bleach.mod.network;

import java.util.UUID;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server-to-Client synchronization payload for Shunsui Kyōraku's Bankai (Karamatsu Shinjū).
 *
 * <p>Notifies clients of the zone's center, radius, current act, and whether it is active.
 * Sent on Bankai entry, each act advancement, abort/revert (active=false), and player join.
 *
 * <h2>Act index encoding</h2>
 * <ul>
 *   <li>{@code  0} — PRE_ACT</li>
 *   <li>{@code  1} — ACT_1 (shared-damage link)</li>
 *   <li>{@code  2} — ACT_2 (bleed marks)</li>
 *   <li>{@code  3} — ACT_3 (SP drain race / underwater)</li>
 *   <li>{@code  4} — FINAL_ACT (thread charge)</li>
 *   <li>{@code -1} — ended / cleared</li>
 * </ul>
 */
public record KaromatsuSyncPayload(
		UUID casterId,
		double x,
		double y,
		double z,
		float radius,
		byte actIndex,
		boolean active
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<KaromatsuSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("karomatsu_sync"));

	public static final StreamCodec<FriendlyByteBuf, KaromatsuSyncPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeUUID(payload.casterId);
				buf.writeDouble(payload.x);
				buf.writeDouble(payload.y);
				buf.writeDouble(payload.z);
				buf.writeFloat(payload.radius);
				buf.writeByte(payload.actIndex);
				buf.writeBoolean(payload.active);
			},
			buf -> new KaromatsuSyncPayload(
					buf.readUUID(),
					buf.readDouble(),
					buf.readDouble(),
					buf.readDouble(),
					buf.readFloat(),
					buf.readByte(),
					buf.readBoolean()));

	@Override
	public CustomPacketPayload.Type<KaromatsuSyncPayload> type() {
		return TYPE;
	}
}
