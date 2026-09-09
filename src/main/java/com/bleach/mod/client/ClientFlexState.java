package com.bleach.mod.client;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.network.FlexStatePayload;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.client.Minecraft;

/**
 * Every Spiritual Flex field this client has been told about · {@link FlexStatePayload}.
 *
 * <p>State only. It knows who is flexing, how wide, what colour and how hard; it draws nothing and
 * simulates nothing — {@link FlexRenderer} owns all of that, and keys its particle pools off the
 * same entity ids.
 *
 * <p>{@link ConcurrentHashMap} for the same reason {@code ClientGinBeamState} uses one: writes
 * arrive on the netty thread by way of {@code client.execute}, reads happen in the render pass, and
 * the two are only <em>almost</em> always the same thread.
 */
public final class ClientFlexState {
	private ClientFlexState() {
	}

	/** One live field. Mutable so a keepalive costs no allocation. */
	public static final class Field {
		public int color;
		public float radius;
		public int tier;
		/** Client tick the last packet for this field arrived on, against the expiry. */
		public int heardOn;
		/**
		 * Set by an onset packet and cleared by the renderer once it has taken it. A flag rather than
		 * a timer because the burst's clock belongs to the frame loop, not to this one.
		 */
		public boolean onsetPending;
	}

	private static final Map<Integer, Field> FIELDS = new ConcurrentHashMap<>();

	public static void update(FlexStatePayload payload) {
		if (!payload.active()) {
			FIELDS.remove(payload.entityId());
			return;
		}

		Field field = FIELDS.computeIfAbsent(payload.entityId(), id -> new Field());
		field.color = payload.color();
		field.radius = payload.radius();
		field.tier = payload.tier();
		field.heardOn = tickCount();
		if (payload.onset()) {
			field.onsetPending = true;
		}
	}

	/** Dropped on disconnect, so a field cannot survive into the next world. */
	public static void clear() {
		FIELDS.clear();
	}

	public static Map<Integer, Field> fields() {
		return FIELDS;
	}

	/**
	 * Forget every field that has gone quiet · {@code FLEX_STATE_EXPIRY_TICKS}.
	 *
	 * <p>The retraction covers the ordinary end of a hold. This covers the ends that cannot send one:
	 * the flexer disconnected, or left this client's tracking range still flexing, or the packet was
	 * simply lost. Called once a frame by the renderer, which is the only reader.
	 */
	public static void expire() {
		if (FIELDS.isEmpty()) {
			return;
		}

		int now = tickCount();
		int expiry = Math.max(1, BleachTuning.FLEX_STATE_EXPIRY_TICKS);
		for (Iterator<Map.Entry<Integer, Field>> it = FIELDS.entrySet().iterator(); it.hasNext();) {
			if (now - it.next().getValue().heardOn > expiry) {
				it.remove();
			}
		}
	}

	private static int tickCount() {
		Minecraft client = Minecraft.getInstance();
		return client.level == null ? 0 : (int) client.level.getGameTime();
	}
}
