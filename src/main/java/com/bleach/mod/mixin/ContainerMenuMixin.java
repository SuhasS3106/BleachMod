package com.bleach.mod.mixin;

import com.bleach.mod.item.Zanpakuto;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The zanpakutō is frozen in place against every container click · PRD §3.2: it cannot be dropped
 * and cannot be placed in a chest.
 *
 * <p>One rule instead of a list of forbidden destinations: <b>the blade may never be picked up by
 * any menu, including the player's own inventory.</b> That reads stricter than the requirement, and
 * it is, deliberately — a rule phrased as "not into a chest" has to enumerate chests, barrels,
 * shulker boxes, hoppers-as-menus, ender chests, villager trades, grindstones and every container a
 * mod adds later, and gets it wrong once. A blade that simply never moves has no such list. Its
 * position is owned by {@link Zanpakuto#draw} and {@link Zanpakuto#sheathe}, which is the only
 * moving it needs.
 *
 * <p>Covers shift-click and hotbar swaps for free: both arrive here as a {@code clicked} call, the
 * first with the blade's own slot and the second with the hotbar index in {@code button}.
 *
 * <p>Not covered: the creative-mode set-slot packet, which bypasses menus entirely. A creative
 * operator can already {@code /give} themselves anything, so there is nothing left to protect.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
	@Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
	private void bleach$freezeZanpakuto(int slotId, int button, ClickType type, Player player,
			CallbackInfo ci) {
		AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;

		// Belt and braces: nothing should ever get the blade onto the cursor, but if something does,
		// the click that would put it down is the last chance to stop it.
		if (Zanpakuto.isUndroppable(self.getCarried())) {
			ci.cancel();
			return;
		}

		if (slotId >= 0 && slotId < self.slots.size()
				&& Zanpakuto.isUndroppable(self.slots.get(slotId).getItem())) {
			ci.cancel();
			return;
		}

		// A swap names its source in `button`, not in `slotId` — the hotbar index for a number key,
		// or SLOT_OFFHAND for the offhand key.
		if (type == ClickType.SWAP && isSwapSource(player, button)
				&& Zanpakuto.isUndroppable(player.getInventory().getItem(button))) {
			ci.cancel();
		}
	}

	private static boolean isSwapSource(Player player, int button) {
		return button >= 0
				&& (button < Inventory.getSelectionSize() || button == Inventory.SLOT_OFFHAND)
				&& button < player.getInventory().getContainerSize();
	}
}
