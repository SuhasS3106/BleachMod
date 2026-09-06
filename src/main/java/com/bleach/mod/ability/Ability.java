package com.bleach.mod.ability;

import com.bleach.mod.attachment.SpiritualData;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * One activatable technique. A Java registry, not datapack JSON — see PRD §3.3 for why: none of
 * these are expressible as data, and a schema would degenerate into a pointer at hardcoded Java.
 *
 * <p><b>Server side only.</b> Every implementation runs exclusively inside
 * {@link AbilityDispatcher}, which has already validated liveness, sword state, kit membership,
 * cooldown, cost and gate before {@link #onActivate} is reached. An implementation never re-checks
 * those and never trusts anything from the client.
 *
 * <p>Two deliberate deviations from the PRD §3.3 signature block:
 * <ul>
 *   <li>{@code spCost} takes the player's {@link SpiritualData}. Every cost in {@code BALANCE.md}
 *       is a <em>fraction of max SP</em>, and max SP is a function of Soul Level — a no-arg
 *       {@code spCost()} could only return the fraction, pushing the multiply out to every call
 *       site and inviting one of them to get it wrong.</li>
 *   <li>{@link #canActivate} and {@link #cooldownTicks} have defaults. Most abilities have no
 *       precondition beyond the dispatcher's own chain and no cooldown of their own; making them
 *       abstract would buy ten identical stub bodies across the five kits.</li>
 * </ul>
 */
public interface Ability {
	/** Registry key. Also the cooldown key, so it must be stable across a reload. */
	ResourceLocation id();

	/**
	 * Ability-specific precondition, checked last in the dispatcher's chain. Return false to reject
	 * silently — <b>nothing is spent and no cooldown starts</b>.
	 */
	default boolean canActivate(ServerPlayer player, SpiritualData data) {
		return true;
	}

	/**
	 * Do the thing. The cost has already been deducted and the cooldown already started, so an
	 * implementation that fails partway must refund explicitly.
	 */
	void onActivate(ServerPlayer player, SpiritualData data);

	/** Cooldown started on a successful activation, after any kit multiplier. Zero means none. */
	default int cooldownTicks(SpiritualData data) {
		return 0;
	}

	/** SP deducted on a successful activation, in absolute SP. */
	default double spCost(SpiritualData data) {
		return 0.0;
	}

	/**
	 * PRD §3.2: only <em>offense</em> requires the drawn blade. Flash Step and Spiritual Flex are
	 * raw spiritual pressure rather than techniques, and override this to false.
	 */
	default boolean requiresDrawnSword() {
		return true;
	}
}
