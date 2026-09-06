package com.bleach.mod.ability.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.bleach.mod.ability.Ability;
import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.kits.EnmaKorogiManager;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.network.AuraSensePayload;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Aura Sense · a hold-to-channel reading of every soul in reach, taken with the eyes shut.
 *
 * <p>Shape follows {@link SpiritualFlex} exactly, and for the same reasons: it is a channel, not an
 * activation, so the dispatcher only ever flips {@code data.sensing} and every tick of behaviour is
 * owned by {@link #tickAll} inside the one server tick handler. The {@link Ability} registration
 * exists so the id resolves and so the sense appears wherever abilities are enumerated.
 *
 * <h2>Reach is the target's, not the sensor's</h2>
 *
 * <p>An aura carries as far as the soul behind it is strong · {@link #reach}. A Soul Level 1 player
 * is felt at 80 blocks and a capped one at ~350, whoever is doing the sensing, and a mob — which has
 * no Soul Level at all — is a candle at {@link BleachTuning#AURA_RANGE_UNRANKED}. That is why the
 * sweep below is two passes rather than one: the player list is a handful of entries that can be
 * walked in full at any distance, while the unranked pass only ever has to look inside a 64-block
 * box, which is what keeps a 700-block-wide scan from being a 700-block-wide entity query.
 *
 * <h2>What it costs</h2>
 *
 * <p><b>No SP.</b> The price is paid in sight: the client draws a blackout over the whole viewport
 * while the channel is up, so this is never an overlay on normal vision — you give up the world to
 * read it. Everyone else gets a particle at the sensor's head to read that trade by.
 *
 * <p>Because nothing is spent, nothing here can fail on the pool, and the channel has exactly two
 * ways to end: the key comes up, or something takes the sense away · {@link #canSense}.
 */
public final class AuraSense implements Ability {

	/** Mobs, and anything else without a Soul Level, count as zero — as in {@link SpiritualFlex}. */
	private static final int UNRANKED_SOUL_LEVEL = 0;

	/** Full saturation/value sweep for the derived mob hue. */
	private static final float HUE_DEGREES = 360.0f;

	@Override
	public ResourceLocation id() {
		return AbilityRegistry.AURA_SENSE;
	}

	/** Pressure-sense, not a technique of the blade. Usable sheathed, and before any kit exists. */
	@Override
	public boolean requiresDrawnSword() {
		return false;
	}

	/** Zero, and there is no per-tick drain behind it either: the sense is paid for in sight alone. */
	@Override
	public double spCost(SpiritualData data) {
		return 0.0;
	}

	/** No-op by design; see the class docs. The channel is owned by {@link #tickAll}. */
	@Override
	public void onActivate(ServerPlayer player, SpiritualData data) {
	}

	// --- Derived values · BALANCE.md §N --------------------------------------------------

	/** How far this Soul Level's aura carries. Unranked entities use {@link #reachUnranked()}. */
	public static double reach(int soulLevel) {
		return BleachTuning.AURA_RANGE_BASE + BleachTuning.AURA_RANGE_PER_LEVEL * (soulLevel - 1);
	}

	public static double reachUnranked() {
		return BleachTuning.AURA_RANGE_UNRANKED;
	}

	/**
	 * Whether this player can sense at all, right now.
	 *
	 * <p>Death and spectator mode are the obvious two — the client sends edges, not a heartbeat, so
	 * nothing else would ever clear the flag for a player who died holding the key.
	 *
	 * <p>The third is <b>Enma Kōrogi</b>. Tōsen's Bankai takes all five senses from everyone inside
	 * the dome, and a sense that kept working through it would not just be an oversight — it would
	 * be strictly the best answer to the ability, since a player robbed of sight is exactly the
	 * player who most wants to close their eyes and read auras instead. The dome's own caster keeps
	 * their senses and so keeps this one; that asymmetry is the whole of Enma Kōrogi.
	 *
	 * <p>Checked on the start edge <em>and</em> every tick of the channel, because a dome that lands
	 * on a player already sensing has to take the sense off them mid-hold.
	 */
	public static boolean canSense(ServerPlayer player) {
		return player.isAlive()
				&& !player.isSpectator()
				&& !EnmaKorogiManager.isDeprived(player);
	}

	// --- The tick -------------------------------------------------------------------------

	/**
	 * Called once per server tick from {@code SpiritualTicker}, alongside the Flex channel. Costs
	 * nothing and touches no pool, so the ordering that matters for Flex does not matter here.
	 */
	public static void tickAll(MinecraftServer server) {
		int interval = Math.max(1, BleachTuning.AURA_SYNC_INTERVAL_TICKS);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			SpiritualData data = BleachAttachments.get(player);
			if (!data.sensing) {
				continue;
			}

			if (!canSense(player)) {
				// The inactive payload is what lifts the player's eyelids, so it must not be skipped:
				// dropping the flag alone would leave them behind a blackout with nothing coming.
				stop(player, data);
				continue;
			}

			if (player.tickCount % interval == 0) {
				push(player);
			}

			tell(player);
		}
	}

	/**
	 * Take a reading and send it. Called on the interval, and once more the moment the key goes down
	 * so the first packet does not wait for the next slot — the client keeps its eyes open until one
	 * arrives, and a visible delay before the lid falls reads as an ability that did not fire.
	 */
	public static void push(ServerPlayer sensor) {
		ServerPlayNetworking.send(sensor, new AuraSensePayload(true, scan(sensor)));
	}

	/**
	 * Drop the channel and tell the client. Idempotent, and reachable from the dispatcher's own
	 * {@code SENSE_STOP} as well as from every server-side reason the channel can end.
	 */
	public static void stop(ServerPlayer player, SpiritualData data) {
		if (!data.sensing) {
			return;
		}
		data.sensing = false;
		ServerPlayNetworking.send(player, AuraSensePayload.INACTIVE);
	}

	// --- The sweep --------------------------------------------------------------------------

	private static List<AuraSensePayload.Aura> scan(ServerPlayer sensor) {
		ServerLevel level = sensor.serverLevel();
		Vec3 origin = sensor.getEyePosition();
		List<AuraSensePayload.Aura> found = new ArrayList<>();

		// Players first, off the level's own list. A capped Soul Level is felt a third of a kilometre
		// away, and no AABB query wants to be that wide — but the list of players in a level is a
		// handful of entries whatever the distance, so it is walked in full and filtered by reach.
		for (ServerPlayer other : level.players()) {
			if (other == sensor || other.isSpectator() || !other.isAlive()) {
				continue;
			}

			SpiritualData otherData = BleachAttachments.get(other);
			double range = reach(otherData.soulLevel);
			if (other.distanceToSqr(origin) > range * range) {
				continue;
			}

			found.add(aura(origin, other, BleachTuning.AURA_COLOR_PLAYER, otherData.soulLevel));
		}

		// Everything else, inside the one box the unranked reach allows. Players are excluded here
		// rather than being allowed to arrive twice with the shorter reach of the two passes.
		double unranked = reachUnranked();
		double unrankedSq = unranked * unranked;
		AABB box = sensor.getBoundingBox().inflate(unranked);
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box,
				candidate -> candidate != sensor && candidate.isAlive() && !(candidate instanceof ServerPlayer))) {

			if (entity.distanceToSqr(origin) > unrankedSq) {
				continue;
			}

			found.add(aura(origin, entity, colorFor(entity.getType()), UNRANKED_SOUL_LEVEL));
		}

		// Nearest first, then truncated: a packet that has to drop readings should drop the faint
		// distant ones, not whatever the entity iteration order happened to reach last.
		int cap = Math.max(0, BleachTuning.AURA_MAX_ENTRIES);
		if (found.size() > cap) {
			found.sort(Comparator.comparingDouble(a -> a.dx() * a.dx() + a.dy() * a.dy() + a.dz() * a.dz()));
			return new ArrayList<>(found.subList(0, cap));
		}
		return found;
	}

	/** Centre of mass rather than feet or eyes, so the blob sits on the body at any height. */
	private static AuraSensePayload.Aura aura(Vec3 origin, LivingEntity entity, int color, int soulLevel) {
		Vec3 centre = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		return new AuraSensePayload.Aura(
				entity.getId(),
				(float) (centre.x - origin.x),
				(float) (centre.y - origin.y),
				(float) (centre.z - origin.z),
				color,
				(byte) soulLevel);
	}

	// --- Colour ---------------------------------------------------------------------------

	/**
	 * One colour per entity type, derived from the type's registry id rather than listed.
	 *
	 * <p>A table would have to be maintained against every mob vanilla and every other mod ships, and
	 * would be wrong the first time one was added; hashing the id is stable across restarts, stable
	 * across the two sides, identical for every entity of a type and different for every other type,
	 * which is the whole of the requirement. Saturation and value are pinned so that no type can draw
	 * itself as a near-black or a washed-out grey against the blackout.
	 */
	public static int colorFor(EntityType<?> type) {
		ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		int hash = id.toString().hashCode();
		float hue = Math.floorMod(hash, (int) HUE_DEGREES) / HUE_DEGREES;
		return Mth.hsvToRgb(hue,
				(float) BleachTuning.AURA_MOB_SATURATION,
				(float) BleachTuning.AURA_MOB_VALUE) & 0xFFFFFF;
	}

	// --- Presentation ----------------------------------------------------------------------

	/**
	 * The outward tell. A sense that could not be seen from outside would be a wallhack with no
	 * counterplay; a pressure mote at the sensor's head is enough for anyone watching to know their
	 * eyes are shut and that they are, right now, walking blind.
	 */
	private static void tell(ServerPlayer sensor) {
		int interval = Math.max(1, BleachTuning.AURA_TELL_INTERVAL_TICKS);
		if (sensor.tickCount % interval != 0) {
			return;
		}

		// Same particle and scale as the Flex ring, so the mod's pressure reads as one material.
		PressureParticleOptions options = new PressureParticleOptions(
				BleachTuning.AURA_COLOR_PLAYER, (float) BleachTuning.FLEX_PARTICLE_SCALE);

		Vec3 eye = sensor.getEyePosition();
		sensor.serverLevel().sendParticles(options, eye.x, eye.y, eye.z, 1, 0.2, 0.2, 0.2, 0.0);
	}
}
