# Quincy Faction — Status

**State:** **the 18-task foundation is complete** — all 17 numbered tasks plus 2b have landed on a
local `quincy` branch. `./gradlew build` and `./gradlew test` are both green (25 JUnit tests).
**Nothing is in-world verified** — see §7, which is still the single largest caveat on the whole
branch, now with `/bleach test` as the designed compensating control.

**Nothing is pushed yet, but the endpoint has changed** (2026-09-08): the branch is to be merged up
to `origin/main` **as a pull request for Suhas to review**, rather than kept local indefinitely.
See §8 for the plan and the blockers. **Not started — do not execute any of §8 without the user.**
**Last session:** 2026-09-08.

**Branch:** `quincy`, branched from `main` @ `750cfb2`. 22 commits, all local.

| Task | Landed | Commit |
|---|---|---|
| 1 · JUnit harness | ✅ | `93805d7` |
| 2 · `Race` + `Races` | ✅ | `00f88c9` |
| 2b · Reishi tuning keys | ✅ | `c9f3174` |
| 3 · `ReishiDensity` | ✅ | `a8dfb82` |
| 4 · `SpiritualData.race` | ✅ | `d21ae57` |
| 5 · Sync race + Blut slot | ✅ | `6901df6` |
| 6 · Kits declare a race | ✅ | `382577a` |
| 7 · Reishi-modified regen | ✅ | `0d2f0ed` |
| 8 · Two-step picker | ✅ | `6888b5b` |
| 11 · `ReishiArrow` | ✅ | `0538664` |
| 10 · `HeiligBogenItem` | ✅ | `eff0440` |
| 9 · `SpiritWeapon` rename | ✅ | `fc3e871` |
| 12 · `onProjectileHit` | ✅ | `333fade` |
| 13 · Attribution fix | ✅ | `e88a170` |
| 14 · `QuincyTransform` | ✅ | `5882478` |
| 15 · Blut | ✅ | `54f1c3b` |
| 16 · Acceptance commands | ✅ | `420fc49` |
| 17 · Documentation | ✅ | *this commit* |

**Tasks 9/10/11 were executed in the order 11 → 10 → 9.** The plan's numbering does not compile:
Task 9's `RaceWeapons` imports Task 10's `HeiligBogenItem`, which imports Task 11's `ReishiArrow`.
The real dependency edges run arrow → bow → registry. The plan offered a stub-and-switch workaround;
reordering was taken instead, so no knowingly-wrong line ever landed and no window existed in which
a Quincy minted a sword. Commits therefore appear out of numeric order in `git log`.

### What the foundation does *not* include

The foundation is the **chassis**. There is still **no playable Quincy**: `BleachKits.IDS` has no
Quincy entry, so `AbilityRegistry.kitsFor(QUINCY)` is empty, the race screen shows Quincy with no
kits behind it, and nothing mints a Heilig Bogen. Everything below the kit is in place and waiting.

**The next piece of work is the Schrift plan (T/D/M/Z)** — see §2.4. The first Schrift is what
turns all of this on, and it is also what makes most of §7.2's manual pass runnable at all.

| Document | Path |
|---|---|
| Design spec | `docs/superpowers/specs/2026-09-07-quincy-faction-design.md` |
| Foundation plan (17 tasks) | `docs/superpowers/plans/2026-09-07-quincy-foundation.md` |
| Schrift plan (T/D/M/Z) | not written yet — comes after the foundation lands |

All three files, plus this one, are still **untracked** — deliberately. The 16 implementation commits
are tracked and local-only.

The working ledger for the run — every ruling, deferred minor, and plan defect found — is at
`.superpowers/sdd/2026-09-07-quincy-foundation/progress.md`, alongside the per-task briefs and
implementer reports. That directory is git-ignored scratch; §7 below carries everything from it that
outlives the run.

Companion to `PRD.md` (what the mod does), `BALANCE.md` (every number), `GUIDE.md` (player-facing).
This file is the working record for the Quincy faction until it earns a real design doc.

---

## 1. Decisions locked

| # | Decision | Consequence |
|---|---|---|
| 1 | **Race is the chassis, Schrift is the kit.** A player picks "Quincy", then picks a Schrift letter. | Bow, Blut, Hirenkyaku and Vollständig are shared Quincy baseline. The letter is the character, the way "Ichigo" is today. |
| 2 | **Two release tiers, same `state` byte.** `BASE / release 1 / release 2`, race-specific display names only. | `SpiritualData.state` (0/1/2) is untouched. Exertion, gates, the Bankai loan + claw-back, HUD notches and the `bleach_mod:released` model predicate all keep working. No state-machine rewrite. |
| 3 | **Letzt Stil is an orthogonal overlay**, not a third tier — it stacks on top of release 1 *or* 2. Same slot a Vizard mask would later fill for Shinigami. | Needs its own flag, its own keybind and its own gate, parallel to `state`. **Deferred: designed after tiers 1 and 2 work.** |
| 4 | **Bow = the drawn weapon.** The Heilig Bogen is a real item living in the attachment, swapped into the main hand on `X` — structural twin of the zanpakutō. | Undroppable / keep-on-death / restore-on-respawn / SPX-requires-drawn-weapon all work unchanged. `Zanpakuto.java` becomes race-aware rather than being duplicated. |
| 5 | **`R` = your Schrift's power. `G` = Vollständig.** | Keybinds are unchanged; only what they resolve to differs by race. |
| 6 | **Four Schrifts for v1:** T (Thunderbolt), D (Deathdealing), M (Miracle), Z (Zombie). | Chosen to avoid overlapping the eight Shinigami. **Z ships last** — see §4.3. Nothing on the scale of The Balance or The Almighty. |
| 7 | **One SP pool, environment-modified regen.** No second resource bar. A Quincy's regen is multiplied by ambient reishi density — high in open air and daylight, low underground, in the Nether, in water. | One hook in `SpiritualTicker.tickRegen`. No new field, no new sync component, no new HUD element. Gives Quincies positional play without forking the economy. |
| 8 | **Blut is a toggled stance on its own key**, proposed `Z`. Cycles Vene (defence) / Arterie (offence) / off — never both at once. Drains SP while held, stacking with the release drain. | Tenth keybind. Needs a synced flag, a HUD tell, and a drain that adds to the release drains the way Flex already does. |
| 9 | **The bow fires a real arrow entity.** Right-click charges, release fires a reishi arrow with travel time, drop and a particle trail. | Dodgeable — the counterplay a ranged faction needs against a melee roster. **Requires fixing `KillAttribution` to credit a projectile's owner**, which also repairs Gin, Suì-Fēng and Yamamoto (all silently paying zero SPX today). One fix, four characters. |
| 10 | **The four universal abilities carry over unchanged.** Flash Step, Spiritual Flex, Aura Sense, Hover. | They are already race-agnostic and sword-free (`requiresDrawnSword() → false`). Flash Step is merely *named* Hirenkyaku in the guide. Zero code, and cross-race parity stays intact. |
| 11 | **Race model B: a byte on `SpiritualData` plus a small `Race` lookup.** `Race` holds only what genuinely differs — weapon item, release-tier display names, regen multiplier hook, which optional bindings are live. Kits declare their race at registration. | Chosen over scattering `if (race == QUINCY)` across four files (A), and over a full first-class `Race` subsystem (C) before a second race has proven the abstraction. **B is designed to grow into C additively** when race choice becomes permanent and Hollows/Fullbringers land. |
| 12 | **Build Quincy-shaped, reconcile with Adil later.** | Accepted risk, taken with eyes open — see §3. Free hedge applied: `Race` carries tier names as a **list**, not a fixed pair, so Arrancar's single-tier resurrección fits without changing the abstraction. |

### Schrift sketches (not yet designed, one line each)

- **T — The Thunderbolt.** Burst. Marks a target and calls a real lightning bolt, chaining to nearby entities with falloff. Vollständig makes arrow hits spawn bolts. Fills the electric gap; vanilla lightning does the visuals for free.
- **D — The Deathdealing.** Attrition. Each hit adds a dose to that target; crossing their lethal threshold kills outright. A clock, where Suì-Fēng's mark is a position — no overlap in play.
- **M — The Miracle.** Tank. Damage taken feeds stacks that raise damage and max HP; Vollständig cashes stacks into a burst. The only "stronger as you lose" identity, and the roster's only bruiser.
- **Z — The Zombie.** Summoner. Mobs you kill rise as short-lived allies. Heaviest to build and collides with the SPX taint rules (see §4.3).

---

## 2. Open questions

0. ~~**Execution mode.**~~ **ANSWERED 2026-09-07: subagent-driven** — a fresh implementer per task,
   a spec-and-quality review after each, fix rounds where needed. Used for all 12 landed tasks.
   Three tasks needed exactly one fix round each (3, 8, 11); the rest passed first time.
1. **Letzt Stil.** Shape and cost. Options on the table were: timed burn + release lockout via a big exertion dump (recommended); low-HP-gated last stand; one-shot per life; charge-then-release. **Deferred by decision until tiers 1 and 2 are done.**
2. **Acquisition.** How a player becomes a Quincy at all — is race chosen at spawn, is there a Quincy Cross item paralleling the Asauchi, and what is the Reforged Asauchi equivalent? **Partly answered:** §4.1 is unblocked and the two-step picker's first screen is now where the race is chosen. What is still open is the *item*: `SpiritWeapon.ensureAsauchi` hands a character-less player an Asauchi regardless of race (§7.5), so a Quincy currently reaches their bow through a blank *blade*. Long-term intent (§3) is still a permanent race choice made before anything else.
3. ~~**Balance parity.**~~ **ANSWERED.** `BALANCE.md` has a §P of its own, organised P.0 shared race constants · P.1 Reishi Arrow · P.2 Heilig Bogen · P.3 Vollständig · P.4 Blut, each with its own §L consumption row. Quincy tiers reuse `DRAIN_SHIKAI` / `DRAIN_BANKAI` and `SpiritualData.gatePercent` unchanged (decision #2), so gate and drain parity is exact by construction rather than by tuning.
4. **The four Schrifts' actual numbers.** Sketches exist in §1; none are designed.

---

## 3. Team and roadmap

Three people, one race each. Fullbringers come after all three are done.

| Who | Owns |
|---|---|
| **Suhas** | Soul Reapers — the existing eight kits. Repo owner, originator of the project. |
| **Adil** | Hollows / Arrancars. |
| **(this user)** | Quincies — bow, Schrift, Vollständig, Letzt Stil. |

Build order agreed for the mod as a whole: **abilities → animations** (bow draw, release-2 cutscene, counters, mechanics) **→ maps, proper UI, subsystems.** Mechanics before presentation, so the balance pass stays honest.

**Long-term:** race is chosen once, at the start, and cannot be changed. At that point the race model should be upgraded from B to C (see decision #11) — a first-class `Race` subsystem owning weapon lifecycle, ability overrides and progression hooks.

### Known coordination risk — **it has now happened** (2026-09-08)

`origin/main` moved to **`d2d1722`** on 2026-09-07 while this branch was being built. The commit is
titled "TODO List: Notes & upcoming fixes" and its message *is* a playtest TODO list — but it
carries ~1,500 lines of new code, and it is authored by **Adil**, not Suhas.

**What landed upstream:**

- **A 9th kit, Shunsui Kyōraku** — `ShunsuiTransform`, `KaromatsuManager` (644 lines),
  `KatenShikaiManager`, a `KaromatsuSyncPayload`, a client state class and a HUD overlay.
- `BleachKits.IDS` is now **9 entries**, and `BleachTuning` grew ~122 lines of `KIT_SHUNSUI_*` and
  `SHUNSUI_*` / Karomatsu constants.
- `.gitignore` was reorganised and now also ignores `run/`, `logs/`, `crash-reports/` and `*.log`.
- One new asset: `models/item/zanpakuto_shunsui.json`, which is the usual three-line
  `{"parent": "bleach_mod:item/asauchi"}`.

**Two consequences that are not about merging:**

1. **The picker is now exactly full.** `ROW = 9` and Shinigami has 9 kits, so the race-two screen
   holds them with zero slots to spare. A 10th Shinigami kit is unselectable — it will now *say so*
   loudly (Task 8's guard) rather than vanishing silently, but it still will not appear. **Suhas and
   Adil need to know this before someone starts a 10th kit**; §4.1's "the failure mode only moved"
   note has stopped being theoretical.
2. **Playtest notes #6 and #7 land on Quincy.** #6 wants max Soul Level raised from 20 to ~100; #7
   wants Shikai drain tapering to zero past level 60–70 and Bankai drain reduced but never zero.
   Decision #2 has Quincy reusing `DRAIN_SHIKAI`, `DRAIN_BANKAI` and `SpiritualData.gatePercent`
   verbatim, so **Quincy would inherit both changes for free** — that is the good outcome and is
   exactly why decision #2 was taken. But §2.3's balance-parity question must be re-answered against
   a 100-level curve rather than a 20-level one, and `BALANCE.md` §D/§E assume 20 throughout.

### The original accepted risk

Adil is building Hollows on the same seam this design introduces (`SpiritualData`, the sync payload, `tickRegen`, the picker). Decision #12 is to build Quincy-shaped and reconcile afterwards rather than landing shared groundwork first.

The specific thing to watch: **Arrancar resurrección is one release tier, not two.** The tier-name list in `Race` is the hedge against that, but if Adil needs anything else race-level that Quincy did not, the reconciliation cost lands on whoever merges second. Worth a five-minute conversation with him before the seam is written, even though we are not blocking on it.

**Both Suhas and Adil need to approve this design before implementation starts.**

---

## 4. Blockers inherited from the v1 mod

Things that must be fixed *before* or *as part of* Quincy landing. All verified against the code.

### 4.1 The picker caps at 9 — hard blocker · ✅ FIXED (Task 8, `6888b5b`)

**Resolved by the two-step picker**, as recommended below. Race screen first, then that race's kits,
so each screen holds well under 9 and the 12-kit roster fits.

Two things worth knowing about the fix:

- The per-screen cap is **still `ROW = 9`**. Splitting by race clears the blocker for the known roster,
  but the *failure mode* only moved — from "12 total kits" to "10th kit within one race". A **loud
  warning** was added on both fill loops so an overflow now hits a log line instead of vanishing
  silently. A 9th Shinigami kit or 9th Schrift would still truncate; it would just say so.
- The brief's **Step 4 (the wrong-token fix)** landed too: `choose()` now prefers the *held* selector
  over the first one an inventory scan finds. Residual, inherent to that step's scope: with two
  selectors and **neither** held, it still resolves by first-index scan.

The original analysis follows.

`menu/ZanpakutoSelectMenu.java:46,62` — `ROW = 9` and the fill loop is `i < choices.size() && i < ROW`. There are already **8** kits. A 10th is **silently unselectable**: no error, no log, it just does not appear.

Four Quincy Schrifts take the roster to 12. Quincy cannot ship without changing this. Two ways out:

- **Widen the menu** to `GENERIC_9x3` (27 slots). Near one-line, holds every kit the mod will plausibly have.
- **Two-step picker** — pick race first (2 slots), then kit within that race (9 slots each). More code, but it is also the natural home for the race decision in §2.2 and §4.1, and it keeps each screen readable.

Recommendation: two-step, because the race choice needs a screen anyway.

### 4.2 `Kit` hard-requires both slots

`ability/Kit.java:27-39` throws unless it gets a non-null Shikai *and* Bankai, with `state()` matching each slot. Decision #2 keeps Quincy inside that contract, so **no change is needed** — but it also means every Schrift must define both tiers. A Schrift with only one idea is not shippable.

### 4.3 Ability and projectile kills pay zero SPX · ✅ FIXED (Task 13, `e88a170`)

`payee()` now accepts an indirect killing blow when — and only when — the source is one of the
mod's own (`BleachDamage.is`). Gin's beam, Suì-Fēng's missile and Yamamoto's cone pay SPX for the
first time; a **vanilla** bow still pays nothing, because a vanilla arrow is neither in the
`bleach` damage-type tag nor a `Player` direct entity. `/bleach test attribution` asserts both
halves of that, which is the check the build itself cannot make.

The drawn-weapon gate was kept, deliberately, as documented below — it needed no change, because
`SpiritWeapon.isDrawn` had already been widened in Task 9 to accept any spirit weapon.

The original analysis follows.

`ReishiArrow` was deliberately built so this fix can repair it: its damage source carries the shooter
as both causing- and direct-entity, verified at the bytecode level, and matches how the existing kits'
ability projectiles construct damage. **One caveat for whoever does Task 13:** the drawn-weapon gate in
`payee()` is documented as *intentional* (PRD §3.2) and is a separate thing from the
`getDirectEntity()` bug. Make it race-aware via `SpiritWeapon.isDrawn`; do not delete it.

`progression/KillAttribution.java:96` requires `source.getDirectEntity() == killer`. Gin's beam, Suì-Fēng's missile and Yamamoto's cone already never award SPX, which contradicts PRD §2.1 ("your own melee **and your own projectiles** both count toward 'you'").

**This lands directly on Quincy**, whose entire identity is ranged. If the bow fires a projectile entity, Quincy kills pay nothing at all until this is fixed. Fix it before the bow, not after.

`Z — The Zombie` compounds it: an allied zombie damaging your target sets the taint flag and voids the payout permanently. Z needs an ownership exemption in `KillAttribution`, which is why it ships last.

### 4.4 Other v1 bugs worth fixing while we are in here

| Where | Problem |
|---|---|
| `effect/FreezeEffect.java:39,51` | Damage cadence keys off `entity.tickCount % 20` but the method only runs when `duration % 10 == 0`. For many entities the phases never coincide and **Rukia's freeze deals no damage at all**. |
| `effect/FreezeEffect.java:53` | Damage source built with a **null attacker**, so every freeze tick taints the victim. Nothing Rukia freezes can ever pay SPX — including to Rukia. |
| `attachment/SpiritualData.java:282` | `copyVolatileFrom` has **no callers**. Dimension change drops SP/exertion/state to defaults, contradicting PRD §1.2 ("SP persists across dimension change"). |
| `tuning/BleachTuning.java:153` | `MOB_SCALE_HEALTH` is a **dead knob** — declared, documented in PRD §2.5, referenced nowhere. Setting it does nothing. |
| `ServerFlightFix.java:31` | Reflects on the Mojang-mapped field `allowFlight`; throws on a remapped production server and silently degrades. `InaccessibleObjectException` is not caught and would escape `SERVER_STARTING`. |
| `assets/.../models/item/zanpakuto_aizen.json` | The `released >= 0.5` override has no upper bound, so **Aizen's Bankai renders the Shikai model**. |
| `mixin/client/GuiHealthMixin.java` | `pushPose` at HEAD, `popPose` at RETURN. If any other mod cancels `renderPlayerHealth`, the pose stack leaks. Highest cross-mod conflict risk in the mod. |
| `menu/ZanpakutoSelectMenu.java:128` | ✅ **FIXED (Task 8).** `choose` consumed the *first* Asauchi found by inventory scan, not the one that opened the menu. Now prefers the held selector. Residual: with two selectors and neither held, still first-index. |
| `MeleeHooks:89`, `YamamotoTransform:309`, `GinTransform:58,135` | Unbounded per-player UUID maps with no disconnect eviction. One entry leaked per player who ever swung. |

### 4.5 Documentation drift · ✅ FIXED (Task 17)

`BleachGuide.topics()` and its index are now derived from `AbilityRegistry.kits()`, grouped by
race, so the list cannot fall behind the roster again; Aizen, Tōsen and Gin are reachable for the
first time, via a generated fallback page. `GUIDE.md` and `BleachGuide.basics()` no longer claim a
character count at all — the count was the thing that kept going stale. `GUIDE.md` gained a Quincy
section and the `Z` row in Controls.

**Still stale:** `PRD.md` still says five characters in two places (`PRD.md:213` and `:219`). It was left alone deliberately —
it is Suhas's document, describes v1 as shipped, and rewriting it from this branch would be the
kind of edit that causes a merge argument. Raise it with him instead.

The original analysis follows.

`PRD.md`, `GUIDE.md` and `BleachGuide.basics()` all still say **five characters**; there are eight. Aizen, Tōsen and Gin have no `/bleach guide` topic (`command/BleachGuide.java:45-48`) and no `/bleach <kit>` debug readout. Quincy will make this worse unless the guide is made data-driven off `BleachKits.IDS`.

---

## 5. Where things are

- Local clone: `C:\Users\Admin\Projects\BleachMod`
- `origin`: `github.com/SuhasS3106/BleachMod` — **do not push.** Work stays local until further notice.
- Verified: `./gradlew build` succeeds → `build/libs/bleach_mod-1.0.0.jar`. Still green at `fc3e871`,
  with 18 JUnit tests passing (harness + `Race`/`Races` + `ReishiDensity`).
- Build note: the Gradle wrapper cannot fetch its distribution behind a restrictive network (`networkTimeout=10000, retries=0` in `gradle/wrapper/gradle-wrapper.properties`). `gradle-9.5.1-bin.zip` has been placed in `~/.gradle/wrapper/dists` by hand; if a fresh machine hangs on "Downloading gradle-9.5.1", that is why.

---

## 6. What is next

The foundation is done. **Nothing in the plan is left to execute.** Two things follow it, in order:

1. **Run §7.2's manual pass.** It has never been run — no Minecraft client was available for any of
   the three sessions. Start with `/bleach test race`, `bow`, `attribution`, `blut` and `reishi`
   (Task 16): they are cheap, they are opped-only, and between them they cover the invariants that
   compile cleanly while being wrong. Then work §7.2 in its listed order, which is ordered by what
   would actually catch a regression. Items 7 and 8 (bow and arrow) need a Quincy kit first.
2. **Write and execute the Schrift plan** (T/D/M/Z, §1 decision #6, §2.4). This is the piece that
   makes a Quincy playable at all. Design each letter's numbers first — none of the four sketches
   in §1 has been designed — then implement, Z last for the reason in §4.3.

Carried forward from the run, still true and still uncommitted to any plan:

- **Everything in §4.4** is still open apart from the two picker rows. Rukia's freeze dealing no
  damage and `copyVolatileFrom` having no callers are the two worth doing first; both are v1 bugs
  that will be blamed on the Quincy branch by whoever merges it.
- **Everything in §7.5** — most importantly, `MeleeHooks.onAfterDamage` will fire a kit's melee
  hooks on a bow-bash. That becomes reachable the moment the first Schrift exists, and no task in
  any plan addresses it. **Fix it as part of the first Schrift, not after.**
- **§7.6's cosmetic list** was never triaged.

Execution mode for the Schrift plan is unsettled again. Tasks 1–11 were subagent-driven, one
implementer per task with a review after each. Tasks 12–17 were executed directly in one session
against a 30-minute deadline, with the same compile-and-test gate but no separate review pass —
**so tasks 12–17 have had no second pair of eyes on them.** A whole-branch review is the obvious
thing to do before the Schrift work starts.

## 7. Run notes — things that outlive the ledger

### 7.1 Nothing is in-world verified

The plan's Tier 2 verification is *compile gate **plus** in-world assertion*. **Only the compile gate
ran.** No Minecraft client was available for any session, so every task states plainly that it
substituted static checks. That is the single biggest caveat on all 18 landed tasks, and it has not
changed.

Task 16's `/bleach test` commands now exist and are the designed compensating control — but they
have themselves never been run in a world. `/bleach test reishi` in particular does something no
other command here does: **it moves the world clock** to noon and then midnight to prove the sky
light read is time-invariant, restoring the original time in a `finally`. Read it before running it
on anything you care about.

### 7.2 Manual pass — run these before trusting the branch

Ordered by what would actually catch a regression.

1. **Load a pre-existing save with a *sheathed* character and press draw.** Do it for two kits. This is
   the one unrecoverable path: the stowed blade persists **by registry id** in attachment NBT, so a
   changed id would deserialize to air and the blade would be silently gone. Ids were verified
   string-identical (`weaponPrefix()` is literally `"zanpakuto"`), but `/give` **cannot** detect this —
   only an existing save can.
2. **Die and respawn twice, once drawn and once sheathed**, plus the Reforged-owed case (`stowedReforged`).
3. **The four undroppable paths individually**: Q-drop while holding; Q-drop while the blade is in the
   selected slot but *not* the main hand (the two disjuncts in the drop mixins are separately
   reachable); shift-click into a chest; number-key swap; offhand swap.
4. **Shikai/Bankai on a kit with model overrides** — proves `BleachItems.zanpakuto()` still feeds
   `ZanpakutoModels` a non-empty blade-only list. If that filter silently returned empty, every other
   check here would still pass and only the model would stop changing.
5. **Quincy in open air at noon vs at midnight → regen must be *identical*.** This proves sky light is
   read as raw/potential rather than time-darkened. A wrong light API compiles, passes all unit tests,
   and is still wrong in play — this is the only check that catches it.
6. `/give @s bleach_mod:zanpakuto_ichigo` through `zanpakuto_gin` — all eight ids resolve; Combat tab
   shows both Asauchi and all eight blades in menu order.
7. **Bow** (once Task 14 gives a Schrift to select): release under `BOW_MIN_DRAW` → no SP spent, no
   arrow; SP at zero → refusal message, no shot; full draw → damage/velocity scale, no phantom draw
   animation; `/kill` mid-draw → no arrow fires from the corpse.
8. **Arrow**: watch `logs/latest.log` for "no renderer registered" on first spawn; confirm no
   placeholder box renders; fire beyond ~64 blocks and check distant observers see the trail
   (`REISHI_ARROW_TRACKING_RANGE` is 4 chunks — possibly short for a Quincy); kill a mob and confirm
   nothing drops; log out mid-flight and confirm the hit still lands without a crash.

### 7.3 Decisions taken during the run

Each was a judgement call made to keep the run moving. Reverse any you disagree with.

| Decision | Why | Cost if wrong |
|---|---|---|
| **Tasks 9/10/11 run as 11 → 10 → 9** | Only acyclic order; avoids the plan's stub-and-switch | Commits out of numeric order in `git log` |
| **"Submerged" includes lava**, not just water | Spec §3.5 names the input "submerged" and gives "low in water" as illustrative prose; the rationale is world/sky access, which lava seals as completely. Near-nil impact — lava is lethal in seconds | A Quincy briefly in lava takes an unintended penalty, stacking with the Nether one. One tag to remove. **Worth confirming with Suhas** |
| **`REISHI_MULT_ABSOLUTE_FLOOR` extracted** from a hardcoded `0.01` | A real, un-governed balance number | One tuning key nobody changes |
| **`MAX_SKY_LIGHT = 15` kept as a literal** | Minecraft's sky-light range is fixed; a tunable would let config emit impossible values | One literal, one comment |
| **Reishi ceiling clamped explicitly** | It held only because two *re-tunable* weights happen to sum to 1.0. Floor was defended, ceiling wasn't | One `Math.min` that can never fire at defaults |
| **Three `EntityType.Builder` values moved to tuning** | Per-entity *choices*, not engine invariants — vanilla entities all differ. Tracking range is a live balance question | Three keys nobody changes |
| **Vanilla `72000` "hold indefinitely" kept literal** | Engine mechanism, not a balance dial | One literal, one comment |
| **Loud-failure guard on the picker cap** | The defect this project lost a task to was *silent* truncation | One log line nobody sees |
| **`BOW_*` in a new §P.2**, not appended to §P.0 | §P.0 is documented as *shared race* constants; P.1 set the per-feature precedent | A markdown reorganisation |
| **`VOLL_*` in §P.3 and `BLUT_*` in §P.4**, not §P.0 as both briefs said | Same precedent as above; §6 of the previous status already ruled this way | A markdown reorganisation |
| **`onProjectileHit` gated on the hit actually landing** (`hurt()` returning true), which the brief did not specify | `MeleeHooks` gates its own hook the same way. Without it, a shot swallowed by invulnerability frames would still feed a Schrift's on-hit effect | A Schrift's on-hit effect fires slightly less often than intended. One `&&` to remove |
| **`VOLL_FS_RANGE_MULT` wired through a new defaulted `TransformAbility.flashStepRangeMult()`** rather than left unconsumed | The plan declared the key and never read it — precisely the dead-knob defect §4.4 records for `MOB_SCALE_HEALTH`. Routing it through the transformation keeps `FlashStep` race-agnostic | Vollständig's Hirenkyaku bonus is real rather than documentation-only |
| **`BLUT_VENE_SPEED_PENALTY` reconciled every tick for every player**, not applied on the stance-change path | Same dead-knob problem, plus a worse one: an attribute modifier applied on a change path leaks on death, logout and dimension change. Converging every tick is cheaper than auditing every path and can never leave a Quincy permanently slow | One attribute lookup per player per tick |
| **Exertion and Blut share the SP bar outline** — exertion keeps the pulse, Blut takes the hue | The brief had Blut's colour simply replace the border, which would have hidden the exertion tell entirely while a stance was up | Two tells on one outline; if it reads badly in play, split them |
| **`/bleach test reishi` moves the world clock itself** rather than asking the operator to compare two runs | §7.2 item 5 is the only check that catches a time-darkened light read, and a two-step manual comparison is the kind of check nobody runs twice | The command mutates world time for two ticks. Restored in a `finally` |
| **The guide's character list derived from `AbilityRegistry.kits()`** | §4.5's drift was not a one-off: the hardcoded list had already lost three kits. A kit with no hand-written page now gets a thin generated one rather than no page | Aizen, Tōsen and Gin have terse pages instead of good ones — but they are reachable |

### 7.4 Defects found in the plan itself

Worth knowing before trusting the remaining briefs.

- **`BleachDamage.spiritPressure(...)` does not exist.** The plan guessed it. Real signature:
  `source(Level, ResourceKey<DamageType>, @Nullable Entity)`. The plan flagged this method as one of
  two it could not verify with `javap` — that warning paid off.
- **Task 9's brief undercounted call sites**, 12 versus the actual 14; its own exclusion grep missed
  `ZanpakutoSelectMenu.java` and `GinTransform.java`. The compile gate would have caught these.
- **Task 9's brief omitted the `isZanpakuto` → `isSpiritWeapon` widening entirely.** This one would
  *not* have failed to compile — the bow would simply never have become undroppable, silently. It was
  supplied out-of-band and is present at `SpiritWeapon.java:42-43`.
- **Task 5's Files header** omits `SpiritualData.java` though its own Step 3 edits it.
- **Task 8's brief Step 4** (the wrong-token fix) is genuinely part of Task 8, not the unassigned §4.4
  bug list — easy to mistake for out-of-scope.

### 7.5 Gaps no task in the plan covers

Not reachable today — no Quincy kit exists in `BleachKits.IDS` yet — but real once one does.

- **`MeleeHooks.onAfterDamage` will fire a kit's melee hooks on a bow-bash.** A Quincy whacking
  something with the Heilig Bogen would trigger melee ability effects. **No task addresses this.**
- **`SpiritWeapon.ensureAsauchi` hands a character-less player an Asauchi regardless of race** — this
  is the unresolved acquisition question (§2.2), expected rather than a regression.
- **`ReishiArrow.onHitEntity` never calls `super.onHitEntity`**, so vanilla hit sound, knockback and
  crit bookkeeping are all skipped — plausibly right for a silent spirit arrow, but it is a bigger
  divergence than the brief's framing states, and it affects how a landed hit *feels*. Judge in play.

### 7.6 Deferred cosmetic items

None block merge; a final review should triage them. Stale "blade" prose in `SpiritWeapon.java` and
the four drop mixins (the class-level javadoc *is* accurate, so no reader is misled); a dead
`SpiritWeapon` import in `GinTransform.java:15`; `BleachKits.java:40` javadoc still says "one sword per
kit"; `DamageScaling.java:58` needs one word ("a *vanilla* bow is a bow at every level"); the
screen-two picker title reads "Choose your shinigami"; `AbilityRegistry.kitsFor`'s javadoc omits its
empty-not-null contract.

### 7.7 Notes worth not rediscovering

- ~~**`build.gradle`'s `repositories { }` block is empty.**~~ Fixed in Task 1: Loom injects the
  Minecraft repositories but not Maven Central, so JUnit could not resolve. `mavenCentral()` and the
  JUnit 5 dependencies are in place and `./gradlew test` works.
- **Every Minecraft signature in the plan was verified with `javap`** against the mapped 1.21.1 jar,
  not written from memory — **except two**, `BleachDamage`'s factory method and
  `PressureParticleOptions`' constructor, flagged in the plan as "read the real source first". That
  warning earned its keep: `BleachDamage.spiritPressure(...)` turned out not to exist (see §7.4).
  `PressureParticleOptions(int, float)` matched.
- **Two deviations from the spec**, both deliberate, both to keep the seam unit-testable: `Race`
  carries a `double reishiSensitivity` rather than a `ToDoubleFunction<ServerPlayer>`, and the weapon
  factory moved to a separate `RaceWeapons`. Recorded in the plan's File Structure section.
- Nothing is pushed. `origin` is Suhas's repo; the working agreement is local only.

---

## 8. Landing the branch — merge up as a PR

**Decided 2026-09-08 by the user.** The working agreement is no longer "local forever": this branch
is to go up to `origin/main` **as a pull request**, so Suhas reviews it rather than receiving a push.
Adil is the other active contributor and the last person to touch the shared seam.

**Status: not started.** Nothing has been pushed, no remote branch exists, no PR exists. The user
explicitly deferred execution. **Do not run any of the steps below without them.**

### 8.1 Merge first, PR second

`origin/main` is one commit ahead (`d2d1722`, see §3) and it conflicts. Merge `origin/main` **into**
`quincy` locally and resolve there, so the PR arrives clean and Suhas reviews Quincy rather than
refereeing a merge.

### 8.2 The five conflicting files

Computed 2026-09-08 by intersecting `git diff --name-only 750cfb2 d2d1722` with the same against
`quincy`. Two are real; three are additive and should merge on their own.

| File | Conflict | Resolution |
|---|---|---|
| **`SpiritualTicker.java`** | **Real, two hunks.** Both branches edited the *same two lines*: the `perTick` line in `tickRegen` (we multiply in `environmentMultiplier`, they add a flat Shunsui idle-regen bonus after it) and the drain line in `tickTransformed` (we left it alone, they gate it behind `KaromatsuManager.isInAct3`). | Keep **both** sides in both hunks. Their Shunsui bonus is added *after* the multiplied regen, so it deliberately bypasses the reishi multiplier — harmless today (Shunsui is Shinigami, sensitivity 0) but **note it in review**: any future flat post-multiplier bonus given to a *Quincy* would silently escape environment scaling and break the §7.2 item 5 invariant. Also check our `Blut.tickAll` call and `environmentMultiplier` still sit outside their hunks. |
| **`BleachKits.java`** | **Real, and it will not compile if merged naively.** They added `SHUNSUI` + an `IDS` entry + a `registerKit(new Kit(SHUNSUI, …))` with the **7-argument** constructor. Our branch made `race` a mandatory 8th argument and added the `RACE_OF` map. | Add `Races.SHINIGAMI` as the 8th argument to the Shunsui `Kit`, and add `SHUNSUI, Races.SHINIGAMI` to `RACE_OF`. `raceOf` would default it to Shinigami anyway, but `/bleach test race` asserts the two agree *explicitly*, and an implicit default is exactly the drift that check exists to catch. |
| `BleachTuning.java` | Additive, different regions — they appended `KIT_SHUNSUI_*` / Karomatsu keys, we appended §P.3 and §P.4. | Should auto-merge. Verify §P.3/§P.4 survive and `BALANCE.md`'s §L rows still point at real symbols. |
| `BleachModClient.java` | Additive — they registered a Karomatsu receiver and overlay, we registered the arrow renderer. | Should auto-merge. |
| `lang/en_us.json` | Additive — they added Shunsui strings, we added `key.bleach_mod.blut`. | Should auto-merge; watch the trailing comma. |

### 8.3 Do before opening the PR

1. **Resolve the merge, then `./gradlew build` *and* `./gradlew test`.** 25 tests must still pass.
   The Shunsui kit arriving means `/bleach test race` now has a 9th kit to check — that assertion has
   never run against a kit somebody else wrote.
2. **Run the manual pass (§7.2) at least as far as items 1–4.** Right now the honest PR description
   is "none of this has been in-world verified", which is a bad thing to hand a reviewer. The dev
   client works — assets are cached, `./gradlew runClient` starts in seconds — so this is cheap now.
3. **Get a whole-branch review.** Tasks 12–17 were executed in one deadline session with no second
   pair of eyes (§6). Reviewing them *before* Suhas sees them is cheaper than after.
4. **Decide what the PR actually claims.** It is a *foundation*, not a feature: no playable Quincy,
   no Schrift, no bow in the world. The PR description must lead with that or it will be reviewed as
   a broken feature rather than a complete chassis. It should also carry §7.3's list of judgement
   calls and §7.4's plan defects, since several are decisions Suhas may want to reverse.

### 8.4 Two things to raise with Suhas and Adil directly, not in the PR

- **The 9-kit picker cap** (§3). This constrains *their* roadmap, not ours, and it should not be
  buried in a Quincy PR.
- **The lava ruling** (§7.3): "submerged" was taken to include lava. Still worth confirming.

