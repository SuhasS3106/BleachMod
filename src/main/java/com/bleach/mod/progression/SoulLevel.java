package com.bleach.mod.progression;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ModToggle;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.network.BleachNetworking;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Soul Points and Soul Level · PRD §2.1–2.4. The award, the daily cap, the level curve and the one
 * attribute Soul Level actually grants.
 *
 * <p>The scalars are public and static because three unrelated places need to agree on them: the
 * award below, the sync payload the stats screen reads, and {@code /bleach} readouts. A second copy
 * of {@link #catchUp} is exactly how a player ends up seeing one multiplier and being paid another.
 */
public final class SoulLevel {
	private SoulLevel() {
	}

	/**
	 * Key for the max-health modifier. A stable {@link ResourceLocation} rather than a random UUID —
	 * 1.21 keys attribute modifiers by id, which is what makes {@link #applyHealth} idempotent
	 * across a rejoin: remove by key, then add.
	 */
	public static final ResourceLocation HEALTH_MODIFIER_ID = BleachMod.id("soul_level_health");

	// --- Scalars · PRD §2.1–2.2, §2.5 -------------------------------------------------

	/** Mobs pay more as the world advances, because §2.5 also makes them hit harder. */
	public static double worldScalar(double wsl) {
		return 1.0 + BleachTuning.WORLD_SCALAR_PER_LEVEL * (wsl - 1.0);
	}

	/**
	 * The behind-the-curve bonus. Clamped at 1.0 on the low side: being <em>above</em> WSL is
	 * neutral, never penalised. Punishing your strongest players for winning is how a PvP server
	 * empties out.
	 */
	public static double catchUp(double wsl, int soulLevel) {
		return Mth.clamp(1.0 + BleachTuning.CATCHUP_PER_LEVEL_GAP * (wsl - soulLevel),
				1.0, BleachTuning.CATCHUP_MAX);
	}

	/**
	 * PRD §2.2: the cap is multiplied by <b>both</b> scalars, for the same reason the awards are.
	 * A flat cap silently cancels the catch-up bonus and a new player gains nothing from being
	 * behind.
	 */
	public static int dailyCap(double wsl, int soulLevel) {
		return (int) (BleachTuning.SPX_DAILY_CAP_BASE * worldScalar(wsl) * catchUp(wsl, soulLevel));
	}

	/** What is left of today's cap, floored at zero. */
	public static int remainingToday(double wsl, SpiritualData data) {
		return Math.max(0, dailyCap(wsl, data.soulLevel) - data.spxEarnedToday);
	}

	/** The mob damage multiplier this player currently faces · PRD §2.5. */
	public static double mobScalar(double wsl, int soulLevel) {
		double effective = Math.min(wsl, soulLevel + (double) BleachTuning.MOB_SCALE_LEVEL_HEADROOM);
		return Math.max(1.0, 1.0 + BleachTuning.MOB_SCALE_PER_LEVEL * (effective - 1.0));
	}

	// --- The daily counter ------------------------------------------------------------

	/**
	 * PRD §2.2: a day stamp, not a timer. {@code !=} rather than {@code >} so that {@code /time set}
	 * moving the clock backwards rolls the counter too — otherwise a server that resets the clock
	 * freezes everyone's cap at whatever they had spent.
	 */
	public static void rollDay(MinecraftServer server, SpiritualData data) {
		long day = WorldSoulLevel.currentDay(server);
		if (day != data.lastSpxDay) {
			data.lastSpxDay = day;
			data.spxEarnedToday = 0;
		}
	}

	// --- The award --------------------------------------------------------------------

	private static void onDeath(LivingEntity victim, DamageSource source) {
		if (!ModToggle.isEnabled() || !(victim.level() instanceof ServerLevel level)) {
			return;
		}

		ServerPlayer killer = KillAttribution.payee(victim, source);
		if (killer == null) {
			return;
		}

		MinecraftServer server = level.getServer();
		SpiritualData data = BleachAttachments.get(killer);
		rollDay(server, data);

		double wsl = WorldSoulLevel.get(server).value();
		double catchUp = catchUp(wsl, data.soulLevel);

		double gross;
		if (victim instanceof ServerPlayer victimPlayer) {
			int gap = Math.max(0, BleachAttachments.get(victimPlayer).soulLevel - data.soulLevel);
			int base = BleachTuning.SPX_PLAYER_BASE + BleachTuning.SPX_PLAYER_PER_LEVEL_GAP * gap;
			// No worldScalar on player kills: they already scale on the level gap, and applying both
			// would double-count · PRD §2.1.
			gross = base * catchUp;
		} else {
			gross = BleachTuning.mobSpx(victim.getType()) * worldScalar(wsl) * catchUp;
		}

		int award = Math.min((int) gross, remainingToday(wsl, data));
		if (award <= 0) {
			return;
		}

		data.spx += award;
		data.spxEarnedToday += award;

		// Announced here, before the level-up loop below spends it. The bank is filled and drawn down
		// in the same tick, so the sync that follows cannot be differenced to recover what was earned
		// · SpxGainPayload.
		BleachNetworking.sendSpxGain(killer, award);

		if (levelUp(killer, data)) {
			// A level-up changes this player's weight in the world average, so the roster entry has
			// to move with it rather than waiting for the next daily sweep.
			WorldSoulLevel.get(server).record(killer, data.lastSpxDay);
		}

		SpiritualTicker.sync(killer, true);
	}

	/**
	 * Spend banked SPX on as many levels as it covers. A loop rather than one level per kill: at the
	 * bottom of the curve a single boss is worth several levels, and paying them out one kill at a
	 * time would strand the surplus.
	 *
	 * @return whether any level was gained
	 */
	public static boolean levelUp(ServerPlayer player, SpiritualData data) {
		boolean gained = false;

		while (data.soulLevel < BleachTuning.SL_MAX) {
			int required = SpxTable.toNext(data.soulLevel);
			if (required <= 0 || data.spx < required) {
				break;
			}
			data.spx -= required;
			data.soulLevel++;
			gained = true;
		}

		// At the cap there is nothing left to buy, and a bank that keeps growing would render as a
		// progress bar filling toward a level that never arrives.
		if (data.soulLevel >= BleachTuning.SL_MAX) {
			data.spx = 0;
		}

		if (gained) {
			applyHealth(player, data);
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS,
					(float) BleachTuning.SL_LEVELUP_SOUND_VOLUME,
					(float) BleachTuning.SL_LEVELUP_SOUND_PITCH);
		}
		return gained;
	}

	// --- The one attribute Soul Level grants · PRD §2.4 --------------------------------

	/**
	 * {@code floor(SL / 2) × SL_HP_PER_TWO_LEVELS} bonus max health, as an {@code ADD_VALUE}
	 * modifier.
	 *
	 * <p>Removed before it is added, every time. The modifier is permanent — it round-trips through
	 * the player's own attribute NBT — so applying without removing on every join would stack a
	 * second copy each session until a capped player had a health bar off the side of the screen.
	 */
	public static void applyHealth(ServerPlayer player, SpiritualData data) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
		if (attribute == null) {
			return;
		}

		attribute.removeModifier(HEALTH_MODIFIER_ID);

		double bonus = Math.floor(data.soulLevel / 2.0) * BleachTuning.SL_HP_PER_TWO_LEVELS;
		if (bonus > 0.0) {
			attribute.addPermanentModifier(new AttributeModifier(
					HEALTH_MODIFIER_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
		}

		// Losing levels is only reachable through /bleach sl set, but a player left above their new
		// maximum renders with a health bar that cannot be refilled or emptied correctly.
		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register(SoulLevel::onDeath);

		ServerPlayerEvents.JOIN.register(player -> {
			SpiritualData data = BleachAttachments.get(player);
			applyHealth(player, data);
			rollDay(player.server, data);
			WorldSoulLevel.get(player.server).record(player, WorldSoulLevel.currentDay(player.server));
		});

		// The attribute modifier does not survive the respawn — the new ServerPlayer is built from a
		// fresh attribute map — so it has to be re-applied rather than merely carried in the data.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
				applyHealth(newPlayer, BleachAttachments.get(newPlayer)));

		// The last chance to bank this player's contribution while their data is still in memory.
		// Everything after this point would have to go back to the player file to find it.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				WorldSoulLevel.get(server).record(handler.player, WorldSoulLevel.currentDay(server)));
	}
}
