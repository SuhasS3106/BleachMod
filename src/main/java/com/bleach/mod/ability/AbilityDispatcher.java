package com.bleach.mod.ability;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ModToggle;
import com.bleach.mod.ability.common.AuraSense;
import com.bleach.mod.ability.common.Hover;
import com.bleach.mod.ability.common.SpiritualFlex;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.item.SpiritWeapon;
import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The single server-side entry point for anything a keypress can ask for.
 *
 * <p><b>Nothing is trusted from the client and the client never predicts.</b> The packet carries an
 * action index and nothing else — no target, no position, no cost, no result. Everything else is
 * resolved here from server state, in a fixed order:
 *
 * <ol>
 *   <li>mod enabled</li>
 *   <li>player alive and not spectating</li>
 *   <li>the action index is one we defined</li>
 *   <li>the drawn sword, if the ability requires it (PRD §3.2)</li>
 *   <li>the player's kit actually has that ability</li>
 *   <li>cooldown expired</li>
 *   <li>SP ≥ cost</li>
 *   <li>entry gate met, for transformations</li>
 *   <li>the ability's own {@code canActivate}</li>
 * </ol>
 *
 * <p>A rejection at any step costs the player nothing: no SP is deducted and no cooldown starts
 * until every check above has passed.
 */
public final class AbilityDispatcher {
	private AbilityDispatcher() {
	}

	/** PRD §3.2: drawn means the blade is in the main hand, nothing weaker. */
	private static boolean hasDrawnSword(ServerPlayer player) {
		return SpiritWeapon.isDrawn(player);
	}

	public static void handle(ServerPlayer player, int index) {
		if (!ModToggle.isEnabled()) {
			return;
		}

		AbilityAction action = AbilityAction.byIndex(index);
		if (action == null) {
			// Malformed or from a newer client. Dropped, not thrown — a bad index must never be able
			// to take down the network thread.
			BleachMod.LOGGER.debug("Rejected ability index {} from {}", index, player.getGameProfile().getName());
			return;
		}

		if (!player.isAlive() || player.isSpectator()) {
			return;
		}

		SpiritualData data = BleachAttachments.get(player);

		switch (action) {
			case FLASH_STEP -> tryActivate(player, data, AbilityRegistry.FLASH_STEP, action);
			case SHIKAI -> toggleTransform(player, data, SpiritualData.STATE_SHIKAI);
			case BANKAI -> toggleTransform(player, data, SpiritualData.STATE_BANKAI);
			case FLEX_START -> setFlexing(player, data, true);
			case FLEX_STOP -> setFlexing(player, data, false);
			case DRAW_SHEATHE -> drawOrSheathe(player, data, action);
			case SENSE_START -> setSensing(player, data, true);
			case SENSE_STOP -> setSensing(player, data, false);
			case HOVER_START -> setHovering(player, data, true);
			case HOVER_STOP -> setHovering(player, data, false);
		}
	}

	// --- Plain abilities --------------------------------------------------------------

	private static void tryActivate(ServerPlayer player, SpiritualData data,
			ResourceLocation id, AbilityAction action) {
		Ability ability = AbilityRegistry.get(id);
		if (ability == null) {
			unimplemented(player, action);
			return;
		}

		if (ability.requiresDrawnSword() && !hasDrawnSword(player)) {
			return;
		}

		if (!AbilityCooldowns.isReady(player, id)) {
			return;
		}

		double cost = ability.spCost(data);
		if (data.sp < cost) {
			return;
		}

		if (!ability.canActivate(player, data)) {
			return;
		}

		data.spend(cost);
		AbilityCooldowns.start(player, id, ability.cooldownTicks(data));
		ability.onActivate(player, data);
		SpiritualTicker.sync(player, true);
	}

	// --- Transformations --------------------------------------------------------------

	/**
	 * Enter, or leave if already in this state.
	 *
	 * <p>Leaving is unconditional — no cost, no gate, no cooldown. A player must always be able to
	 * drop out of a state that is draining them, or the state becomes a trap rather than a choice.
	 *
	 * <p>Switching directly between Shikai and Bankai checks the target's gate <em>before</em>
	 * reverting the current state, so a failed Bankai attempt while in Shikai leaves the player in
	 * Shikai rather than silently dumping them to base.
	 */
	private static void toggleTransform(ServerPlayer player, SpiritualData data, byte target) {
		Kit kit = AbilityRegistry.kitFor(data);
		if (kit == null) {
			// Still carrying an unused Asauchi.
			actionBar(player, "You have no zanpakutō.");
			return;
		}

		TransformAbility ability = kit.transformFor(target);
		if (ability == null) {
			return;
		}

		if (data.state == target) {
			if (!ability.canRevert(player, data)) {
				return;
			}
			SpiritualTicker.forceRevert(player, data);
			SpiritualTicker.sync(player, true);
			return;
		}

		if (!ability.requiresDrawnSword() || hasDrawnSword(player)) {
			if (!AbilityCooldowns.isReady(player, ability.id())) {
				return;
			}

			double gate = data.maxSp() * ability.entryGatePercent(data.soulLevel);
			double cost = ability.spCost(data);
			if (data.sp < Math.max(gate, cost)) {
				actionBar(player, "Not enough spiritual pressure.");
				return;
			}

			if (!ability.canActivate(player, data)) {
				return;
			}

			if (data.isTransformed()) {
				SpiritualTicker.forceRevert(player, data);
			}

			if (cost > 0.0) {
				data.spend(cost);
			}
			AbilityCooldowns.start(player, ability.id(), ability.cooldownTicks(data));
			SpiritualTicker.enter(player, data, target);
			SpiritualTicker.sync(player, true);
		}
	}

	// --- Channels and inventory --------------------------------------------------------

	/**
	 * Flex is a hold, not a toggle, so the server stores only the edge the client reported ·
	 * {@link SpiritualFlex}. The flag is all that happens here; every tick of behaviour is owned by
	 * the server ticker, which is also the only thing that can clear the flag on its own.
	 *
	 * <p>Losing a stop is harmless — the channel drops itself the moment the pool cannot cover a
	 * tick — but losing a <em>start</em> would be silent, so a start on an empty pool is refused
	 * outright rather than set and immediately dropped. The flag then still matches when the
	 * client's release arrives.
	 */
	private static void setFlexing(ServerPlayer player, SpiritualData data, boolean flexing) {
		if (data.flexing == flexing) {
			return;
		}

		if (flexing && data.sp < SpiritualFlex.tickCost(data)) {
			actionBar(player, "Not enough spiritual pressure.");
			return;
		}

		data.flexing = flexing;
	}

	/**
	 * Aura Sense is a hold, exactly as Flex is, but it costs no SP — so the only thing that can
	 * refuse a start is {@link AuraSense#canSense}, which is also what ends the channel if the
	 * answer changes while the key is still down.
	 *
	 * <p>A refusal is silent. Every reason to refuse is a reason the player cannot be told anything
	 * either — Enma Kōrogi has already taken the screen the action bar would be drawn on, and the
	 * dead and the spectating have nothing to act on. The lid simply never falls, which is the
	 * feedback: the client keeps its eyes open until a reading says the server agreed.
	 *
	 * <p>That first reading is pushed here rather than left to the next sweep slot, because the round
	 * trip <em>is</em> the delay before the lid falls and making it two ticks longer would read as a
	 * key that did not fire.
	 */
	private static void setSensing(ServerPlayer player, SpiritualData data, boolean sensing) {
		if (data.sensing == sensing) {
			return;
		}

		if (sensing) {
			if (!AuraSense.canSense(player)) {
				return;
			}
			data.sensing = true;
			AuraSense.push(player);
			return;
		}

		// Routed through stop() rather than clearing the flag, because the client's eyelids are lifted
		// by the inactive payload that only stop() sends.
		AuraSense.stop(player, data);
	}

	/**
	 * Hover is a hold, like the two above it, and the only one of the three whose start can be
	 * refused for a reason that is not the pool · {@link Hover#canStart}. Airborne is the whole
	 * point: a hover that could begin on the ground would be a jump nobody pressed.
	 *
	 * <p>A refusal is silent, and it is not final. What the client reports is the key, not a demand —
	 * the flag is recorded either way and {@code Hover.tickAll} starts the channel on the first tick
	 * the player is actually airborne. That is what makes holding the key <em>before</em> stepping
	 * off a ledge work, which is how anyone who has fallen once will use it.
	 */
	private static void setHovering(ServerPlayer player, SpiritualData data, boolean hovering) {
		if (!hovering) {
			Hover.stop(player, data);
			return;
		}

		if (data.hoverIntent) {
			return;
		}
		data.hoverIntent = true;

		if (!Hover.canStart(player) || data.sp < Hover.tickCost(data)) {
			return;
		}

		Hover.start(player, data);

		// Pushed rather than left to the pool pass at the end of this tick, because the payload is
		// the client's permission to hold itself up: the round trip is already the delay before the
		// fall stops, and making it one tick longer is a tick of falling the player paid not to do.
		SpiritualTicker.sync(player, true);
	}

	/**
	 * The draw/sheathe key · PRD §3.2. {@link SpiritWeapon#sheathe} routes through
	 * {@code SpiritualTicker.forceRevert}, since a released state cannot outlive the blade that
	 * holds it — and a sheathe that skipped the revert would be the Bankai claw-back exploit again
	 * by another route.
	 */
	private static void drawOrSheathe(ServerPlayer player, SpiritualData data, AbilityAction action) {
		if (!data.hasCharacter()) {
			actionBar(player, "You have no zanpakutō.");
			return;
		}

		// Auto-restore before the swap rather than only on join: a player who lost the blade to a
		// bug should get it back on the next keypress, not on the next login.
		SpiritWeapon.ensureRestored(player, data);

		if (SpiritWeapon.toggle(player, data)) {
			SpiritualTicker.sync(player, true);
		}
	}

	// --- Scaffolding ------------------------------------------------------------------

	/**
	 * Phase 2 acceptance, and the reason the whole pipeline is testable before a single ability
	 * exists. Each branch stops logging the moment its phase registers a real implementation, so
	 * this disappears on its own rather than needing to be hunted down.
	 */
	private static void unimplemented(ServerPlayer player, AbilityAction action) {
		BleachMod.LOGGER.info("{} requested {} (index {}) — not implemented yet",
				player.getGameProfile().getName(), action, action.ordinal());
	}

	private static void actionBar(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), true);
	}

	/** The transformation currently running for a player, or null in the base state. */
	@Nullable
	public static TransformAbility activeTransform(SpiritualData data) {
		Kit kit = AbilityRegistry.kitFor(data);
		return kit == null ? null : kit.transformFor(data.state);
	}
}
