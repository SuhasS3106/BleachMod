package com.bleach.mod.progression;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The World Soul Level · PRD §2.5 — {@code WSL = Σ(SL_i × playtime_i) / Σ(playtime_i)}, over
 * players active within the last {@link BleachTuning#WSL_PLAYTIME_WINDOW_DAYS} Minecraft days.
 *
 * <p>Playtime-weighted so a one-time visitor from months ago contributes nothing, and recomputed
 * <b>once per Minecraft day</b>: this is a multiplier input and a display value, never a hot-path
 * computation.
 *
 * <p><b>Offline players count, and no player file is ever opened to find that out.</b> The plan
 * offered two ways to include them — read the attachment back out of stored player NBT through
 * {@code PlayerDataStorage}, or keep a running roster here — and this is the roster. Reading player
 * files means parsing every {@code .dat} in the world on a schedule, racing the save that writes
 * them, and re-deriving a number the server already had in memory when the player logged out. An
 * entry is written on join, on logout, on level-up and on the daily sweep; between those four there
 * is nothing a player can do that changes their contribution.
 */
public class WorldSoulLevel extends SavedData {
	/** File name under {@code <world>/data}. */
	private static final String FILE_ID = "bleach_world_soul_level";

	private static final String KEY_CONTRIBUTORS = "contributors";
	private static final String KEY_ID = "id";
	private static final String KEY_SOUL_LEVEL = "soul_level";
	private static final String KEY_PLAYTIME = "playtime_ticks";
	private static final String KEY_LAST_SEEN_DAY = "last_seen_day";
	private static final String KEY_VALUE = "value";
	private static final String KEY_LAST_COMPUTED_DAY = "last_computed_day";
	private static final String KEY_OVERRIDE = "override";

	/** {@link #override} sentinel. Negative because a real WSL is never below 1. */
	private static final double NO_OVERRIDE = -1.0;

	/** The floor and the value of an empty world — a world with no players is a WSL-1 world. */
	private static final double MINIMUM = 1.0;

	/**
	 * Data fixer type is null on purpose: this is mod data, the vanilla fixers know nothing about
	 * it, and its own schema is one flat list of primitives that has never needed migrating.
	 */
	public static final SavedData.Factory<WorldSoulLevel> FACTORY =
			new SavedData.Factory<>(WorldSoulLevel::new, WorldSoulLevel::load, null);

	/** One player's contribution. Immutable; entries are replaced, never mutated in place. */
	private record Contributor(int soulLevel, long playtimeTicks, long lastSeenDay) {
	}

	private final Map<UUID, Contributor> contributors = new HashMap<>();

	private double value = MINIMUM;
	private long lastComputedDay = Long.MIN_VALUE;
	private double override = NO_OVERRIDE;

	/** The overworld's copy, created on first use. */
	public static WorldSoulLevel get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
	}

	/** The current day stamp, always read from the overworld so every dimension agrees. */
	public static long currentDay(MinecraftServer server) {
		return server.overworld().getDayTime() / BleachTuning.TICKS_PER_MC_DAY;
	}

	/** The value every scalar is computed from. Never below {@link #MINIMUM}. */
	public double value() {
		return override >= MINIMUM ? override : value;
	}

	public boolean isOverridden() {
		return override >= MINIMUM;
	}

	/**
	 * Pin the WSL to a fixed value, or pass a negative number to release it back to the computed
	 * one. Testing the world-scaling acceptance any other way means levelling several players and
	 * waiting a week of Minecraft days.
	 */
	public void setOverride(double pinned) {
		this.override = pinned < MINIMUM ? NO_OVERRIDE : pinned;
		setDirty();
	}

	/** Record where a player currently stands. Cheap enough to call on any lifecycle edge. */
	public void record(ServerPlayer player, long day) {
		SpiritualData data = BleachAttachments.get(player);
		contributors.put(player.getUUID(), new Contributor(data.soulLevel, data.playtimeTicks, day));
		setDirty();
	}

	/**
	 * Recompute if the day has moved on. Called from the server tick, where the whole cost on all
	 * but one tick in {@code 24000 × WSL_RECOMPUTE_DAYS} is a division and a comparison.
	 *
	 * <p>The backwards check is not paranoia: {@code /time set} moves the clock either way, and a
	 * world whose day stamp went backwards would otherwise never recompute again.
	 */
	public void tick(MinecraftServer server) {
		long day = currentDay(server);
		long elapsed = day - lastComputedDay;
		if (elapsed < BleachTuning.WSL_RECOMPUTE_DAYS && elapsed >= 0) {
			return;
		}

		// Online players first. Their stored entry is as old as their last level-up, and on a server
		// where everyone stays logged in that could be the whole session.
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			record(player, day);
		}
		recompute(day);
	}

	/** The §2.5 average over everyone inside the activity window. */
	public void recompute(long day) {
		double weighted = 0.0;
		double total = 0.0;

		for (Contributor contributor : contributors.values()) {
			if (day - contributor.lastSeenDay() > BleachTuning.WSL_PLAYTIME_WINDOW_DAYS) {
				continue;
			}
			weighted += contributor.soulLevel() * (double) contributor.playtimeTicks();
			total += contributor.playtimeTicks();
		}

		// A brand-new player has zero playtime and would otherwise weigh nothing at all, leaving the
		// world at the floor until someone had played a while. That is the correct answer: a world
		// nobody has played is a WSL-1 world.
		value = total <= 0.0 ? MINIMUM : Math.max(MINIMUM, weighted / total);
		lastComputedDay = day;
		setDirty();
	}

	/** Everyone inside the activity window, for {@code /bleach wsl get}. */
	public int activeContributors(long day) {
		int count = 0;
		for (Contributor contributor : contributors.values()) {
			if (day - contributor.lastSeenDay() <= BleachTuning.WSL_PLAYTIME_WINDOW_DAYS) {
				count++;
			}
		}
		return count;
	}

	private static WorldSoulLevel load(CompoundTag tag, HolderLookup.Provider registries) {
		WorldSoulLevel out = new WorldSoulLevel();
		out.value = tag.getDouble(KEY_VALUE);
		out.lastComputedDay = tag.getLong(KEY_LAST_COMPUTED_DAY);
		out.override = tag.contains(KEY_OVERRIDE) ? tag.getDouble(KEY_OVERRIDE) : NO_OVERRIDE;

		ListTag list = tag.getList(KEY_CONTRIBUTORS, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompound(i);
			if (!entry.hasUUID(KEY_ID)) {
				continue;
			}
			out.contributors.put(entry.getUUID(KEY_ID), new Contributor(
					entry.getInt(KEY_SOUL_LEVEL),
					entry.getLong(KEY_PLAYTIME),
					entry.getLong(KEY_LAST_SEEN_DAY)));
		}

		// A file written before anything was computed reads back as zero, which is below the floor.
		out.value = Math.max(MINIMUM, out.value);
		return out;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (Map.Entry<UUID, Contributor> entry : contributors.entrySet()) {
			CompoundTag serialized = new CompoundTag();
			serialized.putUUID(KEY_ID, entry.getKey());
			serialized.putInt(KEY_SOUL_LEVEL, entry.getValue().soulLevel());
			serialized.putLong(KEY_PLAYTIME, entry.getValue().playtimeTicks());
			serialized.putLong(KEY_LAST_SEEN_DAY, entry.getValue().lastSeenDay());
			list.add(serialized);
		}

		tag.put(KEY_CONTRIBUTORS, list);
		tag.putDouble(KEY_VALUE, value);
		tag.putLong(KEY_LAST_COMPUTED_DAY, lastComputedDay);
		tag.putDouble(KEY_OVERRIDE, override);
		return tag;
	}
}
