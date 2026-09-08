package com.bleach.mod.progression;

import org.jetbrains.annotations.Nullable;

import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.item.SpiritWeapon;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * The taint rule · PRD §2.1. <b>The critical mechanic of Phase 5 and the easiest to get subtly
 * wrong</b>, so it lives on its own rather than inside the mixin.
 *
 * <p>SPX is awarded only if every point of damage an entity took came from one player, and only if
 * the killing blow came from that player's drawn zanpakutō. Everything else — a second player, a
 * wandering skeleton, fall damage, cactus, drowning, suffocation, fire the killer did not apply,
 * and <em>their own tamed pets</em> — sets the taint.
 *
 * <p>The pet case is the one people expect to be an exception and is not. A wolf's damage source
 * names the wolf, not its owner, so no player resolves and the entity taints: pet-tanking a target
 * you then finish yourself pays nothing. That is deliberate.
 */
public final class KillAttribution {
	private KillAttribution() {
	}

	/**
	 * Fold one incoming hit into the entity's credit. Called at the head of every server-side
	 * {@code hurt}, before anything can cancel it.
	 */
	public static void record(LivingEntity victim, DamageSource source) {
		KillCredit credit = victim.getAttachedOrCreate(BleachAttachments.KILL_CREDIT);
		if (credit.tainted) {
			// Nothing left to learn about this entity. Bailing here also keeps the common case — a
			// long fight with an already-tainted mob — down to a single field read.
			return;
		}

		ServerPlayer attacker = attackerOf(source);
		if (attacker == null) {
			credit.tainted = true;
			return;
		}

		if (credit.soleDamager == null) {
			credit.soleDamager = attacker.getUUID();
		} else if (!credit.soleDamager.equals(attacker.getUUID())) {
			credit.tainted = true;
		}
	}

	/**
	 * The player behind a damage source, or null.
	 *
	 * <p>{@code getEntity()} already resolves to the shooter for vanilla projectiles, but the
	 * projectile owner is checked too: a source built by hand — which every ability in Phases 6–11
	 * will do — is not obliged to set the causing entity, and a bleach projectile that quietly
	 * tainted its own target would be a bug nobody would look for here.
	 */
	@Nullable
	public static ServerPlayer attackerOf(DamageSource source) {
		if (source.getEntity() instanceof ServerPlayer player) {
			return player;
		}

		Entity direct = source.getDirectEntity();
		if (direct instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer owner) {
			return owner;
		}
		return null;
	}

	/**
	 * Whether this entity's death pays out, and to whom. Null for every rejected case, which is the
	 * overwhelming majority.
	 *
	 * <p>The killing blow must come from the player's own drawn spirit weapon — either directly, or
	 * through one of the mod's own damage sources. <b>A vanilla bow still pays nothing</b>: the
	 * indirect branch is gated on {@link BleachDamage#is}, and an ordinary arrow is neither in the
	 * {@code bleach} damage-type tag nor a {@code Player} direct entity, so it is rejected for the
	 * same reason it always was (PRD §3.2 — the weapon is the progression, not the fight).
	 *
	 * <p>Before this, {@code getDirectEntity() == killer} rejected <em>every</em> indirect source,
	 * which silently paid zero for Gin's beam, Suì-Fēng's missile and Yamamoto's cone as well.
	 *
	 * <p>The drawn-weapon gate is a <em>separate</em> rule and is kept deliberately (PRD §3.2). It is
	 * already race-aware: {@link SpiritWeapon#isDrawn} accepts any spirit weapon, so a Quincy holding
	 * a Heilig Bogen satisfies it exactly as a Shinigami holding a zanpakutō does.
	 */
	@Nullable
	public static ServerPlayer payee(LivingEntity victim, DamageSource source) {
		KillCredit credit = victim.getAttachedOrCreate(BleachAttachments.KILL_CREDIT);
		if (credit.tainted || credit.soleDamager == null) {
			return null;
		}

		ServerPlayer killer = attackerOf(source);
		if (killer == null || !credit.soleDamager.equals(killer.getUUID())) {
			return null;
		}
		if (!SpiritWeapon.isDrawn(killer)) {
			return null;
		}
		return isCreditedBlow(source, killer) ? killer : null;
	}

	/**
	 * The killing blow itself: the player's own hand, or one of the mod's own damage sources.
	 * Anything else — a vanilla arrow, a trident, a mob's hit — is not a credited blow.
	 */
	private static boolean isCreditedBlow(DamageSource source, ServerPlayer killer) {
		if (source.getDirectEntity() == killer) {
			return true;
		}
		return BleachDamage.is(source);
	}
}
