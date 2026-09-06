package com.bleach.mod.network;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C: "this player's Kamishini no Yari is extended." Five bytes, sent on edges.
 *
 * <h2>Why the beam is not sent as particles</h2>
 *
 * <p>The server used to spawn the beam by sending one particle packet per point per viewer, every
 * tick it was held. At a 0.6-block step over 70 blocks that is ~115 packets per viewer per tick —
 * 2,300 packets a tick with twenty people nearby, for a shape that is completely determined by
 * where the caster is standing and which way they are looking. Both of those the client already
 * knows: it is tracking the entity.
 *
 * <p>So nothing about the beam's <em>geometry</em> is transmitted at all. This payload carries the
 * caster's entity id and one boolean, and the client draws the beam from that entity's own eye and
 * look vector each tick, at whatever density it likes. Cost drops from ~115 packets per viewer per
 * tick to two per beam for the whole hold — one when it starts and one when it stops — plus a
 * keepalive.
 *
 * <p>It also renders <em>better</em>: the client has the entity's interpolated rotation, so the beam
 * tracks a swinging look smoothly instead of stepping once per server tick.
 *
 * <h2>The keepalive</h2>
 *
 * <p>Edges alone are not safe. A viewer who comes into range mid-hold never saw the start, and a
 * caster who disconnects or dies mid-hold may never send the stop — either way a client is left
 * with a beam that is wrong until something else corrects it. The server therefore repeats the
 * "active" edge periodically and the client expires any beam it has not heard about recently, so
 * the worst case self-heals within a second instead of persisting forever.
 *
 * @param entityId the caster's entity id, as the client knows it
 * @param active   whether the beam is currently extended
 */
public record GinBeamStatePayload(int entityId, boolean active) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<GinBeamStatePayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("gin_beam_state"));

	public static final StreamCodec<FriendlyByteBuf, GinBeamStatePayload> STREAM_CODEC =
			StreamCodec.of(
					(buf, payload) -> {
						buf.writeVarInt(payload.entityId);
						buf.writeBoolean(payload.active);
					},
					buf -> new GinBeamStatePayload(buf.readVarInt(), buf.readBoolean()));

	@Override
	public CustomPacketPayload.Type<GinBeamStatePayload> type() {
		return TYPE;
	}
}
