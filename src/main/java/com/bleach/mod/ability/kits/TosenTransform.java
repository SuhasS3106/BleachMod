package com.bleach.mod.ability.kits;

import java.util.List;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Kaname Tōsen's released states · Suzumushi & Enma Kōrogi · {@code BALANCE.md} §J.7.
 *
 * <h2>Mobs and bosses</h2>
 *
 * <p>Both releases originally reached players only, which left Tōsen the one kit in the mod with
 * nothing to say to the world around him — a Bankai that swallowed every sense a player had and let
 * the skeleton next to them keep shooting. Both now sweep {@link LivingEntity}, and each release
 * translates its player-facing half into something a mob actually has:
 *
 * <ul>
 *   <li><b>Suzumushi</b> stages off an exposure clock instead of a spiritual pressure pool, so a mob
 *       breaks after the same number of ticks the drain needs to empty a Soul Level 1 player.</li>
 *   <li><b>Enma Kōrogi</b> cannot black out a screen a mob does not have, so it blinds and severs
 *       the target lock instead — the mob is inside the dome and simply cannot find anything.</li>
 * </ul>
 *
 * <p>Bosses are not filtered out, and the target severance in particular works on anything carrying
 * an AI goal — the Wither included. The status effects are the half that cannot be promised:
 * Blindness and Freeze go through {@code addEffect}, so any mob vanilla declares immune to effects
 * shrugs that half off and keeps only the severed aim. That is vanilla's call about that mob, not a
 * gap here, and severing the target is the part that matters against a boss anyway.
 */
public final class TosenTransform {
	private TosenTransform() {
	}

	public static final ResourceLocation SHIKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "tosen/shikai");
	public static final ResourceLocation BANKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "tosen/bankai");

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	// --- Shikai: Suzumushi (Chime Ring) -----------------------------------------------

	private static final class Shikai implements TransformAbility {
		@Override
		public ResourceLocation id() {
			return SHIKAI_ID;
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_SHIKAI;
		}


		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_SHIKAI, soulLevel);
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 2.0f, (float) BleachTuning.TOSEN_SHIKAI_SOUND_PITCH);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			ServerLevel level = player.serverLevel();
			double radius = BleachTuning.TOSEN_SHIKAI_RADIUS;
			double radiusSq = radius * radius;

			boolean playSound = (player.tickCount % BleachTuning.TOSEN_SHIKAI_SOUND_INTERVAL_TICKS == 0);

			// Everything living in the radius except the caster, following the same call Shinji's
			// Bankai aura makes: Suzumushi is a sound in the air, and air does not check whether the
			// thing standing in it has a spiritual pressure bar.
			List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class,
					player.getBoundingBox().inflate(radius),
					e -> e != player && e.isAlive() && !e.isSpectator() && e.distanceToSqr(player) <= radiusSq);

			for (LivingEntity victim : victims) {
				if (victim instanceof ServerPlayer victimPlayer) {
					tickPlayer(victimPlayer, playSound);
				} else {
					tickMob(victim, level, playSound);
				}
			}
		}

		private static void tickPlayer(ServerPlayer victim, boolean playSound) {
			if (playSound) {
				victim.playNotifySound(SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1.5f,
						(float) BleachTuning.TOSEN_SHIKAI_SOUND_PITCH);
			}

			// The chime is disorienting from the first second. Deliberately NOT blindness or
			// freeze: those stay strictly gated on an empty pool, and folding them forward here
			// would collapse the two stages of Suzumushi into one.
			if (BleachTuning.TOSEN_SHIKAI_NAUSEA_TICKS > 0) {
				victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION,
						BleachTuning.TOSEN_SHIKAI_NAUSEA_TICKS, 0, false, false, true));
			}

			SpiritualData victimData = BleachAttachments.get(victim);
			if (victimData != null) {
				if (victimData.sp > 0.0) {
					victimData.spend(BleachTuning.TOSEN_SHIKAI_SP_DRAIN_PER_TICK);
					SpiritualTicker.sync(victim, false);
				}

				// Blindness and freeze strictly when spiritual pressure drops to zero
				if (victimData.sp <= 0.0) {
					deprive(victim);
				}
			}
		}

		/**
		 * The same two stages for something with no spiritual pressure to erode.
		 *
		 * <p>A mob cannot be staged off an empty pool, so it is staged off time instead: exposure
		 * accumulates a tick per tick inside the radius and drains a tick per tick outside it, and
		 * the break lands at {@link BleachTuning#TOSEN_SHIKAI_MOB_RESIST_TICKS} — the same number of
		 * ticks the aura needs to empty a Soul Level 1 player. See {@link SuzumushiExposure} for why
		 * the counter lives on the entity and why the drain is applied lazily.
		 *
		 * <p>Nausea is skipped rather than forgotten. It is a camera effect with no server-side
		 * meaning, so on a mob it would be an icon and nothing more; the first stage for a mob is
		 * the exposure clock itself running.
		 */
		private static void tickMob(LivingEntity victim, ServerLevel level, boolean playSound) {
			if (playSound) {
				level.playSound(null, victim.getX(), victim.getY(), victim.getZ(),
						SoundEvents.BELL_RESONATE, SoundSource.HOSTILE, 1.0f,
						(float) BleachTuning.TOSEN_SHIKAI_SOUND_PITCH);
			}

			int cap = Math.max(1, BleachTuning.TOSEN_SHIKAI_MOB_RESIST_TICKS);
			SuzumushiExposure exposure = victim.getAttachedOrCreate(BleachAttachments.SUZUMUSHI_EXPOSURE);

			int now = victim.tickCount;
			int elapsed = now - exposure.lastSeenTick;
			if (exposure.lastSeenTick != Integer.MIN_VALUE && elapsed > 1) {
				// Time spent outside the radius since the counter was last touched, charged back
				// now. `elapsed - 1` because one of those ticks is this one, which is exposure.
				exposure.exposureTicks = Math.max(0, exposure.exposureTicks - (elapsed - 1));
			}
			exposure.lastSeenTick = now;
			exposure.exposureTicks = Math.min(cap, exposure.exposureTicks + 1);

			if (exposure.exposureTicks >= cap) {
				deprive(victim);

				// Blind and reeling, it has nothing left to hold a bead on. Both halves, for the
				// same reason Shinji's inversion needs both: setTarget is what an attack goal reads,
				// and clearing the revenge memory is what stops a hurt-by goal handing the target
				// straight back on the next tick.
				if (victim instanceof Mob mob) {
					mob.setTarget(null);
					mob.setLastHurtByMob(null);
				}
			}
		}

		/** Suzumushi's second stage, identical for a player and a mob. */
		private static void deprive(LivingEntity victim) {
			int ticks = BleachTuning.TOSEN_SHIKAI_DEPRIVE_TICKS;
			victim.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, ticks, 0, false, false, true));
			if (BleachEffects.FREEZE != null) {
				victim.addEffect(new MobEffectInstance(BleachEffects.FREEZE, ticks, 0, false, false, true));
			}
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
		}
	}

	// --- Bankai: Suzumushi Tsuishiki: Enma Kōrogi --------------------------------------

	private static final class Bankai implements TransformAbility {
		@Override
		public ResourceLocation id() {
			return BANKAI_ID;
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_BANKAI;
		}


		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_BANKAI, soulLevel);
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			EnmaKorogiManager.startDome(player, BleachTuning.TOSEN_BANKAI_RADIUS);
			player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 1.5f, 0.5f);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			EnmaKorogiManager.tickDome(player);

			// The caster trades his own mobility for the dome. The client refuses to start a sprint
			// in LocalPlayerSprintMixin, which is where the decision is actually made; this is the
			// server-authority backstop for a client that is not running the mixin.
			if (player.isSprinting()) {
				player.setSprinting(false);
			}
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			EnmaKorogiManager.endDome(player);
		}
	}
}
