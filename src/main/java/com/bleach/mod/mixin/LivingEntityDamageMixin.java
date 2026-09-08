package com.bleach.mod.mixin;

import com.bleach.mod.ModToggle;
import com.bleach.mod.ability.AbilityDispatcher;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.effect.ReiatsuEffect;
import com.bleach.mod.progression.DamageScaling;
import com.bleach.mod.progression.KillAttribution;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two places Phase 5 has to sit inside the damage pipeline.
 *
 * <p>They are deliberately at different depths. <b>Attribution</b> goes at the head of
 * {@code hurt}, because the taint rule has to see every hit that reaches the entity including the
 * ones that go on to deal nothing. <b>Scaling</b> goes at the return of
 * {@code getDamageAfterMagicAbsorb}, which is the last thing vanilla does to the number — after
 * armour, after Resistance, after Protection — because the multipliers in PRD §2.4 are specified
 * against the final figure.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

	/**
	 * Record who is hitting this entity · PRD §2.1.
	 *
	 * <p>Not cancellable and it changes nothing — it only watches. {@code isInvulnerableTo} is
	 * checked first because damage the entity is outright immune to is not damage: without it a
	 * blaze standing in its own lava taints itself on the first tick and no blaze in the Nether is
	 * ever worth SPX again.
	 */
	@Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
	private void bleach$recordDamager(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide() || !ModToggle.isEnabled() || self.isInvulnerableTo(source)) {
			return;
		}

		if (self instanceof ServerPlayer player && source.is(DamageTypeTags.IS_FIRE)) {
			SpiritualData data = BleachAttachments.get(player);
			if (data.isTransformed()) {
				TransformAbility active = AbilityDispatcher.activeTransform(data);
				if (active != null && active.isFireImmune()) {
					player.clearFire();
					cir.setReturnValue(false);
					return;
				}
			}
		}

		// The silence, for the abilities that are not entities · BALANCE.md §H.4. A guardian's beam,
		// a warden's shout and anything else a mob deals directly without spawning something have no
		// projectile to refuse in ConjuredEntityMixin, so they are refused here instead — by the rule
		// that a silenced attacker can only reach you with its hands.
		if (source.getEntity() instanceof LivingEntity attacker && ReiatsuEffect.isSilenced(attacker)
				&& !bleach$isMelee(source, attacker)) {
			cir.setReturnValue(false);
			return;
		}

		// Flash Step cancels fall damage until the player lands · a Shunpo is not a fall. Checked
		// here rather than by zeroing fallDistance on arrival: the distance keeps accumulating for
		// every block travelled after the step, so only the damage itself can be refused.
		if (self instanceof ServerPlayer stepper && source.is(DamageTypeTags.IS_FALL)
				&& BleachAttachments.get(stepper).flashStepFallGrace) {
			stepper.resetFallDistance();
			cir.setReturnValue(false);
			return;
		}

		// Aizen's Kyōka Suigetsu: an illusion exists for exactly one victim and may harm nobody else.
		// Without this the illusions are invisible to everyone but their victim yet still swing at
		// whoever is nearest — which is how Aizen himself was being chewed on by mobs he could not see.
		java.util.UUID illusionOwner = com.bleach.mod.ability.kits.AizenHypnosisManager.illusionOwner(
				source.getEntity() != null ? source.getEntity() : source.getDirectEntity());
		if (illusionOwner != null && !illusionOwner.equals(self.getUUID())) {
			cir.setReturnValue(false);
			return;
		}

		// Aizen's Kyōka Suigetsu: If the attacker is currently under complete hypnosis, record all damage dealt
		// to any target so it can be reflected back when Shikai reverts.
		if (source.getEntity() instanceof ServerPlayer attackerPlayer && com.bleach.mod.ability.kits.AizenHypnosisManager.isHypnotized(attackerPlayer)) {
			com.bleach.mod.ability.kits.AizenHypnosisManager.recordDamage(attackerPlayer, amount);
		}

		// Aizen's Kyōka Suigetsu: Illusion mobs cannot die. If incoming damage would be fatal, clamp health at 1.0 HP.
		if (com.bleach.mod.ability.kits.AizenHypnosisManager.isIllusionMob(self)) {
			if (self.getHealth() - amount <= 0.0f) {
				self.setHealth(1.0f);
				cir.setReturnValue(false);
				return;
			}
		}

		// Shunsui's Katen Kyōkotsu: Shikai Irooni rule DONT_ATTACK break detection
		if (self instanceof ServerPlayer shunsuiVictim && source.getEntity() instanceof LivingEntity attacker) {
			com.bleach.mod.ability.kits.KatenShikaiManager.onMeleeHitCheck(shunsuiVictim, attacker);
		}

		// Shunsui's Karamatsu Shinjū: Act 1 shared-damage link
		if (com.bleach.mod.ability.kits.KaromatsuManager.isLinked(self)) {
			com.bleach.mod.ability.kits.KaromatsuManager.onSharedDamage(self, amount);
		}

		KillAttribution.record(self, source);

		// PRD §1.2: the regen pause is "on any spend or damage". Every spend path already calls it;
		// this is the damage half, and this mixin is the first thing in the build that can see one.
		if (self instanceof ServerPlayer player) {
			BleachAttachments.get(player).pauseRegen();
		}
	}

	/**
	 * Whether this hit is the attacker's own hands · {@code BALANCE.md} §H.4.
	 *
	 * <p>Two conditions, and both are needed. The <b>direct entity</b> has to be the attacker itself,
	 * which is what separates a swing from anything it launched — a projectile's source names the
	 * shooter as the attacker and the arrow as the direct entity. And the <b>type</b> has to be one
	 * of the three melee ones, which is what catches the abilities that touch you without a
	 * projectile: a guardian's beam and a warden's shout both arrive with the mob as its own direct
	 * entity and are no more its hands than an arrow is.
	 *
	 * <p>Allow-list rather than deny-list on purpose. A type this does not know is treated as an
	 * ability and refused, so a mob added by a future version or another mod is silenced by default
	 * rather than silently exempt — the failure mode is a mob that hits for less, not one that walks
	 * through the whole mechanic.
	 */
	private static boolean bleach$isMelee(DamageSource source, LivingEntity attacker) {
		return source.getDirectEntity() == attacker
				&& (source.is(DamageTypes.MOB_ATTACK)
						|| source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
						|| source.is(DamageTypes.PLAYER_ATTACK)
						|| source.is(DamageTypes.STING));
	}

	/**
	 * Apply the Soul Level multipliers · PRD §2.4–2.5.
	 *
	 * <p>{@code getDamageAfterMagicAbsorb} is protected and vanilla-internal, but it is the only
	 * point where the number is final and still ours to change. Injecting into {@code actuallyHurt}
	 * instead would mean re-implementing armour, and injecting into {@code hurt} would mean
	 * multiplying a figure that armour is about to divide.
	 */
	@Inject(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"), cancellable = true)
	private void bleach$scaleDamage(DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level().isClientSide() || !ModToggle.isEnabled()) {
			return;
		}

		cir.setReturnValue(DamageScaling.apply(self, source, cir.getReturnValue()));
	}
}
