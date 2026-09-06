package com.bleach.mod.mixin.client;

import com.bleach.mod.client.ClientEnmaKorogiState;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mutes sound playback inside Tōsen's Enma Kōrogi Bankai dome — except pain.
 *
 * <p>A total mute makes the dome unreadable rather than frightening: a player who can neither see
 * nor hear anything has no evidence a fight is even happening, and no way to tell that they are the
 * one being hit. Letting hurt and death sounds through leaves exactly one channel open, which is
 * what the sensory deprivation is supposed to feel like — you know something is dying, and not
 * where, or what, or whether it is you.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void bleach$muteInEnmaKorogi(SoundInstance sound, CallbackInfo ci) {
		if (ClientEnmaKorogiState.isAffected() && !bleach$isPain(sound)) {
			ci.cancel();
		}
	}

	/**
	 * Whether this is a damage or death cry, for the local player or anything else.
	 *
	 * <p>Matched on the sound's own path rather than an enumerated list of {@code SoundEvents}
	 * constants: every mob names its own as {@code entity.<mob>.hurt} / {@code .death}, modded ones
	 * included, so the naming convention covers more than a hand-written list ever would and does
	 * not go stale when a mob is added.
	 */
	private static boolean bleach$isPain(SoundInstance sound) {
		if (sound == null || sound.getLocation() == null) {
			return false;
		}
		String path = sound.getLocation().getPath();
		return path.contains("hurt") || path.contains("death");
	}
}
