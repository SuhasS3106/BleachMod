package com.bleach.mod.attachment;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.bleach.mod.ModToggle;
import com.bleach.mod.ability.AbilityDispatcher;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.ability.common.AuraSense;
import com.bleach.mod.ability.common.Blut;
import com.bleach.mod.ability.common.Hover;
import com.bleach.mod.ability.common.SpiritualFlex;
import com.bleach.mod.ability.kits.IchigoTransform;
import com.bleach.mod.item.SpiritWeapon;
import com.bleach.mod.network.BleachNetworking;
import com.bleach.mod.network.SpiritualSyncPayload;
import com.bleach.mod.progression.SoulLevel;
import com.bleach.mod.progression.WorldSoulLevel;
import com.bleach.mod.race.Race;
import com.bleach.mod.race.Races;
import com.bleach.mod.race.ReishiDensity;
import com.bleach.mod.tuning.BleachTuning;
import com.bleach.mod.util.BlockQueue;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;

/**
 * The one server tick handler that owns the SP pool: regen, exertion accrual, transformation drain,
 * and the S2C sync.
 *
 * <p>Regen and drain are mutually exclusive. Running both while transformed would net Bankai down to
 * 3.0 SP/s at SL 1 rather than the 5.0 in {@code BALANCE.md} §C, turning the documented 20-second
 * window into 33 seconds. Draining <em>is</em> spending, and spending pauses regen.
 */
public final class SpiritualTicker {
	private SpiritualTicker() {
	}

	/** Last payload sent per player, so an unchanged bar costs no packets between keepalives. */
	private static final Map<UUID, SpiritualSyncPayload> LAST_SENT = new HashMap<>();

	private static int tickCounter;

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(SpiritualTicker::onEndTick);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.player;
			SpiritualData data = BleachAttachments.get(player);

			// Strip all bleach attribute modifiers on login in case of an unclean restart mid-transformation
			IchigoTransform.stripModifiers(player);

			// And the hover's own leftover, which is saved with the entity rather than with the
			// attachment: an unclean restart mid-hover would otherwise restore a player with no
			// gravity and no channel running that could ever give it back.
			Hover.clearStale(player, data);

			// Persistence cleanup: if left transformed across a restart, revert cleanly
			if (data.isTransformed()) {
				forceRevert(player, data);
			}

			sync(player, true);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.player;
			SpiritualData data = BleachAttachments.get(player);
			if (data.isTransformed()) {
				forceRevert(player, data);
			}
			// Through stop() rather than by clearing the flag: a logout mid-hover must not save the
			// player with the anti-kick exemption still on them.
			Hover.stop(player, data);
			LAST_SENT.remove(player.getUUID());
		});

		ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
			if (victim instanceof ServerPlayer player) {
				SpiritualData data = BleachAttachments.get(player);
				if (data.isTransformed()) {
					forceRevert(player, data);
				}
				Hover.stop(player, data);
			}
		});
	}

	private static void onEndTick(MinecraftServer server) {
		// The master switch, checked here rather than inside the loop: switched off, the mod must be
		// indistinguishable from not being installed, which means not touching the pool, not
		// accumulating playtime and not sending a single packet. ModToggle has already reverted
		// anyone who was mid-transformation.
		if (!ModToggle.isEnabled()) {
			BlockQueue.clear();
			return;
		}

		tickCounter++;
		boolean keepalive = tickCounter % Math.max(1, BleachTuning.SYNC_KEEPALIVE_TICKS) == 0;

		// Tick-sliced world tasks (e.g. Yamamoto extinguish sweep, Rukia snow, Sui-Feng crater)
		BlockQueue.tickAll(server);

		// Recomputes at most once per Minecraft day; on every other tick this is a division and a
		// comparison. The result is read by every player below, so it has to be current first.
		WorldSoulLevel worldSoulLevel = WorldSoulLevel.get(server);
		worldSoulLevel.tick(server);

		// Before the pool pass, not after. Flex charges through data.spend(), which pauses regen —
		// so running it first means the pass below already sees the paused pool and syncs the moved
		// bar in the same tick, rather than the client trailing every flex tick by one.
		SpiritualFlex.tickAll(server);

		// Same slot, same reason: the sense charges through data.spend(), so its drain has to be in
		// the pool before the pass below syncs the bar.
		AuraSense.tickAll(server);

		// And the third channel, on the same rule.
		Hover.tickAll(server);

		// Blut is not a channel but bills the same way — through data.spend(), stacking on top of any
		// release drain — so it belongs in the same slot, before the pool pass syncs the bar.
		Blut.tickAll(server);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			SpiritualData data = BleachAttachments.get(player);

			data.playtimeTicks++;

			// Cleared after the movement phase, so the landing tick's own fall damage is still
			// covered by the grace and only the tick after it drops the flag.
			if (data.flashStepFallGrace && player.onGround()) {
				data.flashStepFallGrace = false;
			}

			// Here rather than only at the moment of a kill, so the stats screen's remaining-cap row
			// resets at the day boundary for a player who is standing still.
			SoulLevel.rollDay(server, data);

			if (data.isTransformed()) {
				tickTransformed(player, data);
			} else {
				tickRegen(player, data);
			}

			sync(player, keepalive);
		}
	}

	private static void tickRegen(ServerPlayer player, SpiritualData data) {
		double max = data.maxSp();

		if (data.regenPauseTicks > 0) {
			data.regenPauseTicks--;
		} else if (data.sp < max) {
			double perTick = data.regenPerSecond() * data.regenMultiplier() * environmentMultiplier(player, data)
					/ BleachTuning.TICKS_PER_SECOND;
			data.sp = Math.min(max, data.sp + perTick);
		}

		// The only place exertion ever clears. Checked outside the pause branch so a player who is
		// already topped up sheds their debt even while regen is frozen by recent damage — but
		// deliberately inside the untransformed path: Bankai entry sets sp to max, and clearing
		// exertion there would make the loan wipe the debt it is supposed to create.
		if (data.sp >= max) {
			data.exertion = 0.0;
		}
	}

	/**
	 * A Quincy draws power from the world rather than producing it · design §3.5. Exactly 1.0 for
	 * a Shinigami, whose race declares zero sensitivity, so this costs the existing path nothing
	 * beyond one field read and a branch that is never taken.
	 */
	/**
	 * Public so {@code /bleach test reishi} can sample the <em>real</em> regen path rather than a
	 * copy of it — a duplicated formula in the command would pass while this one was wrong, which is
	 * exactly the failure that acceptance command exists to catch.
	 */
	public static double environmentMultiplier(ServerPlayer player, SpiritualData data) {
		Race race = Races.byId(data.race);
		if (race.reishiSensitivity() <= 0.0) {
			return 1.0;
		}

		BlockPos pos = player.blockPosition();
		ServerLevel level = player.serverLevel();
		return ReishiDensity.multiplier(
				race.reishiSensitivity(),
				level.getBrightness(LightLayer.SKY, pos),
				level.canSeeSky(pos),
				player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA),
				level.dimensionType().hasSkyLight());
	}

	private static void tickTransformed(ServerPlayer player, SpiritualData data) {
		data.exertion += data.exertionRatePerSecond() / BleachTuning.TICKS_PER_SECOND;
		data.sp -= data.drainPerSecond() / BleachTuning.TICKS_PER_SECOND;

		if (data.sp <= 0.0) {
			data.sp = 0.0;
			forceRevert(player, data);
			return;
		}

		TransformAbility active = AbilityDispatcher.activeTransform(data);
		if (active != null) {
			active.onTick(player, data);
		}
	}

	/**
	 * Enter a transformation. Bankai refills the pool to full — a <b>loan</b>, recorded in
	 * {@code spOnEntry} and clawed back by {@link #forceRevert}. Shikai does not refill.
	 *
	 * <p>The pool moves first and the kit's {@code onEnter} runs after, so an implementation that
	 * reads {@code data.sp} sees the post-loan value rather than a number that is about to change
	 * under it.
	 */
	public static void enter(ServerPlayer player, SpiritualData data, byte state) {
		if (state == SpiritualData.STATE_BASE) {
			forceRevert(player, data);
			return;
		}

		if (data.isTransformed()) {
			forceRevert(player, data);
		}

		if (state == SpiritualData.STATE_BANKAI) {
			data.spOnEntry = data.sp;
			data.sp = data.maxSp();
		}

		data.state = state;

		// Before onEnter, so a kit that swaps or re-mints the blade in its own hook wins rather than
		// having a stale stamp written over the top of it.
		SpiritWeapon.markReleased(player, data, state);

		TransformAbility entered = AbilityDispatcher.activeTransform(data);
		if (entered != null) {
			entered.onEnter(player, data);
		}
	}

	/**
	 * The single revert path. Manual toggle, SP hitting zero, death, dimension change, logout and
	 * sheathing all route here so the claw-back can never be skipped.
	 *
	 * <p>Without {@code sp = min(spOnEntry, sp)} a one-tick Bankai toggle is a free full refill of
	 * the resource — the worst exploit available in this design.
	 */
	public static void forceRevert(ServerPlayer player, SpiritualData data) {
		// Before the state is cleared, so activeTransform can still resolve which one to tear down.
		// TransformAbility#onRevert is documented as idempotent for exactly this reason: this method
		// is reachable twice in a row from paths that do not know about each other.
		TransformAbility leaving = AbilityDispatcher.activeTransform(data);
		if (leaving != null) {
			leaving.onRevert(player, data);
		}

		if (data.state == SpiritualData.STATE_BANKAI) {
			data.sp = Math.min(data.spOnEntry, data.sp);
		}

		data.state = SpiritualData.STATE_BASE;
		SpiritWeapon.markReleased(player, data, SpiritualData.STATE_BASE);
		data.spOnEntry = 0.0;
		data.pauseRegen();
	}

	/** Send the player's numbers if they changed, or unconditionally when {@code force} is set. */
	public static void sync(ServerPlayer player, boolean force) {
		SpiritualData data = BleachAttachments.get(player);
		SpiritualSyncPayload payload =
				SpiritualSyncPayload.of(data, WorldSoulLevel.get(player.server).value());

		if (force || !payload.equals(LAST_SENT.get(player.getUUID()))) {
			LAST_SENT.put(player.getUUID(), payload);
			BleachNetworking.sendSync(player, payload);
		}
	}
}
