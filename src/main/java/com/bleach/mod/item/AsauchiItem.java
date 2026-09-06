package com.bleach.mod.item;

import com.bleach.mod.ModToggle;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.menu.ZanpakutoSelectMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;

/**
 * The blank blade every player starts with · PRD §3.1. Right-click opens the picker; choosing
 * consumes it and hands over a real zanpakutō.
 *
 * <p>The choice is permanent, so this refuses to open once a character has been committed to —
 * a second Asauchi is a duplicate, not a second chance. {@link ReforgedAsauchiItem} is the second
 * chance, and the only difference between the two classes is that one line.
 */
public class AsauchiItem extends Item {
	public AsauchiItem() {
		this(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
	}

	protected AsauchiItem(Item.Properties properties) {
		super(properties);
	}

	/** Whether this token may be used given the player's current state. */
	protected boolean canOpen(SpiritualData data) {
		return !data.hasCharacter();
	}

	protected Component refusal() {
		return Component.literal("You have already claimed a zanpakutō.");
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);

		// Client side does nothing at all: the menu is opened by the server, which then tells the
		// client to display GENERIC_9x1. Predicting it here would open an empty screen.
		if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
		}

		if (!ModToggle.isEnabled()) {
			return InteractionResultHolder.pass(held);
		}

		SpiritualData data = BleachAttachments.get(serverPlayer);
		if (!canOpen(data)) {
			serverPlayer.displayClientMessage(refusal(), true);
			return InteractionResultHolder.fail(held);
		}

		ZanpakutoSelectMenu.open(serverPlayer);
		return InteractionResultHolder.consume(held);
	}
}
