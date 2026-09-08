package com.bleach.mod.ability.kits;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;

/**
 * The dose ledger behind Schrift D · {@code BALANCE.md} §P.6.
 *
 * <p>Askin's Deathdealing is about how much of a thing a body can take. Here a dose is a stacking
 * <b>vulnerability</b>: every dose on a target raises the damage it takes from everything, and doses
 * bleed off if nobody keeps applying them. That makes D a clock — commit to a target and finish it
 * before the doses fade — which is what separates it in play from Suì-Fēng's mark, which is a
 * position rather than a countdown.
 *
 * <p><b>It is deliberately not an outright kill.</b> A guaranteed death threshold makes one kit a
 * mandatory pick and every fight against it un-fun; the multiplier keeps the identity ("things die
 * faster the longer you work them") without the delete button.
 *
 * <p>Keyed by entity UUID rather than held on the entity, because doses apply to any
 * {@link LivingEntity} — mobs included — and only players carry a {@code SpiritualData}.
 */
public final class Doses {
	private Doses() {
	}

	/** One target's dose count and the tick its last dose landed. */
	private static final class Dose {
		private int count;
		private int lastTick;

		private Dose(int count, int lastTick) {
			this.count = count;
			this.lastTick = lastTick;
		}
	}

	private static final Map<UUID, Dose> ACTIVE = new ConcurrentHashMap<>();

	// ---- pure logic, unit tested ---------------------------------------------------

	/**
	 * Damage-taken multiplier for a dose count.
	 *
	 * <p>Clamped at {@link BleachTuning#DOSE_MAX} so a config that lets doses run away cannot make a
	 * target take unbounded damage, and floored at 1.0 so no configuration turns a dose into
	 * protection.
	 */
	public static double damageTakenMultiplier(int count) {
		if (count <= 0) {
			return 1.0;
		}
		int capped = Math.min(count, Math.max(0, BleachTuning.DOSE_MAX));
		return Math.max(1.0, 1.0 + capped * BleachTuning.DOSE_DAMAGE_PER);
	}

	/** The dose count after {@code applied} more, capped. Pure so the cap is testable on its own. */
	public static int stack(int current, int applied) {
		return Math.min(Math.max(0, current) + Math.max(0, applied), Math.max(0, BleachTuning.DOSE_MAX));
	}

	// ---- the ledger ----------------------------------------------------------------

	/** Adds doses to a target and restarts its decay clock. */
	public static void add(LivingEntity target, int amount, int nowTick) {
		if (amount <= 0 || !target.isAlive()) {
			return;
		}
		ACTIVE.compute(target.getUUID(), (id, existing) -> {
			if (existing == null) {
				return new Dose(stack(0, amount), nowTick);
			}
			existing.count = stack(existing.count, amount);
			existing.lastTick = nowTick;
			return existing;
		});
	}

	/** Current doses on a target, or zero. */
	public static int count(LivingEntity target) {
		Dose dose = ACTIVE.get(target.getUUID());
		return dose == null ? 0 : dose.count;
	}

	/** Convenience for the damage hook. */
	public static double damageTakenMultiplier(LivingEntity target) {
		return damageTakenMultiplier(count(target));
	}

	/** Forgets a target entirely — used when it dies, so its UUID cannot leak. */
	public static void clear(LivingEntity target) {
		ACTIVE.remove(target.getUUID());
	}

	/**
	 * Bleeds one dose off every target that has not been re-dosed within
	 * {@link BleachTuning#DOSE_DECAY_TICKS}, and drops entries that reach zero.
	 *
	 * <p>Iterating the whole map each pass is fine: it only ever holds targets a Deathdealing player
	 * has actually hit recently, and every entry is removed as it empties. Nothing here scales with
	 * the number of entities in the world.
	 *
	 * <p>Uses the server's own tick count, not a player's, so the clock cannot be reset by the
	 * dosing player dying, changing dimension, or logging out mid-stack.
	 */
	public static void tickAll(MinecraftServer server) {
		int decay = Math.max(1, BleachTuning.DOSE_DECAY_TICKS);
		int now = server.getTickCount();

		Iterator<Map.Entry<UUID, Dose>> entries = ACTIVE.entrySet().iterator();
		while (entries.hasNext()) {
			Dose dose = entries.next().getValue();
			if (now - dose.lastTick < decay) {
				continue;
			}
			dose.count--;
			dose.lastTick = now;
			if (dose.count <= 0) {
				entries.remove();
			}
		}
	}
}
