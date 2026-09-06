package com.bleach.mod.network;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.AbilityAction;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * "The player pressed a key." C2S, one varint, and that is the entire client contribution to
 * activating an ability.
 *
 * <p>The index is the {@link AbilityAction} ordinal. It is not validated here — a payload that
 * fails to decode kills the connection, which is a far worse outcome than an out-of-range integer
 * that {@code AbilityDispatcher} drops on the floor.
 */
public record AbilityActivatePayload(int action) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<AbilityActivatePayload> TYPE =
			new CustomPacketPayload.Type<>(BleachMod.id("ability_activate"));

	public static final StreamCodec<FriendlyByteBuf, AbilityActivatePayload> STREAM_CODEC =
			StreamCodec.of(
					(buf, payload) -> buf.writeVarInt(payload.action),
					buf -> new AbilityActivatePayload(buf.readVarInt()));

	public static AbilityActivatePayload of(AbilityAction action) {
		return new AbilityActivatePayload(action.ordinal());
	}

	@Override
	public CustomPacketPayload.Type<AbilityActivatePayload> type() {
		return TYPE;
	}
}
