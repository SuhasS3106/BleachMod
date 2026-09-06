package com.bleach.mod.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches {@code Mob#targetSelector}, which is {@code protected}.
 *
 * <p>Needed so Aizen's illusions can have their vanilla targeting stripped at spawn: an illusion
 * must pursue its own victim and nobody else, and the only way to guarantee that is to take away
 * the goal that re-picks a target rather than to keep overwriting its choice afterwards.
 */
@Mixin(Mob.class)
public interface MobGoalsAccessor {

	@Accessor("targetSelector")
	GoalSelector bleach$targetSelector();
}
