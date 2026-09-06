package com.bleach.mod.mixin;

import com.bleach.mod.item.Zanpakuto;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures that neither the Zanpakutō nor the Asauchi can ever be converted into an in-world ItemEntity.
 */
@Mixin(Player.class)
public abstract class PlayerDropMixin {

	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"), cancellable = true)
	private void bleach$preventItemEntityDrop(ItemStack stack, boolean dropAround, boolean includeName,
			CallbackInfoReturnable<ItemEntity> cir) {
		if (Zanpakuto.isUndroppable(stack)) {
			cir.setReturnValue(null);
		}
	}
}
