package com.bleach.mod.item;

import com.bleach.mod.BleachMod;
import com.bleach.mod.attachment.SpiritualData;
import com.mojang.serialization.Codec;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * Data components the mod puts on its own items.
 *
 * <p>Currently one: the released state, which exists so a blade <em>looks</em> like what it is.
 *
 * <h2>Why this is on the stack and not read from the player</h2>
 *
 * <p>The obvious implementation is a model predicate that asks the holder for their
 * {@link SpiritualData}. It does not work. Model predicates run on the client, and the client is
 * only ever sent its <em>own</em> spiritual state · {@code SpiritualSyncPayload} — so every other
 * player's Bankai would render as a plain blade on your screen, which is precisely the case the
 * model is for. Syncing everyone's state to everyone would be a tracking payload and a whole
 * lifecycle for what is, in the end, one number about an item.
 *
 * <p>An item stack, by contrast, is already replicated to everyone who can see the hand holding it,
 * with no work and no new packet. The state goes on the blade; the model reads the blade.
 */
public final class BleachComponents {
	private BleachComponents() {
	}

	/**
	 * The wielder's released state, as {@link SpiritualData#STATE_BASE} / {@code STATE_SHIKAI} /
	 * {@code STATE_BANKAI} · stamped by {@link SpiritWeapon#markReleased}.
	 *
	 * <p><b>Absent means base.</b> The component is removed rather than set to zero on revert, so a
	 * sheathed blade is byte-identical to a freshly minted one and nothing accumulates junk NBT on
	 * an item every player carries for the whole game.
	 */
	public static final DataComponentType<Integer> RELEASED = DataComponentType.<Integer>builder()
			.persistent(Codec.INT)
			.networkSynchronized(ByteBufCodecs.VAR_INT)
			.build();

	public static void register() {
		Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, BleachMod.id("released"), RELEASED);
	}
}
