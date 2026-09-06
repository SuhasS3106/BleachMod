package com.bleach.mod.item;

import com.bleach.mod.attachment.SpiritualData;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * The second chance · PRD §3.1. A rare drop or expensive craft that re-opens the picker for a player
 * who has already committed.
 *
 * <p>No recipe and no loot table yet — those are content decisions, not Phase 4 plumbing. Until one
 * exists this is an operator item: {@code /give <player> bleach_mod:reforged_asauchi}.
 *
 * <p>It opens unconditionally, including for a player who has never chosen, because a first choice
 * is a strictly smaller thing than the re-choice it is already allowed to make.
 */
public class ReforgedAsauchiItem extends AsauchiItem {
	public ReforgedAsauchiItem() {
		super(new Item.Properties().stacksTo(1).rarity(Rarity.RARE).fireResistant());
	}

	@Override
	protected boolean canOpen(SpiritualData data) {
		return true;
	}

	@Override
	protected Component refusal() {
		return Component.empty();
	}
}
