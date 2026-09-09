package com.bleach.mod.ability.kits;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

/**
 * Shinji Hirako's released states · PRD §6.5 · {@code BALANCE.md} §J.5.
 *
 * <ul>
 *   <li><b>Shikai — Sakanade:</b> targeted inversion. Melee hits apply {@link BleachEffects#SAKANADE}
 *       to the target for {@link BleachTuning#SHINJI_SHIKAI_ONHIT_DURATION} ticks (5 s),
 *       rate-limited by {@link BleachTuning#SHINJI_SHIKAI_ONHIT_COOLDOWN} ticks.</li>
 *   <li><b>Bankai — Sakashima Yokoshima Happō Fusagari:</b> proximity inversion aura. Continuously
 *       applies/refreshes {@link BleachEffects#SAKANADE} for {@link BleachTuning#SHINJI_EFFECT_DURATION_TICKS}
 *       ticks to all non-allied entities within {@code SHINJI_RADIUS_BASE + SHINJI_RADIUS_PER_LEVEL * SL}.
 *       Leaving the field lets the effect lapse naturally in ~3 seconds.</li>
 * </ul>
 */
public final class ShinjiTransform {
	private ShinjiTransform() {
	}

	public static final ResourceLocation SHIKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "shinji/shikai");
	public static final ResourceLocation BANKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "shinji/bankai");

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	// --- Shikai: Sakanade -------------------------------------------------------------

	private static final class Shikai implements TransformAbility {
		/** Attacker UUID -> tick of last successful on-hit application. */
		private final Map<UUID, Integer> lastHitTicks = new ConcurrentHashMap<>();

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
			lastHitTicks.remove(player.getUUID());
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			lastHitTicks.remove(player.getUUID());
		}

		@Override
		public void onMeleeHit(ServerPlayer player, LivingEntity target, float damage) {
			if (damage <= 0.0f || !target.isAlive()) {
				return;
			}

			int lastHit = lastHitTicks.getOrDefault(player.getUUID(), -99999);
			if (player.tickCount - lastHit < BleachTuning.SHINJI_SHIKAI_ONHIT_COOLDOWN) {
				return;
			}
			lastHitTicks.put(player.getUUID(), player.tickCount);

			target.addEffect(new MobEffectInstance(BleachEffects.SAKANADE,
					BleachTuning.SHINJI_SHIKAI_ONHIT_DURATION, BleachTuning.SHINJI_SHIKAI_AMPLIFIER,
					false, false, true));

			ServerLevel level = player.serverLevel();
			level.playSound(null, target.getX(), target.getY(), target.getZ(),
					SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.5f, 1.2f);
			level.playSound(null, target.getX(), target.getY(), target.getZ(),
					SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 1.0f, 1.4f);

			level.sendParticles(
					new PressureParticleOptions(BleachTuning.KIT_SHINJI_PARTICLE_COLOR, 1.2f),
					target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
					20, 0.4, 0.4, 0.4, 0.05);
		}
	}

	// --- Bankai: Sakashima Yokoshima Happō Fusagari -----------------------------------

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
			ServerLevel level = player.serverLevel();
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0f, 0.9f);
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.ILLUSIONER_PREPARE_BLINDNESS, SoundSource.PLAYERS, 1.5f, 1.0f);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			ServerLevel level = player.serverLevel();
			double radius = BleachTuning.SHINJI_RADIUS_BASE
					+ BleachTuning.SHINJI_RADIUS_PER_LEVEL * data.soulLevel;
			double radiusSq = radius * radius;

			// Proximity aura. Everything living in the field except the caster — hostile, passive and
			// tamed alike. The old `!isAlliedTo` filter read as a friendly-fire guard but in practice
			// only ever excluded scoreboard teammates, while making the intent look narrower than it
			// is: Sakashima Yokoshima Happō Fusagari inverts the world, not the hostile half of it.
			AABB searchBox = player.getBoundingBox().inflate(radius);
			List<LivingEntity> targets = level.getEntitiesOfClass(
					LivingEntity.class, searchBox,
					e -> e.isAlive() && e != player && player.distanceToSqr(e) <= radiusSq);

			for (LivingEntity target : targets) {
				target.addEffect(new MobEffectInstance(BleachEffects.SAKANADE,
						BleachTuning.SHINJI_EFFECT_DURATION_TICKS, BleachTuning.SHINJI_BANKAI_AMPLIFIER,
						false, false, true));
			}

			// Inverted allegiance. A mob under the field cannot tell friend from foe, so it fights
			// whatever is nearest that is not Shinji. Re-pointed on an interval rather than every
			// tick: a target reassigned 20 times a second never reaches its victim.
			if (player.tickCount % Math.max(1, BleachTuning.SHINJI_BANKAI_RETARGET_TICKS) == 0) {
				invertAllegiance(player, targets);
			}

			// Swirling perimeter aura particles
			int ringCount = BleachTuning.SHINJI_BANKAI_RING_PARTICLES;
			double py = player.getY() + 0.2;
			double phase = (player.tickCount % 40) * (Mth.TWO_PI / 40.0);

			for (int i = 0; i < ringCount; i++) {
				double angle = phase + (Mth.TWO_PI * i) / ringCount;
				double x = player.getX() + Math.cos(angle) * radius;
				double z = player.getZ() + Math.sin(angle) * radius;
				level.sendParticles(
						new PressureParticleOptions(BleachTuning.KIT_SHINJI_PARTICLE_COLOR, 1.0f),
						x, py, z, 1, 0.0, 0.02, 0.0, 0.0);
			}
		}

		/**
		 * Point every afflicted mob at its nearest neighbour instead of at the caster.
		 *
		 * <p>Both halves are needed. {@code setTarget} is what an active attack goal reads, and
		 * {@code setLastHurtByMob} is what wakes the revenge goals that passive and neutral mobs
		 * carry instead of one — a cow has no attack goal to feed, but a wolf, an iron golem, a
		 * piglin or a bee does, and none of them would ever have swung without it.
		 */
		private static void invertAllegiance(ServerPlayer player, List<LivingEntity> inField) {
			for (LivingEntity entity : inField) {
				if (!(entity instanceof Mob mob)) {
					continue;
				}

				LivingEntity victim = null;
				double bestDistSq = Double.MAX_VALUE;

				for (LivingEntity candidate : inField) {
					if (candidate == mob || !candidate.isAlive() || candidate == player) {
						continue;
					}
					double distSq = mob.distanceToSqr(candidate);
					if (distSq < bestDistSq) {
						bestDistSq = distSq;
						victim = candidate;
					}
				}

				if (victim == null) {
					// Nobody else in the field. Dropping the caster as a target is still worth doing:
					// the mob wanders rather than resuming its approach.
					if (mob.getTarget() == player) {
						mob.setTarget(null);
					}
					continue;
				}

				mob.setTarget(victim);
				mob.setLastHurtByMob(victim);
			}
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
		}
	}
}
