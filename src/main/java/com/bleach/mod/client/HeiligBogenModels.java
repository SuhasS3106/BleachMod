package com.bleach.mod.client;

import com.bleach.mod.ability.kits.BleachKits;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.item.BleachItems;
import com.bleach.mod.item.HeiligBogenItem;
import com.bleach.mod.network.SpiritualSyncPayload;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * The Heilig Bogen's {@code pulling} and {@code pull} model predicates.
 *
 * <p>Vanilla's own {@code pull} is hardcoded to twenty ticks. A tier that changes the draw rate —
 * Askin's Hasshein halves it — would otherwise leave the bow drawn halfway on screen at the moment
 * it is mechanically at full power, and a bow that lies about its own charge is worse than one that
 * charges slowly.
 *
 * <p>The rate is recovered from two things the client already has: the item knows which kit it
 * belongs to ({@link HeiligBogenItem#kitId()}), and the sync payload knows the release state. No new
 * packet is needed for either.
 *
 * <p><b>The multiplier itself is read from the client's own tuning.</b> Tuning is not synced, so on
 * a server whose config differs from the client's the animation would pace to the client's number
 * while the damage paced to the server's. That is a cosmetic disagreement in an uncommon setup, and
 * the alternative is syncing a tuning value purely to animate a bow.
 */
public final class HeiligBogenModels {
	private HeiligBogenModels() {
	}

	public static void register() {
		for (HeiligBogenItem bow : BleachItems.heiligBogen()) {
			// The same released predicate the blades use. Only Hoffnung's model reads it today —
			// Gerard is a sword outside Vollstaendig and a bow inside it, and the model has to say
			// so. Registering it for every bow costs nothing and keeps the two families symmetric.
			FabricModelPredicateProviderRegistry.register(bow,
					com.bleach.mod.BleachMod.id("released"),
					(stack, level, entity, seed) ->
							stack.getOrDefault(com.bleach.mod.item.BleachComponents.RELEASED, 0)
									/ (float) SpiritualData.STATE_BANKAI);

			FabricModelPredicateProviderRegistry.register(bow,
					ResourceLocation.withDefaultNamespace("pull"),
					(stack, level, entity, seed) -> {
						if (entity == null || entity.getUseItem() != stack) {
							return 0.0f;
						}
						int held = stack.getUseDuration(entity) - entity.getUseItemRemainingTicks();
						return (float) Math.min(1.0, held / fullDrawTicks(bow));
					});

			FabricModelPredicateProviderRegistry.register(bow,
					ResourceLocation.withDefaultNamespace("pulling"),
					(stack, level, entity, seed) ->
							entity != null && entity.isUsingItem() && entity.getUseItem() == stack
									? 1.0f : 0.0f);
		}
	}

	/** Ticks to a full draw for this bow, given what the client knows about its holder's state. */
	private static double fullDrawTicks(HeiligBogenItem bow) {
		double base = Math.max(1, BleachTuning.BOW_FULL_DRAW_TICKS);
		return base / Math.max(0.01, speedMult(bow));
	}

	/**
	 * Mirrors the server's {@code TransformAbility.bowDrawSpeedMult} for the cases the client can
	 * see. Only Askin's Vollständig moves it today; every other bow and every other state is 1.0,
	 * which is exactly vanilla's pace.
	 */
	private static double speedMult(HeiligBogenItem bow) {
		if (!BleachKits.DEATHDEALING.equals(bow.kitId())) {
			return 1.0;
		}

		SpiritualSyncPayload state = ClientSpiritualState.get();
		if (state == null || state.state() != SpiritualData.STATE_BANKAI) {
			return 1.0;
		}

		Minecraft client = Minecraft.getInstance();
		return client.player == null ? 1.0 : BleachTuning.DEATHDEALING_VOLL_DRAW_MULT;
	}
}
