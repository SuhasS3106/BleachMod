package com.bleach.mod.menu;

import java.util.List;

import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.item.BleachItems;
import com.bleach.mod.item.Zanpakuto;
import com.bleach.mod.race.Race;
import com.bleach.mod.race.Races;

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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

/**
 * The character picker · PRD §3.1, race seam per design §3.1.
 *
 * <p>Two screens, both this same server-side subclass of the vanilla one-row chest menu: screen one
 * lists the registered races, screen two lists the kits within the race just chosen. The client is
 * never told which screen it is looking at — it is only ever told to open {@code GENERIC_9x1} and
 * build a plain {@link ChestMenu} for it. Every behaviour that matters — what the slots contain, what
 * a click means, and the fact that nothing may ever be taken out — is server-side, so the client needs
 * to know nothing. A real graphical picker for zero registration and zero client code.
 *
 * <p>The slots are display only. {@link #clicked} never calls {@code super}, which means no vanilla
 * click handling runs at all: the swords on show are not items the player can reach, they are
 * buttons.
 */
public class ZanpakutoSelectMenu extends ChestMenu {
	private static final Component RACE_TITLE = Component.literal("Choose your path");
	private static final int ROW = 9;

	private final List<Race> raceChoices;
	private final List<Kit> kitChoices;

	private ZanpakutoSelectMenu(int containerId, Inventory playerInventory, SimpleContainer display,
			List<Race> raceChoices, List<Kit> kitChoices) {
		super(MenuType.GENERIC_9x1, containerId, playerInventory, display, 1);
		this.raceChoices = raceChoices;
		this.kitChoices = kitChoices;
	}

	/** Screen one: pick a race. */
	public static void open(ServerPlayer player) {
		List<Race> races = Races.all();
		if (races.isEmpty()) {
			return;
		}

		SimpleContainer display = new SimpleContainer(ROW);
		for (int i = 0; i < races.size() && i < ROW; i++) {
			display.setItem(i, raceStack(races.get(i)));
		}

		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, opener) ->
						new ZanpakutoSelectMenu(containerId, inventory, display, races, null),
				RACE_TITLE));
	}

	/** Screen two: pick a kit within the chosen race. */
	private static void openKits(ServerPlayer player, Race race) {
		List<Kit> choices = AbilityRegistry.kitsFor(race);
		if (choices.isEmpty()) {
			player.displayClientMessage(
					Component.literal("No characters are available for that race yet."), true);
			return;
		}

		SimpleContainer display = new SimpleContainer(ROW);
		for (int i = 0; i < choices.size() && i < ROW; i++) {
			display.setItem(i, displayStack(choices.get(i)));
		}

		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, opener) ->
						new ZanpakutoSelectMenu(containerId, inventory, display, null, choices),
				Component.literal("Choose your " + race.displayName().toLowerCase(java.util.Locale.ROOT))));
	}

	/** The race icon reuses an item that already exists rather than adding an asset. */
	private static ItemStack raceStack(Race race) {
		ItemStack stack = new ItemStack(
				race.id() == Races.QUINCY.id() ? Items.BOW : Items.IRON_SWORD);
		stack.set(DataComponents.CUSTOM_NAME,
				Component.literal(race.displayName()).withStyle(ChatFormatting.GOLD));
		return stack;
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
	 *
	 * <p>Exactly one of {@link #raceChoices} and {@link #kitChoices} is non-null on any given
	 * instance — this is screen one or screen two, never both — so the two branches below can never
	 * both fire for the same click.
	 */
	@Override
	public void clicked(int slotId, int button, ClickType type, Player player) {
		if (slotId < 0 || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		if (raceChoices != null && slotId < raceChoices.size()) {
			Race race = raceChoices.get(slotId);
			// Record the race before the second screen: choose() below needs it, and closing the
			// first menu must not lose it.
			BleachAttachments.get(serverPlayer).race = race.id();
			serverPlayer.closeContainer();
			openKits(serverPlayer, race);
			return;
		}

		if (kitChoices != null && slotId < kitChoices.size()) {
			choose(serverPlayer, kitChoices.get(slotId).id());
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

		Kit kit = AbilityRegistry.kit(kitId.toString());
		if (kit == null || kit.race().id() != data.race) {
			// Nothing should be able to send this, but the menu is a network surface and the kit
			// choice is permanent.
			return;
		}

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
				Component.literal("You are now " + kit.displayName() + "."),
				false);
		SpiritualTicker.sync(player, true);
	}
}
