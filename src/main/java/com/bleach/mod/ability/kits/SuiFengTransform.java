package com.bleach.mod.ability.kits;

import java.util.Arrays;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.effect.BleachEffects;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;
import com.bleach.mod.util.BlockQueue;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Suì-Fēng's released states · PRD §6.3 · {@code BALANCE.md} §J.3.
 *
 * <ul>
 *   <li><b>Shikai — Suzumebachi (Nigeki Kessatsu):</b> a landed strike stamps a <em>homonka</em> on
 *       the exact point of the target's hitbox it touched. A second strike within
 *       {@link BleachTuning#SUI_MARK_TOLERANCE} of that same point kills, unconditionally, bypassing
 *       armour and Soul Level defence — a Totem of Undying still saves. The mark is a place, not a
 *       timer: it never expires on its own, and it is wiped the moment Shikai ends.</li>
 *   <li><b>Bankai — Jakuhō Raikōben:</b> a three-second rooted wind-up that launches an actual
 *       missile. It flies, it can miss, and what it does on impact is ordinary — very large —
 *       <em>damage</em>, scaled by Soul Level on both sides like any other technique.</li>
 * </ul>
 *
 * <h2>Why neither one has a hidden timer any more</h2>
 *
 * <p>Bankai's five-minute lockout used to live in a persisted game-time stamp. Nothing on screen
 * counted it down, so from the player's side the ability simply stopped existing for a while with
 * no way to tell how long. It is now paid for the way everything else in this mod is paid for:
 * firing empties the pool, and the climb back to the 95% entry gate <em>is</em> the cooldown, every
 * second of it visible in the bar.
 *
 * <h2>Where the regeneration throttle sits</h2>
 *
 * <p>On the Shikai kill — {@link BleachTuning#SUI_SHIKAI_KILL_EXERTION} — and not on Bankai. The
 * two releases fail in opposite ways. Jakuhō Raikōben is rooted, telegraphed for three seconds and
 * fires a missile that can be dodged or eaten by terrain, so a throttle on top of the emptied pool
 * charged a miss exactly what it charged a hit. Nigeki Kessatsu cannot miss once the second strike
 * lands: it is instant, ignores armour and Soul Level, and no amount of defence answers it. The
 * exertion debt is the price of that certainty, and because exertion clears only at a full bar,
 * repeat kills get progressively more expensive rather than settling into a rotation.
 */
public final class SuiFengTransform {
	private SuiFengTransform() {
	}

	public static final ResourceLocation SHIKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "suifeng/shikai");
	public static final ResourceLocation BANKAI_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "suifeng/bankai");

	/** Guard against dividing by a zero-sized hitbox on an entity that has one. */
	private static final double SIZE_EPSILON = 1.0e-3;

	/** A player's height. The yardstick {@link BleachTuning#SUI_MARK_TOLERANCE} is quoted against. */
	private static final double REFERENCE_BODY_HEIGHT = 1.8;

	public static TransformAbility shikai() {
		return new Shikai();
	}

	public static TransformAbility bankai() {
		return new Bankai();
	}

	// --- Shikai: Suzumebachi ----------------------------------------------------------

	/**
	 * A homonka, stored in the target's own frame of reference.
	 *
	 * <p>{@code local} is the contact point in the target's <b>body frame</b>, in blocks: {@code x}
	 * across the body from right to left, {@code y} from feet to head, {@code z} from back to front,
	 * with the origin between the feet. So a mark means "the left hand" and stays on the left hand
	 * however the target turns, walks or crouches — see {@link #contactPoint} for how the frame is
	 * built and why it has to be the body's rather than the head's.
	 */
	public record Mark(UUID targetId, Vec3 local) {}

	private static final class Shikai implements TransformAbility {
		/** Attacker UUID -> (Target UUID -> Mark). Keyed per attacker for clean multiplayer isolation. */
		private final Map<UUID, Map<UUID, Mark>> marksByAttacker = new ConcurrentHashMap<>();

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
			// A fresh release starts with a clean slate — marks never carry across activations.
			marksByAttacker.put(player.getUUID(), new ConcurrentHashMap<>());
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			Map<UUID, Mark> marks = marksByAttacker.get(player.getUUID());
			if (marks == null || marks.isEmpty()) {
				return;
			}

			ServerLevel level = player.serverLevel();

			marks.entrySet().removeIf(entry -> {
				Entity entity = level.getEntity(entry.getValue().targetId());
				if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
					return true;
				}

				// Park the butterfly on the marked spot, wherever the target has since moved it to.
				// This is not decoration: the second strike has to land on this point, so the point
				// has to be something the attacker can see and aim at.
				Vec3 worldPos = worldPointOf(target, entry.getValue().local());
				level.sendParticles(
						new PressureParticleOptions(BleachTuning.SUI_MARK_PARTICLE_COLOR,
								(float) BleachTuning.SUI_MARK_PARTICLE_SCALE),
						worldPos.x, worldPos.y, worldPos.z,
						1, 0.02, 0.02, 0.02, 0.0);
				level.sendParticles(ParticleTypes.SOUL, worldPos.x, worldPos.y, worldPos.z, 1, 0.01, 0.01, 0.01, 0.0);

				return false;
			});
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			marksByAttacker.remove(player.getUUID());
		}

		@Override
		public void onMeleeHit(ServerPlayer player, LivingEntity target, float damage) {
			if (damage <= 0.0f || !target.isAlive()) {
				return;
			}

			ServerLevel level = player.serverLevel();
			Vec3 local = contactPoint(player, target);
			if (local == null) {
				// The blow landed but the ray did not reach the body — the attacker was turning, or
				// the client's pick and the server's look vector disagreed. Marking nothing is the
				// safe answer; guessing a point is how this became a free kill last time.
				player.displayClientMessage(Component.literal("Glancing blow — no homonka."), true);
				return;
			}

			Vec3 hitPos = worldPointOf(target, local);

			Map<UUID, Mark> marks = marksByAttacker.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
			Mark existing = marks.get(target.getUUID());
			double distance = existing == null ? Double.NaN : existing.local().distanceTo(local);
			double tolerance = toleranceFor(target);

			if (existing != null && distance <= tolerance) {
				marks.remove(target.getUUID());
				kill(player, BleachAttachments.get(player), level, target);
				return;
			}

			// First strike, or a strike that landed somewhere else on the body and moves the mark.
			marks.put(target.getUUID(), new Mark(target.getUUID(), local));

			level.playSound(null, hitPos.x, hitPos.y, hitPos.z, SoundEvents.BEE_STING, SoundSource.PLAYERS, 1.0f, 1.4f);
			level.sendParticles(
					new PressureParticleOptions(BleachTuning.SUI_MARK_PARTICLE_COLOR,
							(float) BleachTuning.SUI_MARK_PARTICLE_SCALE),
					hitPos.x, hitPos.y, hitPos.z, 6, 0.05, 0.05, 0.05, 0.01);

			// The miss distance, not just the fact of it. Without a number the player cannot tell a
			// near miss from a wild one, and has no way to learn how large the tolerance is.
			player.displayClientMessage(Component.literal(existing == null
					? "Homonka placed — strike the same spot again."
					: String.format(Locale.ROOT, "Homonka moved — off by %.2f (need %.2f).",
							distance, tolerance)), true);
			if (target instanceof ServerPlayer marked) {
				marked.displayClientMessage(Component.literal("You are marked."), true);
			}
		}

		/**
		 * Unconditional kill bypassing armour and Soul Level defence. A Totem of Undying still saves.
		 *
		 * <p>Charged in exertion rather than SP · {@link BleachTuning#SUI_SHIKAI_KILL_EXERTION}. A
		 * flat SP cost would be paid back inside a minute and would make Nigeki Kessatsu a rotation;
		 * the debt makes the second kill cost more than the first, and the third more again, because
		 * exertion only clears once the bar is back at 100%. That is the whole of the balance on an
		 * ability that is instant, armour-blind and cannot be resisted — landing it is meant to end
		 * the fight and then leave you slow.
		 */
		private static void kill(ServerPlayer player, SpiritualData data, ServerLevel level, LivingEntity target) {
			level.playSound(null, target.getX(), target.getY(), target.getZ(),
					SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 2.0f, 1.2f);
			level.playSound(null, target.getX(), target.getY(), target.getZ(),
					SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 1.0f, 1.5f);

			level.sendParticles(ParticleTypes.SOUL,
					target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
					40, 0.5, 0.5, 0.5, 0.1);
			level.sendParticles(
					new PressureParticleOptions(BleachTuning.SUI_MARK_PARTICLE_COLOR, 1.5f),
					target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
					30, 0.4, 0.4, 0.4, 0.08);

			// The strike lands from inside the victim's own damage tick, so vanilla i-frames are
			// already up and would otherwise dock the hit by whatever the sword just dealt.
			target.invulnerableTime = 0;
			target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_MECHANIC_KILL, player),
					target.getMaxHealth() * 10.0f);

			// After the strike, not before: a kill that somehow failed to land should not leave the
			// attacker holding the debt for it.
			data.exertion += BleachTuning.SUI_SHIKAI_KILL_EXERTION;
			data.pauseRegen();
			SpiritualTicker.sync(player, true);
		}
	}

	// --- Hitbox-relative coordinates ---------------------------------------------------

	/**
	 * Where the swing touched the target, <b>in the target's own body frame</b>, or null if the ray
	 * did not reach the body.
	 *
	 * <h2>The ray is transformed, not the answer</h2>
	 *
	 * <p>This is the whole trick, and getting it backwards is what broke the first two attempts. A
	 * body-relative mark needs a body-relative <em>frame</em>: the ray is rotated out of world space
	 * into the target's, and clipped against the box <em>there</em>. Clipping in world space and
	 * rotating the result afterwards mixes two frames — the point comes off a box that never turned,
	 * and then gets spun as though it had, so a target who turned invalidated a perfectly aimed
	 * second strike at around 21 degrees.
	 *
	 * <p>The frame is built from {@link LivingEntity#yBodyRot}, <b>not</b> {@code getYRot()}. For a
	 * living entity {@code getYRot()} is the <em>head</em>: it swivels freely and instantly while the
	 * body lags behind and only turns when the entity actually does. Marking against the head meant
	 * a target could shrug their homonka to the far side of their body by glancing over one shoulder.
	 *
	 * <p>In the returned frame {@code +Z} is the direction the body faces and {@code +X} is the
	 * body's left, so a mark genuinely means "left hand" and follows the hand round as the target
	 * turns. Coordinates come back in blocks, clamped to the box; the size of the target is applied
	 * once in {@link #toleranceFor} instead, so the window stays the same fraction of the body on a
	 * bee as on a player without distorting the space the distance is measured in.
	 *
	 * <p><b>Fails closed.</b> A miss returns null and marks nothing. It used to fall back to the
	 * target's centre, which is a <em>constant</em> — two consecutive misses produced two identical
	 * marks, so any two hits killed.
	 */
	@Nullable
	private static Vec3 contactPoint(ServerPlayer player, LivingEntity target) {
		double width = boxWidth(target);
		double height = boxHeight(target);

		Vec3 eye = toBodyFrame(target, player.getEyePosition());
		Vec3 end = toBodyFrame(target,
				player.getEyePosition().add(player.getLookAngle().scale(BleachTuning.SUI_MARK_RAYCAST_REACH)));

		// The same box the entity really has, rebuilt around the origin of the body frame. Clipping
		// here rather than in world space is what makes the hit point mean a body part.
		AABB body = new AABB(-width * 0.5, 0.0, -width * 0.5, width * 0.5, height, width * 0.5);

		// Kept in blocks, in the body frame — deliberately NOT normalised per axis. Dividing x and z
		// by the width and y by the height looks like "a fraction of the body", but it distorts the
		// space it is measuring: on a player the width is 0.6 and the height 1.8, so a normalised
		// distance made horizontal aim error count three times as heavily as vertical, and the same
		// tolerance meant something different on every mob. Scale belongs in the comparison, once,
		// where {@link #toleranceFor} applies it uniformly.
		return body.clip(eye, end)
				.map(hit -> new Vec3(
						Mth.clamp(hit.x, -width * 0.5, width * 0.5),
						Mth.clamp(hit.y, 0.0, height),
						Mth.clamp(hit.z, -width * 0.5, width * 0.5)))
				.orElse(null);
	}

	/**
	 * A marked body point back into world space, for the butterfly that parks on it.
	 *
	 * <p>Inverse of the transform in {@link #contactPoint}, so the particle sits exactly where the
	 * next strike has to land — and orbits the target as they turn, because the body part does.
	 */
	private static Vec3 worldPointOf(LivingEntity target, Vec3 local) {
		// local is already a body-frame offset in blocks, so the only thing left is the rotation.
		return target.position().add(local.yRot(-bodyYaw(target)));
	}

	/** World point -> the target's body frame: origin at the feet, {@code +Z} along the body's facing. */
	private static Vec3 toBodyFrame(LivingEntity target, Vec3 world) {
		return world.subtract(target.position()).yRot(bodyYaw(target));
	}

	private static float bodyYaw(LivingEntity target) {
		return (float) Math.toRadians(target.yBodyRot);
	}

	/**
	 * The entity's current footprint. Deliberately the live pose rather than a standing constant:
	 * the mark is a fraction of the body, so a target who crouches carries their homonka down with
	 * them instead of leaving it hanging in the air above their head.
	 */
	/**
	 * The kill window for this target, in blocks.
	 *
	 * <p>{@link BleachTuning#SUI_MARK_TOLERANCE} is expressed against a player-sized body and scaled
	 * by the target's largest dimension, so it stays the same <em>fraction of the target</em> across
	 * every mob — the point the old per-axis normalisation was reaching for — while remaining a
	 * single isotropic distance, so aiming half a block to the side is exactly as forgiving as
	 * aiming half a block high.
	 */
	private static double toleranceFor(LivingEntity target) {
		double scale = Math.max(boxWidth(target), boxHeight(target)) / REFERENCE_BODY_HEIGHT;
		return BleachTuning.SUI_MARK_TOLERANCE * scale;
	}

	private static double boxWidth(LivingEntity target) {
		return Math.max(SIZE_EPSILON, target.getBbWidth());
	}

	private static double boxHeight(LivingEntity target) {
		return Math.max(SIZE_EPSILON, target.getBbHeight());
	}

	// --- Bankai: Jakuhō Raikōben ------------------------------------------------------

	/**
	 * Whether a target at this distance is in the core rather than the falloff.
	 *
	 * <p>This is the ring that decides which damage <em>type</em> the target takes, and the two are
	 * not interchangeable. The core is a <b>mechanic</b> — {@link BleachDamage#SPIRIT_MECHANIC_KILL},
	 * bypassing armour, enchantments and resistance, exactly as PRD §2.4 and {@link BleachDamage}'s
	 * class note specify for Jakuhō Raikōben's inner radius. The falloff is ordinary bleach damage
	 * and stays mitigable.
	 *
	 * <p>Both rings used to fire {@code SPIRIT_PRESSURE}, which meant 60 raw arrived as about 11
	 * against Protection IV netherite while an unarmoured mob standing beside it took the full 60.
	 * The Bankai did roughly a fifth of its damage to the only targets it is ever aimed at.
	 */
	public static boolean isCoreHit(double distance, double lethalRadius) {
		return distance <= lethalRadius;
	}

	/**
	 * Blast damage at a distance: flat across the core, then linear to {@code outer} at the falloff
	 * radius and never below it.
	 *
	 * <p>The degenerate case is real rather than theoretical — a tuning pass that sets the two radii
	 * equal would otherwise divide by zero inside the interpolation.
	 */
	public static double blastDamage(double distance, double lethalRadius, double falloffRadius,
			double inner, double outer) {
		if (distance <= lethalRadius) {
			return inner;
		}
		double span = falloffRadius - lethalRadius;
		if (span <= 0.0) {
			return outer;
		}
		double t = Math.min(1.0, (distance - lethalRadius) / span);
		return Mth.lerp(t, inner, outer);
	}

	private static final class Bankai implements TransformAbility {
		private final Map<UUID, Integer> windupTicksByPlayer = new ConcurrentHashMap<>();

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

		/**
		 * None. The pool <em>is</em> the cooldown — see the class note. An ability cooldown here
		 * would be exactly the invisible timer this replaced.
		 */
		@Override
		public int cooldownTicks(SpiritualData data) {
			return 0;
		}

		/**
		 * The missile cannot be put back in the tube. Without this, the very common "press G again
		 * because nothing seems to be happening" during the wind-up reverted the state and cancelled
		 * the shot.
		 */
		@Override
		public boolean canRevert(ServerPlayer player, SpiritualData data) {
			if (windupTicksByPlayer.containsKey(player.getUUID())) {
				player.displayClientMessage(Component.literal("Jakuhō Raikōben is already firing."), true);
				return false;
			}
			return true;
		}

		@Override
		public void onEnter(ServerPlayer player, SpiritualData data) {
			windupTicksByPlayer.put(player.getUUID(), 0);
			ServerLevel level = player.serverLevel();
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.PLAYERS, 2.5f, 0.8f);
		}

		@Override
		public void onTick(ServerPlayer player, SpiritualData data) {
			Integer ticks = windupTicksByPlayer.get(player.getUUID());
			if (ticks == null) {
				return;
			}

			ticks++;
			windupTicksByPlayer.put(player.getUUID(), ticks);
			ServerLevel level = player.serverLevel();

			int windupTotal = BleachTuning.SUI_BANKAI_WINDUP_TICKS;

			if (ticks <= windupTotal) {
				windup(player, level, ticks, windupTotal);
				return;
			}

			windupTicksByPlayer.remove(player.getUUID());
			fire(player, data, level);
		}

		/** Rooted caster, ascending audio telegraph, and an expanding gold warning ring. */
		private static void windup(ServerPlayer player, ServerLevel level, int ticks, int windupTotal) {
			player.addEffect(new MobEffectInstance(BleachEffects.REIATSU, 2, 3, true, false, false));
			Vec3 motion = player.getDeltaMovement();
			player.setDeltaMovement(0.0, motion.y, 0.0);

			if (ticks % 10 == 0) {
				float pitch = 0.6f + 0.6f * ((float) ticks / (float) windupTotal);
				level.playSound(null, player.getX(), player.getY(), player.getZ(),
						SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 2.0f, pitch);
			}

			double currentRadius = (BleachTuning.SUI_BANKAI_FALLOFF_RADIUS * ticks) / (double) windupTotal;
			int ringCount = BleachTuning.SUI_BANKAI_RING_PARTICLES;
			double py = player.getY() + 0.2;

			for (int i = 0; i < ringCount; i++) {
				double angle = (Mth.TWO_PI * i) / ringCount;
				double x = player.getX() + Math.cos(angle) * currentRadius;
				double z = player.getZ() + Math.sin(angle) * currentRadius;
				level.sendParticles(
						new PressureParticleOptions(BleachTuning.KIT_SUIFENG_PARTICLE_COLOR, 1.2f),
						x, py, z, 1, 0.0, 0.02, 0.0, 0.0);
			}
		}

		/**
		 * Launch, then pay for it.
		 *
		 * <p>The caster drops straight back to base rather than staying in Bankai: the missile is the
		 * whole of the state, and there is nothing left to be transformed for once it is away.
		 */
		private static void fire(ServerPlayer player, SpiritualData data, ServerLevel level) {
			Vec3 origin = player.getEyePosition().add(player.getLookAngle().scale(1.0));

			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 4.0f, 0.5f);
			level.playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 2.0f, 1.6f);

			BlockQueue.submit(new JakuhoMissile(level.dimension(), player.getUUID(),
					origin, player.getLookAngle().normalize()));

			// Recoil. Half the caster's current health, never lethal · BALANCE.md §J.3.
			float currentHealth = player.getHealth();
			player.setHealth(Math.max(1.0f,
					currentHealth - (float) (currentHealth * BleachTuning.SUI_BANKAI_SELF_DMG_PCT)));

			// The cooldown, in the only currency this mod has: the pool goes to zero and has to be
			// climbed all the way back to the 95% entry gate before Jakuhō Raikōben exists again.
			//
			// No exertion is added on top. A rooted three-second wind-up that can be interrupted,
			// dodged or simply missed has already paid for itself by emptying the bar; throttling
			// regeneration as well meant a missed shot cost the same minutes as a landed one, which
			// is the one outcome that should not be punished twice. The refill runs at the ordinary
			// rate — the throttle now belongs to the Shikai kill, which cannot miss · #kill.
			data.sp = 0.0;
			data.pauseRegen();

			SpiritualTicker.forceRevert(player, data);
			SpiritualTicker.sync(player, true);
		}

		@Override
		public void onRevert(ServerPlayer player, SpiritualData data) {
			windupTicksByPlayer.remove(player.getUUID());
		}
	}

	// --- The missile -------------------------------------------------------------------

	/**
	 * Jakuhō Raikōben in flight.
	 *
	 * <p>A ray-marched point rather than a registered {@code Entity}: it needs to travel, trail
	 * particles, hit the first thing in its way and detonate, and none of that needs an entity type,
	 * a renderer, a spawn packet or a model. It is server-side arithmetic drawing itself with
	 * particles, which is what the crater and snow tasks already are.
	 *
	 * <p>Each tick advances {@link BleachTuning#SUI_MISSILE_SPEED} blocks in
	 * {@link BleachTuning#SUI_MISSILE_SUBSTEPS} slices, because a projectile moving two blocks a
	 * tick that tests only its endpoints flies straight through walls and through anyone standing in
	 * a doorway.
	 */
	public static final class JakuhoMissile implements BlockQueue.Task {
		private final ResourceKey<Level> dimension;
		private final UUID shooterId;
		private final Vec3 direction;

		private Vec3 position;
		private double travelled;

		public JakuhoMissile(ResourceKey<Level> dimension, UUID shooterId, Vec3 origin, Vec3 direction) {
			this.dimension = dimension;
			this.shooterId = shooterId;
			this.position = origin;
			this.direction = direction;
		}

		@Override
		public boolean tick(MinecraftServer server) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				return false;
			}

			ServerPlayer shooter = server.getPlayerList().getPlayer(shooterId);
			double step = BleachTuning.SUI_MISSILE_SPEED / Math.max(1, BleachTuning.SUI_MISSILE_SUBSTEPS);

			for (int i = 0; i < BleachTuning.SUI_MISSILE_SUBSTEPS; i++) {
				Vec3 next = position.add(direction.scale(step));

				BlockHitResult blockHit = level.clip(new ClipContext(position, next,
						ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
				if (blockHit.getType() != HitResult.Type.MISS) {
					detonate(level, shooter, blockHit.getLocation());
					return false;
				}

				LivingEntity struck = firstEntityAlong(level, shooter, position, next);
				if (struck != null) {
					detonate(level, shooter, struck.getBoundingBox().getCenter());
					return false;
				}

				position = next;
				travelled += step;

				if (travelled >= BleachTuning.SUI_MISSILE_RANGE) {
					detonate(level, shooter, position);
					return false;
				}
			}

			trail(level);
			return true;
		}

		/** The nearest living thing whose hitbox the segment passes through, ignoring the shooter. */
		private LivingEntity firstEntityAlong(ServerLevel level, ServerPlayer shooter, Vec3 from, Vec3 to) {
			AABB sweep = new AABB(from, to).inflate(BleachTuning.SUI_MISSILE_HIT_RADIUS);
			LivingEntity best = null;
			double bestDistSq = Double.MAX_VALUE;

			for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, sweep,
					e -> e.isAlive() && e != shooter)) {
				Optional<Vec3> clip = candidate.getBoundingBox()
						.inflate(BleachTuning.SUI_MISSILE_HIT_RADIUS).clip(from, to);
				if (clip.isEmpty()) {
					continue;
				}
				double distSq = clip.get().distanceToSqr(from);
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					best = candidate;
				}
			}

			return best;
		}

		private void trail(ServerLevel level) {
			level.sendParticles(
					new PressureParticleOptions(BleachTuning.KIT_SUIFENG_PARTICLE_COLOR, 2.0f),
					position.x, position.y, position.z, 6, 0.15, 0.15, 0.15, 0.01);
			level.sendParticles(ParticleTypes.FLAME, position.x, position.y, position.z,
					4, 0.1, 0.1, 0.1, 0.01);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, position.x, position.y, position.z,
					2, 0.2, 0.2, 0.2, 0.0);
		}

		/**
		 * The impact.
		 *
		 * <p>Everything caught takes {@link BleachDamage#SPIRIT_PRESSURE}, which is in the
		 * {@code bleach} tag and therefore runs the full Soul Level pipeline on both sides — the
		 * attacker's damage bonus, and the victim's bleach and general reductions. That is the whole
		 * point of it no longer being an instant kill: a capped defender should survive a hit from
		 * an equally capped attacker, bloodied, and a low-level one should not.
		 */
		private void detonate(ServerLevel level, ServerPlayer shooter, Vec3 at) {
			level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 5.0f, 0.7f);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 4.0f, 0.5f);

			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 5, 2.0, 1.0, 2.0, 0.0);
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y, at.z, 80, 5.0, 2.0, 5.0, 0.05);
			level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 100, 6.0, 2.0, 6.0, 0.2);

			double lethalRadius = BleachTuning.SUI_BANKAI_LETHAL_RADIUS;
			double falloffRadius = BleachTuning.SUI_BANKAI_FALLOFF_RADIUS;
			double falloffRadiusSq = falloffRadius * falloffRadius;
			AABB searchBox = new AABB(at, at).inflate(falloffRadius);

			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, searchBox,
					e -> e.isAlive() && e.distanceToSqr(at) <= falloffRadiusSq)) {

				double dist = Math.sqrt(target.distanceToSqr(at));
				float dmg = (float) blastDamage(dist, lethalRadius, falloffRadius,
						BleachTuning.SUI_BANKAI_DMG_INNER, BleachTuning.SUI_BANKAI_DMG_OUTER);

				// The core is a mechanic, the falloff is damage. Firing SPIRIT_PRESSURE for both is
				// what made the Bankai land for about 11 against Protection IV netherite while an
				// unarmoured mob beside it took the whole 60.
				ResourceKey<DamageType> type = isCoreHit(dist, lethalRadius)
						? BleachDamage.SPIRIT_MECHANIC_KILL
						: BleachDamage.SPIRIT_PRESSURE;

				target.invulnerableTime = 0;
				target.hurt(BleachDamage.source(level, type, shooter), dmg);
			}

			BlockQueue.submit(new SuiFengCraterTask(
					dimension,
					BlockPos.containing(at),
					(int) Math.ceil(BleachTuning.SUI_CRATER_RADIUS),
					BleachTuning.SUI_CRATER_BLOCK_CAP,
					BleachTuning.SUI_CRATER_BLOCKS_PER_TICK));
		}
	}

	// --- Crater & Rim Scorch Task -----------------------------------------------------

	/**
	 * Precomputed tick-sliced spherical crater excavation and rim scorching.
	 *
	 * <p>Uses {@code setBlock} flags {@code 2 | 32} to notify clients while suppressing block drops
	 * and neighbour updates, preventing falling block cascades and item entity lag. Bedrock and block
	 * entities are preserved. Air excavation respects the block cap while the outer rim scorch shell
	 * processes to completion without truncation.
	 */
	public static final class SuiFengCraterTask implements BlockQueue.Task {
		private static final int AIR_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;

		/**
		 * The shape, per radius, built once and shared by every detonation.
		 *
		 * <p>The shape is a pure function of the radius — the noise is hashed from the offset, not
		 * from the world — so rebuilding it per missile was recomputing a constant. At the shipped
		 * radius of 18 that constant costs a 41³ = 68,921-cell scan with a {@code sqrt} in the middle
		 * of it, ~30k object allocations and a comparator sort, all inside the single tick the
		 * missile happens to detonate on. Which is the worst possible tick to spend it: the explosion
		 * particles, the sound, the entity sweep and the first carve slice are all landing on it too.
		 *
		 * <p>Bounded by construction — radius comes from tuning, so this holds one entry in practice
		 * and at most one per radius the config has ever been set to.
		 */
		private static final Map<Integer, long[]> SHAPE_CACHE = new ConcurrentHashMap<>();

		// Offsets are packed into a single long rather than a record, so the shape is one flat array
		// instead of ~30k objects, and Arrays.sort on it is a primitive sort with no comparator and
		// no boxing. distSq occupies the top bits, so that sort *is* the "carve outward from the
		// centre" ordering, for free.
		private static final int COORD_BIAS = 2048;
		private static final long COORD_MASK = 0x3FFF; // 14 bits, 0..16383
		private static final int SHIFT_DZ = 0;
		private static final int SHIFT_DY = 14;
		private static final int SHIFT_DX = 28;
		private static final int SHIFT_AIR = 42;
		private static final int SHIFT_DIST_SQ = 43;

		private static long pack(int dx, int dy, int dz, int distSq, boolean isAir) {
			return ((long) distSq << SHIFT_DIST_SQ)
					| ((isAir ? 1L : 0L) << SHIFT_AIR)
					| ((long) (dx + COORD_BIAS) << SHIFT_DX)
					| ((long) (dy + COORD_BIAS) << SHIFT_DY)
					| ((long) (dz + COORD_BIAS) << SHIFT_DZ);
		}

		private static int unpackDx(long p) {
			return (int) ((p >>> SHIFT_DX) & COORD_MASK) - COORD_BIAS;
		}

		private static int unpackDy(long p) {
			return (int) ((p >>> SHIFT_DY) & COORD_MASK) - COORD_BIAS;
		}

		private static int unpackDz(long p) {
			return (int) ((p >>> SHIFT_DZ) & COORD_MASK) - COORD_BIAS;
		}

		private static boolean unpackIsAir(long p) {
			return ((p >>> SHIFT_AIR) & 1L) != 0L;
		}

		private final ResourceKey<Level> dimension;
		private final BlockPos origin;
		private final long[] offsets;
		private final int blockCap;
		private final int blocksPerTick;

		private int cursor;
		private int blocksModified;
		private final BlockPos.MutableBlockPos curPos = new BlockPos.MutableBlockPos();

		/** Chunk of the last position touched, so a run of blocks inside one chunk looks it up once. */
		private @Nullable LevelChunk cachedChunk;
		private int cachedChunkX = Integer.MIN_VALUE;
		private int cachedChunkZ = Integer.MIN_VALUE;

		public SuiFengCraterTask(ResourceKey<Level> dimension, BlockPos origin, int radius, int cap, int perTick) {
			this.dimension = dimension;
			this.origin = origin.immutable();
			this.blockCap = cap;
			this.blocksPerTick = perTick;
			this.offsets = SHAPE_CACHE.computeIfAbsent(radius, SuiFengCraterTask::buildSphereOffsets);
		}

		private static long[] buildSphereOffsets(int radius) {
			int outer = radius + 2;
			double outerRadiusSq = (double) outer * outer;

			// Sized from the enclosing sphere's volume rather than grown from empty — the array is
			// hundreds of kilobytes at the shipped radius and doubling into it copies it every time.
			long[] packed = new long[Math.max(16, (int) (4.19 * outer * outer * outer) + 64)];
			int count = 0;

			// The `distSq <= outerRadius²` test is spent as loop bounds rather than as a rejection,
			// so the ~48% of the cube that lies outside the sphere is never visited at all.
			for (int dx = -outer; dx <= outer; dx++) {
				double remX = outerRadiusSq - (double) dx * dx;
				if (remX < 0.0) {
					continue;
				}
				int limY = (int) Math.sqrt(remX);

				for (int dy = -limY; dy <= limY; dy++) {
					double remY = remX - (double) dy * dy;
					if (remY < 0.0) {
						continue;
					}
					int limZ = (int) Math.sqrt(remY);

					for (int dz = -limZ; dz <= limZ; dz++) {
						int distSq = dx * dx + dy * dy + dz * dz;

						// Pseudo-random noise (+/- 0.7 blocks) for organic ragged blast edge
						double noise = ((((dx * 3129871) ^ (dy * 612847) ^ (dz * 116129781L)) & 0xFF) / 255.0) * 1.4 - 0.7;
						double effectiveDist = Math.sqrt(distSq) + noise;

						boolean carve = effectiveDist <= radius;
						if (carve || effectiveDist <= radius + 1.8) {
							if (count == packed.length) {
								packed = Arrays.copyOf(packed, count * 2);
							}
							packed[count++] = pack(dx, dy, dz, distSq, carve);
						}
					}
				}
			}

			long[] trimmed = Arrays.copyOf(packed, count);
			// distSq is the high field, so this sorts the sphere from the detonation centre outwards.
			Arrays.sort(trimmed);
			return trimmed;
		}

		/**
		 * The loaded chunk containing {@link #curPos}, or {@code null} if it is not loaded.
		 *
		 * <p>Doubles as the {@code isLoaded} check it replaces. Every {@code level.getBlockState} and
		 * {@code level.getBlockEntity} on a raw position re-resolves the chunk from its coordinates;
		 * the carve visits long runs inside one chunk, so resolving it once per run removes several
		 * lookups per block from the hot loop.
		 */
		private @Nullable LevelChunk chunkFor(ServerLevel level) {
			int cx = curPos.getX() >> 4;
			int cz = curPos.getZ() >> 4;
			if (cx != cachedChunkX || cz != cachedChunkZ) {
				cachedChunkX = cx;
				cachedChunkZ = cz;
				cachedChunk = level.getChunkSource().getChunkNow(cx, cz);
			}
			return cachedChunk;
		}

		@Override
		public boolean tick(MinecraftServer server) {
			ServerLevel level = server.getLevel(dimension);
			if (level == null) {
				return false;
			}

			int budget = blocksPerTick;

			while (budget > 0 && cursor < offsets.length) {
				long offset = offsets[cursor];
				boolean isAir = unpackIsAir(offset);

				// If this is an air offset and we have already reached the block cap, skip carving further air
				if (isAir && blocksModified >= blockCap) {
					cursor++;
					continue;
				}

				cursor++;
				budget--;

				curPos.set(origin.getX() + unpackDx(offset),
						origin.getY() + unpackDy(offset),
						origin.getZ() + unpackDz(offset));

				LevelChunk chunk = chunkFor(level);
				if (chunk == null) {
					continue;
				}

				BlockState state = chunk.getBlockState(curPos);
				// hasBlockEntity() is a flag on the state. The old getBlockEntity(pos) was a map
				// lookup per block to answer the same question, on a loop that runs tens of
				// thousands of times per crater.
				if (state.is(Blocks.BEDROCK) || state.hasBlockEntity()) {
					continue;
				}

				if (isAir) {
					if (!state.isAir()) {
						level.setBlock(curPos, Blocks.AIR.defaultBlockState(), AIR_FLAGS);
						blocksModified++;
					}
				} else {
					// Rim and floor scorch: grass/dirt -> coarse dirt, stone -> blackstone
					if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH)) {
						level.setBlock(curPos, Blocks.COARSE_DIRT.defaultBlockState(), AIR_FLAGS);
					} else if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE) || state.is(Blocks.ANDESITE)
							|| state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) || state.is(Blocks.DEEPSLATE)) {
						level.setBlock(curPos, Blocks.BLACKSTONE.defaultBlockState(), AIR_FLAGS);
					}
				}
			}

			return cursor < offsets.length;
		}
	}
}
