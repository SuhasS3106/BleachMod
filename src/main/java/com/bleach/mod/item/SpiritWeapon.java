package com.bleach.mod.item;

import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.command.BleachGuide;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Everything a race's spirit weapon does outside of being an item: draw, sheathe, and the three
 * guarantees PRD §3.2 makes about it — it cannot be lost, it survives death, and it is restored if
 * it ever goes missing.
 *
 * <p>Race-agnostic on purpose: a {@link ZanpakutoItem} for Shinigami and a
 * {@link HeiligBogenItem} for Quincy both satisfy {@link #isSpiritWeapon}, so every guarantee below
 * — undroppable, kept on death, restored on respawn — covers both without the mixins that enforce
 * them knowing which race they are looking at.
 *
 * <p><b>The weapon is in exactly one place at a time.</b> Either {@link SpiritualData#zanpakuto}
 * holds it (sheathed) or the inventory does (drawn), never both and never neither. Every method
 * here preserves that, and it is what makes duplication impossible without a single item-counting
 * check anywhere.
 *
 * <p>Two mixins do the rest of the work, because they cover paths no event reaches:
 * {@code ServerPlayerDropMixin} refuses the drop key and {@code ContainerMenuMixin} freezes the
 * weapon against every container click. Death is handled here, in
 * {@link ServerLivingEntityEvents#ALLOW_DEATH}, which runs before the inventory is allowed to drop.
 */
public final class SpiritWeapon {
	private SpiritWeapon() {
	}

	/** True for either race's drawn weapon: a Shinigami's blade or a Quincy's bow. */
	public static boolean isSpiritWeapon(ItemStack stack) {
		return stack.getItem() instanceof ZanpakutoItem || stack.getItem() instanceof HeiligBogenItem;
	}

	/**
	 * Items the player may never throw away: the weapon, and the Asauchi that becomes one.
	 *
	 * <p>The Asauchi is on this list for the same reason the weapon is, one step earlier. It is a
	 * character choice in item form, and a character choice that can be dropped is one that can be
	 * traded, stolen, or lost down a hole on the walk back from a death.
	 */
	public static boolean isUndroppable(ItemStack stack) {
		return isSpiritWeapon(stack) || BleachItems.isSelector(stack);
	}

	/**
	 * PRD §3.2: "drawn" is holding the weapon, not merely owning it. A player who has scrolled to
	 * another hotbar slot is sheathed for every purpose that checks — which is the honest reading,
	 * and means the offense gate can never disagree with what the player can see in their hand.
	 */
	public static boolean isDrawn(Player player) {
		return isSpiritWeapon(player.getMainHandItem());
	}

	/**
	 * Whether the drawn weapon is one whose <em>swing</em> is the character's offence — true for a
	 * blade, false for a bow.
	 *
	 * <p>{@link #isDrawn} deliberately went race-agnostic in the Task 9 rename, which is right for
	 * the drop, death and respawn guarantees that gate on it. It is wrong for the melee ability
	 * hook: a Quincy bashing a mob with the Heilig Bogen satisfies {@code isDrawn} and would fire
	 * their Schrift's {@code onMeleeHit}, giving a bow-club the full power of a released state.
	 * {@code QUINCY_STATUS.md} §7.5 records this as the gap no foundation task covered.
	 *
	 * <p>The bow's own power lives in {@code onProjectileHit} instead, so nothing is lost by the
	 * exclusion — a Schrift that wants to react to a bash can still override {@code onMeleeHit} and
	 * will simply never be called while the drawn weapon is a bow.
	 */
	public static boolean isMeleeDrawn(Player player) {
		Item held = player.getMainHandItem().getItem();
		if (held instanceof ZanpakutoItem) {
			return true;
		}
		// Hoffnung is the one weapon that is a blade some of the time. Outside Vollstaendig its
		// swing IS Gerard's offence, so excluding it the way an ordinary bow is excluded would
		// silence the melee hook for a kit built entirely around melee.
		return held instanceof HoffnungItem hoffnung && !HeiligBogenItem.isDrawableBow(player);
	}

	/** A fresh weapon for the given kit, or empty if that kit has no registered weapon. */
	public static ItemStack stackFor(ResourceLocation kitId) {
		Item item = BleachItems.weaponFor(kitId);
		return item == null ? ItemStack.EMPTY : new ItemStack(item);
	}

	// --- Draw / sheathe ----------------------------------------------------------------

	/** Draw if sheathed, sheathe if drawn. The keybind's whole behaviour. */
	public static boolean toggle(ServerPlayer player, SpiritualData data) {
		return findInInventory(player) >= 0 ? sheathe(player, data) : draw(player, data);
	}

	/**
	 * Swap the blade into the selected hotbar slot, stowing whatever was there.
	 *
	 * <p>The displaced stack goes into the attachment rather than into a free slot: a player drawing
	 * with a full inventory must not have an item shuffled somewhere they did not expect, and must
	 * certainly not have one dropped at their feet mid-fight.
	 */
	public static boolean draw(ServerPlayer player, SpiritualData data) {
		if (data.zanpakuto.isEmpty()) {
			return false;
		}

		Inventory inventory = player.getInventory();
		int slot = inventory.selected;

		if (!data.stowedItem.isEmpty()) {
			inventory.placeItemBackInInventory(data.stowedItem);
			data.stowedItem = ItemStack.EMPTY;
		}

		data.stowedItem = inventory.getItem(slot).copy();
		inventory.setItem(slot, data.zanpakuto);
		data.zanpakuto = ItemStack.EMPTY;
		return true;
	}

	/**
	 * Pull the blade back out of wherever it ended up and put the stowed item back in its place.
	 *
	 * <p>A released state cannot outlive the blade that holds it (PRD §3.2), so this reverts through
	 * {@link SpiritualTicker#forceRevert} — the single path that also performs the Bankai claw-back.
	 * Sheathing to dodge the surplus deduction would be the same exploit by another route.
	 */
	public static boolean sheathe(ServerPlayer player, SpiritualData data) {
		int slot = findInInventory(player);
		if (slot < 0) {
			return false;
		}

		Inventory inventory = player.getInventory();
		data.zanpakuto = inventory.removeItemNoUpdate(slot);

		if (!data.stowedItem.isEmpty()) {
			// The freed slot first, which is the round trip in the overwhelmingly common case. If the
			// player has since moved the blade elsewhere and refilled its old home, fall back to the
			// normal "give the player this" path rather than overwriting anything.
			if (inventory.getItem(slot).isEmpty()) {
				inventory.setItem(slot, data.stowedItem);
			} else {
				inventory.placeItemBackInInventory(data.stowedItem);
			}
			data.stowedItem = ItemStack.EMPTY;
		}

		if (data.isTransformed()) {
			SpiritualTicker.forceRevert(player, data);
		}
		return true;
	}

	/** The inventory slot holding the blade, or −1. Covers hotbar, main, armour and offhand. */
	public static int findInInventory(Player player) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			if (isSpiritWeapon(inventory.getItem(i))) {
				return i;
			}
		}
		return -1;
	}

	// --- Appearance ---------------------------------------------------------------------

	/**
	 * Stamp the wielder's released state onto their blade so the model can read it ·
	 * {@link BleachComponents#RELEASED}.
	 *
	 * <p>Called from the two chokepoints in {@code SpiritualTicker} — {@code enter} and
	 * {@code forceRevert} — which every path into and out of a released state already funnels
	 * through, so the stamp cannot drift from {@code data.state} without the state machine itself
	 * being broken first.
	 *
	 * <p>It looks in both of the blade's two homes because the caller does not get to choose which
	 * one is holding it: reverting from a sheathe has already moved the sword into the attachment by
	 * the time the revert runs, while every other revert leaves it in the inventory.
	 */
	public static void markReleased(ServerPlayer player, SpiritualData data, byte state) {
		int slot = findInInventory(player);
		ItemStack blade = slot >= 0 ? player.getInventory().getItem(slot) : data.zanpakuto;
		if (blade.isEmpty()) {
			return;
		}

		if (state == SpiritualData.STATE_BASE) {
			blade.remove(BleachComponents.RELEASED);
		} else {
			blade.set(BleachComponents.RELEASED, (int) state);
		}
	}

	// --- Guarantees ---------------------------------------------------------------------

	/**
	 * Put the blade beyond reach of whatever is about to happen to the inventory. Idempotent, and
	 * safe on a player who never had one.
	 */
	public static void stow(ServerPlayer player, SpiritualData data) {
		if (findInInventory(player) >= 0) {
			sheathe(player, data);
		}
	}

	/**
	 * PRD §3.2: the blade is auto-restored if lost. Runs on join and after respawn — if the player
	 * has committed to a kit and the blade is in neither place, mint a new one. There is nothing to
	 * lose by being generous here: the item is unenchantable in practice, worth nothing to anyone
	 * else, and a player stuck without their sword has no game left.
	 */
	public static void ensureRestored(ServerPlayer player, SpiritualData data) {
		if (!data.hasCharacter() || !data.zanpakuto.isEmpty() || findInInventory(player) >= 0) {
			return;
		}

		ResourceLocation kitId = ResourceLocation.tryParse(data.characterId);
		if (kitId != null) {
			data.zanpakuto = stackFor(kitId);
		}
	}

	/**
	 * PRD §3.1: a player who has not chosen yet always has an Asauchi. Re-granting a lost one is
	 * deliberate — the choice is what is permanent, not the token that opens the menu.
	 */
	public static void ensureAsauchi(ServerPlayer player, SpiritualData data) {
		if (data.hasCharacter() || player.getInventory().contains(BleachItems::isSelector)) {
			return;
		}
		player.getInventory().placeItemBackInInventory(new ItemStack(BleachItems.ASAUCHI));
	}

	/**
	 * Put the blade back in the player's hand rather than leaving it sheathed in the attachment.
	 *
	 * <p>Used on respawn. A player who has just died wants to be holding their sword, not to have
	 * to remember a keybind before they can defend themselves — and a blade that is technically
	 * "safe" in an attachment the player cannot see is indistinguishable, from the outside, from
	 * one that was lost.
	 */
	public static void ensureDrawn(ServerPlayer player, SpiritualData data) {
		if (data.hasCharacter() && findInInventory(player) < 0 && !data.zanpakuto.isEmpty()) {
			draw(player, data);
		}
	}

	public static void register() {
		ServerPlayerEvents.JOIN.register(player -> {
			SpiritualData data = BleachAttachments.get(player);
			ensureRestored(player, data);
			restoreSelectors(player, data);

			// Nothing in vanilla hints that any of this exists. One line, once per session, pointing
			// at the manual — the alternative is every new player asking in chat what the blue bar is.
			player.sendSystemMessage(BleachGuide.welcome());
		});

		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			SpiritualData data = BleachAttachments.get(newPlayer);

			// Whatever the death did to the inventory, the player comes back with the same character,
			// the same blade, in hand, and — if they never chose one — the same Asauchi.
			data.stowedItem = ItemStack.EMPTY;
			ensureRestored(newPlayer, data);
			ensureDrawn(newPlayer, data);
			restoreSelectors(newPlayer, data);

			// resetOnRespawn clears data.state without going through forceRevert, so this is the one
			// place the stamp could survive a state that did not. Cheap, and it costs a mis-rendered
			// Bankai blade on a corpse's replacement if it is left out.
			markReleased(newPlayer, data, data.state);
		});

		// Runs at the top of the death sequence, before dropEquipment — the only hook early enough to
		// take the blade off the player without racing the drop. Returning true leaves the death
		// itself completely untouched.
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) {
				SpiritualData data = BleachAttachments.get(player);
				stow(player, data);
				stripSelectors(player, data);
			}
			return true;
		});
	}

	/**
	 * Take the Asauchi off a player who is about to die.
	 *
	 * <p>It is re-granted on respawn by {@link #ensureAsauchi}, so nothing is lost — but if it is
	 * left in the inventory it drops as a normal item, and an Asauchi lying on the ground is a
	 * second character choice that anyone can walk over and pick up. The token is meant to be
	 * unloseable, not transferable · PRD §3.1.
	 *
	 * <p>A <b>Reforged</b> Asauchi is stripped for the same reason and then <em>counted</em>, because
	 * only half that argument carries over to it. {@link #ensureAsauchi} re-mints the plain token for
	 * anyone without a kit, so it costs nothing to destroy; but a Reforged one is by definition held
	 * by a player who already has a kit, so nothing on the respawn path would ever hand it back. Left
	 * uncounted it is an operator-granted, recipe-less item deleted in silence by an unlucky fall ·
	 * {@link SpiritualData#stowedReforged}.
	 */
	private static void stripSelectors(ServerPlayer player, SpiritualData data) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!BleachItems.isSelector(stack)) {
				continue;
			}
			if (stack.getItem() instanceof ReforgedAsauchiItem) {
				data.stowedReforged += stack.getCount();
			}
			inventory.setItem(i, ItemStack.EMPTY);
			data.owedSelectors = true;
		}
	}

	/**
	 * Give back every token death took, <b>Reforged first</b> · {@link SpiritualData#stowedReforged}.
	 *
	 * <p>The order is the whole point and it used to be the other way round. {@link #ensureAsauchi}
	 * mints a plain token for anyone holding no selector at all, and until the debt above has been
	 * paid a player who died carrying only a Reforged one is holding nothing — so the backstop fired,
	 * minted a plain Asauchi that nobody had before the death, and the Reforged arrived a line later
	 * on top of it. One picker in, two out, once per death, which is a duplicated character choice
	 * rather than a cosmetic surplus.
	 *
	 * <p>Paying the debt first makes {@code ensureAsauchi}'s question — "is this player holding
	 * something that opens the picker?" — one it can answer truthfully, and a Reforged Asauchi is an
	 * honest yes: it opens the picker unconditionally · {@link ReforgedAsauchiItem}.
	 */
	public static void restoreSelectors(ServerPlayer player, SpiritualData data) {
		ensureReforged(player, data);
		ensureAsauchi(player, data);
		data.owedSelectors = false;
	}

	/**
	 * Pay back whatever death took · {@link SpiritualData#stowedReforged}.
	 *
	 * <p>Runs on respawn, and again on join as the backstop: the count is persisted, so a server that
	 * goes down between the death and the respawn still owes the token on the next login rather than
	 * carrying the debt forever.
	 */
	public static void ensureReforged(ServerPlayer player, SpiritualData data) {
		while (data.stowedReforged > 0) {
			player.getInventory().placeItemBackInInventory(new ItemStack(BleachItems.REFORGED_ASAUCHI));
			data.stowedReforged--;
		}
	}
}
