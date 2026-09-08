package com.bleach.mod.ability.kits;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.tuning.BleachTuning;

import org.joml.Vector3f;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Schrift <b>D — The Deathdealing</b>, Askin Nakk Le Vaar · {@code BALANCE.md} §P.6.
 *
 * <p>Askin's power is how much of a thing a body can take. Here that is {@link Doses}: every landed
 * arrow leaves a dose, and every dose raises the damage that target takes from <em>everything</em>,
 * from anyone. Doses bleed off if nobody keeps applying them, so D is a clock — commit to a target
 * and finish it while the stack is up.
 *
 * <p>The two tiers are the same power at two intensities, as {@link QuincyTransform} requires:
 *
 * <ul>
 *   <li><b>Schrift (tier 1):</b> {@link BleachTuning#DOSE_PER_ARROW} doses per landed arrow. One
 *       target at a time, at the speed you can hit it.</li>
 *   <li><b>Vollständig (tier 2):</b> <i>Gift Bad Sonnenschein</i> — a standing dome of poison
 *       anchored where you released, dosing and damaging everything inside it on a clock. The same
 *       mechanic applied to a volume instead of to one arrow at a time. Arrows still dose, harder.</li>
 * </ul>
 *
 * <p><b>It is deliberately not an outright kill</b> — see {@link Doses}. The dose stack is a
 * vulnerability multiplier, not a death threshold.
 *
 * <p>Unlike every other tier in this mod the dome does <b>not</b> follow the player. That is the
 * point of the letter, and it is why {@link #onTierRevert} has real work to do: a dome outlives the
 * intent that made it unless something explicitly takes it down.
 */
public final class DeathdealingTransform {
	private DeathdealingTransform() {
	}

	public static final ResourceLocation SCHRIFT_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "deathdealing/schrift");
	public static final ResourceLocation VOLLSTANDIG_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "deathdealing/vollstandig");

	public static TransformAbility schrift() {
		return new Schrift();
	}

	public static TransformAbility vollstandig() {
		return new Vollstandig();
	}

	/**
	 * Applies doses and <b>shows them</b>.
	 *
	 * <p>The showing is not decoration. Without it the Schrift tier is invisible: the player presses
	 * the key, shoots, and nothing on screen changes — the whole power is a number on an entity
	 * nobody can see. A stacking mechanic the player cannot read is a mechanic they cannot use, so
	 * the ring on the target and the count on the action bar are part of the feature rather than
	 * polish on top of it.
	 */
	private static void dose(ServerPlayer player, LivingEntity target, int amount) {
		Doses.add(target, amount, player.server.getTickCount());
		int stack = Doses.count(target);

		if (player.level() instanceof ServerLevel level) {
			ring(level, target, stack);
		}

		player.displayClientMessage(Component.literal(String.format(
				"Dose %d/%d · ×%.2f", stack, BleachTuning.DOSE_MAX,
				Doses.damageTakenMultiplier(stack))), true);

		// The stack cashes itself the moment it fills, rather than waiting for another hit — a bar
		// that sits full doing nothing is the state players read as "broken".
		if (stack >= BleachTuning.DOSE_MAX) {
			discharge(player, target);
		}
	}

	/**
	 * <b>Gift Ring</b> — "focuses overwhelming lethality onto a specific target area". Fired by
	 * releasing the bow while crouching, so it costs no keybind.
	 *
	 * <p>A targeted area rather than a projectile, which is what the technique is: the ring lands
	 * where the player is looking, up to {@link BleachTuning#GIFT_RING_REACH} blocks away, and doses
	 * everything within {@link BleachTuning#GIFT_RING_RADIUS} of that point at once. Against a single
	 * target it is a worse arrow; against a group it is the only way D reaches a whole fight before
	 * Vollständig.
	 *
	 * @return true always — the shot is consumed whether or not it caught anything, because the SP is
	 *         spent on aiming the ring, not on what it hit
	 */
	private static boolean giftRing(ServerPlayer player, float draw) {
		SpiritualData data = BleachAttachments.get(player);
		double cost = BleachTuning.GIFT_RING_SP_COST;
		if (data.sp < cost) {
			player.displayClientMessage(Component.literal("Not enough spiritual pressure."), true);
			return true;
		}
		data.spend(cost);

		if (!(player.level() instanceof ServerLevel level)) {
			return true;
		}

		Vec3 centre = aimPoint(player, BleachTuning.GIFT_RING_REACH);
		double radius = BleachTuning.GIFT_RING_RADIUS;

		markRing(level, centre, radius);
		level.playSound(null, centre.x, centre.y, centre.z,
				SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 1.0f, 0.6f);

		int caught = 0;
		for (LivingEntity victim : level.getEntitiesOfClass(LivingEntity.class,
				new AABB(centre, centre).inflate(radius),
				candidate -> candidate.isAlive() && candidate != player
						&& candidate.position().distanceTo(centre) <= radius)) {
			dose(player, victim, BleachTuning.GIFT_RING_DOSES);
			caught++;
		}

		player.displayClientMessage(Component.literal(
				"Gift Ring — " + caught + " caught."), true);
		return true;
	}

	/**
	 * Where the player is looking, stopped at the first block. Uses the entity's own view vector
	 * rather than a raycast against entities, so the ring lands on the ground behind a target rather
	 * than sticking to it — a placed area is supposed to be dodgeable by leaving it.
	 */
	private static Vec3 aimPoint(ServerPlayer player, double reach) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0f).scale(reach));
		BlockHitResult hit = player.level().clip(new ClipContext(eye, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
	}

	/** The ring on the ground where a Gift Ring landed. */
	private static void markRing(ServerLevel level, Vec3 centre, double radius) {
		DustParticleOptions dust = dust(BleachTuning.GIFT_RING_PARTICLE_SCALE);
		int points = (int) Math.max(12, Math.ceil(2.0 * Math.PI * radius / 0.4));
		for (int i = 0; i < points; i++) {
			double angle = (i / (double) points) * Math.PI * 2.0;
			level.sendParticles(dust,
					centre.x + Math.cos(angle) * radius,
					centre.y + 0.15,
					centre.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * The payoff for a full stack · Type Soul's Lethal Dosage, adapted to the user's ruling that
	 * doses must never kill outright.
	 *
	 * <p>At {@link BleachTuning#DOSE_MAX} the stack discharges: heavy {@code SPIRIT_MECHANIC} damage
	 * and a stun, then the stack is wiped so the clock starts again. It is the burst Type Soul pays
	 * out at 100%, minus the guaranteed death — which keeps the mechanic's shape (a bar you fill,
	 * then cash) without making D a delete button.
	 *
	 * <p>The damage is ordinary {@code SPIRIT_PRESSURE}, <b>not</b> {@code SPIRIT_MECHANIC_KILL}.
	 * That type is documented as the unavoidable-kill source and exists for Suì-Fēng's two-strike
	 * kill; borrowing it for a burst that is explicitly not a kill would both misstate the intent and
	 * quietly exempt this from every Soul Level reduction. A discharge is damage, so it scales like
	 * damage.
	 */
	private static void discharge(ServerPlayer player, LivingEntity target) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		Doses.clear(target);

		target.invulnerableTime = 0;
		target.hurt(BleachDamage.source(level, BleachDamage.SPIRIT_PRESSURE, player),
				(float) BleachTuning.DOSE_DISCHARGE_DAMAGE);
		target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
				Math.max(1, BleachTuning.DOSE_DISCHARGE_STUN_TICKS),
				BleachTuning.DOSE_DISCHARGE_STUN_LEVEL, false, true, true));
		target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,
				Math.max(1, BleachTuning.DOSE_DISCHARGE_STUN_TICKS), 1, false, true, true));

		burst(level, target);
		level.playSound(null, target.getX(), target.getY(), target.getZ(),
				SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 1.2f, 0.5f);

		player.displayClientMessage(Component.literal("Lethal dose."), true);
	}

	/** A sphere of purple thrown off a target whose stack has just discharged. */
	private static void burst(ServerLevel level, LivingEntity target) {
		DustParticleOptions dust = dust(BleachTuning.DOSE_PARTICLE_SCALE * 1.5);
		for (int i = 0; i < BleachTuning.DOSE_DISCHARGE_PARTICLES; i++) {
			double z = level.random.nextDouble() * 2.0 - 1.0;
			double angle = level.random.nextDouble() * Math.PI * 2.0;
			double ring = Math.sqrt(Math.max(0.0, 1.0 - z * z));
			double r = 1.5;
			level.sendParticles(dust,
					target.getX() + Math.cos(angle) * ring * r,
					target.getY() + target.getBbHeight() * 0.5 + z * r,
					target.getZ() + Math.sin(angle) * ring * r,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	private static DustParticleOptions dust(double scale) {
		int rgb = BleachTuning.KIT_DEATHDEALING_PARTICLE_COLOR;
		return new DustParticleOptions(
				new Vector3f(((rgb >> 16) & 0xFF) / 255.0f,
						((rgb >> 8) & 0xFF) / 255.0f,
						(rgb & 0xFF) / 255.0f),
				(float) scale);
	}

	/** A ring of Askin's purple around the target, one point per dose, so the stack is countable. */
	private static void ring(ServerLevel level, LivingEntity target, int stack) {
		int rgb = BleachTuning.KIT_DEATHDEALING_PARTICLE_COLOR;
		DustParticleOptions dust = new DustParticleOptions(
				new Vector3f(((rgb >> 16) & 0xFF) / 255.0f,
						((rgb >> 8) & 0xFF) / 255.0f,
						(rgb & 0xFF) / 255.0f),
				(float) BleachTuning.DOSE_PARTICLE_SCALE);

		double radius = target.getBbWidth() * 0.5 + BleachTuning.DOSE_RING_MARGIN;
		double height = target.getBbHeight() * 0.6;

		for (int i = 0; i < stack; i++) {
			double angle = (i / (double) Math.max(1, stack)) * Math.PI * 2.0;
			level.sendParticles(dust,
					target.getX() + Math.cos(angle) * radius,
					target.getY() + height,
					target.getZ() + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** Release 1 — the Schrift. Arrows dose; nothing else changes. */
	private static final class Schrift extends QuincyTransform.Tier1 {
		private Schrift() {
			super(SCHRIFT_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
			player.displayClientMessage(
					Component.literal("The Deathdealing — your arrows leave a dose."), true);
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
		}

		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
		}

		@Override
		public void onProjectileHit(ServerPlayer player, LivingEntity target, float damage) {
			dose(player, target, BleachTuning.DOSE_PER_ARROW);
		}

		/**
		 * Crouch-release fires Gift Ring instead of an arrow. Crouching is the only free modifier on
		 * a bow — every key is taken — and it is deliberate rather than accidental, which matters for
		 * a move that costs more SP than a shot.
		 */
		@Override
		public boolean onBowRelease(ServerPlayer player, float draw) {
			return player.isShiftKeyDown() && giftRing(player, draw);
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_DEATHDEALING_PARTICLE_COLOR;
		}
	}

	/** Release 2 — Gift Bad Sonnenschein. The dome, plus heavier arrows. */
	private static final class Vollstandig extends QuincyTransform.Tier2 {
		private Vollstandig() {
			super(VOLLSTANDIG_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
			PoisonDome.place(player);
			player.displayClientMessage(
					Component.literal("Gift Bad Sonnenschein."), true);
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
		}

		/**
		 * Takes the dome down. Reached from every revert path — deliberate toggle-off, SP exhaustion,
		 * death, logout, sheathing and the master switch — which is exactly what a field anchored away
		 * from its owner needs, since nothing else in the world holds a reference to it.
		 */
		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
			PoisonDome.remove(player);
		}

		@Override
		public void onProjectileHit(ServerPlayer player, LivingEntity target, float damage) {
			dose(player, target, BleachTuning.DOSE_PER_ARROW_VOLL);
		}

		@Override
		public boolean onBowRelease(ServerPlayer player, float draw) {
			return player.isShiftKeyDown() && giftRing(player, draw);
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_DEATHDEALING_PARTICLE_COLOR;
		}
	}
}
