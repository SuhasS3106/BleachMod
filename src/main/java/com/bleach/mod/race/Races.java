package com.bleach.mod.race;

import java.util.List;

/**
 * The registered races. Two for now; a third is one constant and one entry in {@link #ALL}.
 *
 * <p><b>Shinigami is id 0 and behaviourally inert</b> — zero reishi sensitivity, no Blut — so the
 * existing eight kits are unchanged by construction rather than by every call site remembering to
 * check. That property is what lets this land without touching how Soul Reapers play.
 */
public final class Races {
	private Races() {
	}

	public static final Race SHINIGAMI = new Race((byte) 0, "Shinigami",
			List.of("Shikai", "Bankai"), "zanpakuto", 0.0, false);

	public static final Race QUINCY = new Race((byte) 1, "Quincy",
			List.of("Schrift", "Vollständig"), "heilig_bogen", 1.0, true);

	private static final List<Race> ALL = List.of(SHINIGAMI, QUINCY);

	/**
	 * The race for a stored id. <b>Never null.</b> An unrecognised id — a save written by a newer
	 * build, or a corrupted byte — resolves to Shinigami rather than throwing, because the
	 * alternative is a player who cannot log in.
	 */
	public static Race byId(byte id) {
		for (Race race : ALL) {
			if (race.id() == id) {
				return race;
			}
		}
		return SHINIGAMI;
	}

	/** Registration order, which is also picker order. */
	public static List<Race> all() {
		return ALL;
	}
}
