package com.bleach.mod.ability.kits;

import com.bleach.mod.tuning.BleachTuning;

/**
 * The arithmetic behind Gerard's kit, kept apart from the abilities so it can be tested without a
 * server.
 *
 * <p>Two of these carry guards rather than numbers. Reflection above 1.0 makes two Gerards an
 * amplifying loop and one Gerard unapproachable with any weapon; a negate chance that can reach 1.0
 * is a player nobody can damage. Neither failure is visible until someone is standing in front of
 * it, which is why both are clamped here rather than trusted to the config.
 */
public final class MiracleAbilities {
	private MiracleAbilities() {
	}

	/**
	 * Fraction of a melee hit thrown back at whoever landed it.
	 *
	 * <p>Canon: Hoffnung is "the hopes of people bundled together", and damaging hope produces
	 * despair — the injury returns disproportionately. It grows with stacks, so the longer a fight
	 * with Gerard runs the worse hitting him becomes.
	 *
	 * <p><b>Held at or below 1.0.</b> Above that, the reflection exceeds the blow that caused it and
	 * melee against him stops being a decision.
	 */
	public static double reflectFraction(int stacks) {
		int held = Math.min(Math.max(0, stacks), Math.max(0, BleachTuning.M_STACK_MAX));
		double raw = BleachTuning.M_REFLECT_BASE + held * BleachTuning.M_REFLECT_PER_STACK;
		return Math.min(1.0, Math.max(0.0, raw));
	}

	/**
	 * Chance for The Miracle to negate an incoming hit outright.
	 *
	 * <p>This is the Schrift itself rather than a side effect of it: <i>"the more improbable a
	 * specific event is, the more likely The Miracle can make it occur."</i> So the chance is driven
	 * by how badly the odds are stacked — how much health is gone, and how many are on him.
	 *
	 * @param healthFraction current health over maximum, 0..1
	 * @param nearbyEnemies hostiles within {@link BleachTuning#M_PROB_ENEMY_RADIUS}, himself excluded
	 */
	public static double miracleChance(double healthFraction, int nearbyEnemies) {
		double missing = 1.0 - Math.min(1.0, Math.max(0.0, healthFraction));
		int extra = Math.max(0, nearbyEnemies - 1);

		double chance = BleachTuning.M_PROB_BASE
				+ BleachTuning.M_PROB_PER_MISSING_HP * missing
				+ BleachTuning.M_PROB_PER_ENEMY * extra;

		// Capped twice: by the tuning value, and then unconditionally under 1.0. A configured cap of
		// 1.0 or above would be a player who cannot be damaged at all.
		double cap = Math.min(0.95, Math.max(0.0, BleachTuning.M_PROB_MAX));
		return Math.min(cap, Math.max(0.0, chance));
	}

	/**
	 * Shortest distance from a point to the beam, treating the beam as a ray from the origin.
	 *
	 * <p>Clamped at the origin rather than projecting backwards, so something standing behind Gerard
	 * is measured from Gerard and not from an imaginary beam coming out of his back.
	 *
	 * @param px py pz the target, relative to the firing point
	 * @param dx dy dz the beam direction, not required to be normalised
	 */
	public static double distanceToRay(double px, double py, double pz,
			double dx, double dy, double dz) {
		double lengthSq = dx * dx + dy * dy + dz * dz;
		if (lengthSq < 1.0e-12) {
			// No direction to project onto. Fall back to straight-line distance rather than NaN.
			return Math.sqrt(px * px + py * py + pz * pz);
		}

		double t = (px * dx + py * dy + pz * dz) / lengthSq;
		t = Math.max(0.0, t);

		double cx = px - dx * t;
		double cy = py - dy * t;
		double cz = pz - dz * t;
		return Math.sqrt(cx * cx + cy * cy + cz * cz);
	}

	/**
	 * Whether a target lies inside a swing's arc.
	 *
	 * @param cosine dot of the caster's look direction with the unit vector to the target
	 * @param arcDegrees half-angle, clamped to 0..180. An arc of zero still connects with whatever
	 *        is dead ahead rather than disabling the move outright
	 */
	public static boolean inArc(double cosine, double arcDegrees) {
		double half = Math.min(180.0, Math.max(0.0, arcDegrees));
		return cosine >= Math.cos(Math.toRadians(half)) - 1.0e-9;
	}
}
