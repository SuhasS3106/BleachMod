package com.bleach.mod.entity;

import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Condensed reishi, fired from a Heilig Bogen · design §4.3.
 *
 * <p><b>It renders as nothing.</b> What you see is a trail of the existing pressure particle tinted
 * by the shooter's kit colour — zero new assets, and it reads better as spirit energy than a wooden
 * shaft would. A no-op renderer ({@link com.bleach.mod.client.InvisibleEntityRenderer}) is registered
 * client-side so the game does not complain.
 *
 * <p>It carries the shooter's kit id so a Schrift can react to its own arrows landing, which is what
 * keeps kit-specific behaviour out of this class entirely.
 *
 * <p>Damage is built through {@link BleachDamage#source} with {@link BleachDamage#SPIRIT_PRESSURE}
 * and the shooter ({@link #getOwner()}) as the attacker — the same call every ability projectile in
 * the mod already uses (see {@code SuiFengTransform}, {@code GinTransform}, {@code YamamotoConeDestructionTask}).
 * That single-entity {@code DamageSource} constructor sets both the direct entity and the causing
 * entity to the shooter, so {@code KillAttribution.attackerOf} resolves the shooter without needing
 * to know this class exists. A null owner (arrow survived a reload with no resolvable shooter, or
 * was otherwise spawned without one) simply produces a damage source with no attacker: the hit still
 * lands, but nothing can be credited to anyone — the same behaviour every other hand-built source in
 * this codebase already has for a null attacker.
 */
public class ReishiArrow extends AbstractArrow {
	@Nullable
	private ResourceLocation kitId;

	public ReishiArrow(EntityType<? extends ReishiArrow> type, Level level) {
		super(type, level);
		this.pickup = Pickup.DISALLOWED;
	}

	public ReishiArrow(LivingEntity shooter, Level level, @Nullable ResourceLocation kitId) {
		super(BleachEntities.REISHI_ARROW, shooter, level, ItemStack.EMPTY, null);
		this.pickup = Pickup.DISALLOWED;
		this.kitId = kitId;
	}

	@Nullable
	public ResourceLocation kitId() {
		return kitId;
	}

	@Override
	protected ItemStack getDefaultPickupItem() {
		// Never used — pickup is DISALLOWED, see the constructors above — but the supertype demands a
		// non-null stack. A reishi arrow is condensed spirit particles; it must never leave a real
		// item on the ground.
		return new ItemStack(Items.ARROW);
	}

	@Override
	public void tick() {
		super.tick();
		if (level() instanceof ServerLevel server && !inGround) {
			server.sendParticles(new PressureParticleOptions(colour(), (float) BleachTuning.REISHI_ARROW_PARTICLE_SCALE),
					getX(), getY(), getZ(), 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	@Override
	protected void onHitEntity(EntityHitResult result) {
		if (!(level() instanceof ServerLevel) || !(result.getEntity() instanceof LivingEntity target)) {
			super.onHitEntity(result);
			return;
		}

		float damage = (float) getBaseDamage();
		target.hurt(BleachDamage.source(level(), BleachDamage.SPIRIT_PRESSURE, getOwner()), damage);
		discard();
	}

	private int colour() {
		Kit kit = kitId == null ? null : AbilityRegistry.kit(kitId.toString());
		return kit == null ? BleachTuning.HUD_COLOR_BASE : kit.particleColor();
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		if (kitId != null) {
			tag.putString("KitId", kitId.toString());
		}
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		kitId = tag.contains("KitId") ? ResourceLocation.tryParse(tag.getString("KitId")) : null;
	}
}
