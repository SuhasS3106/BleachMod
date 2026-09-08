package com.bleach.mod.item;

import com.bleach.mod.ability.AbilityDispatcher;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.entity.ReishiArrow;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.Level;

/**
 * A Quincy's Heilig Bogen — the structural twin of {@link ZanpakutoItem}. Same guarantees: single
 * stack, unbreakable, undroppable, kept on death, restored on respawn. It is the race's drawn
 * weapon, not a release.
 *
 * <p>It uses vanilla bow semantics, which buys the first-person draw-back animation for free —
 * the only animation this phase of the mod gets.
 *
 * <p>Like {@link ZanpakutoItem}, it is deliberately unremarkable in melee: {@code BOW_MELEE_DAMAGE}
 * and {@code BOW_MELEE_SPEED} (§P.2) are far under a real weapon's numbers, because the power lives
 * in what it fires, not in what it swings like.
 */
public class HeiligBogenItem extends Item {
	private final ResourceLocation kitId;

	public HeiligBogenItem(ResourceLocation kitId) {
		super(new Properties()
				.stacksTo(1)
				.rarity(Rarity.EPIC)
				.fireResistant()
				.component(DataComponents.UNBREAKABLE, new Unbreakable(true))
				.attributes(SwordItem.createAttributes(Tiers.IRON,
						BleachTuning.BOW_MELEE_DAMAGE, (float) BleachTuning.BOW_MELEE_SPEED)));
		this.kitId = kitId;
	}

	/** Which kit this bow belongs to. Matches {@link com.bleach.mod.ability.Kit#id()}. */
	public ResourceLocation kitId() {
		return kitId;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		return UseAnim.BOW;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return 72000;   // vanilla's "hold indefinitely"; the draw curve caps the useful part
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		player.startUsingItem(hand);
		return InteractionResultHolder.consume(player.getItemInHand(hand));
	}

	/**
	 * Fires on release, whatever the draw. A draw under {@link BleachTuning#BOW_MIN_DRAW} is refused
	 * outright — no SP spent, no arrow, no sound — and the player is left in no different a state
	 * than any vanilla bow that ran out of arrows: {@link LivingEntity#stopUsingItem()} clears the
	 * use-item bookkeeping unconditionally after calling this method, whether it fires or returns
	 * here early, so a refused shot can never strand the player mid-draw.
	 */
	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
			return;
		}

		SpiritualData data = BleachAttachments.get(player);
		TransformAbility active = data.isTransformed()
				? AbilityDispatcher.activeTransform(data)
				: null;

		// The draw is resolved before the minimum-draw gate, because a transformation that changes
		// the draw rate has to change what counts as too short a pull as well — otherwise a faster
		// bow would refuse shots it has in fact finished charging.
		float draw = drawFraction(getUseDuration(stack, entity) - timeLeft,
				active == null ? 1.0 : active.bowDrawSpeedMult());
		if (draw < BleachTuning.BOW_MIN_DRAW) {
			return;
		}

		// Offer the shot to the active transformation. A Schrift may spend it on something other
		// than an arrow — Gift Ring does — in which case it owns the cost too, and this method must
		// not also charge for an arrow it never fired.
		if (active != null && active.onBowRelease(player, draw)) {
			SpiritualTicker.sync(player, true);
			return;
		}

		if (data.sp < BleachTuning.BOW_SHOT_SP_COST) {
			player.displayClientMessage(Component.literal("Not enough spiritual pressure."), true);
			return;
		}
		data.spend(BleachTuning.BOW_SHOT_SP_COST);

		ReishiArrow arrow = new ReishiArrow(player, level, kitId);
		arrow.setBaseDamage(BleachTuning.BOW_ARROW_DAMAGE * draw);
		arrow.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f,
				(float) (BleachTuning.BOW_ARROW_VELOCITY * draw), 1.0f);
		level.addFreshEntity(arrow);

		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0f, 1.0f / (draw + 0.6f));

		SpiritualTicker.sync(player, true);
	}

	/**
	 * Draw progress, 0..1, reaching 1 at {@link BleachTuning#BOW_FULL_DRAW_TICKS} divided by
	 * {@code speed}. A speed above 1 charges faster; the multiplier is clamped positive so a config
	 * of zero or below cannot make a full draw unreachable.
	 */
	private static float drawFraction(int ticksHeld, double speed) {
		double ticks = Math.max(1, BleachTuning.BOW_FULL_DRAW_TICKS) / Math.max(0.01, speed);
		return (float) Math.min(1.0, ticksHeld / ticks);
	}

	/** Blocks the bundle and shulker-box-as-item routes out of the player's hands. */
	@Override
	public boolean canFitInsideContainerItems() {
		return false;
	}
}
