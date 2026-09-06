package com.bleach.mod.mixin;

import com.bleach.mod.effect.ReiatsuEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;

/**
 * The silence, at the one door everything a mob conjures has to come through ·
 * {@code BALANCE.md} §H.4.
 *
 * <p>An arrow, a splash potion, a fireball, a wind charge, a wither skull, a shulker bullet, a spit,
 * a set of evoker fangs and a summoned vex are all, structurally, the same event: an entity being
 * added to the world that remembers who made it ({@link TraceableEntity#getOwner()}). If that owner
 * is standing in a field it cannot act out of, the entity is simply never added — the mob plays its
 * animation, spends its cooldown, and nothing arrives.
 *
 * <h2>Why this and not a mixin per mob</h2>
 *
 * <p>The alternative is {@code Skeleton#performRangedAttack}, {@code Witch#performRangedAttack},
 * {@code Blaze$BlazeAttackGoal#tick}, {@code Ghast$GhastShootFireballGoal#tick},
 * {@code Breeze#shoot}, {@code Evoker$EvokerAttackSpellGoal}, and one more for every mob added in
 * every future version and every other mod — each of which would have to be found before it could be
 * silenced, and each of which is a place the silence could be forgotten. This is one rule that was
 * already true of all of them, including the ones nobody here has heard of.
 *
 * <p>{@code addFreshEntity} is overridden by {@link ServerLevel} and its override does not call
 * {@code super}, so the target is the override rather than {@code Level}'s own. The passenger variant
 * is the second door: summon spells use it, so an evoker with no fangs would otherwise still get its
 * vexes.
 */
@Mixin(ServerLevel.class)
public abstract class ConjuredEntityMixin {

	@Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
	private void bleach_mod$silenceConjured(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (bleach_mod$silencedOwner(entity)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "tryAddFreshEntityWithPassengers", at = @At("HEAD"), cancellable = true)
	private void bleach_mod$silenceConjuredWithPassengers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (bleach_mod$silencedOwner(entity)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * Whether this entity was made by something that cannot act right now.
	 *
	 * <p>An unowned entity is left alone, which is most of them: a dropped item, a spawned mob, a
	 * boat, an experience orb. So is anything owned by an entity that is not alive-and-pressured —
	 * the check is on the owner's own Reiatsu, never on where the projectile happens to be flying.
	 */
	private static boolean bleach_mod$silencedOwner(Entity entity) {
		if (!(entity instanceof TraceableEntity traceable)) {
			return false;
		}
		return traceable.getOwner() instanceof LivingEntity owner && ReiatsuEffect.isSilenced(owner);
	}
}
