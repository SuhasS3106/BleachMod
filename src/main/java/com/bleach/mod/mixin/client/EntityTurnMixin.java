package com.bleach.mod.mixin.client;

import com.bleach.mod.effect.SakanadeEffect;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The flipped camera · Shinji Hirako's Bankai.
 *
 * <p>{@code KeyboardInputMixin} already reverses WASD for every tier of Sakanade. This reverses
 * <em>mouse look</em> as well, and only at the Bankai tier — Sakashima Yokoshima Happō Fusagari
 * inverts all eight directions, not just the ones your feet use, and the difference between the two
 * released states has to be something the victim can feel.
 *
 * <p>{@code Entity#turn} is the single funnel every look change passes through on the client, which
 * is why the hook sits here rather than in {@code MouseHandler}: it catches raw mouse movement,
 * controller input and the sensitivity-scaled path alike, and it cannot desync because rotation is
 * client-owned and reported to the server afterwards either way.
 */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {

	@ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private double bleach$flipYaw(double yRot) {
		return bleach$flipped() ? -yRot : yRot;
	}

	@ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private double bleach$flipPitch(double xRot) {
		return bleach$flipped() ? -xRot : xRot;
	}

	private boolean bleach$flipped() {
		return (Object) this instanceof LocalPlayer player && SakanadeEffect.flipsCamera(player);
	}
}
