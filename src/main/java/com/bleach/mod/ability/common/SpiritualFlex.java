package com.bleach.mod.ability.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.bleach.mod.ability.Ability;
import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.effect.ReiatsuEffect;
import com.bleach.mod.network.FlexStatePayload;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/**
 * Spiritual Flex · PRD §5 · {@code BALANCE.md} §H.
 *
 * <p>A hold-to-channel field of raw pressure. It requires no sword (PRD §3.2), costs SP every tick
 * it is held, and applies {@link ReiatsuEffect} to everything inside a Soul-Level-scaled radius.
 *
 * <h2>Why this implements {@link Ability} but does nothing on activation</h2>
 *
 * <p>Flex is a channel, not an activation. The dispatcher only ever flips {@code data.flexing} for
 * it, and every tick of behaviour is owned by {@link #tickAll} running inside the one server tick
 * handler — the same shape {@code TransformAbility} uses, and for the same reason: state that is
 * entered and left through two different doors ends up leaking out of one of them. The registration
 * exists so the id resolves and so the ability shows up wherever abilities are enumerated.
 *
 * <h2>The tick, in order</h2>
 *
 * <ol>
 *   <li>every flexer pays {@link #drainPerSecond}, or drops the channel if the pool cannot cover it</li>
 *   <li>each surviving flexer sweeps their radius and resolves a tier per target</li>
 *   <li>a target who is flexing back is charged {@link #counterDrainPerSecond} — <b>once per tick</b>,
 *       no matter how many hostile fields they are standing in — and gets the gap reduction only if
 *       they could pay for it</li>
 * </ol>
 *
 * <p>The counter charge is resolved <em>before</em> the tier is applied rather than after, so PRD
 * §5.2's "if a countering player's SP hits zero they take the full untempered tier" is what actually
 * happens, in the same tick, instead of one tick late.
 */
public final class SpiritualFlex implements Ability {

	/** Mobs, and anything else without a Soul Level, count as zero · PRD §5.1. */
	private static final int UNRANKED_SOUL_LEVEL = 0;

	@Override
	public ResourceLocation id() {
		return AbilityRegistry.SPIRITUAL_FLEX;
	}

	/** PRD §3.2: pressure, not a technique of the blade. Usable sheathed, and before any kit exists. */
	@Override
	public boolean requiresDrawnSword() {
		return false;
	}

	/**
	 * Deliberately zero. The cost is a per-tick drain, not an activation cost — charging one here as
	 * well would bill the player twice for the first tick of every hold.
	 */
	@Override
	public double spCost(SpiritualData data) {
		return 0.0;
	}

	/** No-op by design; see the class docs. The channel is owned by {@link #tickAll}. */
	@Override
	public void onActivate(ServerPlayer player, SpiritualData data) {
	}

	// --- Derived values · BALANCE.md §H --------------------------------------------------

	/** SL 1 → 8.4 blocks · SL 20 → 16.0. */
	public static double radius(SpiritualData data) {
		return BleachTuning.FLEX_RADIUS_BASE + BleachTuning.FLEX_RADIUS_PER_LEVEL * data.soulLevel;
	}

	/** SL 1 → 3.0 SP/s · SL 20 → 1.1. Additive with any transformation drain. */
	public static double drainPerSecond(SpiritualData data) {
		return Math.max(0.0, BleachTuning.FLEX_DRAIN_BASE
				- BleachTuning.FLEX_DRAIN_PER_LEVEL * (data.soulLevel - 1));
	}

	/** SL 1 → 4.0 SP/s · SL 20 → 1.53. Deliberately steeper than flexing: pushing back should bleed. */
	public static double counterDrainPerSecond(SpiritualData data) {
		return Math.max(0.0, BleachTuning.FLEX_COUNTER_DRAIN_BASE
				- BleachTuning.FLEX_COUNTER_DRAIN_PER_LEVEL * (data.soulLevel - 1));
	}

	/** Levels shaved off the gap by pushing back · PRD §5.2. SL 1 → 2 · SL 20 → 7. */
	public static int counterGapReduction(SpiritualData data) {
		return BleachTuning.FLEX_COUNTER_GAP_BASE
				+ data.soulLevel / Math.max(1, BleachTuning.FLEX_COUNTER_GAP_DIVISOR);
	}

	/** What one tick of holding the key costs. Public so the dispatcher can refuse an empty start. */
	public static double tickCost(SpiritualData data) {
		return drainPerSecond(data) / BleachTuning.TICKS_PER_SECOND;
	}

	private static double counterTickCost(SpiritualData data) {
		return counterDrainPerSecond(data) / BleachTuning.TICKS_PER_SECOND;
	}

	// --- The tick -------------------------------------------------------------------------

	/**
	 * Called once per server tick from {@code SpiritualTicker}, <b>before</b> the per-player pool
	 * pass. Running first means the spend has already paused regen and already moved the bar by the
	 * time that pass syncs it, rather than the client seeing every flex tick one tick late.
	 */
	public static void tickAll(MinecraftServer server) {
		List<ServerPlayer> flexers = null;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			SpiritualData data = BleachAttachments.get(player);
			if (!data.flexing) {
				continue;
			}

			// Death and spectator mode both end the channel outright. The client sends edges, not a
			// heartbeat, so nothing else would ever clear the flag for a player who died holding it.
			if (!player.isAlive() || player.isSpectator()) {
				data.flexing = false;
				continue;
			}

			double cost = tickCost(data);
			if (data.sp < cost) {
				// Ran dry. The flag drops rather than the channel stuttering back on at the first
				// point of regen — the same way a transformation ends when the pool does.
				data.flexing = false;
				continue;
			}

			data.spend(cost);

			if (flexers == null) {
				flexers = new ArrayList<>();
			}
			flexers.add(player);
		}

		// Whether each counterer could afford their push-back this tick. Memoised so standing inside
		// three hostile fields costs one counter drain, not three.
		Map<UUID, Boolean> countered = new HashMap<>();
		Set<UUID> stillFlexing = new HashSet<>();

		for (ServerPlayer flexer : flexers == null ? List.<ServerPlayer>of() : flexers) {
			SpiritualData data = BleachAttachments.get(flexer);
			stillFlexing.add(flexer.getUUID());

			int pressured = project(flexer, data, countered);

			// The field lights the world it stands in · FlexLight. After project, so a channel that
			// ran the pool dry inside its own sweep does not light up for the tick it ends on.
			FlexLight.update(flexer);

			// The field is silent by design against anyone within one Soul Level, which from the
			// inside is indistinguishable from a broken ability — so say so rather than leaving the
			// player to guess. PRD §5.1: the tier is bought with the level gap, and nothing else.
			if (flexer.tickCount % Math.max(1, BleachTuning.FLEX_FEEDBACK_TICKS) == 0) {
				flexer.displayClientMessage(Component.literal(pressured > 0
						? "Spiritual pressure — " + pressured + " under your reiatsu"
						: "Spiritual pressure — nothing within reach"), true);
			}

			// The start edge carries the burst; the keepalive after it must not, or the client would
			// re-detonate it every second · FlexStatePayload#onset.
			Integer last = announced.get(flexer.getUUID());
			boolean onset = last == null;
			if (onset || flexer.tickCount - last >= Math.max(1, BleachTuning.FLEX_STATE_KEEPALIVE_TICKS)) {
				broadcast(flexer, data, radius(data), onset);
			}
		}

		// Deliberately outside the loop and reached even when nobody is flexing: the tick on which the
		// last channel drops is exactly the tick with no flexers in it, and that is the one that has
		// to send the retraction.
		retractEnded(server, stillFlexing);
		FlexLight.retainOnly(server, stillFlexing);
	}

	/**
	 * Sweep one flexer's radius and apply a tier to everything that earns one.
	 *
	 * @return how many targets the field actually landed on
	 */
	private static int project(ServerPlayer flexer, SpiritualData data, Map<UUID, Boolean> countered) {
		ServerLevel level = flexer.serverLevel();
		double radius = radius(data);
		double radiusSq = radius * radius;

		// Box first, squared distance second — the box is what the chunk lookup can index, the
		// distance is what makes the field round rather than a cube.
		int pressured = 0;
		AABB box = flexer.getBoundingBox().inflate(radius);
		for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box,
				candidate -> candidate != flexer && candidate.isAlive())) {

			if (target.distanceToSqr(flexer) > radiusSq) {
				continue;
			}
			if (target instanceof ServerPlayer player && player.isSpectator()) {
				continue;
			}

			if (apply(flexer, data, target, countered)) {
				pressured++;
			}
		}

		return pressured;
	}

	private static boolean apply(ServerPlayer flexer, SpiritualData flexerData, LivingEntity target,
			Map<UUID, Boolean> countered) {
		SpiritualData targetData = target instanceof ServerPlayer player
				? BleachAttachments.get(player) : null;
		int targetSoulLevel = targetData == null ? UNRANKED_SOUL_LEVEL : targetData.soulLevel;

		int gap = flexerData.soulLevel - targetSoulLevel;
		if (gap < BleachTuning.FLEX_BASELINE_MIN_GAP) {
			// Genuinely outranked. Nothing to apply — and nothing to counter either, so a player
			// standing next to a weaker flexer is never billed for resisting a field that was not
			// pressuring them.
			return false;
		}

		// Resolved before the tier so a counterer's spend and its benefit land in the same tick.
		// Reached even when the gap alone buys nothing, because a peer's field is no longer silent:
		// there is now something real to push back against, so the charge is honest.
		boolean countering = targetData != null && targetData.flexing
				&& charge(target.getUUID(), targetData, countered);
		if (countering) {
			gap -= counterGapReduction(targetData);
		}

		int duration = BleachTuning.FLEX_EFFECT_DURATION_TICKS;
		int amplifier = ReiatsuEffect.amplifierFor(gap);

		if (amplifier == ReiatsuEffect.NO_TIER) {
			// The gap bought no tier of its own. Against a peer the field is still a physical
			// presence — being cancelled outright by anyone who merely happens to share your level
			// made the whole ability read as broken in even matches, which is the common case.
			//
			// Pushing back is what actually cancels it, not standing there being the same level:
			// a target already spending SP to counter has answered the field and owes nothing more.
			if (countering || BleachTuning.FLEX_BASELINE_DURATION_TICKS <= 0) {
				return false;
			}

			amplifier = 0;
			duration = BleachTuning.FLEX_BASELINE_DURATION_TICKS;
		}

		// A weaker instance never replaces a stronger one in vanilla, so without this a target who
		// starts countering stays crushed until the old tier expires — up to three seconds of
		// counterplay doing visibly nothing.
		MobEffectInstance current = target.getEffect(BleachEffects.REIATSU);
		if (current != null && current.getAmplifier() > amplifier) {
			target.removeEffect(BleachEffects.REIATSU);
		}

		target.addEffect(new MobEffectInstance(BleachEffects.REIATSU,
				duration, amplifier, true, false, true));

		// The bleed · BALANCE.md §H.5. Standing in a field you are outranked in costs health, on the
		// same gap the tier was bought with — so it scales with the level difference, it is answered
		// by countering exactly as the tier is, and a peer's field still cannot hurt you at all.
		bleed(flexer, target, gap);

		if (ReiatsuEffect.isCrushing(amplifier)) {
			// The one vanilla effect Reiatsu reaches for, because nausea has no attribute equivalent.
			// Hidden on both counts it can be hidden on; it still lists by name in the inventory
			// panel, which is PRD §11 item 2.
			target.addEffect(new MobEffectInstance(MobEffects.CONFUSION,
					duration, BleachTuning.REIATSU_NAUSEA_AMPLIFIER,
					true, false, false));
		}

		return true;
	}

	/**
	 * One damage tick of standing in a field · {@code BALANCE.md} §H.5.
	 *
	 * <p>The cadence is keyed to the <b>flexer's</b> tick count, not the target's, so each field
	 * bills each of its victims once a second: standing in two hostile fields hurts twice as much,
	 * which is the honest answer and the same rule the tiers already follow by taking the strongest.
	 *
	 * <p>{@link com.bleach.mod.damage.BleachDamage#SPIRIT_PRESSURE_BLEED} — in the same
	 * {@code bleach} tag and carrying the same death message as the generic ability type, so the
	 * bleed still goes through the Soul Level pipeline like everything else: the victim's bleach
	 * resistance applies and the kill is attributed to the flexer. It differs in exactly one way,
	 * {@code minecraft:no_knockback}, because a field that shoves is a field that pushes its victim
	 * out of itself — see the type's own note.
	 *
	 * <p>The one thing it inherits that is worth naming is vanilla's invulnerability window — a
	 * target being hit by a sword in the same second may shrug off that second's tick, which is
	 * correct enough: this is a bleed, not a burst, and it comes round again.
	 */
	private static void bleed(ServerPlayer flexer, LivingEntity target, int gap) {
		if (BleachTuning.REIATSU_DAMAGE_HOSTILE_ONLY
				&& !(target instanceof Enemy) && !(target instanceof ServerPlayer)) {
			return;
		}

		int interval = Math.max(1, BleachTuning.REIATSU_DAMAGE_INTERVAL_TICKS);
		if (flexer.tickCount % interval != 0) {
			return;
		}

		double damage = ReiatsuEffect.damageFor(gap);
		if (damage <= 0.0) {
			return;
		}

		target.hurt(BleachDamage.source(flexer.serverLevel(), BleachDamage.SPIRIT_PRESSURE_BLEED, flexer),
				(float) damage);
	}

	/**
	 * Bill a counterer, at most once per tick.
	 *
	 * @return whether they paid, and therefore whether they get the gap reduction. A counterer who
	 *         cannot afford it takes the full untempered tier — PRD §5.2.
	 */
	private static boolean charge(UUID id, SpiritualData data, Map<UUID, Boolean> countered) {
		Boolean already = countered.get(id);
		if (already != null) {
			return already;
		}

		double cost = counterTickCost(data);
		boolean afforded = data.sp >= cost;
		if (afforded) {
			data.spend(cost);
		} else {
			// Out of pressure with the key still down: the channel drops, exactly as it does for the
			// flex drain itself.
			data.flexing = false;
		}

		countered.put(id, afforded);
		return afforded;
	}

	// --- Presentation ---------------------------------------------------------------------

	/**
	 * Flexers this server has told clients about, and the tick each was last told about ·
	 * {@link FlexStatePayload}. Keyed on UUID rather than entity id so a respawn cannot inherit a
	 * previous body's announcement.
	 */
	private static final Map<UUID, Integer> announced = new HashMap<>();

	/**
	 * Tell nearby clients the field is up, on the start edge and on the keepalive beat.
	 *
	 * <h2>Why no particles</h2>
	 *
	 * <p>This used to spawn the entire field from here every other tick: a ring walked around the
	 * radius at 3.5 points per block plus a scatter of risers, each one its own {@code sendParticles}
	 * call to every viewer. At Soul Level 20 that is around ninety packets per viewer per emission,
	 * five times a second, for a shape completely determined by where the flexer is standing and how
	 * strong they are — both of which every client tracking them already knows.
	 *
	 * <p>It also looked flat, and could not not look flat: an effect that updates five times a second
	 * out of independently spawned specks has no continuity to read. The client now owns the whole
	 * presentation · {@code FlexRenderer}, and this sends one small packet per hold plus a keepalive.
	 *
	 * <p>{@code PlayerLookup.tracking} plus the flexer themselves. Tracking is exactly the set of
	 * clients that can resolve the entity id to a position to draw around — and it excludes the
	 * flexer, who is the one person guaranteed to want to see their own pressure.
	 */
	private static void broadcast(ServerPlayer flexer, SpiritualData data, double radius,
			boolean onset) {
		announced.put(flexer.getUUID(), flexer.tickCount);

		int tier = Math.max(0, mobTier(data));
		FlexStatePayload payload = new FlexStatePayload(flexer.getId(), true, onset,
				particleColor(data), (float) radius, (byte) tier);

		ServerPlayNetworking.send(flexer, payload);
		for (ServerPlayer viewer : PlayerLookup.tracking(flexer)) {
			ServerPlayNetworking.send(viewer, payload);
		}
	}

	/**
	 * Retract every announcement whose channel has ended.
	 *
	 * <p>Runs off {@link #announced} rather than off the flexers list because the interesting case is
	 * precisely a player who is no longer in it: dropped the key, ran dry, died, or logged out. The
	 * first three can be told to their viewers; the last cannot, which is what the client's expiry is
	 * for, so the entry is dropped either way and nothing accumulates.
	 */
	private static void retractEnded(MinecraftServer server, Set<UUID> stillFlexing) {
		if (announced.isEmpty()) {
			return;
		}

		Iterator<Map.Entry<UUID, Integer>> it = announced.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Integer> entry = it.next();
			if (stillFlexing.contains(entry.getKey())) {
				continue;
			}
			it.remove();

			ServerPlayer flexer = server.getPlayerList().getPlayer(entry.getKey());
			if (flexer == null) {
				continue;
			}

			FlexStatePayload off = FlexStatePayload.off(flexer.getId());
			ServerPlayNetworking.send(flexer, off);
			for (ServerPlayer viewer : PlayerLookup.tracking(flexer)) {
				ServerPlayNetworking.send(viewer, off);
			}
		}
	}

	/** Falls back to the HUD colour before a zanpakutō exists, so the field matches the bar. */
	private static int particleColor(SpiritualData data) {
		Kit kit = AbilityRegistry.kitFor(data);
		return kit == null ? BleachTuning.HUD_COLOR_BASE : kit.particleColor();
	}

	/** The tier this flexer would land on a mob right now. Reported by {@code /bleach flex}. */
	public static int mobTier(SpiritualData data) {
		return ReiatsuEffect.amplifierFor(data.soulLevel - UNRANKED_SOUL_LEVEL);
	}
}
