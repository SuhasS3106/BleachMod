package com.bleach.mod.mixin.client;

import com.bleach.mod.client.ClientHoverState;
import com.bleach.mod.tuning.BleachTuning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * The hover's physics · {@code Hover} · {@code BALANCE.md} §O.
 *
 * <p>Replaces the local player's movement outright while the channel is up: no gravity, no jumping,
 * no ground friction, and <b>WASD along the look vector instead of the ground plane</b>. Forward is
 * wherever the crosshair points, so looking up and holding forward climbs and looking down and
 * holding forward dives — which is why there is no ascend or descend key to bind. Strafing stays
 * level on purpose; rolling the player's sideways motion with their pitch makes a hover impossible
 * to hold a line in.
 *
 * <h2>Why the client and not the server</h2>
 *
 * <p>Player movement in Minecraft is client-authoritative: the client simulates and the server
 * accepts what it is told, within limits. A server-side hover would have to write a velocity every
 * tick <em>against</em> a client that is simultaneously simulating a fall, and the visible result is
 * the rubber-banding that every server-side flight implementation has. So the movement is computed
 * exactly once, here, and the server's role is the permission and the bill · {@code Hover}.
 *
 * <p>{@link ClientHoverState#isHovering()} is that permission, and the reason this cannot be turned
 * on unilaterally: it wants the key <em>and</em> a server packet that says the channel is live, and
 * the server stops saying so the moment the pool runs dry.
 *
 * <h2>Targeting {@code LivingEntity} rather than {@code LocalPlayer}</h2>
 *
 * <p>{@code LocalPlayer} does not override {@code travel}, and a mixin cannot inject into a method a
 * target class only inherits. So the target is the class that declares it, and the
 * {@code instanceof} below is what narrows it back to the one entity this may ever apply to — the
 * player at this keyboard. Everyone else's hover arrives as position packets, exactly like the rest
 * of their movement.
 */
@Mixin(LivingEntity.class)
public abstract class LocalPlayerHoverMixin {

	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void bleach$hover(Vec3 travelVector, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!(self instanceof LocalPlayer player) || player != Minecraft.getInstance().player) {
			return;
		}

		if (!ClientHoverState.isHovering()) {
			return;
		}

		// Fluids, vehicles and elytra all have movement of their own that the player chose; a hover
		// that overrode them would be taking the boat's steering away rather than replacing a fall.
		if (player.isPassenger() || player.isInWater() || player.isInLava() || player.isFallFlying()
				|| player.getAbilities().flying) {
			return;
		}

		Vec3 look = player.getLookAngle();
		float forward = player.input.forwardImpulse;
		float strafe = player.input.leftImpulse;

		Vec3 wanted = Vec3.ZERO;
		if (forward != 0.0f) {
			wanted = wanted.add(look.scale(forward));
		}
		if (strafe != 0.0f) {
			// Derived from yaw rather than from the look vector: both horizontal components of the
			// look vector go to zero when the player looks straight up, and a strafe direction that
			// vanishes exactly when someone is climbing is worse than no strafe at all.
			float yaw = player.getYRot() * Mth.DEG_TO_RAD;
			wanted = wanted.add(new Vec3(Mth.cos(yaw), 0.0, Mth.sin(yaw)).scale(strafe));
		}

		// Normalised, so holding two keys is a direction rather than 1.41× the speed.
		Vec3 target = wanted.lengthSqr() > 1.0E-6
				? wanted.normalize().scale(BleachTuning.HOVER_SPEED)
				: Vec3.ZERO;

		// An exponential approach rather than an assignment: the player has weight, a direction
		// change takes a few ticks to bite, and letting go settles to a genuine stop instead of
		// drifting on forever the way a frictionless zero-gravity body otherwise would.
		Vec3 delta = player.getDeltaMovement().lerp(target, BleachTuning.HOVER_RESPONSE);
		if (delta.lengthSqr() < BleachTuning.HOVER_REST_EPSILON * BleachTuning.HOVER_REST_EPSILON) {
			delta = Vec3.ZERO;
		}

		player.setDeltaMovement(delta);
		player.move(MoverType.SELF, delta);

		// The server clears this too, every tick of the channel. Here as well because the client is
		// what reports the fall it is about to be billed for, and a fall distance that survived the
		// hover would be charged in full on the tick the player lands.
		player.resetFallDistance();

		ci.cancel();
	}
}
