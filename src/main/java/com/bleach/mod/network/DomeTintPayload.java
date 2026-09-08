package com.bleach.mod.network;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "You are inside a Gift Bereich", S2C, sent only on the tick membership changes.
 *
 * <p>Deliberately a one-bit edge rather than a per-tick state: the client's tint is a fade it drives
 * itself, so all the server owes it is the moment the answer flips. Sending this every tick would be
 * a packet per player per tick for a boolean that changes twice a fight.
 *
 * <p>It carries no position, radius or owner. A client that knew the dome's geometry could draw its
 * exact edge and stand one block outside it; the tint is all the client is entitled to.
 */
public record DomeTintPayload(boolean inside) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<DomeTintPayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("dome_tint"));

	public static final StreamCodec<FriendlyByteBuf, DomeTintPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBoolean(payload.inside),
			buf -> new DomeTintPayload(buf.readBoolean()));

	@Override
	public CustomPacketPayload.Type<DomeTintPayload> type() {
		return TYPE;
	}
}
