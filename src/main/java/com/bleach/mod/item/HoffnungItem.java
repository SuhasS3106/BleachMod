package com.bleach.mod.item;

import com.bleach.mod.ability.AbilityDispatcher;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.component.Unbreakable;

/**
 * Hoffnung — Gerard Valkyrie's weapon, and the only one in the mod that changes what it <em>is</em>.
 *
 * <p>Canon: <i>"Gerard's favored weapon manifests in the form of a black, double-edged sword"</i>,
 * and the Heilig Bogen is a separate thing he summons by pulling his hands apart. Every other Quincy
 * here carries a bow and nothing else; Gerard is a swordsman who has a bow, which is why he needed
 * his own item class rather than a flag on the shared one.
 *
 * <p><b>A sword until Vollstaendig, a bow inside it.</b> Melee damage is baked into an item at
 * construction and cannot vary by state, so the sword numbers are the ones it is built with —
 * {@link #isBowMode} then decides whether the bow behaviours in {@link HeiligBogenItem} are live at
 * all. In the Schrift, right-click does nothing: it is a sword and there is no draw to start.
 *
 * <p>The model follows the same {@code bleach_mod:released} predicate Ichigo's Bankai blade uses —
 * blade shape at 0 and 0.5, bow at 1.0.
 */
public final class HoffnungItem extends HeiligBogenItem {

	public HoffnungItem(ResourceLocation kitId) {
		super(kitId, new Properties()
				.stacksTo(1)
				.rarity(Rarity.EPIC)
				.fireResistant()
				.component(DataComponents.UNBREAKABLE, new Unbreakable(true))
				// A swordsman's numbers, not the deliberately feeble BOW_MELEE_DAMAGE the other two
				// Quincies carry. Gerard's power is in the swing; theirs is in what they fire.
				.attributes(SwordItem.createAttributes(Tiers.IRON,
						BleachTuning.ZANPAKUTO_ATTACK_DAMAGE,
						(float) BleachTuning.ZANPAKUTO_ATTACK_SPEED)));
	}

	/**
	 * A bow only in Vollstaendig.
	 *
	 * <p>Asks the dispatcher which transformation is live rather than reading a stored state byte,
	 * so this cannot answer yes for a Gerard who is in the Schrift or in no tier at all.
	 */
	@Override
	protected boolean isBowMode(LivingEntity holder) {
		if (!(holder instanceof net.minecraft.world.entity.player.Player player)) {
			return false;
		}
		SpiritualData data = BleachAttachments.get(player);
		if (data.state != SpiritualData.STATE_BANKAI) {
			return false;
		}
		TransformAbility active = AbilityDispatcher.activeTransform(data);
		return active != null;
	}

	// getUseAnimation is deliberately not overridden. It has no holder parameter in 1.21.1 so it
	// cannot be state-aware, but it is only consulted while an item is actually being used — and in
	// sword mode use() refuses, so no draw ever starts and the pose is never reached.
}
