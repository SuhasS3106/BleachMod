package com.bleach.mod.network;

import java.util.ArrayList;
import java.util.List;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * What one sensor's closed eyes can feel, S2C, sent only to that sensor.
 *
 * <p>This is the one payload in the mod that carries other entities' positions, because it has to:
 * an aura at three hundred blocks is far outside the entity tracking range, so the client has no
 * entity to read a position off. Everything about the reading is resolved server-side — reach,
 * colour, and whether the entity is in range at all — and the client is handed screen-space fuel
 * and nothing else. It never sees a name, a type or a health value, so what leaks is exactly the
 * blob that gets drawn.
 *
 * <p>Positions are <b>deltas from the sensor's own eye position</b> at scan time, as floats. Absolute
 * doubles would be nine bytes wider per aura for precision that a blob whose radius is measured in
 * whole blocks cannot use, and the client's own eye position is the frame it draws in anyway.
 *
 * <p>{@code active} false is the channel closing — sent once when the server drops the sense, so a
 * client whose eyes are shut because the server said so also opens them when the server says so.
 */
public record AuraSensePayload(boolean active, List<Aura> auras) implements CustomPacketPayload {

	/**
	 * One reading. {@code entityId} is carried purely so the client can match an aura to its previous
	 * frame and smooth between two packets rather than teleporting it ten times a second; nothing
	 * else on the client looks the id up, and at these ranges there is usually no entity to look up.
	 *
	 * <p>{@code burn} is how hard the soul is pushing — a single resolved multiplier rather than the
	 * state and the Flex flag it was derived from. Sending the multiplier keeps the rule on the
	 * server where the rest of the reading is decided, and keeps the packet from telling a client
	 * "that player is in Bankai" when all it is entitled to draw is "that one is burning ×7.6".
	 *
	 * <p>{@code body} is how physically big the creature is, in blocks — the cube root of its
	 * bounding box's volume, so a wide flat spider and a tall thin breeze both land in the middle
	 * rather than one of them being scored on height alone. It has to come over the wire for the same
	 * reason the position does: at these ranges there is no entity on the client to measure. It is a
	 * plain fact about the creature with no tuning folded into it, so the client is free to decide
	 * what it is worth · {@code BleachTuning#AURA_MOB_SIZE_PER_BLOCK}.
	 */
	public record Aura(int entityId, float dx, float dy, float dz, int color, byte soulLevel,
			float burn, float body) {
	}

	public static final CustomPacketPayload.Type<AuraSensePayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("aura_sense"));

	/** The empty reading. Also what a sensor gets when the channel drops under them. */
	public static final AuraSensePayload INACTIVE = new AuraSensePayload(false, List.of());

	/**
	 * A hard bound on the decode loop, deliberately unrelated to {@code AURA_MAX_ENTRIES}: tuning is
	 * a server-side file that is never synced, so the client cannot use it to size an allocation a
	 * packet asked for.
	 */
	private static final int MAX_WIRE_ENTRIES = 256;

	/**
	 * Fixed-point scale for {@link Aura#body} on the wire: one byte, eighths of a block, so the
	 * range runs to just under 32 blocks and the step is well below anything a radius can show. A
	 * float here would be three bytes an entry for precision nothing downstream can use, and this
	 * packet already carries up to forty-eight entries several times a second.
	 */
	private static final float BODY_SCALE = 8.0f;

	public static final StreamCodec<FriendlyByteBuf, AuraSensePayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeBoolean(payload.active);

				// Capped on the WRITE side as well, because the read side clamps to the same bound.
				// Sending more than the reader will consume desyncs the buffer by whole entries and
				// kicks the client with "found N bytes extra" — a decode error that names neither
				// this packet's contents nor the setting that caused it. AURA_MAX_ENTRIES is a
				// server-side config value, so nothing but this stops someone raising it past the
				// wire bound and disconnecting every player who closes their eyes.
				int count = Math.min(payload.auras.size(), MAX_WIRE_ENTRIES);
				buf.writeVarInt(count);

				for (int i = 0; i < count; i++) {
					Aura aura = payload.auras.get(i);
					buf.writeVarInt(aura.entityId);
					buf.writeFloat(aura.dx);
					buf.writeFloat(aura.dy);
					buf.writeFloat(aura.dz);
					buf.writeInt(aura.color);
					buf.writeByte(aura.soulLevel);
					buf.writeFloat(aura.burn);
					buf.writeByte(Math.min(255, Math.max(0, Math.round(aura.body * BODY_SCALE))));
				}
			},
			buf -> {
				boolean active = buf.readBoolean();
				int count = Math.min(buf.readVarInt(), MAX_WIRE_ENTRIES);
				List<Aura> auras = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					auras.add(new Aura(
							buf.readVarInt(),
							buf.readFloat(),
							buf.readFloat(),
							buf.readFloat(),
							buf.readInt(),
							buf.readByte(),
							buf.readFloat(),
							buf.readUnsignedByte() / BODY_SCALE));
				}
				return new AuraSensePayload(active, auras);
			});

	@Override
	public CustomPacketPayload.Type<AuraSensePayload> type() {
		return TYPE;
	}
}
