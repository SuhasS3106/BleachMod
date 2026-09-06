package com.bleach.mod.mixin;

import com.bleach.mod.ability.kits.AizenHypnosisManager;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures complete private visibility for Aizen's Kyōka Suigetsu illusion mobs.
 *
 * <p>Vanilla calls {@link Entity#broadcastToPlayer} before registering/sending tracking packets
 * for any entity. If the entity is tagged as an illusion for another player, this cancels
 * broadcasting, completely preventing any other player from seeing or tracking the entity.
 */
@Mixin(Entity.class)
public abstract class EntityTrackerMixin {

	@Inject(method = "broadcastToPlayer", at = @At("HEAD"), cancellable = true)
	private void bleach$filterIllusionVisibility(ServerPlayer player, CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (AizenHypnosisManager.isEntityHiddenFrom(self, player)) {
			cir.setReturnValue(false);
		}
	}
}
