package com.bleach.mod.ability.common;

import com.bleach.mod.ability.Ability;
import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hover · a hold-to-channel stand in mid-air · {@code BALANCE.md} §O.
 *
 * <p>Hold the key while airborne and the player stops falling. They keep moving with WASD, but along
 * the <b>look vector</b> rather than the ground plane — forward is wherever the crosshair points, so
 * looking up and pressing forward climbs and there is no separate ascend or descend key. Releasing
 * the key, running dry or touching the ground all end it and the fall resumes from there.
 *
 * <p>It exists because Flash Step leaves you thirty blocks up with Slow Falling and forty ticks of
 * patience. This is the answer to that, and the reason it costs SP per tick rather than a flat
 * activation: the pool is what decides how long you get to stay up there.
 *
 * <h2>Where the movement actually happens</h2>
 *
 * <p><b>On the client, and only on the client</b> · {@code LocalPlayerHoverMixin}. Minecraft's player
 * movement is client-authoritative: a server that writes a velocity every tick is fighting the
 * client's own physics, and the visible result is the stutter that every server-side flight mod has.
 * So the client owns the motion and this class owns the permission — it spends the SP, it decides
 * when the channel ends, and the answer rides back on the sync payload the client is already
 * reading. Nothing hovers without the server having said so in the previous packet.
 *
 * <p>The one piece of server-side physics here is {@link ServerPlayer#setNoGravity}, which is not
 * about movement at all: {@code ServerGamePacketListenerImpl} kicks a player who is off the ground
 * for eighty ticks with "Flying is not enabled on this server", and it skips that check entirely for
 * an entity whose gravity is zero. That flag is the whole of the exemption, and clearing it is why
 * every path out of the channel routes through {@link #stop}.
 *
 * <h2>Why this implements {@link Ability} but does nothing on activation</h2>
 *
 * <p>Same shape and same reason as {@link SpiritualFlex}: a channel is entered and left through one
 * door, so the dispatcher only flips {@code data.hovering} and every tick of behaviour is owned by
 * {@link #tickAll}. The registration exists so the id resolves and so the hover appears wherever
 * abilities are enumerated.
 */
public final class Hover implements Ability {

	@Override
	public ResourceLocation id() {
		return AbilityRegistry.HOVER;
	}

	/** Pressure holding a body up, not a technique of the blade · PRD §3.2. Usable sheathed. */
	@Override
	public boolean requiresDrawnSword() {
		return false;
	}

	/** Zero by design — the cost is the per-tick drain, and charging here would double-bill tick one. */
	@Override
	public double spCost(SpiritualData data) {
		return 0.0;
	}

	/** No-op by design; see the class docs. The channel is owned by {@link #tickAll}. */
	@Override
	public void onActivate(ServerPlayer player, SpiritualData data) {
	}

	// --- Derived values · BALANCE.md §O --------------------------------------------------

	/** SL 1 → 4.0 SP/s · SL 20 → 1.15. Additive with any transformation drain. */
	public static double drainPerSecond(SpiritualData data) {
		return Math.max(0.0, BleachTuning.HOVER_DRAIN_BASE
				- BleachTuning.HOVER_DRAIN_PER_LEVEL * (data.soulLevel - 1));
	}

	/** What one tick of holding the key costs. Public so the dispatcher can refuse an empty start. */
	public static double tickCost(SpiritualData data) {
		return drainPerSecond(data) / BleachTuning.TICKS_PER_SECOND;
	}

	// --- Gates ---------------------------------------------------------------------------

	/**
	 * Whether the player is in a state a hover can begin from.
	 *
	 * <p>Airborne is the whole point — a hover that could start on the ground would be a jump the
	 * player never made. The rest are cases where something else already owns the player's movement
	 * and taking it away would fight that owner rather than replace it.
	 */
	public static boolean canStart(ServerPlayer player) {
		return canHold(player)
				&& !player.onGround()
				&& !player.getAbilities().flying
				&& !player.isFallFlying();
	}

	/**
	 * Whether a hover already up may continue. Deliberately weaker than {@link #canStart}: a player
	 * who clips the ground for one tick on the way past a ledge should keep hovering, and it is the
	 * landing check in {@link #tickAll} — the ground under them, not a single {@code onGround} frame
	 * — that ends the channel.
	 */
	private static boolean canHold(ServerPlayer player) {
		return player.isAlive()
				&& !player.isSpectator()
				&& !player.isPassenger()
				&& !player.isSleeping();
	}

	// --- The tick -------------------------------------------------------------------------

	/**
	 * Called once per server tick from {@code SpiritualTicker}, in the same slot as the other two
	 * channels and before the per-player pool pass — the drain goes through {@code data.spend}, so
	 * it has to land in the pool before that pass syncs the bar, or the client trails every tick of
	 * the hold by one.
	 */
	public static void tickAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			SpiritualData data = BleachAttachments.get(player);
			if (!data.hoverIntent && !data.hovering) {
				continue;
			}

			// Death, spectator, a vehicle and creative flight all end it outright, intent included.
			// The client sends edges rather than a heartbeat, so nothing else would clear the flags
			// for a player who died holding the key.
			if (!canHold(player) || player.getAbilities().flying) {
				stop(player, data);
				continue;
			}

			if (!data.hovering) {
				// Held, but not yet granted: the press landed on a tick the player was still standing
				// on the ground, which is what holding the key as you run off a ledge looks like. The
				// channel starts on the first tick the air is real, and bills from the tick after.
				if (canStart(player) && data.sp >= tickCost(data)) {
					start(player, data);
				}
				continue;
			}

			// Landed. The channel ends, but the key is still down and the intent with it, so a jump
			// out of this same hold picks straight back up rather than needing the key cycled.
			if (player.onGround()) {
				suspend(player, data);
				continue;
			}

			double cost = tickCost(data);
			if (data.sp < cost) {
				// Ran dry, and the intent goes with the channel: leaving it up would restart the
				// hover on the first tick of regen and stutter the player down the sky a metre at a
				// time. It drops, exactly as the flex and the transformations do, and it takes a
				// fresh press to ask again.
				stop(player, data);
				continue;
			}

			data.spend(cost);

			// The fall never accumulates while the pressure is holding them up — that is the whole
			// promise of the ability. It restarts from zero the moment they let go, so the drop
			// after a hover still hurts; it is only the drop they spent SP not to take that does not.
			player.resetFallDistance();

			tell(player, data);
		}
	}

	/**
	 * Begin the channel. Sets the anti-kick exemption before the flag, so there is no tick in which
	 * the server believes a player is hovering while the vanilla floating check still applies to
	 * them.
	 */
	public static void start(ServerPlayer player, SpiritualData data) {
		if (data.hovering) {
			return;
		}
		player.setNoGravity(true);
		data.hovering = true;
	}

	/**
	 * End the channel and give gravity back, but leave the key's intent standing. The landing case,
	 * and the only one — everything else that ends a hover ends the intent with it.
	 */
	private static void suspend(ServerPlayer player, SpiritualData data) {
		if (!data.hovering) {
			return;
		}
		data.hovering = false;
		player.setNoGravity(false);
	}

	/**
	 * Drop the channel <em>and</em> the intent behind it. Idempotent, and reachable from the
	 * dispatcher's own {@code HOVER_STOP} as well as from every server-side reason the hover can end.
	 *
	 * <p>The client learns about it through the next sync payload, which is also what stops it
	 * holding the player up — so this is the only place that needs to know the hover is over.
	 */
	public static void stop(ServerPlayer player, SpiritualData data) {
		data.hoverIntent = false;
		suspend(player, data);
	}

	/**
	 * Clear a hover that outlived the session that started it.
	 *
	 * <p>{@code NoGravity} is saved with the player entity, so a crash or a kill taken mid-hover
	 * would otherwise restore someone who floats forever with nothing running that could ever put
	 * them down. Same reasoning, and the same join slot, as the attribute-modifier strip.
	 */
	public static void clearStale(ServerPlayer player, SpiritualData data) {
		data.hoverIntent = false;
		data.hovering = false;
		if (player.isNoGravity()) {
			player.setNoGravity(false);
		}
	}

	// --- Presentation ---------------------------------------------------------------------

	/** A little pressure spilling downward under the player's feet — the thing holding them up. */
	private static void tell(ServerPlayer player, SpiritualData data) {
		int interval = Math.max(1, BleachTuning.HOVER_PARTICLE_INTERVAL_TICKS);
		if (player.tickCount % interval != 0 || BleachTuning.HOVER_PARTICLE_COUNT <= 0) {
			return;
		}

		ServerLevel level = player.serverLevel();
		PressureParticleOptions options = new PressureParticleOptions(
				particleColor(data), (float) BleachTuning.HOVER_PARTICLE_SCALE);

		// Count 0 makes the three offsets a velocity rather than a scatter — the only way to give a
		// server-spawned particle a direction — so the scatter is walked here, one packet apiece.
		double spread = BleachTuning.HOVER_PARTICLE_SPREAD;
		for (int i = 0; i < BleachTuning.HOVER_PARTICLE_COUNT; i++) {
			level.sendParticles(options,
					player.getX() + (level.random.nextDouble() - 0.5) * 2.0 * spread,
					player.getY(),
					player.getZ() + (level.random.nextDouble() - 0.5) * 2.0 * spread,
					0, 0.0, -BleachTuning.HOVER_PARTICLE_FALL, 0.0, 1.0);
		}
	}

	/** Falls back to the HUD colour before a zanpakutō exists, so the spill matches the bar. */
	private static int particleColor(SpiritualData data) {
		Kit kit = AbilityRegistry.kitFor(data);
		return kit == null ? BleachTuning.HUD_COLOR_BASE : kit.particleColor();
	}
}
