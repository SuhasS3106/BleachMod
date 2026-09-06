package com.bleach.mod.network;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "You just earned this much SPX", S2C, sent at the moment of the award.
 *
 * <h2>Why this is a packet and not a subtraction</h2>
 *
 * <p>The obvious client-side alternative — watch {@code spx} in {@link SpiritualSyncPayload} and pop
 * the difference — is wrong, and quietly so. SPX is a <b>bank that is spent in the same tick it is
 * filled</b>: a kill awards into {@code data.spx} and {@code SoulLevel#levelUp} immediately draws
 * levels out of it. A kill worth 30 that buys a 40-point level syncs a bank that fell by ten, so the
 * difference would read {@code −10 SPX} on the single occasion the player most wants to be told they
 * gained something.
 *
 * <p>Reconstructing the award from the level-up would mean the client knowing the cost of every level
 * crossed, and the curve lives in server-side tuning that is deliberately never synced. So the server
 * simply says what it granted. One int, sent only when there is something to say — which for a
 * discrete, per-kill award is far less traffic than any polling scheme would be.
 *
 * <p>{@code amount} is the <b>gross award</b> after the daily cap has clipped it, because that is what
 * the player actually earned. An award clipped to zero is never sent.
 */
public record SpxGainPayload(int amount) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SpxGainPayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("spx_gain"));

	public static final StreamCodec<FriendlyByteBuf, SpxGainPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeVarInt(payload.amount),
			buf -> new SpxGainPayload(buf.readVarInt()));

	@Override
	public CustomPacketPayload.Type<SpxGainPayload> type() {
		return TYPE;
	}
}
