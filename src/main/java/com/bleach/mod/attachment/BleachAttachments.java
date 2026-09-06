package com.bleach.mod.attachment;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.common.Hover;
import com.bleach.mod.ability.kits.IchigoTransform;
import com.bleach.mod.ability.kits.MobInversion;
import com.bleach.mod.ability.kits.SuzumushiExposure;
import com.bleach.mod.progression.KillCredit;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.world.entity.player.Player;

public final class BleachAttachments {
	private BleachAttachments() {
	}

	/**
	 * Persistent <b>and</b> {@code copyOnDeath}, with the respawn reset applied afterwards in
	 * {@link #register()}.
	 *
	 * <p>It used to be the other way round — no {@code copyOnDeath}, everything hand-copied in
	 * {@code COPY_FROM} — on the reasoning that copying the whole object would leave the reset
	 * racing Fabric's own copy. The race was real; it just ran the other way. Fabric's attachment
	 * copy and {@code ServerPlayerEvents.COPY_FROM} both hang off {@code ServerPlayer#restoreFrom},
	 * and the attachment half lands <em>after</em> the event, so a hand-copy into a slot that
	 * {@code copyOnDeath} was not going to fill was overwritten a moment later by the new player's
	 * freshly initialised, empty data. Every death wiped the chosen kit, which took the zanpakutō
	 * with it and handed the player a new Asauchi as though they had never picked a character.
	 *
	 * <p>So: let Fabric do the copying, which is the half that wins, and do the reset in
	 * {@code AFTER_RESPAWN}, which is the only hook guaranteed to run after both.
	 */
	public static final AttachmentType<SpiritualData> SPIRITUAL_DATA = AttachmentRegistry.create(
			BleachMod.id("spiritual_data"),
			builder -> builder
					.persistent(SpiritualData.CODEC)
					.copyOnDeath()
					.initializer(SpiritualData::new));

	/**
	 * Kill attribution · PRD §2.1. Deliberately <b>not</b> persistent and <b>not</b> synced: it is
	 * per-entity scratch state that lives for one fight, the client has no use for it, and writing
	 * it to disk would mean an entity coming back from a restart still owing somebody a payout.
	 *
	 * <p>Lives here rather than in {@code progression} so that both attachment types are registered
	 * from one class-init and neither can be missed — an unregistered {@link AttachmentType} fails
	 * at first use, deep inside a damage handler.
	 */
	public static final AttachmentType<KillCredit> KILL_CREDIT = AttachmentRegistry.create(
			BleachMod.id("kill_credit"),
			builder -> builder.initializer(KillCredit::new));

	/**
	 * Shinji's per-mob stagger roll · PRD §6.5. Non-persistent and unsynced, for the same reasons as
	 * {@link #KILL_CREDIT}: it is one entity's scratch state, it is worth a few ticks, and the client
	 * never reads it.
	 *
	 * <p>An attachment rather than a static {@code Map<UUID, …>} specifically so that a mob which
	 * unloads while afflicted takes its roll with it — see {@link MobInversion}.
	 */
	public static final AttachmentType<MobInversion> MOB_INVERSION = AttachmentRegistry.create(
			BleachMod.id("mob_inversion"),
			builder -> builder.initializer(MobInversion::new));

	/**
	 * A mob's accumulated exposure to Tōsen's Suzumushi · PRD §6.7. Non-persistent and unsynced,
	 * for the same reasons as {@link #MOB_INVERSION}, and an attachment for the same reason too:
	 * the counter should leave with the mob that owns it — see {@link SuzumushiExposure}.
	 */
	public static final AttachmentType<SuzumushiExposure> SUZUMUSHI_EXPOSURE = AttachmentRegistry.create(
			BleachMod.id("suzumushi_exposure"),
			builder -> builder.initializer(SuzumushiExposure::new));

	public static SpiritualData get(Player player) {
		return player.getAttachedOrCreate(SPIRITUAL_DATA);
	}

	public static void register() {
		ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
			SpiritualData from = get(oldPlayer);

			// The revert has to happen on the old player, while its attribute modifiers are still
			// reachable · PRD §1.6. Both paths need it: a dimension change forces a revert too.
			if (from.isTransformed()) {
				SpiritualTicker.forceRevert(oldPlayer, from);
			}

			// And the same for a hover, on the old player for the same reason: the NoGravity flag it
			// set lives on the entity, not in the attachment, so it has to come off the entity that
			// has it. clearStale then covers the other direction — a flag the copy carried across.
			Hover.stop(oldPlayer, from);
			Hover.clearStale(newPlayer, get(newPlayer));

			// Belt to the copyOnDeath braces. Harmless if Fabric's copy lands after this and
			// overwrites it with the same values; load-bearing if it ever lands before.
			get(newPlayer).copyProgressionFrom(from);
		});

		// After both the event above and Fabric's own attachment copy, which is what makes this the
		// only safe place to clear the pool. PRD §1.2: respawn refills SP to 100% and clears
		// exertion; progression, the kit and the blade all survive.
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (alive) {
				return;
			}

			SpiritualData data = get(newPlayer);
			data.copyProgressionFrom(get(oldPlayer));
			data.resetOnRespawn();

			// A crash or an unclean revert can strand a modifier on the profile the respawn copied
			// from. Nothing else will ever take it off, so strip unconditionally.
			IchigoTransform.stripModifiers(newPlayer);
		});
	}
}
