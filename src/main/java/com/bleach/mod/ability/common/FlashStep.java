package com.bleach.mod.ability.common;

import com.bleach.mod.ability.Ability;
import com.bleach.mod.ability.AbilityCooldowns;
import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;
import com.bleach.mod.util.BlockQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The universal blink · PRD §4 · {@code BALANCE.md} §G.
 *
 * <p>Available to every player from Soul Level 1, drawn or sheathed — it is raw spiritual pressure,
 * not a technique of the blade, so {@link #requiresDrawnSword()} is false.
 *
 * <p>Range scales on <b>both</b> Soul Level and <em>current</em> SP: the ceiling rises with mastery,
 * the reach right now depends on what is left in the tank. That second term is what makes the
 * ability read as a resource rather than a cooldown — a player at 20% SP visibly cannot escape as
 * far as one at full, without any extra rule to explain.
 *
 * <p>Two branches, decided by the raycast:
 *
 * <ul>
 *   <li><b>Ground</b> — anything the ray hits, and also a miss while looking level or downward,
 *       whose destination is simply the ray end. Teleport {@link BleachTuning#FS_STOP_SHORT} blocks
 *       short of that point, then validate; a blocked destination walks back toward the eye rather
 *       than clipping into the wall.</li>
 *   <li><b>Sky</b> — a genuine miss with the look pitch above the horizon. With
 *       {@link BleachTuning#FS_SKY_TELEPORT} set, which is the default, there is no separate branch
 *       at all: the step teleports to the ray end exactly as it does on the ground, keeps a little
 *       forward momentum and lands under Slow Falling. The old impulse branch is still here behind
 *       that flag, and is kept only because the shape is occasionally what a config wants — it is
 *       measurably slower to travel the same distance, because an impulse has to fight drag and a
 *       teleport does not.</li>
 * </ul>
 *
 * <p>The dispatcher has already spent the SP and started the cooldown by the time
 * {@link #onActivate} runs, so the one path that can fail — every candidate destination blocked —
 * {@linkplain #refund refunds both}.
 */
public final class FlashStep implements Ability {

	@Override
	public ResourceLocation id() {
		return AbilityRegistry.FLASH_STEP;
	}

	/** PRD §3.2: pressure, not a technique. Usable sheathed, and before a zanpakutō is ever chosen. */
	@Override
	public boolean requiresDrawnSword() {
		return false;
	}

	@Override
	public double spCost(SpiritualData data) {
		return data.maxSp() * BleachTuning.FS_COST_PCT;
	}

	@Override
	public int cooldownTicks(SpiritualData data) {
		return (int) Math.round(BleachTuning.FS_COOLDOWN_TICKS * cooldownMult(data));
	}

	// --- Derived values ----------------------------------------------------------------

	/**
	 * {@code BALANCE.md} §G. SL 1 empty → 6.0 blocks · SL 1 full → 18.0 · SL 20 full → 23.7 ·
	 * Sui-Feng SL 20 full → 35.6.
	 *
	 * <p>Public because {@code /bleach fs} reports it: the acceptance test is "range visibly shrinks
	 * as SP drains", and "visibly" is a great deal easier to check against a number.
	 */
	public static double range(SpiritualData data) {
		double spFraction = data.maxSp() <= 0.0 ? 0.0 : data.sp / data.maxSp();
		return (BleachTuning.FS_RANGE_BASE
				+ BleachTuning.FS_RANGE_PER_LEVEL * (data.soulLevel - 1)
				+ BleachTuning.FS_RANGE_SP_TERM * spFraction) * rangeMult(data);
	}

	/**
	 * Kit multipliers are identity until a zanpakutō has been chosen in Phase 4 — Flash Step is
	 * usable from the first tick of a new world, so "no kit" has to mean unmodified rather than
	 * unavailable.
	 */
	private static double rangeMult(SpiritualData data) {
		Kit kit = AbilityRegistry.kitFor(data);
		return kit == null ? 1.0 : kit.flashStepRangeMult();
	}

	private static double cooldownMult(SpiritualData data) {
		Kit kit = AbilityRegistry.kitFor(data);
		return kit == null ? 1.0 : kit.flashStepCooldownMult();
	}

	private static int particleColor(SpiritualData data) {
		Kit kit = AbilityRegistry.kitFor(data);
		return kit == null ? BleachTuning.HUD_COLOR_BASE : kit.particleColor();
	}

	// --- Activation --------------------------------------------------------------------

	@Override
	public void onActivate(ServerPlayer player, SpiritualData data) {
		ServerLevel level = player.serverLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double range = range(data);

		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(range)),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

		// A negative pitch is above the horizon. A miss while looking level or down is not open sky
		// — it is a long drop, a distant hillside, or the edge of the loaded world — so it falls
		// through to the ground branch, where hit.getLocation() is the ray end and the clearance
		// walk-back decides whether there is anywhere to land.
		if (hit.getType() == HitResult.Type.MISS && player.getXRot() < 0.0f && !BleachTuning.FS_SKY_TELEPORT) {
			skyStep(player, data, level, look, range);
			return;
		}

		groundStep(player, data, level, eye, look, hit.getLocation());
	}

	/**
	 * Teleport short of the hit point, or as close to it as fits.
	 *
	 * <p>The ray runs from the <em>eye</em>, so every candidate along it is an eye position and the
	 * feet offset has to come back off before the bounding box is tested. Getting that wrong is the
	 * classic version of this bug: the eye clears the ceiling, the body does not, and the player
	 * ends up suffocating inside the floor above.
	 */
	private void groundStep(ServerPlayer player, SpiritualData data, ServerLevel level,
			Vec3 eye, Vec3 look, Vec3 aim) {
		Vec3 from = player.position();
		Vec3 feetOffset = from.subtract(eye);
		double reach = eye.distanceTo(aim) - BleachTuning.FS_STOP_SHORT;

		for (double d = reach; d > 0.0; d -= BleachTuning.FS_CLEARANCE_STEP) {
			Vec3 dest = eye.add(look.scale(d)).add(feetOffset);
			if (!fits(player, level, from, dest)) {
				continue;
			}

			player.teleportTo(dest.x, dest.y, dest.z);
			player.resetFallDistance();

			// A step through the air is not a fall. Held until the player lands · see SpiritualData.
			data.flashStepFallGrace = BleachTuning.FS_NO_FALL_DAMAGE;

			// Teleporting leaves the client's own velocity untouched, so without this the player
			// arrives already moving in whatever direction they were before. A step that ends in
			// open air keeps a little of the look vector instead of a dead stop, so chaining steps
			// through the sky flows rather than stalling at every hop.
			// Not player.onGround(): teleportTo does not re-run the ground check, so it still
			// reports where the player stepped from rather than where they arrived.
			boolean airborne = !supported(player, level, from, dest);
			player.setDeltaMovement(airborne
					? look.scale(BleachTuning.FS_SKY_CARRY_MOMENTUM)
					: Vec3.ZERO);
			player.connection.send(new ClientboundSetEntityMotionPacket(player));

			if (airborne && BleachTuning.FS_SKY_SLOW_FALLING_TICKS > 0) {
				// The landing is never what kills you · BALANCE.md §G.
				player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING,
						BleachTuning.FS_SKY_SLOW_FALLING_TICKS, 0, true, false, true));
			}

			burst(level, data, from);
			blinkSound(level, from);

			// A beat late, on purpose. The particle packet and the teleport packet leave in the same
			// tick, but the client applies the position update on its own next frame — so an arrival
			// burst sent now blooms at the destination while the player is visibly still standing at
			// the origin, and the step reads as the effect arriving before you do.
			Vec3 arrival = dest;
			BlockQueue.submitDelayed(BleachTuning.FS_ARRIVAL_BURST_DELAY_TICKS, () -> {
				burst(level, data, arrival);
				blinkSound(level, arrival);
			});
			return;
		}

		// Nose against a wall with no room to arrive anywhere along the ray. Nothing happened, so
		// nothing is charged.
		refund(player, data);
	}

	/**
	 * Nothing to arrive at, so the sky branch is an impulse instead of a teleport: a shove along the
	 * look vector, Levitation to hold the arc up for a moment, and Slow Falling to make sure the
	 * landing is never the thing that kills you. Both durations are capped in {@code BALANCE.md} —
	 * this is a hop, not flight.
	 */
	private void skyStep(ServerPlayer player, SpiritualData data, ServerLevel level, Vec3 look,
			double range) {
		// Levitation is off by default and the reason this branch used to feel like Feather Falling:
		// the effect overwrites vertical velocity with a fixed crawl every tick, so it ate the
		// impulse it was supposed to hold up. Left tunable rather than deleted.
		if (BleachTuning.FS_SKY_LEVITATION_TICKS > 0) {
			player.addEffect(new MobEffectInstance(MobEffects.LEVITATION,
					BleachTuning.FS_SKY_LEVITATION_TICKS, 0, true, false, true));
		}
		if (BleachTuning.FS_SKY_SLOW_FALLING_TICKS > 0) {
			player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING,
					BleachTuning.FS_SKY_SLOW_FALLING_TICKS, 0, true, false, true));
		}

		double impulse = range * BleachTuning.FS_SKY_RANGE_PCT * BleachTuning.FS_SKY_IMPULSE_PER_BLOCK;
		// Set rather than add: a step is a step, and adding to whatever the fall had already built
		// up made the same keypress travel a different distance every time.
		player.setDeltaMovement(look.scale(impulse));
		player.resetFallDistance();
		data.flashStepFallGrace = BleachTuning.FS_NO_FALL_DAMAGE;
		// push() only mutates the server-side delta; the client is authoritative over its own
		// movement until it is told otherwise.
		player.hurtMarked = true;
		player.connection.send(new ClientboundSetEntityMotionPacket(player));

		burst(level, data, player.position());
		blinkSound(level, player.position());
	}

	/** Whether there is anything under {@code dest} to stand on. */
	private static boolean supported(ServerPlayer player, ServerLevel level, Vec3 from, Vec3 dest) {
		AABB feet = player.getBoundingBox().move(dest.subtract(from))
				.move(0.0, -BleachTuning.FS_GROUND_PROBE, 0.0);
		return !level.noCollision(player, feet);
	}

	/**
	 * Whether the player's own bounding box, moved to {@code dest}, is free of blocks and other
	 * entities. Checking the real AABB rather than "the two blocks at the target" is what makes
	 * slabs, stairs, fences and a boat parked in the landing zone all behave.
	 */
	private static boolean fits(ServerPlayer player, ServerLevel level, Vec3 from, Vec3 dest) {
		if (!level.isLoaded(BlockPos.containing(dest))) {
			return false;
		}
		AABB box = player.getBoundingBox().move(dest.subtract(from));
		return level.noCollision(player, box);
	}

	// --- Presentation ------------------------------------------------------------------

	/**
	 * Tinted per kit, which is the whole visual identity of the ability. Phase 3 shipped this as
	 * vanilla redstone dust because the custom particle did not exist yet; Phase 8 swapped in
	 * {@link PressureParticleOptions}, and the colour source did not change.
	 */
	private static void burst(ServerLevel level, SpiritualData data, Vec3 at) {
		PressureParticleOptions pressure = new PressureParticleOptions(
				particleColor(data), (float) BleachTuning.FS_PARTICLE_SCALE);

		double spread = BleachTuning.FS_PARTICLE_SPREAD;
		level.sendParticles(pressure, at.x, at.y + spread, at.z,
				BleachTuning.FS_PARTICLE_COUNT, spread, spread, spread, 0.0);
	}

	private static void blinkSound(ServerLevel level, Vec3 at) {
		level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS,
				(float) BleachTuning.FS_SOUND_VOLUME, (float) BleachTuning.FS_SOUND_PITCH);
	}

	// --- Failure -----------------------------------------------------------------------

	/**
	 * Give back exactly what the dispatcher took. The regen pause {@code spend} set is deliberately
	 * left in place — it is 3 seconds of slower refill, it costs nothing the player can see, and
	 * clearing it would mean {@link SpiritualData} growing an un-pause method whose only caller is
	 * this one failure path.
	 */
	private void refund(ServerPlayer player, SpiritualData data) {
		data.sp = Math.min(data.maxSp(), data.sp + spCost(data));
		AbilityCooldowns.clear(player, id());
	}
}
