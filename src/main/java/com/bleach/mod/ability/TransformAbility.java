package com.bleach.mod.ability;

import com.bleach.mod.attachment.SpiritualData;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * A released state — Shikai or Bankai. Unlike a plain {@link Ability} these are not fire-and-forget:
 * they own a slice of the tick loop for as long as they are active, and they must be able to be
 * torn down from paths the player did not ask for (SP hitting zero, death, sheathing, logout).
 *
 * <p>Every kit defines both. {@link AbilityRegistry#registerKit} throws on a null in either slot —
 * a character with only one released state plays half the game, and the Shikai/Bankai split
 * (sustainable stance vs. committed burn) is the mod's core tension.
 */
public interface TransformAbility extends Ability {
	/** Which slot this occupies: {@link SpiritualData#STATE_SHIKAI} or {@code STATE_BANKAI}. */
	byte state();

	/** SP per second while active. Read by the ticker, not by the dispatcher. */
	double drainPerSecond();

	/** Entry threshold as a fraction of max SP at the given Soul Level. */
	double entryGatePercent(int soulLevel);

	/** Apply attribute modifiers, effects and bookkeeping. Called after the pool has been moved. */
	void onEnter(ServerPlayer player, SpiritualData data);

	/** Once per server tick while active. Drain and exertion are the ticker's job, not this one's. */
	void onTick(ServerPlayer player, SpiritualData data);

	/**
	 * Undo everything {@link #onEnter} did. Reached from every revert path without exception, so it
	 * must be idempotent and must never assume the player is alive, loaded or in the same dimension.
	 */
	void onRevert(ServerPlayer player, SpiritualData data);

	/**
	 * Whether the player may leave this state right now. Almost always yes — a state that drains you
	 * and cannot be dropped is a trap, not a choice — so the one legitimate no is a committed action
	 * already in flight, such as Suì-Fēng's Bankai mid-launch. An implementation returning false
	 * should say why on the action bar, or the key reads as broken.
	 *
	 * <p>Only consulted for a deliberate toggle-off. Death, logout, SP exhaustion, sheathing and the
	 * master switch all revert regardless.
	 */
	default boolean canRevert(ServerPlayer player, SpiritualData data) {
		return true;
	}

	/** Melee hook, live only while this transformation is. */
	default void onMeleeHit(ServerPlayer player, LivingEntity target, float damage) {
	}

	/** Melee damage bonus fraction while active (e.g. +0.25 for Ichigo Shikai, +0.40 for Ichigo Bankai). */
	default double meleeDamageBonus() {
		return 0.0;
	}

	/** Whether this transformation grants complete immunity to fire and lava damage. */
	default boolean isFireImmune() {
		return false;
	}

	/**
	 * Unused. Transformations are a toggle owned by the state machine: the dispatcher routes them
	 * through {@code SpiritualTicker.enter} / {@code forceRevert} so the Bankai loan and its
	 * claw-back can never be bypassed, and those call {@link #onEnter} / {@link #onRevert} instead.
	 */
	@Override
	default void onActivate(ServerPlayer player, SpiritualData data) {
	}
}
