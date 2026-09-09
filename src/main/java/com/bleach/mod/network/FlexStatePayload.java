package com.bleach.mod.network;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C: "this player's Spiritual Flex is up." Twelve bytes, sent on edges and on a keepalive beat ·
 * {@link com.bleach.mod.ability.common.SpiritualFlex}.
 *
 * <h2>Why the field is not sent as particles</h2>
 *
 * <p>The same argument {@code GinBeamStatePayload} makes about the beam, and for the same reason.
 * The server used to spawn the whole field itself — a ring walked at 3.5 points per block plus a
 * scatter of risers, one {@code sendParticles} call each, every other tick a channel was held. At
 * Soul Level 20 that is around ninety packets per viewer per emission, five times a second, for a
 * shape completely determined by where the flexer is standing and how strong they are. Both of those
 * every client tracking them already knows.
 *
 * <p>So none of the field's geometry travels. This carries the flexer's entity id and the four
 * numbers the shape cannot be derived from — how wide it is, what colour, which tier, and whether
 * this is the moment it came up — and {@code FlexRenderer} draws it from the entity's own
 * interpolated position at whatever density the client can afford.
 *
 * <h2>The keepalive and the onset flag</h2>
 *
 * <p>Edges alone are not safe: a viewer who walks into range mid-hold never saw the start, and a
 * flexer who disconnects mid-hold may never send the stop. So the server repeats the active edge
 * every {@code FLEX_STATE_KEEPALIVE_TICKS} and the client expires anything it has not heard about
 * for {@code FLEX_STATE_EXPIRY_TICKS}, and both failures self-heal within about a second.
 *
 * <p>That repeat is exactly why {@link #onset} is on the wire rather than inferred from "the client
 * had no field for this id a moment ago". The activation burst has to fire once per hold; a client
 * that inferred it would re-detonate it on every keepalive, and one that had just walked into range
 * would play a burst for a field that has been up for a minute.
 *
 * @param entityId the flexer's entity id, as the client knows it
 * @param active   whether the channel is up; false is the retraction
 * @param onset    whether this is the packet that started the hold, and so carries the burst
 * @param color    packed RGB, the kit's own particle colour · {@code Kit#particleColor}
 * @param radius   the field's current radius in blocks · {@code SpiritualFlex#radius}
 * @param tier     the Reiatsu amplifier this field would land on a mob, 0..3 · intensity, not damage
 */
public record FlexStatePayload(int entityId, boolean active, boolean onset, int color, float radius,
		byte tier) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<FlexStatePayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("flex_state"));

	public static final StreamCodec<FriendlyByteBuf, FlexStatePayload> STREAM_CODEC =
			StreamCodec.of(
					(buf, payload) -> {
						buf.writeVarInt(payload.entityId);
						buf.writeBoolean(payload.active);
						buf.writeBoolean(payload.onset);
						buf.writeInt(payload.color);
						buf.writeFloat(payload.radius);
						buf.writeByte(payload.tier);
					},
					buf -> new FlexStatePayload(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
							buf.readInt(), buf.readFloat(), buf.readByte()));

	/**
	 * The retraction. Everything but the id is dead weight on a packet that means "stop drawing",
	 * so nothing is filled in — the client reads {@link #active} first and never looks at the rest.
	 */
	public static FlexStatePayload off(int entityId) {
		return new FlexStatePayload(entityId, false, false, 0, 0.0f, (byte) 0);
	}

	@Override
	public CustomPacketPayload.Type<FlexStatePayload> type() {
		return TYPE;
	}
}
