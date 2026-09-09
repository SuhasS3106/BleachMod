package com.bleach.mod.network;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "You are on the stage, and it is currently in this act", S2C, sent only to participants and only
 * on the tick the answer changes.
 *
 * <h2>Why this is an edge and not a broadcast</h2>
 *
 * <p>It used to carry the zone's centre, radius and caster to <b>every player in the world</b>, and
 * the client decided from its own position whether it was inside. Two things were wrong with that.
 * A client that knows the zone's exact geometry can stand one block outside it, which is the same
 * information leak {@link DomeTintPayload} refuses to create. And the client's answer and the
 * server's answer were computed by different code from different data, so a player who was no longer
 * a participant could still be tinted, and a participant standing outside the drawn shell could not
 * be — the tint said one thing and containment did another.
 *
 * <p>Now membership is the server's alone. All the client is owed is the moment it changes, and
 * which act to paint; the fade between them is the client's own business.
 *
 * <h2>Act index encoding</h2>
 * <ul>
 *   <li>{@code  0} — PRE_ACT</li>
 *   <li>{@code  1} — ACT_1 (shared-damage link)</li>
 *   <li>{@code  2} — ACT_2 (the rot)</li>
 *   <li>{@code  3} — ACT_3 (SP drain race / drowning)</li>
 *   <li>{@code  4} — FINAL_ACT (thread charge)</li>
 *   <li>{@code -1} — ended / cleared</li>
 * </ul>
 */
public record KaromatsuSyncPayload(byte actIndex, boolean active) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<KaromatsuSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("karomatsu_sync"));

	public static final StreamCodec<FriendlyByteBuf, KaromatsuSyncPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeByte(payload.actIndex);
				buf.writeBoolean(payload.active);
			},
			buf -> new KaromatsuSyncPayload(buf.readByte(), buf.readBoolean()));

	@Override
	public CustomPacketPayload.Type<KaromatsuSyncPayload> type() {
		return TYPE;
	}
}
