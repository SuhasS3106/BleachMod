package com.bleach.mod.network;

import com.bleach.mod.BleachMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The two halves of the master on/off switch.
 *
 * <p>{@link Request} is C2S and carries nothing: it asks the server to flip whatever the current
 * state is. The client does not get to say <em>which</em> way, because it does not know — the flag
 * is server state and the toggle is server-authoritative like everything else.
 *
 * <p>{@link State} is the S2C answer, broadcast to every player whenever the flag changes and sent
 * once on join. It is the only reason the client knows about the flag at all: it needs it to decide
 * whether to draw the HUD.
 */
public final class ModTogglePayload {
	private ModTogglePayload() {
	}

	/** C2S: "flip it." Permission is checked on arrival, not here. */
	public record Request() implements CustomPacketPayload {

		public static final CustomPacketPayload.Type<Request> TYPE =
				new CustomPacketPayload.Type<>(BleachMod.id("mod_toggle_request"));

		public static final StreamCodec<FriendlyByteBuf, Request> STREAM_CODEC =
				StreamCodec.unit(new Request());

		@Override
		public CustomPacketPayload.Type<Request> type() {
			return TYPE;
		}
	}

	/** S2C: the authoritative flag. */
	public record State(boolean enabled) implements CustomPacketPayload {

		public static final CustomPacketPayload.Type<State> TYPE =
				new CustomPacketPayload.Type<>(BleachMod.id("mod_toggle_state"));

		public static final StreamCodec<FriendlyByteBuf, State> STREAM_CODEC = StreamCodec.of(
				(buf, payload) -> buf.writeBoolean(payload.enabled),
				buf -> new State(buf.readBoolean()));

		@Override
		public CustomPacketPayload.Type<State> type() {
			return TYPE;
		}
	}
}
