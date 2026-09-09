package com.bleach.mod.ability.common;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The light a raised field casts on the world · {@code BALANCE.md} §H.6.
 *
 * <h2>Why a block and not a brighter particle</h2>
 *
 * <p>The field was already the brightest thing on screen and still did not <em>light</em> anything:
 * additive particles write colour, and colour is not illumination. The floor under a flexer stayed
 * exactly as dark as it was, which is what left the effect sitting in front of the scene rather than
 * in it — §H.6's fifth failure, and the one the three particle layers could not answer on their own.
 *
 * <p>§H.6 also records the attempt that was cut: a filled disc of light drawn on the floor. That was
 * rejected for being a <em>decal</em> — flat, centred on the player, sliding along with them — and
 * nothing here revives it. This lights the world for real, with a {@code minecraft:light} block
 * carried along at chest height, so the illumination falls on terrain, mobs and other players, is
 * seen identically by every client with no rendering of ours involved, and behaves like light rather
 * than like a texture: it pools in a doorway, it does not reach through a wall.
 *
 * <h2>What this costs, and the rules that keep it cheap</h2>
 *
 * <p>A light block move is a light-engine update, so the block is moved only when the flexer changes
 * <em>block</em> — a sprinting player is around five updates a second, comparable to a torch being
 * carried. The block is placed only into air, so no light-source of ours can ever destroy anything,
 * and it is removed only when what stands there is still one of ours, so an operator or another mod
 * replacing it is never fought over.
 *
 * <p>Every path that ends a channel already converges on {@code SpiritualFlex.retractEnded} — key
 * released, pool empty, death, logout — which is where {@link #clear} is called from, so there is one
 * teardown rather than four. Server shutdown is the one exit that does not pass through a tick at
 * all, so it is handled here: an invisible light block left behind in a saved world is not something
 * a player can find or break.
 */
public final class FlexLight {
	private FlexLight() {
	}

	/** Where a flexer's light currently stands. */
	private record Placed(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private static final Map<UUID, Placed> LIGHTS = new HashMap<>();

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPING.register(FlexLight::clearAll);
	}

	/**
	 * Put this flexer's light where it belongs, moving or placing it as needed.
	 *
	 * <p>Called once per tick per flexer. The common case by far is a player who has not changed
	 * block since last tick, which costs a map lookup and an equality check.
	 */
	public static void update(ServerPlayer flexer) {
		int level = Mth.clamp(BleachTuning.FLEX_LIGHT_LEVEL, 0, 15);
		if (level <= 0) {
			// Switched off. Anything already placed still has to come back out, or turning the dial
			// down mid-session strands it.
			clear(flexer.getServer(), flexer.getUUID());
			return;
		}

		ServerLevel world = flexer.serverLevel();
		BlockPos wanted = flexer.blockPosition().above(Math.max(0, BleachTuning.FLEX_LIGHT_HEIGHT));

		Placed current = LIGHTS.get(flexer.getUUID());
		if (current != null) {
			if (current.dimension() == world.dimension() && current.pos().equals(wanted)) {
				return;
			}
			remove(flexer.getServer(), current);
		}

		// Only ever into air. A light source that eats the block it moves into would make the ability
		// destructive, and a flexer walking through a wall of chests would be a griefing tool.
		if (!world.getBlockState(wanted).isAir()) {
			LIGHTS.remove(flexer.getUUID());
			return;
		}

		BlockState light = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
		world.setBlock(wanted, light, Block.UPDATE_CLIENTS);
		LIGHTS.put(flexer.getUUID(), new Placed(world.dimension(), wanted));
	}

	/** Take down the light for a channel that has ended. Safe to call for a player who has none. */
	public static void clear(MinecraftServer server, UUID id) {
		Placed placed = LIGHTS.remove(id);
		if (placed != null) {
			remove(server, placed);
		}
	}

	/**
	 * Drop every light whose owner is no longer flexing.
	 *
	 * <p>Driven off the same set {@code SpiritualFlex} retracts announcements with, for the same
	 * reason: the interesting case is a player who is not in the flexers list any more, and a logout
	 * is exactly that.
	 */
	public static void retainOnly(MinecraftServer server, Set<UUID> stillFlexing) {
		if (LIGHTS.isEmpty()) {
			return;
		}

		Iterator<Map.Entry<UUID, Placed>> it = LIGHTS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Placed> entry = it.next();
			if (stillFlexing.contains(entry.getKey())) {
				continue;
			}
			remove(server, entry.getValue());
			it.remove();
		}
	}

	private static void clearAll(MinecraftServer server) {
		for (Placed placed : LIGHTS.values()) {
			remove(server, placed);
		}
		LIGHTS.clear();
	}

	/**
	 * Remove one placed light.
	 *
	 * <p>Guarded on the block still being a light block, so this can never clear something that took
	 * its place — an unloaded chunk that has since been edited, or an operator who put something
	 * there. {@code getLevel} returning null covers a dimension unloaded underneath us.
	 */
	private static void remove(MinecraftServer server, Placed placed) {
		if (server == null) {
			return;
		}

		ServerLevel world = server.getLevel(placed.dimension());
		if (world == null) {
			return;
		}

		if (world.getBlockState(placed.pos()).is(Blocks.LIGHT)) {
			world.setBlock(placed.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}
}
