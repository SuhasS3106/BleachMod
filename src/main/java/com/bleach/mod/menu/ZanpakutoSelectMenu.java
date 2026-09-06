package com.bleach.mod.menu;

import java.util.ArrayList;
import java.util.List;

import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.item.BleachItems;
import com.bleach.mod.item.Zanpakuto;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * The zanpakutō picker · PRD §3.1.
 *
 * <p><b>No {@code MenuType} is registered and no screen class exists.</b> This is a server-side
 * subclass of the vanilla one-row chest menu: the server runs this class, the client is told to open
 * {@code GENERIC_9x1} and builds a plain {@link ChestMenu} for it. Every behaviour that matters —
 * what the slots contain, what a click means, and the fact that nothing may ever be taken out — is
 * server-side, so the client needs to know nothing. A real graphical picker for zero registration
 * and zero client code.
 *
 * <p>The slots are display only. {@link #clicked} never calls {@code super}, which means no vanilla
 * click handling runs at all: the swords on show are not items the player can reach, they are
 * buttons.
 */
public class ZanpakutoSelectMenu extends ChestMenu {
	private static final Component TITLE = Component.literal("Choose your zanpakutō");
	private static final int ROW = 9;

	private final List<Kit> choices;

	private ZanpakutoSelectMenu(int containerId, Inventory playerInventory, SimpleContainer display,
			List<Kit> choices) {
		super(MenuType.GENERIC_9x1, containerId, playerInventory, display, 1);
		this.choices = choices;
	}

	/** Open the picker for a player. Silently does nothing if no kits are registered. */
	public static void open(ServerPlayer player) {
		List<Kit> choices = new ArrayList<>(AbilityRegistry.kits());
		if (choices.isEmpty()) {
			return;
		}

		SimpleContainer display = new SimpleContainer(ROW);
		for (int i = 0; i < choices.size() && i < ROW; i++) {
			display.setItem(i, displayStack(choices.get(i)));
		}

		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, opener) ->
						new ZanpakutoSelectMenu(containerId, inventory, display, choices),
				TITLE));
	}

	private static ItemStack displayStack(Kit kit) {
		ItemStack stack = Zanpakuto.stackFor(kit.id());
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		stack.set(DataComponents.CUSTOM_NAME,
				Component.literal(kit.displayName()).withStyle(ChatFormatting.GOLD));
		return stack;
	}

	/**
	 * The one behaviour this class exists for. A click inside the display row is a choice; every
	 * other click — the player's own inventory, shift-clicks, hotbar swaps, drops outside the window
	 * — is swallowed.
	 *
	 * <p>Deliberately not a call to {@code super} in any branch. The alternative, letting vanilla
	 * handle inventory clicks and only intercepting the top row, would leave the picker as a live
	 * container the player could shuffle items through while the menu is open, and would put the
	 * blade one shift-click away from a slot it must never occupy.
	 */
	@Override
	public void clicked(int slotId, int button, ClickType type, Player player) {
		if (slotId >= 0 && slotId < choices.size() && player instanceof ServerPlayer serverPlayer) {
			choose(serverPlayer, choices.get(slotId).id());
			serverPlayer.closeContainer();
		}
	}

	/** Nothing leaves this menu, so there is nothing to quick-move. */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	/**
	 * Commit the choice: consume the Asauchi, record the kit, and hand the blade over drawn.
	 *
	 * <p>Drawn rather than sheathed on purpose — a player who has just picked a sword and been given
	 * nothing visible has no way to tell whether it worked, and the draw key is not something they
	 * have been taught yet.
	 */
	private static void choose(ServerPlayer player, ResourceLocation kitId) {
		SpiritualData data = BleachAttachments.get(player);

		ItemStack blade = Zanpakuto.stackFor(kitId);
		if (blade.isEmpty()) {
			return;
		}

		// Consume whichever token opened the menu. Found by scanning rather than remembered from the
		// open call: the player is free to move it between slots while the menu is up.
		int token = -1;
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (BleachItems.isSelector(inventory.getItem(i))) {
				token = i;
				break;
			}
		}
		if (token < 0) {
			return;
		}
		inventory.removeItem(token, 1);

		// Re-choosing with a Reforged Asauchi: the old blade goes away with the old character.
		Zanpakuto.stow(player, data);
		data.characterId = kitId.toString();
		data.zanpakuto = blade;
		Zanpakuto.draw(player, data);

		player.displayClientMessage(
				Component.literal("You are now " + AbilityRegistry.kit(kitId.toString()).displayName() + "."),
				false);
		SpiritualTicker.sync(player, true);
	}
}
