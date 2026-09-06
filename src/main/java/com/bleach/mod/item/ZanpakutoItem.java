package com.bleach.mod.item;

import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;

/**
 * A character's zanpakutō · PRD §3.1–3.2. One instance per kit, minted by {@link BleachItems}.
 *
 * <p>It carries its kit's id rather than the {@link com.bleach.mod.ability.Kit} itself: items are
 * registered before any kit exists, and keeping the reference one-way means a kit can be rewritten
 * in Phase 6 without its sword noticing.
 *
 * <p>Mechanically it is an iron sword that never breaks. The blade is not where a character's power
 * lives — Soul Level scaling (PRD §2.4) and the released states are — so a zanpakutō that outclassed
 * vanilla gear on its own stats would make the progression it exists to gate irrelevant.
 *
 * <p>It is also never allowed to leave its owner: {@link Zanpakuto} pulls it back into the
 * attachment before death, and the two mixins in {@code com.bleach.mod.mixin} refuse the drop key
 * and freeze it in place against every container click.
 */
public class ZanpakutoItem extends SwordItem {
	private final ResourceLocation kitId;

	public ZanpakutoItem(ResourceLocation kitId) {
		super(Tiers.IRON, properties());
		this.kitId = kitId;
	}

	private static Item.Properties properties() {
		return new Item.Properties()
				.stacksTo(1)
				.rarity(Rarity.EPIC)
				.fireResistant()
				// No durability(): an item with no max damage never breaks. A zanpakutō that could be
				// worn out would be a zanpakutō the player has to be protected from losing, and the
				// whole point of the attachment is that it cannot be lost.
				.attributes(SwordItem.createAttributes(Tiers.IRON,
						BleachTuning.ZANPAKUTO_ATTACK_DAMAGE,
						(float) BleachTuning.ZANPAKUTO_ATTACK_SPEED));
	}

	/** Which kit this blade belongs to. Matches {@link com.bleach.mod.ability.Kit#id()}. */
	public ResourceLocation kitId() {
		return kitId;
	}

	/** Blocks the bundle and shulker-box-as-item routes out of the player's hands. */
	@Override
	public boolean canFitInsideContainerItems() {
		return false;
	}
}
