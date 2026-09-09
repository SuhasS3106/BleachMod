package com.bleach.mod.network;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "This is how much punishment you are currently carrying." S2C, to Gerard alone.
 *
 * <p>Sent only on the tick the number changes, the same edge discipline
 * {@link KaromatsuSyncPayload} uses — stacks move on damage and once a second while decaying, so a
 * per-tick broadcast would be a packet for nothing on most ticks.
 *
 * <p><b>Only to the player it describes.</b> An opponent knowing Gerard's exact stack count would
 * know precisely when Godly Size is about to fire, and the whole tension of the kit is that they can
 * only see him growing. What the world gets is the body; what Gerard gets is the number.
 *
 * <p>{@code coreExposed} rides along because it is the one state the player most needs to see and
 * cannot infer: after the miracle saves you, you are taking double damage for ten seconds and
 * nothing else on screen says so.
 */
public record MiracleSyncPayload(int stacks, int maxStacks, boolean godlySize, boolean coreExposed)
		implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<MiracleSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("miracle_sync"));

	public static final StreamCodec<FriendlyByteBuf, MiracleSyncPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeVarInt(payload.stacks);
				buf.writeVarInt(payload.maxStacks);
				buf.writeBoolean(payload.godlySize);
				buf.writeBoolean(payload.coreExposed);
			},
			buf -> new MiracleSyncPayload(
					buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));

	/** The falling edge: no longer Gerard, or no longer transformed. */
	public static MiracleSyncPayload cleared() {
		return new MiracleSyncPayload(0, 0, false, false);
	}

	@Override
	public CustomPacketPayload.Type<MiracleSyncPayload> type() {
		return TYPE;
	}
}
