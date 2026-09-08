package com.bleach.mod.ability.common;

import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Blut — the reishi a Quincy circulates through their own body · design §5.4. Defensive (Vene) or
 * offensive (Arterie), never both.
 *
 * <p>The four methods below are pure arithmetic and unit tested. The billing and the stance change
 * live further down and need a server.
 *
 * <p>The stance is a synced byte on {@link SpiritualData#blut}, not an entry on the state machine:
 * it stacks on top of a release rather than replacing one, and its drain adds to the release drain
 * exactly as Flex's does.
 */
public final class Blut {
	private Blut() {
	}

	public static final byte OFF = 0;
	public static final byte VENE = 1;
	public static final byte ARTERIE = 2;

	/** OFF → Vene → Arterie → OFF. An unrecognised value returns to OFF rather than sticking. */
	public static byte cycle(byte current) {
		return switch (current) {
			case OFF -> VENE;
			case VENE -> ARTERIE;
			default -> OFF;
		};
	}

	/** Multiplier on damage taken. Floored at zero — a config file can hold any number. */
	public static double damageTakenMultiplier(byte stance) {
		if (stance != VENE) {
			return 1.0;
		}
		return Math.max(0.0, 1.0 - BleachTuning.BLUT_VENE_REDUCTION);
	}

	/** Multiplier on bleach damage dealt. */
	public static double damageDealtMultiplier(byte stance) {
		if (stance != ARTERIE) {
			return 1.0;
		}
		return Math.max(0.0, 1.0 + BleachTuning.BLUT_ARTERIE_BONUS);
	}

	/** SP charged per tick while a stance is up. Additive with the release drain. */
	public static double tickCost(int soulLevel) {
		double perSecond = Math.max(0.0,
				BleachTuning.BLUT_DRAIN_BASE - BleachTuning.BLUT_DRAIN_PER_LEVEL * (soulLevel - 1));
		return perSecond / BleachTuning.TICKS_PER_SECOND;
	}

	// --- Server half -------------------------------------------------------------------

	private static final ResourceLocation VENE_SPEED_ID =
			ResourceLocation.fromNamespaceAndPath("bleach_mod", "blut_vene_speed");

	/**
	 * Charge every player holding a stance, drop anyone who cannot pay, and reconcile the Vene speed
	 * penalty with the stance.
	 *
	 * <p>The reconcile runs for <em>every</em> player, not only those with a stance up, because the
	 * modifier must also come off along paths this loop never sees a stance change on — death,
	 * logout, a dimension hop that resets the attachment. Converging every tick is cheaper than
	 * hunting down each of those paths, and it can never leave a Quincy permanently slow.
	 */
	public static void tickAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			SpiritualData data = BleachAttachments.get(player);

			if (data.blut != OFF) {
				double cost = tickCost(data.soulLevel);
				if (data.sp < cost) {
					data.blut = OFF;
					player.displayClientMessage(Component.literal("Blut fades."), true);
				} else {
					data.spend(cost);
				}
			}

			reconcileSpeed(player, data.blut);
		}
	}

	/**
	 * Vene trades mobility for hardness, so it is the one stance with a cost beyond SP. Idempotent
	 * in both directions.
	 */
	private static void reconcileSpeed(ServerPlayer player, byte stance) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}

		boolean wanted = stance == VENE;
		boolean present = speed.getModifier(VENE_SPEED_ID) != null;
		if (wanted == present) {
			return;
		}

		if (wanted) {
			speed.addPermanentModifier(new AttributeModifier(VENE_SPEED_ID,
					BleachTuning.BLUT_VENE_SPEED_PENALTY, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else {
			speed.removeModifier(VENE_SPEED_ID);
		}
	}
}
