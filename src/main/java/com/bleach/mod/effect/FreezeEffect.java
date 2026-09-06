package com.bleach.mod.effect;

import java.util.function.BiConsumer;

import com.bleach.mod.BleachMod;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Rukia Kuchiki's freeze debuff · PRD §6.4 · {@code BALANCE.md} §J.4.
 *
 * <p>Inflicts heavy slowness ({@link BleachTuning#RUKIA_FREEZE_SPEED_MULT}) via an attribute modifier,
 * emits snowflake particles around afflicted entities, and deals periodic freeze damage
 * ({@link BleachTuning#RUKIA_FREEZE_DMG_PER_SEC}) every second.
 */
public final class FreezeEffect extends MobEffect {

	private static final ResourceLocation MOVEMENT_MODIFIER = BleachMod.id("freeze_movement_speed");
	private static final int TICK_INTERVAL = 10;
	private static final int DAMAGE_INTERVAL = 20;

	public FreezeEffect() {
		super(MobEffectCategory.HARMFUL, BleachTuning.RUKIA_FREEZE_EFFECT_COLOR);
	}

	@Override
	public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
		return duration % TICK_INTERVAL == 0;
	}

	@Override
	public boolean applyEffectTick(LivingEntity entity, int amplifier) {
		if (entity.level() instanceof ServerLevel serverLevel) {
			serverLevel.sendParticles(ParticleTypes.SNOWFLAKE,
					entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
					2, 0.25, 0.25, 0.25, 0.01);
		}

		if (entity.tickCount % DAMAGE_INTERVAL == 0) {
			// Deals BleachDamage.SPIRIT_PRESSURE so freeze damage respects armor rating and damages armor durability.
			entity.hurt(com.bleach.mod.damage.BleachDamage.source(entity.level(),
					com.bleach.mod.damage.BleachDamage.SPIRIT_PRESSURE, null),
					(float) BleachTuning.RUKIA_FREEZE_DMG_PER_SEC);
		}

		return true;
	}

	// --- Attribute modifiers ------------------------------------------------------------

	@Override
	public void createModifiers(int amplifier, BiConsumer<Holder<Attribute>, AttributeModifier> output) {
		output.accept(Attributes.MOVEMENT_SPEED,
				new AttributeModifier(MOVEMENT_MODIFIER, BleachTuning.RUKIA_FREEZE_SPEED_MULT,
						AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	@Override
	public void addAttributeModifiers(AttributeMap attributes, int amplifier) {
		createModifiers(amplifier, (attribute, modifier) -> {
			AttributeInstance instance = attributes.getInstance(attribute);
			if (instance != null) {
				instance.removeModifier(modifier.id());
				instance.addPermanentModifier(modifier);
			}
		});
	}

	@Override
	public void removeAttributeModifiers(AttributeMap attributes) {
		createModifiers(0, (attribute, modifier) -> {
			AttributeInstance instance = attributes.getInstance(attribute);
			if (instance != null) {
				instance.removeModifier(modifier.id());
			}
		});
	}
}
