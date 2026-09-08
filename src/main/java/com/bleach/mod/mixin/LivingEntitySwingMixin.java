package com.bleach.mod.mixin;

import com.bleach.mod.ability.MeleeHooks;
import com.bleach.mod.ability.kits.BleachKits;
import com.bleach.mod.ability.kits.GinTransform;
import com.bleach.mod.ability.kits.YamamotoTransform;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.item.ZanpakutoItem;
import com.bleach.mod.util.BlockQueue;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts player swings on the server for release-specific swing mechanics:
 * <ul>
 *   <li>Yamamoto's Bankai raven conical destruction on miss/air swing</li>
 *   <li>Gin's Shikai long-range piercing thrust, on every swing</li>
 * </ul>
 *
 * <p>The raven is deferred by one tick and gated on {@link MeleeHooks#didHitDirectlyRecently},
 * because this event fires at the <em>start</em> of a swing, before the attack has been resolved
 * against anything — an air swing and a swing about to connect are indistinguishable here. The hit
 * is recorded during that tick, and reading it on the next one is what separates them. Gin's
 * thrust needs none of that: it fires unconditionally and is priced accordingly.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {

	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"))
	private void bleach$onSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
		if (hand != InteractionHand.MAIN_HAND) {
			return;
		}

		if ((Object) this instanceof ServerPlayer player) {
			if (player.getMainHandItem().getItem() instanceof ZanpakutoItem blade) {
				SpiritualData data = BleachAttachments.get(player);

				if (BleachKits.YAMAMOTO.equals(blade.kitId()) && data.state == SpiritualData.STATE_BANKAI) {
					BlockQueue.submitDelayed(1, () -> {
						if (player.isAlive() && !MeleeHooks.didHitDirectlyRecently(player)) {
							YamamotoTransform.onBankaiSwingMiss(player);
						}
					});
				} else if (BleachKits.GIN.equals(blade.kitId()) && data.state == SpiritualData.STATE_SHIKAI) {
					// Deliberately NOT gated on a miss the way the raven above is. Shinsō fires on
					// every swing and bills for every swing, including one that also connects as
					// ordinary melee — the release is meant to be expensive enough that you think
					// before swinging at all, and a free version for anything within arm's reach is
					// exactly the escape hatch that would undo that.
					GinTransform.onShikaiSwing(player);
				} else if (BleachKits.SHUNSUI.equals(blade.kitId()) && data.state == SpiritualData.STATE_SHIKAI) {
					BlockQueue.submitDelayed(1, () -> {
						if (player.isAlive() && !MeleeHooks.didHitDirectlyRecently(player)) {
							com.bleach.mod.ability.kits.KatenShikaiManager.castRules(player);
						}
					});
				}
			}
		}
	}
}
