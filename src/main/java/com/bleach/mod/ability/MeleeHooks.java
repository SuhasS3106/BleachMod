package com.bleach.mod.ability;

import com.bleach.mod.ModToggle;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.item.SpiritWeapon;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * The transformation melee hook: every landed zanpakutō swing, routed to the attacker's active
 * released state.
 *
 * <p><b>Why this is an event and not a mixin.</b> It used to be an {@code @Inject} into
 * {@code LivingEntity#actuallyHurt}. That method is <em>overridden outright</em> by
 * {@code Player#actuallyHurt}, which never calls {@code super} — so the hook fired when you hit a
 * zombie and was silently dead when you hit a player. Every on-hit Shikai in the mod (Ichigo's
 * cleave, Rukia's freeze burst, Shinji's Sakanade, Suì-Fēng's Nigeki Kessatsu mark) therefore did
 * nothing at all in PvP, which is the only place most of them are ever used.
 *
 * <p>{@link ServerLivingEntityEvents#AFTER_DAMAGE} is fired from {@code LivingEntity#hurt}, which
 * {@code Player} does call through, so one registration covers players and mobs alike.
 *
 * <h2>What counts as a swing</h2>
 * <ul>
 *   <li>the damage actually landed and was not turned away by a shield</li>
 *   <li>the attacker is a player and is also the <em>direct</em> entity — never a projectile</li>
 *   <li>the source is {@link DamageTypes#PLAYER_ATTACK}, which excludes the mod's own ability
 *       damage and so stops Ichigo's cleave from recursing into itself</li>
 *   <li>the blade is in the attacker's main hand · PRD §3.2</li>
 * </ul>
 */
public final class MeleeHooks {
	private MeleeHooks() {
	}

	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register(MeleeHooks::onAfterDamage);
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer) {
				LAST_DIRECT_HIT_TICK.put(serverPlayer.getUUID(), serverPlayer.tickCount);
			}
			return net.minecraft.world.InteractionResult.PASS;
		});
	}

	private static void onAfterDamage(LivingEntity victim, DamageSource source,
			float baseDamage, float damageTaken, boolean blocked) {
		if (!ModToggle.isEnabled() || victim.level().isClientSide()) {
			return;
		}

		if (!(source.getEntity() instanceof ServerPlayer attacker) || source.getDirectEntity() != attacker) {
			return;
		}

		if (!source.is(DamageTypes.PLAYER_ATTACK) || !SpiritWeapon.isDrawn(attacker)) {
			return;
		}

		// Direct melee attack connected with an entity (even if blocked by a shield or dealing 0 damage).
		// Record this so air-swing / miss abilities (like Yamamoto's Bankai raven cone) do NOT trigger.
		LAST_DIRECT_HIT_TICK.put(attacker.getUUID(), attacker.tickCount);

		if (blocked || damageTaken <= 0.0f) {
			return;
		}

		SpiritualData data = BleachAttachments.get(attacker);
		if (!data.isTransformed()) {
			return;
		}

		// A bow-bash is a direct hit — it is recorded above, and Yamamoto's air-swing check still
		// reads correctly — but it is not the character's offence, so it must not fire a Schrift's
		// melee hook. See SpiritWeapon.isMeleeDrawn and QUINCY_STATUS.md §7.5.
		if (!SpiritWeapon.isMeleeDrawn(attacker)) {
			return;
		}

		TransformAbility active = AbilityDispatcher.activeTransform(data);
		if (active != null) {
			active.onMeleeHit(attacker, victim, damageTaken);
		}
	}

	/**
	 * Tracks the game tick of the most recent landed direct melee hit for each player UUID.
	 * Used by {@link com.bleach.mod.mixin.LivingEntitySwingMixin} to distinguish an air swing
	 * (which triggers Bankai raven conical destruction) from a landed melee hit.
	 */
	public static final java.util.Map<java.util.UUID, Integer> LAST_DIRECT_HIT_TICK = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Returns {@code true} if the player landed a direct melee hit within the last 1 game tick.
	 */
	public static boolean didHitDirectlyRecently(ServerPlayer player) {
		Integer tick = LAST_DIRECT_HIT_TICK.get(player.getUUID());
		return tick != null && Math.abs(player.tickCount - tick) <= 1;
	}
}
