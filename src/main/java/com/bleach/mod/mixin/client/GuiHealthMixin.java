package com.bleach.mod.mixin.client;

import com.bleach.mod.client.ClientSpiritualState;
import com.bleach.mod.client.SpiritualHud;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Make room for the SP bar directly above the experience bar.
 *
 * <p>The bar used to be parked in vanilla's armour/air row, two rows up and separated from the XP
 * bar by the whole health line — which is a long way from the thing it is meant to read as a
 * sibling of. Putting it where it belongs means the vanilla status stack has to move up by one row
 * to make the space, and {@code renderPlayerHealth} is the single method that draws all of it:
 * hearts, food, armour, air and the mount health bar.
 *
 * <p>So this shifts that one method's output rather than reimplementing any of it. The whole row
 * block moves as a unit, which is what keeps hearts and food aligned with each other and with the
 * armour row above them — a per-element offset would have to re-derive vanilla's own conditional
 * row shuffle and would drift the moment it changed.
 *
 * <p>Skipped entirely when the mod is switched off, so "off" still means the HUD is
 * indistinguishable from not having the mod installed.
 */
@Mixin(Gui.class)
public abstract class GuiHealthMixin {

	@Inject(method = "renderPlayerHealth", at = @At("HEAD"))
	private void bleach$liftRows(GuiGraphics graphics, CallbackInfo ci) {
		graphics.pose().pushPose();
		if (ClientSpiritualState.isEnabled() && ClientSpiritualState.get() != null) {
			graphics.pose().translate(0.0f, -SpiritualHud.rowLift(), 0.0f);
		}
	}

	@Inject(method = "renderPlayerHealth", at = @At("RETURN"))
	private void bleach$dropRows(GuiGraphics graphics, CallbackInfo ci) {
		graphics.pose().popPose();
	}

	@Inject(method = "renderOverlayMessage", at = @At("HEAD"))
	private void bleach$liftOverlayMessage(GuiGraphics graphics, net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
		graphics.pose().pushPose();
		if (ClientSpiritualState.isEnabled() && ClientSpiritualState.get() != null) {
			graphics.pose().translate(0.0f, -SpiritualHud.rowLift(), 0.0f);
		}
	}

	@Inject(method = "renderOverlayMessage", at = @At("RETURN"))
	private void bleach$dropOverlayMessage(GuiGraphics graphics, net.minecraft.client.DeltaTracker deltaTracker, CallbackInfo ci) {
		graphics.pose().popPose();
	}
}
