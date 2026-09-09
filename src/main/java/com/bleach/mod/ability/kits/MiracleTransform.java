package com.bleach.mod.ability.kits;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.bleach.mod.BleachMod;
import com.bleach.mod.ability.AbilityDispatcher;
import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.network.MiracleSyncPayload;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Schrift M — The Miracle · Gerard Valkyrie.
 *
 * <p>The only <em>stronger as you lose</em> identity in the mod, and the roster's only bruiser: a
 * Quincy who wants to be <em>in</em> the fight, on a race built for range. Damage taken feeds
 * stacks; stacks buy melee damage, empty hearts and size.
 *
 * <p>This class holds the arithmetic. Every number here is a potential exploit rather than merely a
 * tuning value, because the mechanic rewards the one thing every other kit is trying to avoid.
 * Three guards carry that weight:
 *
 * <ul>
 *   <li><b>Only a living attacker feeds stacks.</b> Enforced at the call site, not here. Fall
 *       damage, lava, drowning and cactus are all things a player can inflict on themselves in
 *       private before a fight; counting them would make the miracle farmable, which is the same
 *       class of defect as a drain that reaches zero.</li>
 *   <li><b>Stacks are capped</b>, or being hit is unboundedly good.</li>
 *   <li><b>The death save is once per Vollständig and not re-earnable</b> — decided 2026-09-09.
 *       Spending it does not let you rebuild to another within the same transformation, so the
 *       person fighting Gerard knows a second one is not coming.</li>
 * </ul>
 *
 * <p>The miracle does not save you from anything in {@code BleachDamage.SPIRIT_MECHANIC}. That one
 * tag check covers Suì-Fēng's Nigeki Kessatsu and, since 2026-09-09, her Bankai's core. The design
 * spec's §8.2 also named "D's dose kill", but §10.2 records that D was deliberately built with no
 * outright kill, so there is nothing there to exclude.
 */
public final class MiracleTransform {
	private MiracleTransform() {
	}

	/**
	 * One ability id per tier, not one per kit.
	 *
	 * <p>{@code AbilityRegistry.register} rejects a duplicate id, and both tiers of a kit are
	 * registered separately — so passing the kit id to both throws at game init, long after the unit
	 * tests are happy. Matches the {@code thunderbolt/schrift} shape T and D already use.
	 */
	public static final ResourceLocation SCHRIFT_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "miracle/schrift");
	public static final ResourceLocation VOLLSTANDIG_ID =
			ResourceLocation.fromNamespaceAndPath(BleachMod.MOD_ID, "miracle/vollstandig");

	/**
	 * Stacks earned from one hit.
	 *
	 * <p>Rounds <em>up</em>, so a graze is worth one stack rather than nothing. Chip damage is most
	 * of what a bruiser eats, and flooring it would make the identity fire only against the big hits
	 * it is least likely to survive.
	 *
	 * @param rateMult {@link BleachTuning#M_VOLL_STACK_MULT} in Vollständig, 1.0 in the Schrift
	 */
	public static int stacksFrom(double damage, double perDamage, double rateMult) {
		if (damage <= 0.0) {
			return 0;
		}
		return (int) Math.ceil(damage * Math.max(0.0, perDamage) * Math.max(0.0, rateMult));
	}

	/** Holds a stack count inside {@code 0 .. M_STACK_MAX}. */
	public static int clampStacks(int stacks) {
		return Math.min(Math.max(0, stacks), Math.max(0, BleachTuning.M_STACK_MAX));
	}

	/** Melee damage bonus as a fraction, for the existing {@code meleeDamageBonus()} hook. */
	public static double damageBonus(int stacks) {
		return clampStacks(stacks) * Math.max(0.0, BleachTuning.M_DMG_PER_STACK);
	}

	/** Bonus max health, which arrives as empty hearts — headroom, not a heal. */
	public static double bonusHp(int stacks) {
		return clampStacks(stacks) * Math.max(0.0, BleachTuning.M_HP_PER_STACK);
	}

	/**
	 * Damage reduction while in Vollständig, as a fraction.
	 *
	 * <p>Held strictly under 1.0 whatever the tuning says. This compounds with the Soul Level
	 * reduction rather than replacing it, so at the shipped numbers the worst case a player can
	 * reach is {@code 0.50 × 0.80 = 0.40} — the lowest damage-taken figure in the mod, and bounded
	 * on purpose. A reduction that could reach 1.0 would be an invulnerable bruiser, which is the
	 * §12.3 mistake wearing a different hat.
	 */
	public static double damageReduction(int stacks) {
		double raw = clampStacks(stacks) * Math.max(0.0, BleachTuning.M_DMG_REDUCTION_PER_STACK);
		return Math.min(0.95, raw);
	}

	/**
	 * Body size at this stack count, interpolated from 1.0 toward {@link BleachTuning#M_SCALE_MAX}.
	 *
	 * <p>Vanilla {@code Attributes.SCALE}, so no rendering work and no dependency. Never below 1.0:
	 * a cap misconfigured under one would otherwise shrink Gerard as he took punishment, which is
	 * the mechanic backwards.
	 */
	public static double scaleFor(int stacks) {
		int max = Math.max(1, BleachTuning.M_STACK_MAX);
		double t = clampStacks(stacks) / (double) max;
		double target = Math.max(1.0, BleachTuning.M_SCALE_MAX);
		return 1.0 + (target - 1.0) * t;
	}

	/**
	 * Whether the death save is available.
	 *
	 * @param alreadyUsed whether this Vollständig has already spent its one save. Reverting and
	 *        re-entering clears it, and that costs the full gate and pool.
	 */
	public static boolean canMiracle(int stacks, boolean alreadyUsed) {
		return !alreadyUsed && stacks >= BleachTuning.M_MIRACLE_MIN_STACKS;
	}

	// ================================================================================
	// Live state
	// ================================================================================

	/** Attribute keys. Removed-then-added on every change, the way SoulLevel.applyHealth is. */
	private static final ResourceLocation HP_MODIFIER_ID = BleachMod.id("miracle_stack_health");
	private static final ResourceLocation SCALE_MODIFIER_ID = BleachMod.id("miracle_stack_scale");
	private static final ResourceLocation DMG_MODIFIER_ID = BleachMod.id("miracle_stack_damage");

	/** Player UUID to current stacks. */
	private static final Map<UUID, Integer> STACKS = new ConcurrentHashMap<>();
	/** Player UUID to the server tick of the last stack gained, for the decay grace. */
	private static final Map<UUID, Integer> LAST_HIT_TICK = new ConcurrentHashMap<>();
	/** Player UUID to whether this Vollstaendig has spent its one save. */
	private static final Map<UUID, Boolean> MIRACLE_SPENT = new ConcurrentHashMap<>();
	/** Player UUID to the tick the Quincy Cross stops being exposed. */
	private static final Map<UUID, Integer> CORE_EXPOSED_UNTIL = new ConcurrentHashMap<>();
	/** Player UUID to the tick it starts — after the standing-up window, not before it. */
	private static final Map<UUID, Integer> CORE_EXPOSED_FROM = new ConcurrentHashMap<>();
	/** Player UUID to whether Godly Size has already fired this transformation. */
	private static final Map<UUID, Boolean> GODLY_SIZE_FIRED = new ConcurrentHashMap<>();
	/**
	 * Players currently having damage reflected off them.
	 *
	 * <p>Without this, two Gerards in melee reflect each other's reflections until the stack
	 * overflows. The flag is set for the duration of one reflected hit and cleared in a finally.
	 */
	private static final java.util.Set<UUID> REFLECTING = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Player UUID to the last payload sent, so the sync stays an edge rather than per-tick spam. */
	private static final Map<UUID, MiracleSyncPayload> LAST_SENT = new ConcurrentHashMap<>();

	public static int stacksOf(ServerPlayer player) {
		return STACKS.getOrDefault(player.getUUID(), 0);
	}

	/** Registers the death save. Called from {@code BleachMod}. */
	public static void register() {
		ServerLivingEntityEvents.ALLOW_DEATH.register(MiracleTransform::allowDeath);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(MiracleTransform::allowDamage);
	}

	/**
	 * The Miracle — the Schrift itself, rather than the side effect of it.
	 *
	 * <p>Canon: <i>"the more improbable a specific event is, the more likely The Miracle can make it
	 * occur"</i>. So the chance to shrug a hit off entirely rises as the odds worsen — health gone,
	 * and how many are on him. Returning {@code false} cancels the damage.
	 *
	 * <p>Never negates anything in {@code SPIRIT_MECHANIC}: same parity rule as the death save.
	 */
	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayer player)) {
			return true;
		}
		TransformAbility active = AbilityDispatcher.activeTransform(BleachAttachments.get(player));
		if (!(active instanceof Schrift) && !(active instanceof Vollstandig)) {
			return true;
		}
		if (source.is(BleachDamage.SPIRIT_MECHANIC)) {
			return true;
		}

		double hpFraction = player.getMaxHealth() <= 0.0f
				? 0.0 : player.getHealth() / player.getMaxHealth();
		double radius = BleachTuning.M_PROB_ENEMY_RADIUS;
		int nearby = player.serverLevel().getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(radius),
				e -> e != player && e.isAlive()).size();

		double chance = MiracleAbilities.miracleChance(hpFraction, nearby);
		if (player.getRandom().nextDouble() >= chance) {
			return true;
		}

		ServerLevel level = player.serverLevel();
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.2f, 1.9f);
		level.sendParticles(new PressureParticleOptions(BleachTuning.KIT_MIRACLE_PARTICLE_COLOR, 1.3f),
				player.getX(), player.getY() + 1.0, player.getZ(), 25, 0.4, 0.7, 0.4, 0.02);
		return false;
	}

	/** Damage multiplier against Gerard, read by {@code DamageScaling}. See {@link #allowDeath}. */
	public static double coreExposureMultiplier(ServerPlayer player) {
		UUID id = player.getUUID();
		Integer until = CORE_EXPOSED_UNTIL.get(id);
		Integer from = CORE_EXPOSED_FROM.get(id);
		if (until == null || from == null) {
			return 1.0;
		}
		if (player.tickCount < from || player.tickCount >= until) {
			return 1.0;
		}
		return Math.max(1.0, BleachTuning.M_CORE_DAMAGE_MULT);
	}

	/**
	 * The miracle itself: lethal damage survived at 1 HP, once per Vollstaendig.
	 *
	 * <p>Returning {@code false} cancels the death. Everything above it is a reason not to.
	 */
	private static boolean allowDeath(LivingEntity entity, DamageSource source, float damage) {
		if (!(entity instanceof ServerPlayer player)) {
			return true;
		}

		SpiritualData data = BleachAttachments.get(player);
		// Asking the dispatcher which transform is live, rather than comparing a stored kit id,
		// means this cannot answer yes for a Gerard who is in the Schrift or in no tier at all.
		if (!(AbilityDispatcher.activeTransform(data) instanceof Vollstandig)) {
			return true;
		}

		// Mechanics are not damage. This one tag check covers Sui-Feng's Nigeki Kessatsu and, since
		// 2026-09-09, her Bankai's core. Letting a Quincy shrug those off deletes the character.
		if (source.is(BleachDamage.SPIRIT_MECHANIC)) {
			return true;
		}

		UUID id = player.getUUID();
		int stacks = STACKS.getOrDefault(id, 0);
		if (!canMiracle(stacks, MIRACLE_SPENT.getOrDefault(id, false))) {
			return true;
		}

		MIRACLE_SPENT.put(id, true);
		setStacks(player, 0);
		player.setHealth(1.0f);

		// Standing up. Surviving at one health point and dying to the next swing is a delay, not a
		// miracle — canon has him regenerate "stronger than before", and vanilla's own totem grants
		// Regeneration and Absorption for the same reason.
		int invuln = Math.max(0, BleachTuning.M_SAVE_INVULN_TICKS);
		player.invulnerableTime = invuln;
		player.addEffect(new MobEffectInstance(MobEffects.REGENERATION,
				Math.max(1, BleachTuning.M_SAVE_REGEN_TICKS),
				Math.max(0, BleachTuning.M_SAVE_REGEN_AMP), false, true, true));
		player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION,
				Math.max(1, BleachTuning.M_SAVE_ABSORB_TICKS),
				Math.max(0, BleachTuning.M_SAVE_ABSORB_AMP), false, true, true));

		// The Quincy Cross is what keeps him alive, and cheating death leaves it showing. Starts
		// only once the invulnerability has closed: the counterplay is meant to land on a Gerard who
		// is back on his feet, not on one who has not moved yet.
		CORE_EXPOSED_UNTIL.put(id,
				player.tickCount + invuln + Math.max(0, BleachTuning.M_CORE_EXPOSED_TICKS));
		CORE_EXPOSED_FROM.put(id, player.tickCount + invuln);
		data.exertion += BleachTuning.M_MIRACLE_EXERTION;
		SpiritualTicker.sync(player, true);

		ServerLevel level = player.serverLevel();
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.5f, 0.7f);
		level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
				player.getX(), player.getY() + 1.0, player.getZ(), 120, 0.6, 0.9, 0.6, 0.4);
		return false;
	}

	// ================================================================================
	// Stack bookkeeping
	// ================================================================================

	/**
	 * Hoffnung's Reflection — hope becoming despair.
	 *
	 * <p>Canon: damaging Hoffnung injures whoever damaged it, disproportionately. Melee only, since
	 * the reflection is a property of the blade being struck rather than of Gerard being hurt.
	 *
	 * <p>The {@link #REFLECTING} guard is load-bearing: without it, two Gerards in melee reflect
	 * each other's reflections until the stack overflows, and the reflected hit would also feed the
	 * victim's own stacks.
	 */
	private static void reflect(ServerPlayer player, LivingEntity attacker, float damage) {
		if (damage <= 0.0f || !attacker.isAlive()) {
			return;
		}
		UUID id = player.getUUID();
		if (!REFLECTING.add(id)) {
			return;
		}
		try {
			float back = (float) (damage * MiracleAbilities.reflectFraction(
					STACKS.getOrDefault(id, 0)));
			if (back <= 0.0f) {
				return;
			}
			attacker.invulnerableTime = 0;
			attacker.hurt(BleachDamage.source(player.serverLevel(),
					BleachDamage.SPIRIT_PRESSURE, player), back);

			ServerLevel level = player.serverLevel();
			level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
					SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.PLAYERS, 1.0f, 0.7f);
			level.sendParticles(
					new PressureParticleOptions(BleachTuning.KIT_MIRACLE_PARTICLE_COLOR, 1.2f),
					attacker.getX(), attacker.getY() + attacker.getBbHeight() * 0.6, attacker.getZ(),
					18, 0.3, 0.3, 0.3, 0.04);
		} finally {
			REFLECTING.remove(id);
		}
	}

	private static void gain(ServerPlayer player, float damage, double rateMult) {
		UUID id = player.getUUID();
		int gained = stacksFrom(damage, BleachTuning.M_STACK_PER_DAMAGE, rateMult);
		if (gained <= 0) {
			return;
		}
		LAST_HIT_TICK.put(id, player.tickCount);
		setStacks(player, STACKS.getOrDefault(id, 0) + gained);
	}

	/** Decay: nothing for the grace period, then one stack every M_STACK_DECAY_INTERVAL. */
	private static void tickDecay(ServerPlayer player) {
		UUID id = player.getUUID();
		int stacks = STACKS.getOrDefault(id, 0);
		if (stacks <= 0) {
			return;
		}
		int since = player.tickCount - LAST_HIT_TICK.getOrDefault(id, player.tickCount);
		if (since < BleachTuning.M_STACK_GRACE_TICKS) {
			return;
		}
		int interval = Math.max(1, BleachTuning.M_STACK_DECAY_INTERVAL);
		if ((since - BleachTuning.M_STACK_GRACE_TICKS) % interval == 0) {
			setStacks(player, stacks - 1);
		}
	}

	/**
	 * Per-tick sync for the things no stack change announces.
	 *
	 * <p>The exposed-core window closes on a clock rather than on an event, so nothing else would
	 * ever tell the client it had ended. {@link #sync} is an edge, so calling it every tick costs a
	 * map comparison and sends nothing on the ticks where the answer has not moved.
	 */
	private static void tickSync(ServerPlayer player) {
		sync(player);
	}

	/**
	 * The single place stacks change, so the two attribute modifiers can never drift from the count
	 * they are derived from.
	 */
	/**
	 * Push the number to the player it belongs to, and only when it has actually changed.
	 *
	 * <p>Sent to Gerard alone: an opponent who knew the exact count would know precisely when Godly
	 * Size is about to fire, and the tension of the kit is that they can only see him growing.
	 */
	private static void sync(ServerPlayer player) {
		UUID id = player.getUUID();
		MiracleSyncPayload payload = new MiracleSyncPayload(
				STACKS.getOrDefault(id, 0),
				Math.max(0, BleachTuning.M_STACK_MAX),
				GODLY_SIZE_FIRED.getOrDefault(id, false),
				coreExposureMultiplier(player) > 1.0);

		if (payload.equals(LAST_SENT.get(id))) {
			return;
		}
		LAST_SENT.put(id, payload);
		ServerPlayNetworking.send(player, payload);
	}

	private static void setStacks(ServerPlayer player, int raw) {
		int stacks = clampStacks(raw);
		STACKS.put(player.getUUID(), stacks);
		applyStackHealth(player, stacks);
		applyStackScale(player, stacks);
		applyStackDamage(player, stacks);

		if (stacks >= Math.max(1, BleachTuning.M_STACK_MAX)) {
			godlySize(player);
		}
		sync(player);
	}

	/**
	 * Godly Size — the threshold, not the slope.
	 *
	 * <p>Canon: <i>"if Gerard suffers massive harm, he will grow gigantic... with all his injuries
	 * completely healed."</i> Stacks grow him smoothly only as far as
	 * {@link BleachTuning#M_SCALE_SOFT}; reaching the ceiling is a transformation with a heal, a
	 * roar and a jump to {@link BleachTuning#M_SCALE_MAX}.
	 *
	 * <p>Once per transformation. Reverting clears it, and that costs the full gate and pool.
	 */
	private static void godlySize(ServerPlayer player) {
		UUID id = player.getUUID();
		if (GODLY_SIZE_FIRED.getOrDefault(id, false)) {
			return;
		}
		GODLY_SIZE_FIRED.put(id, true);

		player.setHealth(player.getMaxHealth());
		applyStackScale(player, BleachTuning.M_STACK_MAX);

		ServerLevel level = player.serverLevel();
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ENDER_DRAGON_GROWL, SoundSource.PLAYERS, 2.0f, 0.5f);
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0f, 0.5f);
		level.sendParticles(new PressureParticleOptions(BleachTuning.KIT_MIRACLE_PARTICLE_COLOR, 2.2f),
				player.getX(), player.getY() + 1.2, player.getZ(), 160, 1.2, 1.6, 1.2, 0.15);
	}

	/**
	 * The melee bonus, as a vanilla {@code ATTACK_DAMAGE} modifier rather than through
	 * {@code TransformAbility.meleeDamageBonus()}.
	 *
	 * <p>That hook takes no player and so cannot answer a per-player stack count. An attribute can,
	 * it uses the same remove-then-add idempotence as the other two, and it needs no shared code.
	 * {@code ADD_MULTIPLIED_TOTAL} makes it the fraction the design asked for rather than a flat
	 * addition, so a better weapon still matters.
	 */
	private static void applyStackDamage(ServerPlayer player, int stacks) {
		AttributeInstance attribute = player.getAttribute(Attributes.ATTACK_DAMAGE);
		if (attribute == null) {
			return;
		}
		attribute.removeModifier(DMG_MODIFIER_ID);
		double bonus = damageBonus(stacks);
		if (bonus > 0.0) {
			attribute.addPermanentModifier(new AttributeModifier(
					DMG_MODIFIER_ID, bonus, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	/**
	 * Damage-taken multiplier for this player, read by {@code DamageScaling}.
	 *
	 * <p>Only Vollstaendig grants it; the Schrift builds stacks but takes full damage. Returns 1.0
	 * for everyone who is not a transformed Gerard, so the call site needs no kit conditional.
	 */
	public static double damageTakenMultiplier(ServerPlayer player) {
		SpiritualData data = BleachAttachments.get(player);
		if (!(AbilityDispatcher.activeTransform(data) instanceof Vollstandig)) {
			return 1.0;
		}
		return 1.0 - damageReduction(STACKS.getOrDefault(player.getUUID(), 0));
	}

	private static void applyStackHealth(ServerPlayer player, int stacks) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
		if (attribute == null) {
			return;
		}
		attribute.removeModifier(HP_MODIFIER_ID);
		double bonus = bonusHp(stacks);
		if (bonus > 0.0) {
			attribute.addPermanentModifier(new AttributeModifier(
					HP_MODIFIER_ID, bonus, AttributeModifier.Operation.ADD_VALUE));
		}
		// The bonus is headroom, never a heal — the miracle is that you keep standing, not that your
		// wounds close. Only the shrink direction needs correcting.
		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
	}

	private static void applyStackScale(ServerPlayer player, int stacks) {
		AttributeInstance attribute = player.getAttribute(Attributes.SCALE);
		if (attribute == null) {
			return;
		}
		attribute.removeModifier(SCALE_MODIFIER_ID);
		// Stacks alone only reach the soft size; the rest is Godly Size's to grant.
		double scale = GODLY_SIZE_FIRED.getOrDefault(player.getUUID(), false)
				? Math.max(1.0, BleachTuning.M_SCALE_MAX)
				: Math.min(Math.max(1.0, BleachTuning.M_SCALE_SOFT), scaleFor(stacks));
		if (scale > 1.0) {
			attribute.addPermanentModifier(new AttributeModifier(
					SCALE_MODIFIER_ID, scale - 1.0, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	/** Drops every trace: stacks, both modifiers, the grace clock and the spent flag. */
	private static void clear(ServerPlayer player) {
		UUID id = player.getUUID();
		setStacks(player, 0);
		STACKS.remove(id);
		LAST_HIT_TICK.remove(id);
		MIRACLE_SPENT.remove(id);
		CORE_EXPOSED_UNTIL.remove(id);
		CORE_EXPOSED_FROM.remove(id);
		GODLY_SIZE_FIRED.remove(id);
		REFLECTING.remove(id);
		LAST_SENT.remove(id);
		// The falling edge, or the bar hangs on screen after he drops out.
		ServerPlayNetworking.send(player, MiracleSyncPayload.cleared());
	}

	// ================================================================================
	// Tiers
	// ================================================================================

	public static TransformAbility schrift() {
		return new Schrift();
	}

	public static TransformAbility vollstandig() {
		return new Vollstandig();
	}

	/** Release 1 — the Schrift. The stance that builds. */
	private static final class Schrift extends QuincyTransform.Tier1 {
		private Schrift() {
			super(SCHRIFT_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
			setStacks(player, 0);
			LAST_HIT_TICK.put(player.getUUID(), player.tickCount);
			sync(player);
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
			tickDecay(player);
			tickSync(player);
		}

		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
			clear(player);
		}

		@Override
		public void onDamageTaken(ServerPlayer player, LivingEntity attacker, float damage) {
			gain(player, damage, 1.0);
			reflect(player, attacker, damage);
		}

		@Override
		public ResourceLocation kitAbilityId() {
			return HoffnungsWrath.ID;
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_MIRACLE_PARTICLE_COLOR;
		}
	}

	/** Release 2 — Vollstaendig. The miracle itself. */
	private static final class Vollstandig extends QuincyTransform.Tier2 {
		private Vollstandig() {
			super(VOLLSTANDIG_ID);
		}

		@Override
		protected void onTierEnter(ServerPlayer player, SpiritualData data) {
			// Stacks carry across the tier change. The Schrift is where they were earned, and
			// wiping them on entry would make the burst worthless exactly when it should be biggest.
			int stacks = STACKS.getOrDefault(player.getUUID(), 0);
			MIRACLE_SPENT.put(player.getUUID(), false);
			burst(player, stacks);
		}

		@Override
		protected void onTierTick(ServerPlayer player, SpiritualData data) {
			tickDecay(player);
			tickSync(player);
		}

		@Override
		protected void onTierRevert(ServerPlayer player, SpiritualData data) {
			clear(player);
		}

		@Override
		public void onDamageTaken(ServerPlayer player, LivingEntity attacker, float damage) {
			gain(player, damage, BleachTuning.M_VOLL_STACK_MULT);
			reflect(player, attacker, damage);
		}

		@Override
		public ResourceLocation kitAbilityId() {
			return HeiligPfeil.ID;
		}

		@Override
		protected int wingColour() {
			return BleachTuning.KIT_MIRACLE_PARTICLE_COLOR;
		}
	}

	/** Entry payoff: a heal and a shove, both scaled by what you walked in carrying. */
	private static void burst(ServerPlayer player, int stacks) {
		ServerLevel level = player.serverLevel();
		float heal = (float) (clampStacks(stacks) * Math.max(0.0, BleachTuning.M_BURST_HEAL_PER_STACK));
		if (heal > 0.0f) {
			player.heal(heal);
		}

		double radius = BleachTuning.M_BURST_RADIUS;
		double kb = clampStacks(stacks) * Math.max(0.0, BleachTuning.M_BURST_KB_PER_STACK);
		if (kb > 0.0) {
			AABB box = player.getBoundingBox().inflate(radius);
			for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box,
					e -> e != player && e.isAlive() && e.distanceToSqr(player) <= radius * radius)) {
				Vec3 away = target.position().subtract(player.position());
				if (away.lengthSqr() < 1.0e-4) {
					continue;
				}
				away = away.normalize();
				target.push(away.x * kb, 0.35 * kb, away.z * kb);
				target.hurtMarked = true;
			}
		}

		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0f, 0.6f);
		level.sendParticles(new PressureParticleOptions(BleachTuning.KIT_MIRACLE_PARTICLE_COLOR, 1.6f),
				player.getX(), player.getY() + 1.0, player.getZ(), 90,
				radius * 0.4, 1.0, radius * 0.4, 0.05);
	}
}
