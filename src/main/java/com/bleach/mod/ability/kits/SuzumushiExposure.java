package com.bleach.mod.ability.kits;

/**
 * One mob's accumulated exposure to Suzumushi's chime · {@code BALANCE.md} §J.7.
 *
 * <p>Tōsen's Shikai stages itself off the victim's spiritual pressure: the chime drains the pool,
 * and Blindness and Freeze land only once it is empty. A mob has no pool, so it needs a stand-in
 * for the same clock, and this is it — one counter that fills while the mob stands in the radius
 * and drains while it does not. {@code TOSEN_SHIKAI_MOB_RESIST_TICKS} is deliberately set to the
 * number of ticks it takes {@code TOSEN_SHIKAI_SP_DRAIN_PER_TICK} to empty a Soul Level 1 player,
 * so a zombie and a fresh shinigami break at the same moment.
 *
 * <p>Attached to the mob rather than held in a static UUID map, for the same reason as
 * {@link MobInversion}: a map cannot learn that an entity has gone away, so every mob that dies,
 * despawns or unloads inside the radius would leak its entry forever. Hanging the counter off the
 * entity makes the question disappear.
 *
 * <p>The drain is applied lazily, on the next tick the mob is seen, rather than by a ticker of its
 * own — {@link #lastSeenTick} is what makes that possible. Nothing reads the counter except the
 * aura that writes it, so a mob that walks out and never comes back does not need updating; one
 * that comes back has its absence charged against it at that moment.
 *
 * <p>Non-persistent. The whole counter is worth a few seconds and the effects it gates are
 * vanilla-tracked, so it has nothing to say after a chunk reload.
 */
public final class SuzumushiExposure {
	/** Ticks of accumulated exposure, clamped to the resist threshold so recovery stays bounded. */
	public int exposureTicks;

	/** The tick the counter was last touched, so time spent out of the radius can be measured. */
	public int lastSeenTick = Integer.MIN_VALUE;
}
