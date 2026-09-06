package com.bleach.mod.network;

import com.bleach.mod.BleachMod;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.progression.SoulLevel;
import com.bleach.mod.progression.SpxTable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The owning player's own numbers, S2C only. ~40 bytes; nobody else needs your bar.
 *
 * <p>The client stores the last one received and renders from it. <b>Nothing on the client ever
 * computes SP itself</b> — no prediction, no interpolation of the underlying value.
 *
 * <p>The one component the client acts on rather than draws is {@code hovering}: the server's
 * standing permission for {@code LocalPlayerHoverMixin} to hold the player up, renewed with every
 * packet. It rides here rather than on a payload of its own because it changes at exactly the moments
 * this one is already being sent — the drain moves the bar every tick of the hold.
 *
 * <p>That rule is why the last three components are here rather than being derived client-side from
 * the two before them. The formulas are simple enough to duplicate, but they read
 * {@code BleachTuning}, and tuning is a server-side config file that is never synced — so a server
 * with a tuned {@code CATCHUP_PER_LEVEL_GAP} would show every player a catch-up multiplier it was
 * not paying them. Derived once, on the side that owns the numbers.
 */
public record SpiritualSyncPayload(float sp, float maxSp, int soulLevel, int spx, int spxToNext,
		byte state, float regenMult, float worldSoulLevel, int spxRemainingToday, float catchUp,
		float mobScalar, boolean hovering) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<SpiritualSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("spiritual_sync"));

	/**
	 * Hand-written rather than {@code StreamCodec.composite}, whose overloads stop short of the
	 * twelve components here.
	 */
	public static final StreamCodec<FriendlyByteBuf, SpiritualSyncPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeFloat(payload.sp);
				buf.writeFloat(payload.maxSp);
				buf.writeVarInt(payload.soulLevel);
				buf.writeVarInt(payload.spx);
				buf.writeVarInt(payload.spxToNext);
				buf.writeByte(payload.state);
				buf.writeFloat(payload.regenMult);
				buf.writeFloat(payload.worldSoulLevel);
				buf.writeVarInt(payload.spxRemainingToday);
				buf.writeFloat(payload.catchUp);
				buf.writeFloat(payload.mobScalar);
				buf.writeBoolean(payload.hovering);
			},
			buf -> new SpiritualSyncPayload(
					buf.readFloat(),
					buf.readFloat(),
					buf.readVarInt(),
					buf.readVarInt(),
					buf.readVarInt(),
					buf.readByte(),
					buf.readFloat(),
					buf.readFloat(),
					buf.readVarInt(),
					buf.readFloat(),
					buf.readFloat(),
					buf.readBoolean()));

	public static SpiritualSyncPayload of(SpiritualData data, double worldSoulLevel) {
		return new SpiritualSyncPayload(
				(float) data.sp,
				(float) data.maxSp(),
				data.soulLevel,
				data.spx,
				SpxTable.toNext(data.soulLevel),
				data.state,
				(float) data.regenMultiplier(),
				(float) worldSoulLevel,
				SoulLevel.remainingToday(worldSoulLevel, data),
				(float) SoulLevel.catchUp(worldSoulLevel, data.soulLevel),
				(float) SoulLevel.mobScalar(worldSoulLevel, data.soulLevel),
				data.hovering);
	}

	@Override
	public CustomPacketPayload.Type<SpiritualSyncPayload> type() {
		return TYPE;
	}
}
