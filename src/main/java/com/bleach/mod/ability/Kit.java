package com.bleach.mod.ability;

import java.util.Objects;

import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.race.Race;
import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

/**
 * One playable character. PRD §3.3: adding a sixth is one {@code Kit} entry plus its two abilities,
 * and no other file changes.
 *
 * @param id                    registry key; also what {@link SpiritualData#characterId} stores
 * @param displayName           shown in the Asauchi selection menu and the stats screen
 * @param shikai                never null
 * @param bankai                never null
 * @param flashStepRangeMult    kit multiplier on Flash Step range · {@code BALANCE.md} §I
 * @param flashStepCooldownMult kit multiplier on Flash Step cooldown · {@code BALANCE.md} §I
 * @param particleColor         packed RGB, used by Flash Step and Flex particles
 * @param race                  the race this kit belongs to; never null · Task 6
 */
public record Kit(ResourceLocation id, String displayName,
		TransformAbility shikai, TransformAbility bankai,
		double flashStepRangeMult, double flashStepCooldownMult,
		int particleColor, Race race) {

	public Kit {
		Objects.requireNonNull(id, "kit id");
		Objects.requireNonNull(displayName, "kit displayName");
		Objects.requireNonNull(shikai, "kit " + id + " has no Shikai");
		Objects.requireNonNull(bankai, "kit " + id + " has no Bankai");
		Objects.requireNonNull(race, "kit " + id + " has no race");

		if (shikai.state() != SpiritualData.STATE_SHIKAI) {
			throw new IllegalArgumentException("Kit " + id + ": Shikai slot holds a non-Shikai ability");
		}
		if (bankai.state() != SpiritualData.STATE_BANKAI) {
			throw new IllegalArgumentException("Kit " + id + ": Bankai slot holds a non-Bankai ability");
		}
	}

	/** The released state for the given state byte, or null in the base state. */
	@Nullable
	public TransformAbility transformFor(byte state) {
		return switch (state) {
			case SpiritualData.STATE_SHIKAI -> shikai;
			case SpiritualData.STATE_BANKAI -> bankai;
			default -> null;
		};
	}

	/** What {@link SpiritualData#characterId} stores for this kit. */
	public String storageId() {
		return id.toString();
	}
}
