# Quincy Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the race seam, the reishi bow and arrow, the SPX attribution fix, the shared Quincy release base, and Blut — everything a Schrift needs to exist, with zero behaviour change for the eight Shinigami kits.

**Architecture:** A `race` byte on `SpiritualData` plus a pure-data `Race` record looked up from a `Races` registry. Race-specific behaviour resolves through the `Race` object rather than conditionals at call sites. Quincy reuses the existing two-tier `state` machine (release 1 = Schrift, release 2 = Vollständig), so gates, drains, exertion, the Bankai loan and claw-back are inherited rather than reimplemented.

**Tech Stack:** Minecraft 1.21.1 · Fabric Loader 0.19.5 · Fabric API 0.116.17+1.21.1 · Java 21 · Mojang official mappings · Gradle 9.5.1 (fabric-loom 1.17-SNAPSHOT) · JUnit 5 (added by Task 1)

**Spec:** `docs/superpowers/specs/2026-09-07-quincy-faction-design.md`

## Global Constraints

- **Local only. Never push.** `origin` is `github.com/SuhasS3106/BleachMod` and belongs to Suhas. Work on a local `quincy` branch; commit locally after each task; run no `git push` at any point.
- **No numeric literal in game logic.** Every constant is a `public static` non-final field in `com.bleach.mod.tuning.BleachTuning` and gets a row in `BALANCE.md`. This is `BALANCE.md`'s rule 1 and it is not optional.
- **Every new `BleachTuning` field must be `public static` and NOT `final`** — the reflective loader (`tunableFields()`, `BleachTuning.java:1196-1211`) only picks up public static non-final fields of type `double`, `int`, `boolean` or `double[]`. A `final` field is silently un-tunable.
- **Do not bump `CURRENT_CONFIG_VERSION`.** It tracks file *format*, not defaults. Adding keys does not change the format; the overrides-only loader picks up new defaults automatically.
- **Shinigami behaviour must not change.** `Races.SHINIGAMI` returns a reishi sensitivity of `0.0` and `hasBlut() == false`, so the existing path is unchanged by construction. Any task that makes a Shinigami behave differently is a bug in that task.
- **`AbilityAction` ordinals are wire protocol.** Append only. Never reorder, never remove (`AbilityAction.java:6-7`).
- **Mojang official mappings.** Signatures in this plan were read from the mapped jar with `javap` and are exact for 1.21.1.
- Java 21, `options.release = 21`.

## Verification tiers

This repo has no test framework and Minecraft resists unit testing, so verification is split. **Both tiers are mandatory; neither substitutes for the other.**

- **Tier 1 — JUnit (real red/green TDD).** For classes with **zero Minecraft imports**. Task 1 adds the harness. New pure-logic classes are deliberately designed to stay MC-free so they can be tested this way: `Race`, `Races`, `ReishiDensity`, `BlutStance`.
- **Tier 2 — compile gate plus in-world assertion.** For anything touching the MC runtime (items, entities, mixins, menus, HUD). The gate is `./gradlew build`, and the assertion is a `/bleach test <name>` operator command following the precedent already set by `/bleach test clawback` (`BleachCommands.java:137-142, 323-354`), which returns 1 on pass and 0 on fail.

`./gradlew build` takes roughly 4 minutes cold and under a minute warm. Do not skip it — mixin and codec errors surface only at compile or load.

> **Build environment note.** The Gradle wrapper is configured `networkTimeout=10000, retries=0` and fails to fetch its own distribution on a slow network. If `./gradlew` hangs on "Downloading gradle-9.5.1", the distribution is already present at `~/.gradle/wrapper/dists/gradle-9.5.1-bin/`.

---

## File Structure

**New package `com.bleach.mod.race`** — the seam. Pure data, no Minecraft imports in `Race`/`Races`/`ReishiDensity`, which is what makes them Tier 1 testable.

| File | Responsibility |
|---|---|
| `race/Race.java` | One race as pure data: id, display name, tier names, weapon prefix, reishi sensitivity, whether it has Blut |
| `race/Races.java` | The two race constants and a lookup by id |
| `race/ReishiDensity.java` | Pure function: environment readings + sensitivity → regen multiplier |
| `race/RaceWeapons.java` | The one Minecraft-coupled part: race → weapon item factory |
| `item/SpiritWeapon.java` | Renamed from `Zanpakuto.java`, made race-aware |
| `item/HeiligBogenItem.java` | The reishi bow |
| `entity/BleachEntities.java` | Entity type registration |
| `entity/ReishiArrow.java` | The arrow |
| `ability/kits/QuincyTransform.java` | Abstract base for every Schrift's two tiers |
| `ability/common/Blut.java` | Blut stance logic and per-tick billing |
| `client/BlutHud.java` | *(no new file — the tint folds into `SpiritualHud`)* |

**Deviations from the spec, deliberate, both improving testability:**

1. **Spec §3.2 gave `Race` a `ToDoubleFunction<ServerPlayer> regenMultiplier`.** That forces a Minecraft import into the seam's core type. Replaced with a plain `double reishiSensitivity`, with the environment reading done by `ReishiDensity` at the call site. `Race` stays pure and unit-testable, and sensitivity becomes a tunable number rather than buried logic.
2. **Spec §3.2 gave `Race` a `BiFunction<..., Item>` weapon factory.** Moved to `RaceWeapons`, for the same reason.

---

## Task 1: Test harness

**Files:**
- Modify: `build.gradle:5-12` (repositories), `build.gradle:20-30` (dependencies), end of file (test block)
- Test: `src/test/java/com/bleach/mod/HarnessTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: a working `./gradlew test` running JUnit 5 against `src/test/java`

**Why first:** every Tier 1 task after this one depends on it, and the repository's `repositories { }` block is currently **empty** — Loom injects the Minecraft repositories but not Maven Central, so JUnit cannot resolve until that is fixed. Discovering that in the middle of Task 2 wastes the task.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/bleach/mod/HarnessTest.java`:

```java
package com.bleach.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HarnessTest {
	@Test
	void harnessRuns() {
		assertEquals(4, 2 + 2);
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test`
Expected: FAIL — the `test` task either does not exist or reports "Could not resolve org.junit.jupiter:junit-jupiter".

- [ ] **Step 3: Add Maven Central and JUnit**

In `build.gradle`, replace the empty `repositories { ... }` block near the top with:

```groovy
repositories {
	// Loom adds the Minecraft and Fabric repositories automatically. Maven Central is ours,
	// for the test-only dependencies below.
	mavenCentral()
}
```

Add to the `dependencies { }` block, after the existing `modImplementation` lines:

```groovy
	// Unit tests cover pure logic only — classes with no Minecraft imports. Anything touching the
	// game runtime is verified by `./gradlew build` plus an in-world `/bleach test` command.
	testImplementation platform("org.junit:junit-bom:5.10.2")
	testImplementation "org.junit.jupiter:junit-jupiter"
	testRuntimeOnly "org.junit.platform:junit-platform-launcher"
```

Add at the end of `build.gradle`:

```groovy
tasks.named('test') {
	useJUnitPlatform()
	testLogging {
		events "passed", "failed", "skipped"
	}
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test`
Expected: PASS — `HarnessTest > harnessRuns() PASSED`.

- [ ] **Step 5: Commit**

```bash
git checkout -b quincy
git add build.gradle src/test/java/com/bleach/mod/HarnessTest.java
git commit -m "build: add JUnit 5 harness for pure-logic tests"
```

---

## Task 2: The `Race` record and `Races` registry

**Files:**
- Create: `src/main/java/com/bleach/mod/race/Race.java`
- Create: `src/main/java/com/bleach/mod/race/Races.java`
- Test: `src/test/java/com/bleach/mod/race/RacesTest.java`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `Race(byte id, String displayName, List<String> tierNames, String weaponPrefix, double reishiSensitivity, boolean hasBlut)`
  - `Race.tierName(byte state)` → `String`
  - `Races.SHINIGAMI`, `Races.QUINCY` — `Race` constants
  - `Races.byId(byte)` → `Race`, never null, unknown ids fall back to `SHINIGAMI`
  - `Races.all()` → `List<Race>` in registration order

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/bleach/mod/race/RacesTest.java`:

```java
package com.bleach.mod.race;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RacesTest {

	@Test
	void shinigamiIsIdZeroSoExistingSavesLoadUnchanged() {
		assertEquals(0, Races.SHINIGAMI.id());
	}

	@Test
	void shinigamiIsBehaviourallyInert() {
		assertEquals(0.0, Races.SHINIGAMI.reishiSensitivity());
		assertFalse(Races.SHINIGAMI.hasBlut());
	}

	@Test
	void quincyHasBlutAndReadsTheEnvironment() {
		assertTrue(Races.QUINCY.hasBlut());
		assertTrue(Races.QUINCY.reishiSensitivity() > 0.0);
	}

	@Test
	void unknownIdFallsBackToShinigamiRatherThanThrowing() {
		assertSame(Races.SHINIGAMI, Races.byId((byte) 0));
		assertSame(Races.QUINCY, Races.byId((byte) 1));
		assertSame(Races.SHINIGAMI, Races.byId((byte) 99));
		assertSame(Races.SHINIGAMI, Races.byId((byte) -1));
	}

	@Test
	void tierNamesAreRaceSpecific() {
		assertEquals("Shikai", Races.SHINIGAMI.tierName((byte) 1));
		assertEquals("Bankai", Races.SHINIGAMI.tierName((byte) 2));
		assertEquals("Schrift", Races.QUINCY.tierName((byte) 1));
		assertEquals("Vollständig", Races.QUINCY.tierName((byte) 2));
	}

	@Test
	void baseStateHasNoTierName() {
		assertEquals("", Races.SHINIGAMI.tierName((byte) 0));
		assertEquals("", Races.QUINCY.tierName((byte) 0));
	}

	@Test
	void aRaceMayDeclareFewerTiersThanTheStateMachineAllows() {
		// The hedge for Adil: Arrancar resurrección is one release tier, not two.
		Race oneTier = new Race((byte) 7, "Arrancar", java.util.List.of("Resurrección"),
				"zanpakuto", 0.0, false);
		assertEquals("Resurrección", oneTier.tierName((byte) 1));
		assertEquals("", oneTier.tierName((byte) 2));
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.bleach.mod.race.RacesTest"`
Expected: FAIL — compilation error, `package com.bleach.mod.race does not exist`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/bleach/mod/race/Race.java`:

```java
package com.bleach.mod.race;

import java.util.List;
import java.util.Objects;

/**
 * One playable race — the chassis a kit hangs off. A Shinigami's kit is a character; a Quincy's kit
 * is a Schrift. What differs between the two is entirely in this record.
 *
 * <p><b>Deliberately free of Minecraft imports.</b> The environment reading lives in
 * {@link ReishiDensity} and the weapon factory in {@code RaceWeapons}, so this type stays pure data
 * and can be unit tested without booting a game.
 *
 * @param id                 stored in {@code SpiritualData.race}. <b>Shinigami must be 0</b> so every
 *                           existing save loads as one with no migration.
 * @param displayName        shown in the picker and the guide
 * @param tierNames          release tier names, index 0 being release 1. A race may declare fewer
 *                           than the state machine allows — one entry is legal, and is what an
 *                           Arrancar's single resurrección needs.
 * @param weaponPrefix       item registry prefix: {@code zanpakuto} · {@code heilig_bogen}
 * @param reishiSensitivity  0 ignores the environment entirely; 1 is full exposure. Fed to
 *                           {@link ReishiDensity}.
 * @param hasBlut            whether the Blut stance key does anything for this race
 */
public record Race(byte id, String displayName, List<String> tierNames, String weaponPrefix,
		double reishiSensitivity, boolean hasBlut) {

	public Race {
		Objects.requireNonNull(displayName, "race displayName");
		Objects.requireNonNull(tierNames, "race tierNames");
		Objects.requireNonNull(weaponPrefix, "race weaponPrefix");
		tierNames = List.copyOf(tierNames);
	}

	/**
	 * The name of a release tier for this race, or the empty string for the base state and for any
	 * tier this race does not declare.
	 *
	 * <p>Returns empty rather than throwing because callers are display code: a guide line or a HUD
	 * label asking about a tier that does not exist should render nothing, not crash a client.
	 */
	public String tierName(byte state) {
		int index = state - 1;
		return index < 0 || index >= tierNames.size() ? "" : tierNames.get(index);
	}
}
```

Create `src/main/java/com/bleach/mod/race/Races.java`:

```java
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
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests "com.bleach.mod.race.RacesTest"`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bleach/mod/race/ src/test/java/com/bleach/mod/race/RacesTest.java
git commit -m "feat(race): add Race record and Races registry"
```

---

## Task 2b: Tuning keys for the race seam

**Files:**
- Modify: `src/main/java/com/bleach/mod/tuning/BleachTuning.java` (append a new section after the Gin block, around line 758)
- Modify: `BALANCE.md` (append §P.0 after §O)

**Interfaces:**
- Consumes: nothing
- Produces: `BleachTuning.REISHI_MULT_MIN`, `REISHI_MULT_MAX`, `REISHI_SKYLIGHT_WEIGHT`, `REISHI_SKY_ACCESS_WEIGHT`, `REISHI_SUBMERGED_PENALTY`, `REISHI_NO_SKY_DIMENSION_PENALTY`

**Why before Task 3:** `ReishiDensity` reads these, and a task that invents its own literals then retrofits tuning keys writes the numbers twice.

- [ ] **Step 1: Add the fields**

In `BleachTuning.java`, after the Gin section (the `GIN_*` block ending near line 758) and before the `// K. Networking and presentation` banner, add:

```java
	// --- P.0 Quincy · shared race constants ------------------------------------------

	/** Floor on a Quincy's environment regen multiplier — deep underground, submerged, in the Nether. */
	public static double REISHI_MULT_MIN = 0.45;
	/** Ceiling — open sky, full daylight. */
	public static double REISHI_MULT_MAX = 1.35;
	/** How much of the span is bought by sky light level (0..15 normalised). */
	public static double REISHI_SKYLIGHT_WEIGHT = 0.6;
	/** How much of the span is bought by having a clear column to the sky at all. */
	public static double REISHI_SKY_ACCESS_WEIGHT = 0.4;
	/** Multiplier applied on top while the player is submerged in fluid. */
	public static double REISHI_SUBMERGED_PENALTY = 0.7;
	/** Multiplier applied on top in a dimension with no natural sky — the Nether. */
	public static double REISHI_NO_SKY_DIMENSION_PENALTY = 0.6;
```

- [ ] **Step 2: Document them**

Append to `BALANCE.md`, after §O and before §L:

```markdown
## P. Quincy

### P.0 Shared race constants · *design §3.5*

A Quincy draws power from ambient reishi rather than producing it internally, so their regen is
multiplied by the environment. `Races.SHINIGAMI` has a sensitivity of 0 and is never affected.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `REISHI_MULT_MIN` | 0.45 | × | Floor — deep underground, submerged, in the Nether |
| `REISHI_MULT_MAX` | 1.35 | × | Ceiling — open sky, full daylight |
| `REISHI_SKYLIGHT_WEIGHT` | 0.6 | frac | Share of the span bought by sky light level |
| `REISHI_SKY_ACCESS_WEIGHT` | 0.4 | frac | Share bought by having a clear column to the sky |
| `REISHI_SUBMERGED_PENALTY` | 0.7 | × | Applied on top while submerged |
| `REISHI_NO_SKY_DIMENSION_PENALTY` | 0.6 | × | Applied on top in a dimension with no natural sky |

The two weights are a share of the span between floor and ceiling and should sum to 1.0. They are
separate keys rather than one because sky *access* and sky *light* differ at night: standing outside
at midnight still beats standing in a cave, which is the distinction a Quincy should feel.
```

- [ ] **Step 3: Verify the loader picks them up**

Run: `./gradlew build`
Then, in a dev world, `/bleach reload` and open `config/bleach_mod/tuning-defaults.json`.
Expected: all six `REISHI_*` keys are listed with the defaults above. If any is missing, it was declared `final` — the reflective loader skips those.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bleach/mod/tuning/BleachTuning.java BALANCE.md
git commit -m "feat(tuning): add BALANCE.md P.0 reishi density constants"
```

---

## Task 3: `ReishiDensity`

**Files:**
- Create: `src/main/java/com/bleach/mod/race/ReishiDensity.java`
- Test: `src/test/java/com/bleach/mod/race/ReishiDensityTest.java`

**Interfaces:**
- Consumes: `BleachTuning.REISHI_*` (Task 2b), `Race.reishiSensitivity()` (Task 2)
- Produces: `ReishiDensity.multiplier(double sensitivity, int skyLight, boolean hasSkyAccess, boolean submerged, boolean dimensionHasSky)` → `double`

`BleachTuning` imports Gson and `ResourceLocation`, but reading a `public static double` off it triggers no Minecraft bootstrap, so this stays Tier 1 testable. The test sets the tuning fields directly.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/bleach/mod/race/ReishiDensityTest.java`:

```java
package com.bleach.mod.race;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReishiDensityTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.REISHI_MULT_MIN = 0.45;
		BleachTuning.REISHI_MULT_MAX = 1.35;
		BleachTuning.REISHI_SKYLIGHT_WEIGHT = 0.6;
		BleachTuning.REISHI_SKY_ACCESS_WEIGHT = 0.4;
		BleachTuning.REISHI_SUBMERGED_PENALTY = 0.7;
		BleachTuning.REISHI_NO_SKY_DIMENSION_PENALTY = 0.6;
	}

	@Test
	void zeroSensitivityIsAlwaysExactlyOne() {
		// The Shinigami guarantee. Any environment, any weather, always 1.0.
		assertEquals(1.0, ReishiDensity.multiplier(0.0, 0, false, true, false));
		assertEquals(1.0, ReishiDensity.multiplier(0.0, 15, true, false, true));
	}

	@Test
	void openSkyInFullDaylightReachesTheCeiling() {
		assertEquals(1.35, ReishiDensity.multiplier(1.0, 15, true, false, true), 1e-9);
	}

	@Test
	void sealedUndergroundReachesTheFloor() {
		assertEquals(0.45, ReishiDensity.multiplier(1.0, 0, false, false, true), 1e-9);
	}

	@Test
	void skyAccessAtNightStillBeatsACave() {
		double night = ReishiDensity.multiplier(1.0, 0, true, false, true);
		double cave = ReishiDensity.multiplier(1.0, 0, false, false, true);
		assertTrue(night > cave, "standing outside at midnight must beat a cave");
	}

	@Test
	void submergedAppliesItsPenaltyOnTop() {
		double dry = ReishiDensity.multiplier(1.0, 15, true, false, true);
		double wet = ReishiDensity.multiplier(1.0, 15, true, true, true);
		assertEquals(dry * 0.7, wet, 1e-9);
	}

	@Test
	void aDimensionWithNoSkyAppliesItsPenaltyOnTop() {
		double overworld = ReishiDensity.multiplier(1.0, 15, true, false, true);
		double nether = ReishiDensity.multiplier(1.0, 15, true, false, false);
		assertEquals(overworld * 0.6, nether, 1e-9);
	}

	@Test
	void penaltiesCompound() {
		double best = ReishiDensity.multiplier(1.0, 15, true, false, true);
		double worst = ReishiDensity.multiplier(1.0, 15, true, true, false);
		assertEquals(best * 0.7 * 0.6, worst, 1e-9);
	}

	@Test
	void partialSensitivityInterpolatesTowardOne() {
		double full = ReishiDensity.multiplier(1.0, 0, false, false, true);
		double half = ReishiDensity.multiplier(0.5, 0, false, false, true);
		assertEquals(1.0 + (full - 1.0) * 0.5, half, 1e-9);
	}

	@Test
	void neverReturnsANegativeOrZeroMultiplier() {
		BleachTuning.REISHI_SUBMERGED_PENALTY = -5.0;   // a config file can hold anything
		assertTrue(ReishiDensity.multiplier(1.0, 0, false, true, false) > 0.0);
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "com.bleach.mod.race.ReishiDensityTest"`
Expected: FAIL — `cannot find symbol: class ReishiDensity`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/bleach/mod/race/ReishiDensity.java`:

```java
package com.bleach.mod.race;

import com.bleach.mod.tuning.BleachTuning;

/**
 * How much ambient reishi a position offers, as a multiplier on SP regen · design §3.5.
 *
 * <p>Pure arithmetic with no Minecraft imports, so it is unit tested directly. The caller reads the
 * four environment facts off the world and passes them in; that split is what keeps the formula
 * testable and the world access in one place.
 */
public final class ReishiDensity {
	private ReishiDensity() {
	}

	private static final int MAX_SKY_LIGHT = 15;

	/**
	 * @param sensitivity     {@link Race#reishiSensitivity()} — 0 ignores the environment entirely
	 * @param skyLight        sky light at the player's position, 0..15
	 * @param hasSkyAccess    whether there is a clear column to the sky above the player
	 * @param submerged       whether the player's eyes are in a fluid
	 * @param dimensionHasSky whether this dimension has a natural sky at all
	 * @return a strictly positive multiplier. Exactly 1.0 when {@code sensitivity} is 0.
	 */
	public static double multiplier(double sensitivity, int skyLight, boolean hasSkyAccess,
			boolean submerged, boolean dimensionHasSky) {
		if (sensitivity <= 0.0) {
			return 1.0;
		}

		double light = Math.max(0, Math.min(MAX_SKY_LIGHT, skyLight)) / (double) MAX_SKY_LIGHT;
		double exposure = BleachTuning.REISHI_SKYLIGHT_WEIGHT * light
				+ BleachTuning.REISHI_SKY_ACCESS_WEIGHT * (hasSkyAccess ? 1.0 : 0.0);

		double raw = BleachTuning.REISHI_MULT_MIN
				+ (BleachTuning.REISHI_MULT_MAX - BleachTuning.REISHI_MULT_MIN) * exposure;

		if (submerged) {
			raw *= BleachTuning.REISHI_SUBMERGED_PENALTY;
		}
		if (!dimensionHasSky) {
			raw *= BleachTuning.REISHI_NO_SKY_DIMENSION_PENALTY;
		}

		// Interpolate from 1.0 by sensitivity, so a partially sensitive race is partially affected
		// and a zero-sensitivity race is exactly unaffected.
		double scaled = 1.0 + (raw - 1.0) * Math.max(0.0, Math.min(1.0, sensitivity));

		// A config file can hold any number somebody types into it, and a non-positive regen
		// multiplier would freeze the pool outright rather than merely slowing it.
		return Math.max(0.01, scaled);
	}
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests "com.bleach.mod.race.ReishiDensityTest"`
Expected: PASS — 9 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bleach/mod/race/ReishiDensity.java src/test/java/com/bleach/mod/race/ReishiDensityTest.java
git commit -m "feat(race): add ReishiDensity regen multiplier"
```

---

## Task 4: `SpiritualData.race`

**Files:**
- Modify: `src/main/java/com/bleach/mod/attachment/SpiritualData.java:24-39` (codec), `:55-60` (fields), `:147-165` (private ctor), `:262-279` (`copyProgressionFrom`)

**Interfaces:**
- Consumes: `Races` (Task 2)
- Produces: `SpiritualData.race` (`byte`), persisted as `"race"`, defaulting to `Races.SHINIGAMI.id()`

This is Tier 2 — the codec cannot be exercised without Minecraft's `ItemStack.OPTIONAL_CODEC`.

- [ ] **Step 1: Add the field**

In `SpiritualData.java`, after the `characterId` declaration (line 60), add:

```java
	/**
	 * Which race this player is · design §3.1. {@link com.bleach.mod.race.Races#byId} resolves it.
	 *
	 * <p><b>Defaults to 0, which is Shinigami, and that default is doing real work.</b> Every save
	 * written before races existed omits the field, so every existing player loads as a Shinigami
	 * with no migration step and no config version bump.
	 */
	public byte race;
```

- [ ] **Step 2: Add it to the codec**

In the `CODEC` group (lines 24-39), add a line **after** the `stowed_reforged` entry so the group order matches the constructor:

```java
			Codec.INT.optionalFieldOf("stowed_reforged", 0).forGetter(d -> d.stowedReforged),
			Codec.BYTE.optionalFieldOf("race", (byte) 0).forGetter(d -> d.race)
```

(the `stowed_reforged` line gains a trailing comma; the `race` line is new and last).

- [ ] **Step 3: Extend the private constructor**

Change the signature at line 147-150 to take one more parameter and assign it:

```java
	private SpiritualData(double sp, int soulLevel, int spx, double exertion, long lastSpxDay,
			int spxEarnedToday, long playtimeTicks, byte state, int regenPauseTicks,
			Optional<String> characterId, double spOnEntry, ItemStack zanpakuto, ItemStack stowedItem,
			int stowedReforged, byte race) {
```

and add, after `this.stowedReforged = stowedReforged;`:

```java
		this.race = race;
```

- [ ] **Step 4: Carry it across death**

In `copyProgressionFrom` (line 262), add alongside `characterId`:

```java
		this.characterId = other.characterId;
		// Race is progression, not pool state: it survives death exactly as the chosen kit does.
		this.race = other.race;
```

**Do not** touch `resetOnRespawn()` — race must survive a respawn, and that method deliberately only clears volatile state.

- [ ] **Step 5: Verify it compiles and round-trips**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. A `RecordCodecBuilder` group whose arity does not match the constructor fails at compile, so a green build is real evidence the codec is wired.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bleach/mod/attachment/SpiritualData.java
git commit -m "feat(race): persist race on SpiritualData, defaulting to Shinigami"
```

---

## Task 5: Sync the race (and reserve the Blut slot)

**Files:**
- Modify: `src/main/java/com/bleach/mod/network/SpiritualSyncPayload.java` — record components (`:34-36`), stream codec (`:46-77`), `of` (`:88-104`), and the stale class comment at `:42-45`
- Modify: `src/main/java/com/bleach/mod/client/ClientSpiritualState.java` — no change needed if it stores the whole payload; verify

**Interfaces:**
- Consumes: `SpiritualData.race` (Task 4)
- Produces: `SpiritualSyncPayload.race()` (`byte`) and `SpiritualSyncPayload.blut()` (`byte`), 16 components total

Both components land now, in one wire change, rather than breaking the payload twice.

- [ ] **Step 1: Extend the record**

```java
public record SpiritualSyncPayload(float sp, float maxSp, int soulLevel, int spx, int spxToNext,
		byte state, float regenMult, float worldSoulLevel, int spxRemainingToday, float catchUp,
		float mobScalar, boolean hovering, float shikaiGate, float bankaiGate, byte race, byte blut)
		implements CustomPacketPayload {
```

- [ ] **Step 2: Fix the lying comment and extend the codec**

Replace the comment at lines 42-45 and add two writes and two reads:

```java
	/**
	 * Hand-written rather than {@code StreamCodec.composite}, whose overloads stop short of the
	 * sixteen components here.
	 */
```

In the writer, after `buf.writeFloat(payload.bankaiGate);`:

```java
				buf.writeByte(payload.race);
				buf.writeByte(payload.blut);
```

In the reader, after the final `buf.readFloat()`:

```java
					buf.readByte(),
					buf.readByte()));
```

(the previous last `buf.readFloat()` gains a trailing comma).

- [ ] **Step 3: Extend `of`**

```java
				gate(data, SpiritualData.STATE_SHIKAI),
				gate(data, SpiritualData.STATE_BANKAI),
				data.race,
				data.blut);
```

`data.blut` does not exist yet — **add the field now** in `SpiritualData.java`, next to `flexing`:

```java
	/**
	 * Blut stance · design §5.4. 0 off, 1 Vene, 2 Arterie.
	 *
	 * <p>Not persisted and not in the codec, for the same reason as {@link #flexing}: it is a stance
	 * the player is holding, and a stale value restored from disk would bill someone who is not
	 * pressing anything.
	 */
	public byte blut;
```

and clear it in `resetOnRespawn()` alongside the other channel flags:

```java
		this.blut = 0;
```

- [ ] **Step 4: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

In-world check: join a dev world and confirm the SP bar still renders and still tracks SP. A payload arity mismatch between writer and reader produces a decode exception and a disconnect, so a working HUD is the assertion here.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bleach/mod/network/SpiritualSyncPayload.java src/main/java/com/bleach/mod/attachment/SpiritualData.java
git commit -m "feat(race): sync race and blut to the owning client"
```

---

## Task 6: Kits declare a race

**Files:**
- Modify: `src/main/java/com/bleach/mod/ability/Kit.java:22-39`
- Modify: `src/main/java/com/bleach/mod/ability/kits/BleachKits.java:45-85` (all eight registrations)
- Modify: `src/main/java/com/bleach/mod/ability/AbilityRegistry.java` (add `kitsFor`)

**Interfaces:**
- Consumes: `Race`, `Races` (Task 2)
- Produces: `Kit.race()` → `Race`; `AbilityRegistry.kitsFor(Race)` → `List<Kit>` in registration order

- [ ] **Step 1: Add the component**

In `Kit.java`, add `Race race` as the **last** component and null-check it:

```java
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
		...
```

Add the import `com.bleach.mod.race.Race`.

- [ ] **Step 2: Declare Shinigami on all eight existing kits**

In `BleachKits.java`, append `Races.SHINIGAMI` as the last argument of each of the eight `new Kit(...)` calls. For example the Ichigo registration becomes:

```java
		AbilityRegistry.registerKit(new Kit(ICHIGO, "Ichigo Kurosaki",
				IchigoTransform.shikai(), IchigoTransform.bankai(),
				BleachTuning.KIT_ICHIGO_FS_RANGE_MULT, BleachTuning.KIT_ICHIGO_FS_COOLDOWN_MULT,
				BleachTuning.KIT_ICHIGO_PARTICLE_COLOR, Races.SHINIGAMI));
```

Do the same for `YAMAMOTO`, `SUIFENG`, `RUKIA`, `SHINJI`, `AIZEN`, `TOSEN`, `GIN`. Add the import `com.bleach.mod.race.Races`.

- [ ] **Step 3: Add the filter**

In `AbilityRegistry.java`, after `kits()`:

```java
	/** The kits belonging to one race, in registration order. Drives the second picker screen. */
	public static List<Kit> kitsFor(Race race) {
		List<Kit> matching = new ArrayList<>();
		for (Kit kit : KITS.values()) {
			if (kit.race().id() == race.id()) {
				matching.add(kit);
			}
		}
		return matching;
	}
```

Add imports `java.util.ArrayList`, `java.util.List`, `com.bleach.mod.race.Race`.

- [ ] **Step 4: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. The compact constructor throws on a null race, so a missed registration is a startup crash rather than a silent misfiling — start a dev world to confirm it boots.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bleach/mod/ability/Kit.java src/main/java/com/bleach/mod/ability/kits/BleachKits.java src/main/java/com/bleach/mod/ability/AbilityRegistry.java
git commit -m "feat(race): kits declare their race; add AbilityRegistry.kitsFor"
```

---

## Task 7: Environment-modified regen

**Files:**
- Modify: `src/main/java/com/bleach/mod/attachment/SpiritualTicker.java:149-166` (`tickRegen`)

**Interfaces:**
- Consumes: `ReishiDensity.multiplier` (Task 3), `Races.byId` (Task 2), `SpiritualData.race` (Task 4)
- Produces: nothing new

- [ ] **Step 1: Apply the multiplier**

`tickRegen` currently adds `data.regenPerSecond() * data.regenMultiplier() / 20.0`. Change the regen line to fold in the environment, and add a helper to the same class:

```java
			double gained = data.regenPerSecond()
					* data.regenMultiplier()
					* environmentMultiplier(player, data)
					/ BleachTuning.TICKS_PER_SECOND;
```

Add the private helper:

```java
	/**
	 * A Quincy draws power from the world rather than producing it · design §3.5. Exactly 1.0 for
	 * a Shinigami, whose race declares zero sensitivity, so this costs the existing path nothing
	 * beyond one field read and a branch that is never taken.
	 */
	private static double environmentMultiplier(ServerPlayer player, SpiritualData data) {
		Race race = Races.byId(data.race);
		if (race.reishiSensitivity() <= 0.0) {
			return 1.0;
		}

		BlockPos pos = player.blockPosition();
		ServerLevel level = player.serverLevel();
		return ReishiDensity.multiplier(
				race.reishiSensitivity(),
				level.getBrightness(LightLayer.SKY, pos),
				level.canSeeSky(pos),
				player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA),
				level.dimensionType().hasSkyLight());
	}
```

Imports to add: `net.minecraft.core.BlockPos`, `net.minecraft.server.level.ServerLevel`, `net.minecraft.world.level.LightLayer`, `net.minecraft.tags.FluidTags`, `com.bleach.mod.race.Race`, `com.bleach.mod.race.Races`, `com.bleach.mod.race.ReishiDensity`.

- [ ] **Step 2: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

In-world, as a Shinigami: `/bleach sp set 0`, then time the refill above ground and in a cave. **They must be identical** — this is the Shinigami-unchanged guarantee, and it is the single most important manual check in the plan.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bleach/mod/attachment/SpiritualTicker.java
git commit -m "feat(race): multiply regen by ambient reishi density"
```

---

## Task 8: Two-step picker

**Files:**
- Modify: `src/main/java/com/bleach/mod/menu/ZanpakutoSelectMenu.java` (whole file)

**Interfaces:**
- Consumes: `Races.all()`, `AbilityRegistry.kitsFor` (Task 6)
- Produces: `ZanpakutoSelectMenu.open(ServerPlayer)` unchanged as the entry point

**Why this is a blocker:** `ROW = 9` with a `i < ROW` fill loop means a **tenth kit is silently unselectable** — no error, no log. There are already eight. Four Schrifts take the roster to twelve.

- [ ] **Step 1: Replace the open flow with a race screen**

Change `open` to show one slot per race, and add a second method for the kit screen:

```java
	/** Screen one: pick a race. */
	public static void open(ServerPlayer player) {
		List<Race> races = Races.all();
		if (races.isEmpty()) {
			return;
		}

		SimpleContainer display = new SimpleContainer(ROW);
		for (int i = 0; i < races.size() && i < ROW; i++) {
			display.setItem(i, raceStack(races.get(i)));
		}

		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, opener) ->
						new ZanpakutoSelectMenu(containerId, inventory, display, races, null),
				RACE_TITLE));
	}

	/** Screen two: pick a kit within the chosen race. */
	private static void openKits(ServerPlayer player, Race race) {
		List<Kit> choices = AbilityRegistry.kitsFor(race);
		if (choices.isEmpty()) {
			player.displayClientMessage(
					Component.literal("No characters are available for that race yet."), true);
			return;
		}

		SimpleContainer display = new SimpleContainer(ROW);
		for (int i = 0; i < choices.size() && i < ROW; i++) {
			display.setItem(i, displayStack(choices.get(i)));
		}

		player.openMenu(new SimpleMenuProvider(
				(containerId, inventory, opener) ->
						new ZanpakutoSelectMenu(containerId, inventory, display, null, choices),
				Component.literal("Choose your " + race.displayName().toLowerCase(java.util.Locale.ROOT))));
	}
```

Fields and constructor become:

```java
	private static final Component RACE_TITLE = Component.literal("Choose your path");
	private static final int ROW = 9;

	private final List<Race> raceChoices;
	private final List<Kit> kitChoices;

	private ZanpakutoSelectMenu(int containerId, Inventory playerInventory, SimpleContainer display,
			List<Race> raceChoices, List<Kit> kitChoices) {
		super(MenuType.GENERIC_9x1, containerId, playerInventory, display, 1);
		this.raceChoices = raceChoices;
		this.kitChoices = kitChoices;
	}
```

The race icon reuses an item that already exists rather than adding an asset:

```java
	private static ItemStack raceStack(Race race) {
		ItemStack stack = new ItemStack(
				race.id() == Races.QUINCY.id() ? Items.BOW : Items.IRON_SWORD);
		stack.set(DataComponents.CUSTOM_NAME,
				Component.literal(race.displayName()).withStyle(ChatFormatting.GOLD));
		return stack;
	}
```

Add imports `net.minecraft.world.item.Items`, `com.bleach.mod.race.Race`, `com.bleach.mod.race.Races`.

- [ ] **Step 2: Route the click**

```java
	@Override
	public void clicked(int slotId, int button, ClickType type, Player player) {
		if (slotId < 0 || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}

		if (raceChoices != null && slotId < raceChoices.size()) {
			Race race = raceChoices.get(slotId);
			// Record the race before the second screen: choose() below needs it, and closing the
			// first menu must not lose it.
			BleachAttachments.get(serverPlayer).race = race.id();
			serverPlayer.closeContainer();
			openKits(serverPlayer, race);
			return;
		}

		if (kitChoices != null && slotId < kitChoices.size()) {
			choose(serverPlayer, kitChoices.get(slotId).id());
			serverPlayer.closeContainer();
		}
	}
```

- [ ] **Step 3: Guard `choose` against a cross-race pick**

In `choose`, after resolving the kit, refuse a mismatch rather than trusting the screen:

```java
		Kit kit = AbilityRegistry.kit(kitId.toString());
		if (kit == null || kit.race().id() != data.race) {
			// Nothing should be able to send this, but the menu is a network surface and the kit
			// choice is permanent.
			return;
		}
```

and use `kit.displayName()` for the confirmation message instead of a second registry lookup.

- [ ] **Step 4: Fix the wrong-token bug while here**

`choose` consumes the **first** selector in the inventory, which is the wrong one for a player holding both a plain and a Reforged Asauchi. Prefer the held item:

```java
		int token = -1;
		Inventory inventory = player.getInventory();
		if (BleachItems.isSelector(player.getMainHandItem())) {
			token = inventory.selected;
		} else {
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				if (BleachItems.isSelector(inventory.getItem(i))) {
					token = i;
					break;
				}
			}
		}
```

- [ ] **Step 5: Verify**

Run: `./gradlew build`

In-world:
1. `/bleach kit clear`, then right-click the Asauchi. Expect a two-slot screen: Shinigami, Quincy.
2. Click Shinigami. Expect the eight existing kits.
3. Pick one. Expect the existing "You are now X." message and a drawn blade.
4. `/bleach kit clear` again, pick Quincy. Expect "No characters are available for that race yet." — correct until Task 14.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bleach/mod/menu/ZanpakutoSelectMenu.java
git commit -m "feat(race): two-step race then kit picker; lift the nine-kit cap"
```

---

## Task 9: `Zanpakuto` → `SpiritWeapon`, race-aware minting

**Files:**
- Rename: `src/main/java/com/bleach/mod/item/Zanpakuto.java` → `src/main/java/com/bleach/mod/item/SpiritWeapon.java`
- Create: `src/main/java/com/bleach/mod/race/RaceWeapons.java`
- Modify: `src/main/java/com/bleach/mod/item/BleachItems.java:42-79`
- Modify: every importer of `Zanpakuto` (12 call sites)

**Interfaces:**
- Consumes: `Race` (Task 2), `Kit.race()` (Task 6)
- Produces: `SpiritWeapon` with the same public API as `Zanpakuto`; `RaceWeapons.factoryFor(Race)`; `BleachItems.weaponFor(ResourceLocation)`

**Rename, do not copy.** `Zanpakuto` owns draw/sheathe, the one-place invariant, undroppable enforcement across six paths, death-stow and respawn-restore. A second copy for bows is how those two implementations drift and one starts dropping weapons on death.

- [ ] **Step 1: Rename the class**

```bash
git mv src/main/java/com/bleach/mod/item/Zanpakuto.java src/main/java/com/bleach/mod/item/SpiritWeapon.java
```

Rename the type inside the file, then update every importer. Find them with:

```bash
grep -rln "Zanpakuto\b" --include=*.java src/main/java | grep -v "ZanpakutoItem\|ZanpakutoSelectMenu\|ZanpakutoModels"
```

`ZanpakutoItem`, `ZanpakutoSelectMenu` and `ZanpakutoModels` keep their names — they are about the *sword*, which still exists.

- [ ] **Step 2: Add the weapon factory**

Create `src/main/java/com/bleach/mod/race/RaceWeapons.java`:

```java
package com.bleach.mod.race;

import java.util.function.Function;

import com.bleach.mod.item.HeiligBogenItem;
import com.bleach.mod.item.ZanpakutoItem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * The one Minecraft-coupled half of the race seam: which item class a race's kits carry.
 *
 * <p>Separate from {@link Race} so that record stays free of Minecraft imports and unit testable.
 * This is the only place in the mod that maps a race to a concrete weapon class.
 */
public final class RaceWeapons {
	private RaceWeapons() {
	}

	/** The item constructor for a race's spirit weapon. */
	public static Function<ResourceLocation, Item> factoryFor(Race race) {
		if (race.id() == Races.QUINCY.id()) {
			return HeiligBogenItem::new;
		}
		return ZanpakutoItem::new;
	}
}
```

This forward-references `HeiligBogenItem`, created in Task 10. **Do Task 10 before compiling this**, or stub the Quincy branch to `ZanpakutoItem::new` and switch it in Task 10.

- [ ] **Step 3: Mint per race in `BleachItems`**

The current loop hardcodes `ZanpakutoItem` and the `zanpakuto_` prefix. It also cannot ask a kit for its race, because items are registered before kits exist (`BleachItems.java:23-30`). Resolve by keying off `BleachKits` directly:

```java
	/** One weapon per kit, in {@link BleachKits#IDS} order. */
	private static final Map<ResourceLocation, Item> WEAPONS = new LinkedHashMap<>();

	public static void register() {
		register("asauchi", ASAUCHI);
		register("reforged_asauchi", REFORGED_ASAUCHI);

		for (ResourceLocation kitId : BleachKits.IDS) {
			Race race = BleachKits.raceOf(kitId);
			Item weapon = RaceWeapons.factoryFor(race).apply(kitId);
			WEAPONS.put(kitId, weapon);
			register(race.weaponPrefix() + "_" + kitId.getPath(), weapon);
		}

		ItemGroupEvents.modifyEntriesEvent(COMBAT_TAB).register(entries -> {
			entries.accept(ASAUCHI);
			entries.accept(REFORGED_ASAUCHI);
			for (Item weapon : WEAPONS.values()) {
				entries.accept(weapon);
			}
		});
	}

	/** The spirit weapon item for a kit, or null if that kit has none. */
	@Nullable
	public static Item weaponFor(ResourceLocation kitId) {
		return WEAPONS.get(kitId);
	}
```

Keep `zanpakuto()` working for `ZanpakutoModels`, which needs only the swords:

```java
	/** Every registered blade. Used client-side to hang the released-state model predicate on each. */
	public static Iterable<ZanpakutoItem> zanpakuto() {
		List<ZanpakutoItem> blades = new ArrayList<>();
		for (Item weapon : WEAPONS.values()) {
			if (weapon instanceof ZanpakutoItem blade) {
				blades.add(blade);
			}
		}
		return blades;
	}
```

Update `zanpakutoFor` callers to `weaponFor`.

- [ ] **Step 4: Add the id→race table**

In `BleachKits.java`, next to `IDS`:

```java
	/** Which race each kit belongs to. Read by {@code BleachItems} before any Kit object exists. */
	private static final Map<ResourceLocation, Race> RACE_OF = Map.of(
			ICHIGO, Races.SHINIGAMI, YAMAMOTO, Races.SHINIGAMI, SUIFENG, Races.SHINIGAMI,
			RUKIA, Races.SHINIGAMI, SHINJI, Races.SHINIGAMI, AIZEN, Races.SHINIGAMI,
			TOSEN, Races.SHINIGAMI, GIN, Races.SHINIGAMI);

	/** Never null — an unlisted kit is treated as Shinigami, which is the pre-race behaviour. */
	public static Race raceOf(ResourceLocation kitId) {
		return RACE_OF.getOrDefault(kitId, Races.SHINIGAMI);
	}
```

> **Consistency risk to watch:** `RACE_OF` and the `Races.X` argument passed to each `new Kit(...)` in `register()` are two statements of the same fact. Task 16 adds `/bleach test race` which asserts they agree for every kit.

- [ ] **Step 5: Verify**

Run: `./gradlew build`

In-world: every existing zanpakutō still registers as `bleach_mod:zanpakuto_<kit>` — confirm with `/give @s bleach_mod:zanpakuto_ichigo`. Draw, sheathe, die, respawn: the blade must survive all four.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/com/bleach/mod/
git commit -m "refactor(item): rename Zanpakuto to SpiritWeapon and mint weapons per race"
```

---

## Task 10: `HeiligBogenItem`

**Files:**
- Create: `src/main/java/com/bleach/mod/item/HeiligBogenItem.java`
- Modify: `src/main/java/com/bleach/mod/tuning/BleachTuning.java` (§P.0 block)
- Modify: `BALANCE.md` §P.0

**Interfaces:**
- Consumes: `BleachEntities.REISHI_ARROW` (Task 11), `SpiritualData` for the SP cost
- Produces: `HeiligBogenItem(ResourceLocation kitId)`, `HeiligBogenItem.kitId()`

Signatures confirmed against the 1.21.1 mapped jar: `use` returns `InteractionResultHolder<ItemStack>`; `getUseDuration(ItemStack, LivingEntity)` takes two arguments; `releaseUsing(ItemStack, Level, LivingEntity, int)`.

- [ ] **Step 1: Add the tuning keys**

Append to the §P.0 block in `BleachTuning.java`:

```java
	/** Melee damage of the bow used as a club. Deliberately far under the zanpakutō's 3. */
	public static int BOW_MELEE_DAMAGE = 1;
	/** Melee attack speed modifier for the bow. */
	public static double BOW_MELEE_SPEED = -2.8;
	/** SP charged per shot, whatever the draw. */
	public static double BOW_SHOT_SP_COST = 6.0;
	/** Ticks of draw for a full-power shot. */
	public static int BOW_FULL_DRAW_TICKS = 20;
	/** Arrow damage at a full draw, before Soul Level scaling. */
	public static double BOW_ARROW_DAMAGE = 7.0;
	/** Arrow launch velocity at a full draw, blocks per tick. */
	public static double BOW_ARROW_VELOCITY = 3.0;
	/** Minimum draw fraction below which the shot is refused outright. */
	public static double BOW_MIN_DRAW = 0.15;
```

Add matching rows to `BALANCE.md` §P.0, and note beneath the table that `BOW_MELEE_DAMAGE` and `BOW_MELEE_SPEED` are baked into the item's default components at registration and therefore need a restart — the same caveat §I.1 records for the zanpakutō.

- [ ] **Step 2: Write the item**

```java
package com.bleach.mod.item;

import com.bleach.mod.attachment.BleachAttachments;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.attachment.SpiritualTicker;
import com.bleach.mod.entity.ReishiArrow;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.Level;

/**
 * A Quincy's Heilig Bogen — the structural twin of {@link ZanpakutoItem}. Same guarantees: single
 * stack, unbreakable, undroppable, kept on death, restored on respawn. It is the race's drawn weapon,
 * not a release.
 *
 * <p>It uses vanilla bow semantics, which buys the first-person draw-back animation for nothing —
 * the only animation this phase of the mod gets.
 */
public class HeiligBogenItem extends Item {
	private final ResourceLocation kitId;

	public HeiligBogenItem(ResourceLocation kitId) {
		super(new Properties()
				.stacksTo(1)
				.rarity(Rarity.EPIC)
				.fireResistant()
				.component(DataComponents.UNBREAKABLE, new Unbreakable(true))
				.attributes(SwordItem.createAttributes(Tiers.IRON,
						BleachTuning.BOW_MELEE_DAMAGE, (float) BleachTuning.BOW_MELEE_SPEED)));
		this.kitId = kitId;
	}

	public ResourceLocation kitId() {
		return kitId;
	}

	@Override
	public UseAnim getUseAnimation(ItemStack stack) {
		return UseAnim.BOW;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return 72000;   // vanilla's "hold indefinitely"; the draw curve caps the useful part
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		player.startUsingItem(hand);
		return InteractionResultHolder.consume(player.getItemInHand(hand));
	}

	@Override
	public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		if (level.isClientSide || !(entity instanceof ServerPlayer player)) {
			return;
		}

		float draw = drawFraction(getUseDuration(stack, entity) - timeLeft);
		if (draw < BleachTuning.BOW_MIN_DRAW) {
			return;
		}

		SpiritualData data = BleachAttachments.get(player);
		if (data.sp < BleachTuning.BOW_SHOT_SP_COST) {
			player.displayClientMessage(
					net.minecraft.network.chat.Component.literal("Not enough spiritual pressure."), true);
			return;
		}
		data.spend(BleachTuning.BOW_SHOT_SP_COST);

		ReishiArrow arrow = new ReishiArrow(player, level, kitId);
		arrow.setBaseDamage(BleachTuning.BOW_ARROW_DAMAGE * draw);
		arrow.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0f,
				(float) (BleachTuning.BOW_ARROW_VELOCITY * draw), 1.0f);
		level.addFreshEntity(arrow);

		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0f, 1.0f / (draw + 0.6f));

		SpiritualTicker.sync(player, true);
	}

	/** Draw progress, 0..1, reaching 1 at {@link BleachTuning#BOW_FULL_DRAW_TICKS}. */
	private static float drawFraction(int ticksHeld) {
		return Math.min(1.0f, ticksHeld / (float) Math.max(1, BleachTuning.BOW_FULL_DRAW_TICKS));
	}

	@Override
	public boolean canFitInsideContainerItems() {
		return false;
	}
}
```

- [ ] **Step 3: Recognise it as a spirit weapon**

In `SpiritWeapon.java`, widen `isZanpakuto` (rename it `isSpiritWeapon`) and `isUndroppable` to accept both item classes:

```java
	public static boolean isSpiritWeapon(ItemStack stack) {
		return stack.getItem() instanceof ZanpakutoItem || stack.getItem() instanceof HeiligBogenItem;
	}
```

Every call site of the old name updates with it. **This is the step that gives the bow all six undroppable guarantees**; missing it means bows drop on death.

- [ ] **Step 4: Point `RaceWeapons` at it**

Switch the Quincy branch of `RaceWeapons.factoryFor` from the Task 9 stub to `HeiligBogenItem::new`.

- [ ] **Step 5: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. `ReishiArrow` must exist first — do Task 11 before this compiles, or land Tasks 10 and 11 as one commit.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bleach/mod/item/ src/main/java/com/bleach/mod/race/RaceWeapons.java src/main/java/com/bleach/mod/tuning/BleachTuning.java BALANCE.md
git commit -m "feat(quincy): add HeiligBogenItem with vanilla bow draw semantics"
```

---

## Task 11: `ReishiArrow` and `BleachEntities`

**Files:**
- Create: `src/main/java/com/bleach/mod/entity/BleachEntities.java`
- Create: `src/main/java/com/bleach/mod/entity/ReishiArrow.java`
- Create: `src/main/java/com/bleach/mod/client/InvisibleEntityRenderer.java`
- Modify: `src/main/java/com/bleach/mod/BleachMod.java` (call `BleachEntities.register()`)
- Modify: `src/main/java/com/bleach/mod/BleachModClient.java` (register the renderer)

**Interfaces:**
- Consumes: `BleachDamage`, `PressureParticleOptions`, `Kit.particleColor()`
- Produces: `BleachEntities.REISHI_ARROW`; `ReishiArrow(ServerPlayer shooter, Level level, ResourceLocation kitId)`; `ReishiArrow.kitId()`

Signatures confirmed by `javap`: `AbstractArrow(EntityType<? extends AbstractArrow>, LivingEntity, Level, ItemStack, ItemStack)`, `protected abstract ItemStack getDefaultPickupItem()`, `protected void onHitEntity(EntityHitResult)`, `AbstractArrow.Pickup.DISALLOWED`, `EntityRenderer.getTextureLocation(T)` abstract, `EntityType.Builder.of(EntityFactory, MobCategory)`.

- [ ] **Step 1: Register the entity type**

```java
package com.bleach.mod.entity;

import com.bleach.mod.BleachMod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Every entity the mod adds. Called from {@code BleachMod#onInitialize}. */
public final class BleachEntities {
	private BleachEntities() {
	}

	public static EntityType<ReishiArrow> REISHI_ARROW;

	public static void register() {
		REISHI_ARROW = Registry.register(BuiltInRegistries.ENTITY_TYPE,
				BleachMod.id("reishi_arrow"),
				EntityType.Builder.<ReishiArrow>of(ReishiArrow::new, MobCategory.MISC)
						.sized(0.5f, 0.5f)
						.clientTrackingRange(4)
						.updateInterval(20)
						.build("reishi_arrow"));
	}
}
```

- [ ] **Step 2: Write the arrow**

```java
package com.bleach.mod.entity;

import com.bleach.mod.ability.AbilityRegistry;
import com.bleach.mod.ability.Kit;
import com.bleach.mod.damage.BleachDamage;
import com.bleach.mod.particle.PressureParticleOptions;

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
 * Condensed reishi, fired from a Heilig Bogen.
 *
 * <p><b>It renders as nothing.</b> What you see is a trail of the existing pressure particle tinted
 * by the shooter's kit colour — zero new assets, and it reads better as spirit energy than a wooden
 * shaft would. A no-op renderer is registered client-side so the game does not complain.
 *
 * <p>It carries the shooter's kit id so a Schrift can react to its own arrows landing, which is what
 * keeps kit-specific behaviour out of this class entirely.
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
		// Never used — pickup is DISALLOWED — but the supertype demands a non-null stack.
		return new ItemStack(Items.ARROW);
	}

	@Override
	public void tick() {
		super.tick();
		if (level() instanceof ServerLevel server && !inGround) {
			server.sendParticles(new PressureParticleOptions(colour(), 0.5f),
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
		target.hurt(BleachDamage.spiritPressure(level(), getOwner()), damage);
		discard();
	}

	private int colour() {
		Kit kit = kitId == null ? null : AbilityRegistry.kit(kitId.toString());
		return kit == null ? 0xFFFFFF : kit.particleColor();
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
```

> **Check before writing:** the exact factory method on `BleachDamage` for a `spirit_pressure` source with an attacker. Read `src/main/java/com/bleach/mod/damage/BleachDamage.java` and use whatever it actually exposes; the call above is the shape, not necessarily the name.

- [ ] **Step 3: Register a no-op renderer**

```java
package com.bleach.mod.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Draws nothing. The reishi arrow is visible only as its particle trail, but Minecraft still needs a
 * renderer registered for the entity type or it logs an error every time one spawns.
 */
public class InvisibleEntityRenderer<T extends Entity> extends EntityRenderer<T> {
	private static final ResourceLocation EMPTY =
			ResourceLocation.withDefaultNamespace("textures/misc/white.png");

	public InvisibleEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public boolean shouldRender(T entity, Frustum frustum, double x, double y, double z) {
		return false;
	}

	@Override
	public ResourceLocation getTextureLocation(T entity) {
		return EMPTY;
	}
}
```

In `BleachModClient.onInitializeClient`, add:

```java
		EntityRendererRegistry.register(BleachEntities.REISHI_ARROW, InvisibleEntityRenderer::new);
```

(import `net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry`).

In `BleachMod.onInitialize`, add `BleachEntities.register();` **after** `BleachTuning.load()` and before `BleachItems.register()`.

- [ ] **Step 4: Verify**

Run: `./gradlew build`

In-world, with a Quincy bow in hand (`/give @s bleach_mod:heilig_bogen_<kit>` once Task 14 registers one, or temporarily give a Shinigami kit a bow to test): hold right-click, release, and watch a particle trail travel and damage what it hits. Confirm nothing drops on the ground and no renderer error appears in `logs/latest.log`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bleach/mod/entity/ src/main/java/com/bleach/mod/client/InvisibleEntityRenderer.java src/main/java/com/bleach/mod/BleachMod.java src/main/java/com/bleach/mod/BleachModClient.java
git commit -m "feat(quincy): add ReishiArrow entity with particle-only rendering"
```

---

## Task 12: `onProjectileHit` hook

**Files:**
- Modify: `src/main/java/com/bleach/mod/ability/TransformAbility.java` (add the default method after `onMeleeHit` at `:53`)
- Modify: `src/main/java/com/bleach/mod/entity/ReishiArrow.java` (`onHitEntity`)

**Interfaces:**
- Consumes: `AbilityDispatcher.activeTransform(SpiritualData)`
- Produces: `TransformAbility.onProjectileHit(ServerPlayer, LivingEntity, float)`, defaulted empty

- [ ] **Step 1: Add the hook**

```java
	/**
	 * Projectile hook, live only while this transformation is — the exact mirror of
	 * {@link #onMeleeHit}. Defaulted empty, so every Shinigami kit ignores it.
	 *
	 * <p>This is what lets a Schrift react to its own arrows landing without any kit-id conditional
	 * in the projectile.
	 */
	default void onProjectileHit(ServerPlayer player, LivingEntity target, float damage) {
	}
```

- [ ] **Step 2: Call it from the arrow**

In `ReishiArrow.onHitEntity`, after applying damage:

```java
		if (getOwner() instanceof ServerPlayer shooter) {
			SpiritualData data = BleachAttachments.get(shooter);
			TransformAbility active = AbilityDispatcher.activeTransform(data);
			if (active != null) {
				active.onProjectileHit(shooter, target, damage);
			}
		}
```

- [ ] **Step 3: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, and no existing kit's behaviour changes — the default body is empty.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bleach/mod/ability/TransformAbility.java src/main/java/com/bleach/mod/entity/ReishiArrow.java
git commit -m "feat(ability): add onProjectileHit mirror of onMeleeHit"
```

---

## Task 13: The attribution fix

**Files:**
- Modify: `src/main/java/com/bleach/mod/progression/KillAttribution.java:77-100`

**Interfaces:**
- Consumes: `BleachDamage.is(DamageSource)`, `SpiritWeapon.isDrawn(Player)`
- Produces: `KillAttribution.payee` accepting the mod's own projectiles

**The trap:** accepting any projectile also makes **vanilla bow kills pay SPX**, contradicting `SETUP.md` §5 ("a bow kill pays nothing either") and the "a bow is a bow at every level" rule. The condition must be narrowed to the mod's own damage.

- [ ] **Step 1: Rewrite `payee`**

```java
	/**
	 * Whether this entity's death pays out, and to whom. Null for every rejected case, which is the
	 * overwhelming majority.
	 *
	 * <p>The killing blow must come from the player's own drawn spirit weapon — either directly, or
	 * through a projectile the mod itself fired. <b>A vanilla bow still pays nothing</b>: the
	 * projectile branch is gated on {@link BleachDamage#is}, so an ordinary arrow is rejected for the
	 * same reason it always was (PRD §3.2 — the weapon is the progression, not the fight).
	 *
	 * <p>Before this, {@code getDirectEntity() == killer} rejected <em>every</em> projectile, which
	 * silently paid zero for Gin's beam, Suì-Fēng's missile and Yamamoto's cone as well.
	 */
	@Nullable
	public static ServerPlayer payee(LivingEntity victim, DamageSource source) {
		KillCredit credit = victim.getAttachedOrCreate(BleachAttachments.KILL_CREDIT);
		if (credit.tainted || credit.soleDamager == null) {
			return null;
		}

		ServerPlayer killer = attackerOf(source);
		if (killer == null || !credit.soleDamager.equals(killer.getUUID())) {
			return null;
		}
		if (!SpiritWeapon.isDrawn(killer)) {
			return null;
		}
		return isCreditedBlow(source, killer) ? killer : null;
	}

	/**
	 * The killing blow itself: the player's own hand, or one of the mod's own damage sources.
	 * Anything else — a vanilla arrow, a trident, a mob's hit — is not a credited blow.
	 */
	private static boolean isCreditedBlow(DamageSource source, ServerPlayer killer) {
		if (source.getDirectEntity() == killer) {
			return true;
		}
		return BleachDamage.is(source);
	}
```

Add the import `com.bleach.mod.damage.BleachDamage` and swap `Zanpakuto` for `SpiritWeapon`.

- [ ] **Step 2: Verify — this is the one that must not be taken on trust**

Run: `./gradlew build`

In-world, on a fresh mob each time (taint is permanent):
1. Drawn zanpakutō, melee kill → **pays SPX**, `+N SPX` popup appears. (Unchanged.)
2. Drawn zanpakutō, **vanilla bow** in the offhand, bow kill → **pays nothing**. This is the regression check for the trap.
3. Drawn bow, reishi arrow kill → **pays SPX**.
4. Gin Bankai beam kill on an untouched mob → **pays SPX**, where it previously paid nothing.
5. Let a skeleton hit the mob first, then kill it cleanly → **pays nothing**. (Taint unchanged.)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bleach/mod/progression/KillAttribution.java
git commit -m "fix(progression): credit the mod's own projectiles for SPX, never vanilla ones"
```

---

## Task 14: `QuincyTransform` and the shared Vollständig

**Files:**
- Create: `src/main/java/com/bleach/mod/ability/kits/QuincyTransform.java`
- Modify: `src/main/java/com/bleach/mod/tuning/BleachTuning.java` (§P.0)
- Modify: `BALANCE.md` §P.0

**Interfaces:**
- Consumes: `TransformAbility`, `SpiritualData.gatePercent`
- Produces:
  - `abstract class QuincyTransform implements TransformAbility`
  - `protected abstract void onTierEnter(ServerPlayer, SpiritualData)` / `onTierTick` / `onTierRevert`
  - `QuincyTransform.Tier1` / `QuincyTransform.Tier2` abstract subclasses supplying `state()`, `drainPerSecond()`, `entryGatePercent()`

- [ ] **Step 1: Add the Vollständig tuning keys**

```java
	/** Vollständig movement speed bonus, ADD_MULTIPLIED_TOTAL. */
	public static double VOLL_SPEED = 0.35;
	/** Vollständig bleach melee and arrow damage bonus. */
	public static double VOLL_DMG = 0.45;
	/** Vollständig Flash Step (Hirenkyaku) range multiplier while active. */
	public static double VOLL_FS_RANGE_MULT = 1.35;
	/** Wing particles emitted per tick behind the shoulders. */
	public static int VOLL_WING_PARTICLES = 6;
	/** How far behind the player the wing arc sits, blocks. */
	public static double VOLL_WING_OFFSET = 0.45;
	/** Radius of the wing arc, blocks. */
	public static double VOLL_WING_RADIUS = 1.1;
```

Add matching `BALANCE.md` §P.0 rows.

- [ ] **Step 2: Write the base**

```java
package com.bleach.mod.ability.kits;

import com.bleach.mod.ability.TransformAbility;
import com.bleach.mod.attachment.SpiritualData;
import com.bleach.mod.particle.PressureParticleOptions;
import com.bleach.mod.tuning.BleachTuning;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The shared half of every Schrift · design §5.1.
 *
 * <p>Tier 1 is the letter's own power and this base holds almost nothing for it. Tier 2 is
 * Vollständig, and this base holds <b>all</b> of it — the stat package, the Hirenkyaku bonus and the
 * wings — leaving a subclass to supply only an amplified form of its tier-1 power.
 *
 * <p>That split is what makes four Schrifts affordable: a Schrift is one power written at two
 * intensities, not two unrelated kits.
 *
 * <p><b>Per-player state must never be an instance field.</b> One kit object is shared by every
 * player who picked that Schrift; a field would put all of them on one cooldown. Use a
 * {@code Map<UUID, …>} keyed by the player, as {@code RukiaTransform} does.
 */
public abstract class QuincyTransform implements TransformAbility {

	private static final ResourceLocation VOLL_SPEED_ID =
			ResourceLocation.fromNamespaceAndPath("bleach_mod", "vollstandig_speed");

	private final ResourceLocation id;

	protected QuincyTransform(ResourceLocation id) {
		this.id = id;
	}

	@Override
	public ResourceLocation id() {
		return id;
	}

	/** The Schrift's own entry work. */
	protected abstract void onTierEnter(ServerPlayer player, SpiritualData data);

	/** The Schrift's own per-tick work. */
	protected abstract void onTierTick(ServerPlayer player, SpiritualData data);

	/** The Schrift's own teardown. Must be idempotent — see {@link TransformAbility#onRevert}. */
	protected abstract void onTierRevert(ServerPlayer player, SpiritualData data);

	/** Whether this tier is Vollständig and should carry the shared release package. */
	protected boolean isVollstandig() {
		return state() == SpiritualData.STATE_BANKAI;
	}

	@Override
	public void onEnter(ServerPlayer player, SpiritualData data) {
		if (isVollstandig()) {
			applySpeed(player);
		}
		onTierEnter(player, data);
	}

	@Override
	public void onTick(ServerPlayer player, SpiritualData data) {
		if (isVollstandig()) {
			drawWings(player);
		}
		onTierTick(player, data);
	}

	@Override
	public void onRevert(ServerPlayer player, SpiritualData data) {
		removeSpeed(player);
		onTierRevert(player, data);
	}

	@Override
	public double meleeDamageBonus() {
		return isVollstandig() ? BleachTuning.VOLL_DMG : 0.0;
	}

	private static void applySpeed(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		speed.removeModifier(VOLL_SPEED_ID);
		speed.addPermanentModifier(new AttributeModifier(VOLL_SPEED_ID,
				BleachTuning.VOLL_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	/** Idempotent, and safe on a dead or unloaded player — every revert path reaches it. */
	protected static void removeSpeed(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(VOLL_SPEED_ID);
		}
	}

	/**
	 * The wings — an arc of pressure particles behind the shoulders, tinted by the kit colour. No
	 * model and no texture; this is the one visual that reads at range as "in Vollständig".
	 */
	private void drawWings(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		double yaw = Math.toRadians(player.getYRot());
		double backX = Math.sin(yaw) * BleachTuning.VOLL_WING_OFFSET;
		double backZ = -Math.cos(yaw) * BleachTuning.VOLL_WING_OFFSET;

		for (int i = 0; i < BleachTuning.VOLL_WING_PARTICLES; i++) {
			double t = (i / (double) Math.max(1, BleachTuning.VOLL_WING_PARTICLES)) * Math.PI;
			double spread = Math.cos(t) * BleachTuning.VOLL_WING_RADIUS;
			double lift = Math.sin(t) * BleachTuning.VOLL_WING_RADIUS;

			level.sendParticles(new PressureParticleOptions(wingColour(), 0.7f),
					player.getX() + backX + Math.cos(yaw) * spread,
					player.getY() + 1.0 + lift,
					player.getZ() + backZ + Math.sin(yaw) * spread,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/** Overridden by a Schrift that wants its own wing colour; defaults to white. */
	protected int wingColour() {
		return 0xFFFFFF;
	}

	/** Release 1 — the Schrift. Sits in the Shikai slot and inherits its gate and drain. */
	public abstract static class Tier1 extends QuincyTransform {
		protected Tier1(ResourceLocation id) {
			super(id);
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_SHIKAI;
		}

		@Override
		public double drainPerSecond() {
			return BleachTuning.DRAIN_SHIKAI;
		}

		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_SHIKAI, soulLevel);
		}
	}

	/** Release 2 — Vollständig. Sits in the Bankai slot, loan and claw-back included. */
	public abstract static class Tier2 extends QuincyTransform {
		protected Tier2(ResourceLocation id) {
			super(id);
		}

		@Override
		public byte state() {
			return SpiritualData.STATE_BANKAI;
		}

		@Override
		public double drainPerSecond() {
			return BleachTuning.DRAIN_BANKAI;
		}

		@Override
		public double entryGatePercent(int soulLevel) {
			return SpiritualData.gatePercent(SpiritualData.STATE_BANKAI, soulLevel);
		}
	}
}
```

> **Check before writing:** the real constructor of `PressureParticleOptions`. Read `src/main/java/com/bleach/mod/particle/PressureParticleOptions.java` and match it; `(colour, scale)` is the assumed shape.

- [ ] **Step 3: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. No kit uses this yet, so nothing changes in-game — that is correct at this step.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bleach/mod/ability/kits/QuincyTransform.java src/main/java/com/bleach/mod/tuning/BleachTuning.java BALANCE.md
git commit -m "feat(quincy): add QuincyTransform base and shared Vollstandig package"
```

---

## Task 15: Blut

**Files:**
- Create: `src/main/java/com/bleach/mod/ability/common/Blut.java`
- Create: `src/test/java/com/bleach/mod/ability/common/BlutStanceTest.java`
- Modify: `src/main/java/com/bleach/mod/ability/AbilityAction.java` (append `BLUT_CYCLE`)
- Modify: `src/main/java/com/bleach/mod/ability/AbilityDispatcher.java` (route it)
- Modify: `src/main/java/com/bleach/mod/client/BleachKeybinds.java` (the `Z` binding)
- Modify: `src/main/resources/assets/bleach_mod/lang/en_us.json`
- Modify: `src/main/java/com/bleach/mod/progression/DamageScaling.java`
- Modify: `src/main/java/com/bleach/mod/attachment/SpiritualTicker.java` (bill it)
- Modify: `src/main/java/com/bleach/mod/client/SpiritualHud.java` (border tint)
- Modify: `src/main/java/com/bleach/mod/tuning/BleachTuning.java`, `BALANCE.md`

**Interfaces:**
- Consumes: `SpiritualData.blut` (Task 5), `Race.hasBlut()` (Task 2)
- Produces:
  - `Blut.OFF = 0`, `Blut.VENE = 1`, `Blut.ARTERIE = 2`
  - `Blut.cycle(byte current)` → `byte` *(pure, unit tested)*
  - `Blut.damageTakenMultiplier(byte stance)` / `damageDealtMultiplier(byte stance)` *(pure, unit tested)*
  - `Blut.tickCost(SpiritualData)` → `double`
  - `Blut.tickAll(MinecraftServer)`

- [ ] **Step 1: Add tuning keys**

```java
	/** Damage taken multiplier reduction while Blut Vene is up. */
	public static double BLUT_VENE_REDUCTION = 0.25;
	/** Movement speed penalty while Blut Vene is up, ADD_MULTIPLIED_TOTAL. */
	public static double BLUT_VENE_SPEED_PENALTY = -0.15;
	/** Bleach damage dealt bonus while Blut Arterie is up. */
	public static double BLUT_ARTERIE_BONUS = 0.30;
	/** Blut drain at SL 1, SP/s. Additive with the release drain. */
	public static double BLUT_DRAIN_BASE = 2.0;
	/** Drain reduction per Soul Level. */
	public static double BLUT_DRAIN_PER_LEVEL = 0.06;
	/** SP bar border colour while Vene is up. */
	public static int BLUT_COLOR_VENE = 0x60A5FA;
	/** SP bar border colour while Arterie is up. */
	public static int BLUT_COLOR_ARTERIE = 0xDC2626;
```

Add matching `BALANCE.md` §P.0 rows.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/bleach/mod/ability/common/BlutStanceTest.java`:

```java
package com.bleach.mod.ability.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bleach.mod.tuning.BleachTuning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BlutStanceTest {

	@BeforeEach
	void pinTuning() {
		BleachTuning.BLUT_VENE_REDUCTION = 0.25;
		BleachTuning.BLUT_ARTERIE_BONUS = 0.30;
	}

	@Test
	void cycleGoesOffVeneArterieOff() {
		assertEquals(Blut.VENE, Blut.cycle(Blut.OFF));
		assertEquals(Blut.ARTERIE, Blut.cycle(Blut.VENE));
		assertEquals(Blut.OFF, Blut.cycle(Blut.ARTERIE));
	}

	@Test
	void anUnknownStanceCyclesBackToOff() {
		assertEquals(Blut.OFF, Blut.cycle((byte) 42));
	}

	@Test
	void veneReducesDamageTakenAndArterieDoesNot() {
		assertEquals(0.75, Blut.damageTakenMultiplier(Blut.VENE), 1e-9);
		assertEquals(1.0, Blut.damageTakenMultiplier(Blut.ARTERIE), 1e-9);
		assertEquals(1.0, Blut.damageTakenMultiplier(Blut.OFF), 1e-9);
	}

	@Test
	void arterieRaisesDamageDealtAndVeneDoesNot() {
		assertEquals(1.30, Blut.damageDealtMultiplier(Blut.ARTERIE), 1e-9);
		assertEquals(1.0, Blut.damageDealtMultiplier(Blut.VENE), 1e-9);
		assertEquals(1.0, Blut.damageDealtMultiplier(Blut.OFF), 1e-9);
	}

	@Test
	void theTwoStancesAreMutuallyExclusiveByConstruction() {
		// There is no stance for which both multipliers move.
		for (byte stance = 0; stance <= 2; stance++) {
			boolean defensive = Blut.damageTakenMultiplier(stance) != 1.0;
			boolean offensive = Blut.damageDealtMultiplier(stance) != 1.0;
			assertEquals(false, defensive && offensive, "stance " + stance + " is both");
		}
	}

	@Test
	void reductionIsFlooredSoAConfigCannotTurnDamageIntoHealing() {
		BleachTuning.BLUT_VENE_REDUCTION = 5.0;
		assertEquals(0.0, Blut.damageTakenMultiplier(Blut.VENE), 1e-9);
	}
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew test --tests "com.bleach.mod.ability.common.BlutStanceTest"`
Expected: FAIL — `cannot find symbol: class Blut`.

- [ ] **Step 4: Write the pure half**

Create `Blut.java` with the pure statics first — no Minecraft imports in these four methods, which is what keeps the test Tier 1:

```java
package com.bleach.mod.ability.common;

import com.bleach.mod.tuning.BleachTuning;

/**
 * Blut — the reishi a Quincy circulates through their own body · design §5.4. Defensive (Vene) or
 * offensive (Arterie), never both.
 *
 * <p>The four methods below are pure arithmetic and unit tested. The billing and the stance change
 * live further down and need a server.
 */
public final class Blut {
	private Blut() {
	}

	public static final byte OFF = 0;
	public static final byte VENE = 1;
	public static final byte ARTERIE = 2;

	/** OFF → Vene → Arterie → OFF. An unrecognised value returns to OFF rather than sticking. */
	public static byte cycle(byte current) {
		return switch (current) {
			case OFF -> VENE;
			case VENE -> ARTERIE;
			default -> OFF;
		};
	}

	/** Multiplier on damage taken. Floored at zero — a config file can hold any number. */
	public static double damageTakenMultiplier(byte stance) {
		if (stance != VENE) {
			return 1.0;
		}
		return Math.max(0.0, 1.0 - BleachTuning.BLUT_VENE_REDUCTION);
	}

	/** Multiplier on bleach damage dealt. */
	public static double damageDealtMultiplier(byte stance) {
		if (stance != ARTERIE) {
			return 1.0;
		}
		return Math.max(0.0, 1.0 + BleachTuning.BLUT_ARTERIE_BONUS);
	}

	/** SP charged per tick while a stance is up. Additive with the release drain. */
	public static double tickCost(int soulLevel) {
		double perSecond = Math.max(0.0,
				BleachTuning.BLUT_DRAIN_BASE - BleachTuning.BLUT_DRAIN_PER_LEVEL * (soulLevel - 1));
		return perSecond / BleachTuning.TICKS_PER_SECOND;
	}
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew test --tests "com.bleach.mod.ability.common.BlutStanceTest"`
Expected: PASS — 6 tests.

- [ ] **Step 6: Wire the server half**

Append to `Blut.java` (these need Minecraft and are Tier 2):

```java
	/** Charge every player holding a stance, dropping anyone who cannot pay. Called by the ticker. */
	public static void tickAll(net.minecraft.server.MinecraftServer server) {
		for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
			com.bleach.mod.attachment.SpiritualData data =
					com.bleach.mod.attachment.BleachAttachments.get(player);
			if (data.blut == OFF) {
				continue;
			}

			double cost = tickCost(data.soulLevel);
			if (data.sp < cost) {
				data.blut = OFF;
				player.displayClientMessage(
						net.minecraft.network.chat.Component.literal("Blut fades."), true);
				continue;
			}
			data.spend(cost);
		}
	}
```

In `SpiritualTicker.onEndTick`, call `Blut.tickAll(server);` alongside `SpiritualFlex.tickAll` and `Hover.tickAll` — **before** the per-player pool pass, so the spend is in the pool when the bar syncs.

- [ ] **Step 7: Wire the input**

Append to `AbilityAction`, **after `HOVER_STOP`**:

```java
	/** Cycle the Blut stance: off → Vene → Arterie → off. Quincy only. */
	BLUT_CYCLE;
```

In `AbilityDispatcher.handle`'s switch, add:

```java
			case BLUT_CYCLE -> cycleBlut(player, data);
```

and the method:

```java
	private static void cycleBlut(ServerPlayer player, SpiritualData data) {
		if (!Races.byId(data.race).hasBlut()) {
			return;   // Shinigami: the key does nothing at all
		}

		byte next = Blut.cycle(data.blut);
		if (next != Blut.OFF && data.sp < Blut.tickCost(data.soulLevel)) {
			player.displayClientMessage(
					Component.literal("Not enough spiritual pressure."), true);
			return;
		}
		data.blut = next;
		SpiritualTicker.sync(player, true);
	}
```

In `BleachKeybinds`, register and read the binding:

```java
	private static final KeyMapping BLUT = register("blut", GLFW.GLFW_KEY_Z);
```

and in the tick loop, alongside the other press-style keys:

```java
		while (BLUT.consumeClick()) {
			send(AbilityAction.BLUT_CYCLE);
		}
```

Add to `en_us.json`:

```json
	"key.bleach_mod.blut": "Blut (cycle)",
```

- [ ] **Step 8: Apply the multipliers**

In `DamageScaling.apply`, inside the existing victim block after the bleach reduction:

```java
				// Blut Vene · design §5.4. 1.21.1 has no damage-taken attribute, so it lands here
				// with the Soul Level reductions rather than being scattered.
				damage *= Blut.damageTakenMultiplier(BleachAttachments.get(victimPlayer).blut);
```

and inside the bleach-dealt block, after the Soul Level bonus:

```java
					damage *= Blut.damageDealtMultiplier(attackerData.blut);
```

- [ ] **Step 9: The HUD tell**

In `SpiritualHud`, where the border is drawn, choose the border colour from the synced stance:

```java
		int borderColour = switch (state.blut()) {
			case 1 -> BleachTuning.BLUT_COLOR_VENE;
			case 2 -> BleachTuning.BLUT_COLOR_ARTERIE;
			default -> existingBorderColour;
		};
```

- [ ] **Step 10: Verify**

Run: `./gradlew build` and `./gradlew test`

In-world:
1. As a **Shinigami**, press `Z`. Nothing happens — no message, no drain, no tint. Non-negotiable.
2. As a Quincy, `Z` cycles blue tint → red tint → none, with SP draining while either is up.
3. In Vollständig with Arterie up, confirm the drains **add** (`/bleach sp get` falling faster than either alone).
4. Set SP to 1 and confirm the stance drops itself with "Blut fades." rather than pinning SP at zero.

- [ ] **Step 11: Commit**

```bash
git add -A src/main/java src/test/java src/main/resources BALANCE.md
git commit -m "feat(quincy): add Blut stance with drain, damage multipliers and HUD tell"
```

---

## Task 16: Acceptance commands

**Files:**
- Modify: `src/main/java/com/bleach/mod/command/BleachCommands.java` (add `test` subcommands beside `clawback` at `:137-142`)

**Interfaces:**
- Consumes: everything above
- Produces: `/bleach test race`, `/bleach test bow`, `/bleach test attribution`, `/bleach test blut`

These follow the `/bleach test clawback` precedent exactly (`BleachCommands.java:323-354`): assert in-world, print `PASS ·` or `FAIL ·`, and **return 1 on pass, 0 on fail** so they can be chained from a command block or a function file.

- [ ] **Step 1: Add `/bleach test race`**

Asserts the invariants that no unit test can reach:

```java
	private static int testRace(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		StringBuilder failures = new StringBuilder();

		// 1. Shinigami must be inert.
		if (Races.SHINIGAMI.reishiSensitivity() != 0.0 || Races.SHINIGAMI.hasBlut()) {
			failures.append("Shinigami is not inert; ");
		}

		// 2. The two statements of a kit's race must agree · Task 9 step 4.
		for (Kit kit : AbilityRegistry.kits()) {
			Race declared = kit.race();
			Race table = BleachKits.raceOf(kit.id());
			if (declared.id() != table.id()) {
				failures.append(kit.id()).append(" race mismatch; ");
			}
		}

		// 3. Every kit has a registered weapon.
		for (Kit kit : AbilityRegistry.kits()) {
			if (BleachItems.weaponFor(kit.id()) == null) {
				failures.append(kit.id()).append(" has no weapon; ");
			}
		}

		if (failures.isEmpty()) {
			source.sendSuccess(() -> Component.literal("PASS · race seam consistent"), false);
			return 1;
		}
		source.sendFailure(Component.literal("FAIL · " + failures));
		return 0;
	}
```

- [ ] **Step 2: Add `/bleach test bow`**

Asserts the bow inherits every zanpakutō guarantee:

```java
	private static int testBow(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		StringBuilder failures = new StringBuilder();

		for (Kit kit : AbilityRegistry.kitsFor(Races.QUINCY)) {
			ItemStack stack = new ItemStack(BleachItems.weaponFor(kit.id()));
			if (!SpiritWeapon.isSpiritWeapon(stack)) {
				failures.append(kit.id()).append(" not a spirit weapon; ");
			}
			if (!SpiritWeapon.isUndroppable(stack)) {
				failures.append(kit.id()).append(" is droppable; ");
			}
			if (stack.getItem().canFitInsideContainerItems()) {
				failures.append(kit.id()).append(" fits in a bundle; ");
			}
		}

		if (failures.isEmpty()) {
			source.sendSuccess(() -> Component.literal("PASS · bows carry the weapon guarantees"), false);
			return 1;
		}
		source.sendFailure(Component.literal("FAIL · " + failures));
		return 0;
	}
```

- [ ] **Step 3: Add `/bleach test attribution`**

The important one — it catches the Task 13 trap, where a correct-looking fix silently starts paying for vanilla bows:

```java
	private static int testAttribution(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = player.serverLevel();

		// A vanilla arrow from this player must NOT be a credited blow, even with a weapon drawn.
		Arrow vanilla = new Arrow(level, player, new ItemStack(Items.ARROW), null);
		DamageSource vanillaSource = level.damageSources().arrow(vanilla, player);
		boolean vanillaCredited = BleachDamage.is(vanillaSource);

		// One of ours must be.
		ReishiArrow ours = new ReishiArrow(player, level, null);
		DamageSource ourSource = BleachDamage.spiritPressure(level, player);
		boolean oursCredited = BleachDamage.is(ourSource);

		vanilla.discard();
		ours.discard();

		if (!vanillaCredited && oursCredited) {
			source.sendSuccess(() -> Component.literal(
					"PASS · vanilla arrows pay nothing, reishi arrows pay"), false);
			return 1;
		}
		source.sendFailure(Component.literal("FAIL · vanillaCredited=" + vanillaCredited
				+ " oursCredited=" + oursCredited));
		return 0;
	}
```

> Adjust the `DamageSource` construction to whatever `BleachDamage` actually exposes — read it first.

- [ ] **Step 4: Add `/bleach test blut`**

```java
	private static int testBlut(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		SpiritualData data = BleachAttachments.get(player);
		StringBuilder failures = new StringBuilder();

		if (Blut.cycle(Blut.ARTERIE) != Blut.OFF) {
			failures.append("cycle does not return to off; ");
		}
		if (Blut.damageTakenMultiplier(Blut.VENE) >= 1.0) {
			failures.append("Vene does not reduce damage taken; ");
		}
		if (Blut.damageDealtMultiplier(Blut.ARTERIE) <= 1.0) {
			failures.append("Arterie does not raise damage dealt; ");
		}
		if (!Races.byId(data.race).hasBlut() && data.blut != Blut.OFF) {
			failures.append("a race without Blut is holding a stance; ");
		}

		if (failures.isEmpty()) {
			source.sendSuccess(() -> Component.literal("PASS · Blut consistent"), false);
			return 1;
		}
		source.sendFailure(Component.literal("FAIL · " + failures));
		return 0;
	}
```

- [ ] **Step 5: Register the subcommands**

Beside the existing `clawback` branch:

```java
					.then(admin("test")
							.then(Commands.literal("clawback") /* ...existing... */)
							.then(Commands.literal("race").executes(ctx -> testRace(ctx.getSource())))
							.then(Commands.literal("bow").executes(ctx -> testBow(ctx.getSource())))
							.then(Commands.literal("attribution")
									.executes(ctx -> testAttribution(ctx.getSource())))
							.then(Commands.literal("blut").executes(ctx -> testBlut(ctx.getSource()))))
```

- [ ] **Step 6: Verify**

Run: `./gradlew build`

In-world, opped: run all four. All four must print `PASS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/bleach/mod/command/BleachCommands.java
git commit -m "test: add /bleach test race, bow, attribution and blut"
```

---

## Task 17: Documentation

**Files:**
- Modify: `GUIDE.md` (Controls table, a Quincy section)
- Modify: `src/main/java/com/bleach/mod/command/BleachGuide.java`
- Modify: `QUINCY_STATUS.md` (mark the foundation done)

**Interfaces:**
- Consumes: everything
- Produces: no code interface

- [ ] **Step 1: Make the guide iterate**

`BleachGuide.topics()` (`:45-48`) is a hand-maintained list covering five of eight kits — Aizen, Tōsen and Gin are already missing. Replace the hardcoded character list with iteration over `AbilityRegistry.kits()`, grouped by race, so it cannot fall behind again.

- [ ] **Step 2: Update `GUIDE.md`**

Add `Z` to the Controls table as "Blut — cycle Vene / Arterie / off (Quincy only)". Add a Quincy section covering the bow, the two tiers, Blut, and ambient reishi. Correct "one of five characters" to reflect the real count.

- [ ] **Step 3: Update `QUINCY_STATUS.md`**

Move the foundation items out of §2 open questions, and record that §4.1 (picker) and §4.3 (attribution) are now fixed.

- [ ] **Step 4: Verify**

Run: `./gradlew build`, then `/bleach guide` in-world and confirm every registered kit has a topic.

- [ ] **Step 5: Commit**

```bash
git add GUIDE.md QUINCY_STATUS.md src/main/java/com/bleach/mod/command/BleachGuide.java
git commit -m "docs: document the Quincy race and make the guide iterate kits"
```

---

## Self-review

**Spec coverage.** Every section of the design maps to a task:

| Spec | Task |
|---|---|
| §3.1 race data | 4 |
| §3.2 `Race` record | 2 *(with the two documented deviations)* |
| §3.3 ownership of the truth | 6, 8 |
| §3.4 four hooks | 7 (regen), 9 (items), 15 (Blut gate), 17 (guide) |
| §3.5 ambient reishi | 2b, 3, 7 |
| §4.1 `SpiritWeapon` | 9 |
| §4.2 `HeiligBogenItem` | 10 |
| §4.3 `ReishiArrow` | 11 |
| §4.3 `onProjectileHit` | 12 |
| §4.4 attribution fix + trap | 13, 16 |
| §5.1 `QuincyTransform` | 14 |
| §5.2 wings | 14 |
| §5.3 balance parity | 14 (`Tier1`/`Tier2` reuse the Shinigami gates and drains) |
| §5.4 Blut | 15 |
| §9.1 picker cap | 8 |
| §9.4 wrong-token bug | 8 step 4 |
| §11 testing | 1 (harness), 16 (commands) |
| §13 `BALANCE.md` §P | 2b, 10, 14, 15 |

**Gaps accepted and why.** §9.4's other seven bugs (FreezeEffect ×2, `copyVolatileFrom`, `MOB_SCALE_HEALTH`, `ServerFlightFix`, the Aizen model override, `GuiHealthMixin`) are **not** in this plan. They are real and worth fixing, but none blocks Quincy and each touches Suhas's half; they belong in a separate pass he reviews. `QUINCY_STATUS.md` §4.4 keeps the list.

**Type consistency checked.** `Race.reishiSensitivity()` (double, not a function) is used consistently in Tasks 2, 3 and 7. `SpiritWeapon.isSpiritWeapon` is the renamed `isZanpakuto` and is used under the new name in Tasks 10, 13 and 16. `BleachItems.weaponFor` replaces `zanpakutoFor` in Tasks 9 and 16. `Blut.tickCost(int soulLevel)` takes the level, not the data object, in both its definition (15) and its callers (15, 16).

**Two forward references are called out at their sites rather than reordered**, because splitting them would produce a task that cannot compile on its own: `RaceWeapons` → `HeiligBogenItem` (Task 9 step 2 says to stub it), and `HeiligBogenItem` → `ReishiArrow` (Task 10 step 5 says to land 10 and 11 together).

**Three places tell the implementer to read the real source before writing**, rather than trusting a guessed signature: `BleachDamage`'s factory method (Tasks 11 and 16) and `PressureParticleOptions`' constructor (Task 14). Everything else was verified with `javap` against the mapped 1.21.1 jar.
