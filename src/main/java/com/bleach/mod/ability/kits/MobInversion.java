package com.bleach.mod.ability.kits;

/**
 * One mob's current Sakanade stagger roll · PRD §6.5.
 *
 * <p>Attached to the {@link net.minecraft.world.entity.Mob} itself rather than held in a static map
 * keyed by UUID. That is not a style preference: a UUID map has no way to learn that an entity has
 * gone away, so every mob that dies, despawns or unloads while afflicted leaks its entry forever.
 * Eviction hooks close the paths you remember — death — and miss the ones you do not — chunk
 * unload, which is the common case for a mob staggered at the edge of the aura and then left
 * behind. Hanging the state off the entity makes the question disappear: the roll is collected with
 * the entity that owns it.
 *
 * <p>Non-persistent. A roll is worth at most {@code SHINJI_MOB_REROLL_TICKS}, and the effect that
 * justifies it is vanilla-tracked and will have lapsed long before the chunk comes back.
 */
public final class MobInversion {
	/** The tick the current roll was made on, so the reroll interval can be measured. */
	public int lastRollTick = Integer.MIN_VALUE;

	/** Whether this mob is currently stumbling backwards. */
	public boolean inverted;
}
