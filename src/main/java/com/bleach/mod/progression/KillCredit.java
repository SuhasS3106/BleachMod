package com.bleach.mod.progression;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * Who is allowed to be paid for killing this entity · PRD §2.1.
 *
 * <p>Two fields, and the second one only ever goes one way. {@link #tainted} is set the moment any
 * damage arrives that is not from {@link #soleDamager} and <b>never clears</b> — that permanence is
 * what kills drop farms, grinder splash kills and pet-tanking in a single boolean, without a damage
 * ledger, a share table or a timer.
 *
 * <p>Attached to every {@link net.minecraft.world.entity.LivingEntity} lazily and
 * <b>non-persistently</b>. An entity that unloads mid-fight comes back with no credit recorded and
 * therefore pays nothing, which is the safe direction to fail: the alternative is an attribution
 * that survives a restart and pays out for a kill nobody watched.
 */
public final class KillCredit {
	/** The first player to damage this entity, or null if nothing has yet. */
	@Nullable
	public UUID soleDamager;

	/** Once true, this entity's SPX is permanently zero. */
	public boolean tainted;
}
