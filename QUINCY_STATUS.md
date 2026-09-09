# Quincy Faction — Status

**State:** the 18-task foundation is complete and **two Schrifts are playable** — T, The Thunderbolt
(§9) and D, The Deathdealing (§10), both landed 2026-09-08. `./gradlew build` and `./gradlew test`
are green (**42 JUnit tests**). Vollständig's presentation was reworked the same day (§11).

**In-world verification has begun but is not finished** — see §7.1. Playtesting on 2026-09-08 ran
`/bleach test race`, `blut` and `bow` (all PASS), confirmed the two-step picker, and **found two
real bugs nothing else would have**: a server crash on autosave with an arrow in flight (`85f5fcf`,
latent since Task 11), and wings that rendered as a vertical sprinkle. `/bleach test attribution`
still has no recorded result. §10.3 and §11 list what remains unchecked.

**SHIPPED.** `origin/main` is `1ea2de0` — the Quincy faction is live in Suhas's repo, pushed
directly by the user's decision rather than through review. PR #1 shows as merged. Rollback refs and
the two compile-breaking changes other people will hit are in **§8**.

**Next up: Adil's seven-item todo list — analysed, none of it built. See §12.**

**Last session:** 2026-09-08. **Branch:** `main` and `quincy` are identical at `1ea2de0`; the
working tree is clean apart from untracked `docs/`.

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

### What the foundation did *not* include — and what closed it

The foundation was the **chassis**, and until 2026-09-08 there was no playable Quincy at all:
`BleachKits.IDS` had no Quincy entry, so `AbilityRegistry.kitsFor(QUINCY)` was empty, the race
screen showed Quincy with no kits behind it, and nothing minted a Heilig Bogen.

**Schrift T closed that** — see §9. One kit entry turned the whole chassis on, and **D followed the
same day** (§10), which is the evidence that `QuincyTransform`'s "one power at two intensities" shape
generalises rather than having only ever fitted T. **M and Z remain undesigned**, and Z still ships
last for the reason in §4.3.

| Document | Path |
|---|---|
| Design spec | `docs/superpowers/specs/2026-09-07-quincy-faction-design.md` |
| Foundation plan (17 tasks) | `docs/superpowers/plans/2026-09-07-quincy-foundation.md` |
| Schrift T design | §9 of this file |
| Schrift D design | §10 of this file |
| Schrift plan (M/Z) | not written yet |

Both Schrifts were built on the **compressed path by the user's explicit choice** — design agreed in
chat, recorded here, no separate spec or plan document, no per-task subagent review. That is a
deliberate trade and it is the third body of code on this branch in that condition; see §6.

`docs/superpowers/` is still **untracked** and did not ship with the merge — say if it should. This
file, `BALANCE.md` and `GUIDE.md` are tracked and are now on `origin/main`.

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
- ~~Nothing is pushed. `origin` is Suhas's repo; the working agreement is local only.~~ **Obsolete —
  shipped to `origin/main` on 2026-09-08. See §8.**

---

## 8. Landing the branch — **DONE, shipped 2026-09-08**

**`origin/main` is now `1ea2de0`.** The Quincy faction is live in Suhas's repo.

What happened, in order: `origin/main` (`d2d1722` — Shunsui and Karomatsu) was merged **into**
`quincy` at `b7440cf`; the merged tree built green with 42 JUnit tests; `quincy` was pushed and
PR #1 opened; then, **by the user's decision, `main` was fast-forwarded to `quincy` and pushed
directly** rather than waiting for review. GitHub detected the merge and marked PR #1 merged.

The user was told that this lands two compile-breaking changes in Adil's and Suhas's trees on their
next pull, and chose to push without warning them first. **They still need telling:**

- **`Kit` gained a mandatory 8th argument** (`race`). Shunsui's registration passes
  `Races.SHINIGAMI`; that is the only change to his kit.
- **`Zanpakuto` was renamed `SpiritWeapon`.** Registry ids are unchanged, so existing saves keep
  their blades, but any in-flight branch naming `Zanpakuto` will not compile.

**Rollback refs, all on origin:** tag `main-before-quincy` (`d2d1722`) restores Suhas's main; branch
`quincy-backup` and tag `quincy-snapshot-2026-09-08` preserve this work at `1ea2de0`.

**Process note for next time:** the branch reached 37 local commits before its first push and the
user pushed back — *"next time just make it one, since we working on local directory."* One commit
per session on a local branch; keep the reasoning in the commit body, which is the part that was
actually useful.

**What the merge actually cost, versus what §8.2 predicted:**

| File | Predicted | Actual |
|---|---|---|
| `SpiritualTicker.java` | real, two hunks | **auto-merged clean** |
| `BleachKits.java` | real, will not compile | correct — plus a defect neither branch could show alone |
| `BleachTuning.java` | additive | auto-merged clean |
| `BleachModClient.java` | additive | **conflicted** — both appended to the same two blocks |
| `lang/en_us.json` | additive | **conflicted** — both appended to the last line |
| `BleachNetworking.java` | not listed | **conflicted** |

Two things worth carrying forward. Shunsui is listed in `RACE_OF` **explicitly** rather than left to
`raceOf`'s Shinigami default: the default exists so an unrecognised kit cannot crash a login, not as
a way to declare a kit's race, and `/bleach test race` asserts the map and the constructor agree.
And `RACE_OF` had to move from `Map.of` to `Map.ofEntries` — `Map.of` caps at ten pairs and the
**eleventh kit crossed it**, which is a compile error neither branch could have produced on its own.

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
| **`BleachKits.java`** | **Real, will not compile if merged naively, and it got worse on 2026-09-08.** They added `SHUNSUI` + an `IDS` entry + a `registerKit(new Kit(SHUNSUI, …))` with the **7-argument** constructor. Our branch made `race` a mandatory 8th argument, added the `RACE_OF` map, and has since appended `THUNDERBOLT` to *the same three places* — so `IDS`, `RACE_OF` and the `register()` tail are now all three-way conflicts rather than two-way. | Add `Races.SHINIGAMI` as the 8th argument to the Shunsui `Kit`, and add `SHUNSUI, Races.SHINIGAMI` to `RACE_OF`. `raceOf` would default it to Shinigami anyway, but `/bleach test race` asserts the two agree *explicitly*, and an implicit default is exactly the drift that check exists to catch. **Keep `THUNDERBOLT` last in `IDS`** — the Shinigami must stay in their existing menu order, and screen two is race-filtered so a Quincy at the end costs them nothing. |
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


---

## 9. Schrift T — The Thunderbolt · landed 2026-09-08

The first playable Quincy, and the first kit of any race whose power fires off a **projectile**
rather than a swing. Registered as **Candice Catnipp** (`bleach_mod:thunderbolt`), following the
existing convention that a kit is a character, not a mechanic.

Numbers live in `BALANCE.md` §P.5. This section is the *why*.

### 9.1 What it does

| | Tier 1 — Schrift (`R`) | Tier 2 — Vollständig (`G`) |
|---|---|---|
| Trigger | landed reishi arrow | landed reishi arrow |
| Bolt | one, on the target | one, **chaining to 3** within 5 blocks |
| Chain damage | — | ×0.5 falloff per link (3.0 / 1.5 / 0.75) |
| Cooldown | 60 ticks | 20 ticks |
| From the base | — | speed, `VOLL_DMG`, `VOLL_FS_RANGE_MULT`, cyan wings |

Drain and gates are `DRAIN_SHIKAI` / `DRAIN_BANKAI` untouched, so decision #2's tier parity holds by
construction rather than by tuning.

### 9.2 Decisions taken

| Decision | Why | Cost if wrong |
|---|---|---|
| **Power rides `onProjectileHit`, not an activated key** | `BleachKeybinds` has no free slot, and the established pattern (`IchigoTransform`) is that a tier is a stance expressed as passives and on-hit hooks. This is also the first thing ever to exercise Task 12's hook. | The Schrift cannot be aimed independently of the bow |
| **Bolts are `setVisualOnly(true)` plus hand-applied damage** | Buys vanilla flash, thunderclap and dynamic lighting free while suppressing fire, and keeps damage as `SPIRIT_PRESSURE` inside the `BLEACH` tag so Soul Level scaling applies. | **No mob conversions** — no charged creepers, witches or zombified piglins. Reversible by passing `false` and dropping the manual damage, at the cost of a hardcoded vanilla 5 and forest fires |
| **Reused `SPIRIT_PRESSURE` rather than minting a `thunderbolt` damage type** | A dedicated type is a datapack JSON plus a lang key, for a custom death message only. | Death messages say spirit pressure, not lightning. One JSON to add |
| **Primary target's `invulnerableTime` is cleared before the bolt** | The hook runs immediately after the arrow's own `hurt`, inside vanilla's 20-tick immunity window. `hurt` only applies the excess over `lastHurt`, and the bolt (6.0) is smaller than the arrow (7.0) — so **without this the tier-1 power is silently inert while compiling and passing every unit test.** | The struck target can be re-hit by anything else a tick earlier than vanilla intends |
| **Chain links sorted by distance** | Outward falloff is the only ordering a player can read in play. | Cosmetic |
| **Quincy sits last in `BleachKits.IDS`** | The eight Shinigami keep their existing menu positions; screen two is race-filtered anyway. | Menu order |
| **Bow model parents vanilla `item/bow`** | Carries the three `pulling`/`pull` overrides, so the draw-back animation works with no custom art — consistent with the agreed abilities-before-animations build order. | A Heilig Bogen looks exactly like a vanilla bow until someone draws art |

### 9.3 The bow-bash bug is fixed

§7.5's first gap is closed. `MeleeHooks.onAfterDamage` gated on `SpiritWeapon.isDrawn`, which went
race-agnostic in the Task 9 rename — so a Quincy clubbing a mob with the bow would have fired their
Schrift's `onMeleeHit`. New `SpiritWeapon.isMeleeDrawn` narrows it to blades, and the check sits
**after** `LAST_DIRECT_HIT_TICK` is recorded, so Yamamoto's air-swing detection is unchanged.

### 9.4 A real bug the unit tests caught

`isReady`'s no-bolt-yet sentinel is `Integer.MIN_VALUE`, and `0 - Integer.MIN_VALUE` overflows back
to a negative in `int` arithmetic — which made a freshly transformed player permanently **not**
ready. The subtraction is now widened to `long`. Worth recording because **nothing in play would
have caught it**: the symptom is a power that simply never fires, which reads as "not implemented
yet" rather than as a bug.

### 9.5 In-world verification — **PvP-tested 2026-09-09, kept**

Two players, live server, `bleach_mod 2.0.0`. User's verdict: *"schrift T is gud for pvp, we keep
it"*. The kit is **kept as designed** — no retune requested.

| # | Check | Result |
|---|---|---|
| 1 | **Does the bolt damage the struck target?** | ✅ confirmed — see the arithmetic below |
| 2 | Does chaining pick sane targets? | ⚠️ **not testable in a duel** — see §9.6 |
| 3 | Bow-bash inertness | ⬜ still open |
| 4 | Does the cooldown read as a rhythm? | ✅ confirmed — the 3s/1s pacing is what "good for PvP" is describing |
| 5 | §7.2 items 7 and 8 (bow and arrow) | ⬜ still open |

**Item 1, from the numbers rather than from a claim.** Observed 5 hearts in Schrift and 7.5 in
Vollständig, against raw values of 13 (`BOW_ARROW_DAMAGE` 7 + `THUNDER_BOLT_DAMAGE` 6) and 18.85
(the same ×1.45 for `VOLL_DMG`). Had the i-frame reset failed, the arrow would have landed alone at
about 2.75 hearts — so the bolt is provably firing, and §9.2's riskiest decision holds.

**A second thing falls out of it for free.** Both tiers land at a consistent ~78% of raw, which is
what a defender's Soul Level damage-taken reduction looks like. That is the §E asymptotic curve from
Adil's item 6 working in live PvP, which nothing had confirmed until this session. It is indirect
evidence rather than a measurement — the exact figure depends on both players' Soul Levels, which
were not recorded — but the shape is right and both tiers agree.

### 9.6 Chaining cannot be tested in a duel

*"Can't really make out 3 lightning strikes."* That is correct behaviour, not a defect.
`chainTargets` excludes both the shooter and the primary target, so a 1v1 with no third body inside
`THUNDER_CHAIN_RADIUS` (5 blocks) of the person you hit has **nothing to chain to** and correctly
produces zero links.

Testing it needs a cluster: Vollständig into three or more mobs standing within 5 blocks of each
other, then confirm three links, nearest first, at 3.0 / 1.5 / 0.75 falloff. Until someone does
that, chain selection and the falloff ordering remain unverified — the unit tests cover
`chainDamage`'s arithmetic but nothing reaches the entity search.

Worth knowing before that test: the bolts are `setVisualOnly(true)`, so each link gets a real
vanilla flash and thunderclap. Three links should be unmistakable when there is anything to hit.

---

## 10. Schrift D — The Deathdealing · landed 2026-09-08

Askin Nakk Le Vaar (`bleach_mod:deathdealing`), the second Quincy. Numbers in `BALANCE.md` §P.6.

### 10.1 What it does

The letter is about how much of a thing a body can take, so the mechanic is a **dose**. Every landed
arrow leaves one; every dose raises the damage that target takes from *everything, from anyone*.
Doses bleed off untended, so D is a clock — commit and finish while the stack is up.

| | Tier 1 — Schrift (`R`) | Tier 2 — Vollständig (`G`) |
|---|---|---|
| Arrows | 1 dose per hit | 2 doses per hit |
| Field | — | **Gift Bad Sonnenschein** — a 7-block dome anchored where you released |
| Dome effect | — | 1.5 bleach damage + 1 dose per second to everything inside but you |

Same power at two intensities: the dome is the dose mechanic applied to a volume instead of to one
arrow at a time.

### 10.2 Decisions taken

| Decision | Why | Cost if wrong |
|---|---|---|
| **No outright kill** — doses multiply damage taken, they do not cross a death threshold | *User's call, 2026-09-08.* A guaranteed delete makes one kit mandatory and every fight against it un-fun. Keeps the "things die faster the longer you work them" identity without the button | D is less faithful to Askin, and less feared. One threshold check to add if it ever wants to be |
| **The dome stays where you popped it** | *User's call, 2026-09-08.* Every other tier is a stance that follows you; a fixed field is terrain — bait, zone, get caught out of. It is the entire reason the letter plays unlike anything else here | More machinery than a stance: the dome needs its own position, owner and teardown |
| **Poison is cosmetic; `DOME_DAMAGE` is real** | Vanilla Poison **cannot kill** — it floors at half a heart — so it can never be the damage. It rides along purely as the on-screen tell | Nothing; the alternative does not work |
| **A dome dies with its owner's logout** | Nothing else in the world holds a reference to one. An orphaned dome is a permanent poison field nobody can switch off | A player who relogs mid-fight loses their field |
| **The dose multiplier sits outside `DamageScaling`'s `ServerPlayer` block** | Doses land on mobs, and mobs carry no `SpiritualData` | — |
| **Doses are keyed by entity UUID, not held on the entity** | Same reason | Entries are dropped as they empty, so the map only ever holds recently-dosed targets |
| **Decay uses the server tick, not the dosing player's** | A player dying, changing dimension or logging out mid-stack must not reset or freeze the clock on targets they already dosed | — |

### 10.3 In-world verification — **four of five confirmed 2026-09-09**

**The first thing on this branch anyone has actually played.** Two players, live server, on
`bleach_mod 2.0.0` — so this run also carries Adil's seven items and the drain taper, not just the
Schrift D code as it landed on the 8th. User's verdict: *"deathdealing is a big success"*.

| # | Check | Result |
|---|---|---|
| 1 | Does the dome damage and dose? | ✅ confirmed |
| 2 | Does it stay put when you walk away, and vanish on revert? | ✅ confirmed |
| 3 | **Do doses raise damage taken from an unrelated source?** | ✅ confirmed — the core mechanic, and the one no unit test reaches |
| 4 | Does the dome survive a relog? *(it should not)* | ⬜ **still open** |
| 5 | Do doses decay untended? | ✅ confirmed |

Item 3 is the one that mattered. Doses multiplying damage from *anything, from anyone* is the whole
of the letter, it sits outside `DamageScaling`'s `ServerPlayer` block so mobs can carry it, and no
unit test can reach it. It works.

Only the relog teardown is left, and it is the unhappy path: pop a dome, log out, log back in, and
confirm no orphaned poison field is left running with nothing holding a reference to it.

Two things this run does **not** tell us. The other four §10.2 decisions were about feel rather than
function and want more than one session to judge — in particular whether "no outright kill" leaves D
feared enough to be worth picking. And nothing here exercised Schrift T, Shunsui, or the new drain
rates, which remain on their own lists (§9.5, §13.5, §14.3, §15.4).

## 11. Vollständig presentation — reworked 2026-09-08

Three passes, all driven by playtest rather than by design:

1. **The particle was wrong.** `PressureParticle` rises by design, so any static shape smeared into a
   vertical sprinkle. Wings now use vanilla dust, which stays put and still takes a per-kit tint.
2. **The geometry was wrong.** Six points traced one arch over the player's head. Now two mirrored
   feather fans, length peaking mid-fan, swept back in proportion to reach.
3. **The style is now per-Schrift.** `wingJagged()` defaults to smooth and T overrides it to draw
   zigzag lightning; colour already came from the kit. T is Candice's electric green (`0x5CFF9E`),
   dense (7 × 12 per wing) and big (radius 2.0).

Also added: **a bell on entering Vollständig**, broadcast rather than sent to the releasing player;
and an **aura** — a loose column of the kit's colour that is always on while released. **The wings
only unfurl when standing still**; moving, they furl and the aura carries it alone. That is how the
release reads on screen, and it also drops a running Quincy from 84 particles a tick to 10.

---

## 12. Adil's todo list — analysed 2026-09-08, **items 1, 2, 4, 5 and 6 landed 2026-09-08 · §13**

Adil handed the user seven items. Items 1–5 are about **his own Shunsui kit** (Katen Kyōkotsu /
Karamatsu Shinjū); 6 and 7 are **global progression** and affect every kit in the mod.

This section is the analysis, kept as written. **§13 is what was actually built**, and where the
analysis turned out to be wrong it says so — item 4 in particular had the right warning attached to
the wrong cause.

### 12.1 The list, verbatim

1. Stage 2 is very annoying — reduce screenshake, reduce noise from damage.
2. Make indications that the Bankai is active, and when it changes acts.
3. Shikai doesn't work; show the caster the rule for each player they look at.
4. Players affected by the Bankai cannot see their SP.
5. You are able to leave the boundary.
6. Make max level higher than 20, maybe 100 — more SP and max HP, because without Prot 4 netherite
   you die too quickly.
7. Shikai shouldn't drain SP at higher levels: the drain reduces up to SL 60/70 then becomes 0, and
   only drains when a move is used. Past that threshold the passive Bankai drain should also reduce,
   but never reach 0.

### 12.2 What was actually found

| # | Status | Finding |
|---|---|---|
| **1** | **Root cause found** | `KaromatsuManager.tickAct2` runs `target.invulnerableTime = 0; target.hurt(...)` **every tick**, so a participant takes 20 hurt sounds, 20 red flashes and 20 camera kicks per second. That is the "annoying" and the "noise", not the shake. **Fix:** apply the bleed once per 20 ticks with a full second's damage — identical DPS, one hit a second — and stop zeroing `invulnerableTime`, since vanilla's 20-tick immunity then lines up exactly. Also drop the 3-particles-per-tick-per-participant to the same interval. The separate camera shake is `REIATSU_SHAKE_AMPLITUDE_PX` (4.0), which is the low-SP overlay rather than anything Shunsui owns. |
| **2** | Ready | `ClientKaromatsuState` already syncs the act, so this needs a readable tell, not new plumbing. Cheapest honest version is a message to every participant on `advanceTo`. |
| **3** | **Blocked on a repro** | "Doesn't work" is not actionable — need what he pressed and what happened. **And the second half reverses a deliberate decision:** `KatenShikaiManager`'s javadoc states *"The rule assigned to each enemy is never revealed — only the consequence."* Revealing rules on look is a design change, not a bug fix. Fine to make; Adil should know he is overturning it. |
| **4** | **Not the obvious cause** — *and the cause found later was a third thing, see §13.3* | `KaromatsuOverlay` fills the whole viewport, but `SpiritualHud.register()` runs *after* it in `BleachModClient`, so the bar draws on top. Whatever hides the SP is something else — do not "fix" the draw order without reproducing first. |
| **5** | **Solved elsewhere already** | `KaromatsuManager:536-542` contains a participant by zeroing outward velocity through `setDeltaMovement`. **That does nothing authoritative to a player** — the client keeps sending its own position and the server accepts it. This is the identical bug fixed in `PoisonDome` on the same day; the fix is `player.connection.teleport(...)` for `ServerPlayer` and `teleportTo` for everything else. Lift `PoisonDome.place(...)` across. |
| **6** | **Design decided, not built** | See §12.3 — as literally specified it makes players invulnerable. |
| **7** | **Conflicts with a user ruling** | See §12.4. |

### 12.3 Item 6 — the cap raise makes players invulnerable

`DamageScaling` reduces damage taken linearly and floors at zero:
`multiplier = max(0, 1 − rate × (level − 1))`.

| Rate | Default | Reaches zero at |
|---|---|---|
| `SL_BLEACH_DMG_TAKEN_PER_LEVEL` | 0.015 | **SL 68** |
| `SL_GENERAL_DMG_TAKEN_PER_LEVEL` | 0.010 | **SL 101** |

So raising `SL_MAX` to 100 means **a player at SL 68 takes zero damage from every ability in the
mod**, and at SL 101 zero damage from anything. `BleachTuning` claims the floor "is not reachable at
any shipped tuning" — true at cap 20, false the moment the cap moves. `SL_BLEACH_DMG_DEALT_PER_LEVEL`
has the mirror problem: linear at 0.020 gives **+198% damage dealt** at SL 100.

**Decided by the user 2026-09-08:** replace the linear curve with an **asymptotic** one, with a
**hard floor of 50%** — damage taken never drops below half, at any level, at any tuning.

Worked design, not yet written:

- `progress(sl) = 1 − exp(−k × (sl − 1))`, with `k ≈ 0.035` so SL 100 sits at ~97% of the asymptote.
- Two caps whose **product is the floor**: general `0.25` and bleach `0.3333`, giving
  `0.75 × 0.6667 = 0.50` exactly for bleach damage, which takes both.
- Damage *dealt* wants the same treatment, capped near `+0.60`, or it runs away at the new cap.
- At SL 20 this lands around −26% taken versus today's −42%: progression is stretched over five
  times the levels, so the same level is deliberately worth less.

**SP and HP need no work.** `SP_MAX_PER_LEVEL` (10) and `SL_HP_PER_TWO_LEVELS` (1.0) are already
linear, so the cap raise alone takes a capped player from 290 SP to 1090 and from +10 HP to +50 —
which is exactly Adil's stated goal of not dying instantly.

### 12.4 Item 7 conflicts with the user's own instruction

Adil wants the Shikai drain to **taper to zero** by SL 60–70. The user's instruction on the same day
was *"at higher SL levels, we get a lot more reiatsu, but the rei drain remains the same."*

Those are two different mechanisms for the same feel. The user's version is already delivered for
free by §12.3: a flat `DRAIN_BANKAI` of 5/s against 1090 SP is 218 seconds of Bankai, against 58
today. **Treat the user's ruling as the decision and Adil's item 7 as superseded** — but the user
should tell him, because he asked for something specific and will notice it is not there.

### 12.5 Suggested order

Items **1, 2, 5** are self-contained and can land together — 5 is a straight lift from `PoisonDome`.
Item **6** is the one with real blast radius: it changes the curve every kit is balanced against, so
it wants its own pass and a fresh look at `BALANCE.md` §E. Items **3 and 4** should not be attempted
until Adil supplies a repro; both currently point at causes that turn out to be wrong.

---

## 13. Adil's list — built 2026-09-08

Items **1, 2, 4, 5 and 6**. Items **3 and 7** deliberately untouched: 3 still needs a repro from
Adil and its second half reverses a documented decision (§12.2); 7 is superseded by the user's own
ruling (§12.4) and Adil should be told rather than quietly ignored.

One commit. 52 JUnit tests green — the ten new ones are the §E curve, which is the only part of this
session's work a unit test can reach.

### 13.1 Item 6 — the cap raise, and three things it broke that §12.3 did not catch

`SL_MAX` is 100. The §E curve is now asymptotic exactly as designed in §12.3: `SL_CURVE_K = 0.035`,
caps of `0.25` general and `0.3333` bleach, `SL_DMG_TAKEN_FLOOR = 0.50`, and a matching
`SL_BLEACH_DMG_DEALT_CAP = 0.60`. It lives in a new `progression/SoulLevelCurve`, precomputed on the
tuning reload hook next to `SpxTable` for the same reason that one is — `Math.exp` has no business
running inside a damage event.

Numbers landed where §12.3 predicted: **SL 20 is −26.4% taken and +29.1% dealt** where it used to be
−42% and +38%, and **SL 100 is −48.7% and +58.1%**, hard against the floor without crossing it.
SP and HP were left alone and give 1090 SP and +50 HP at the cap, as §12.3 said they would.

**§12.3 checked `DamageScaling` and stopped there. Three other linear terms run off the same cliff:**

| Found | What it does at the new cap | Done |
|---|---|---|
| `gatePercent` is `base − 0.015 × (SL − 1)`, unfloored | Bankai's entry gate goes **negative at SL 64**, Shikai's at SL 44. A negative gate is one an empty pool clears — release becomes free for the top third of the ladder | Floored at `GATE_FLOOR_PCT = 0.10`. Releasing always costs something |
| `exertionK()` is `0.060 − 0.0024 × (SL − 1)`, unfloored | Goes **negative at SL 26**, and it is a denominator: `1 / (1 + k × exertion)` has a pole at `exertion = 1/\|k\|`. Regeneration is *infinite* at exactly that value and negative past it | Floored at `EXERTION_K_FLOOR = 0.005`. Exertion stops mattering much at high level without ever inverting |
| Nine more per-level constants that are merely *large* rather than broken | `SP_REGEN_PCT_PER_LEVEL` reaches 140 SP/s against a 5/s Bankai drain; `AURA_RANGE_PER_LEVEL` reaches 1486 blocks; Yamamoto's Bankai cone reaches 225 damage at 139 blocks | **Recorded, not changed** — `BALANCE.md` §E.2 is the table. Retuning nine kits is its own pass |

The first row of that last group is the user's own ruling from §12.4 — *"at higher SL levels we get a
lot more reiatsu, but the drain remains the same"* — so it is intended and stays. The other two rows
worth doing first are Yamamoto's cone and Aura Sense's range: both are already past the range at
which the other player can see what is happening to them.

**One thing the cap raise did not get and needs a decision.** The SPX curve was not touched, so the
ladder now costs **~722,000 SPX end to end** against ~10,400 to reach SL 20 — 69× the grind, against
a 200/MC-day base cap. Every number above is correct at every level whether or not anybody reaches
it, but SL 100 is currently decorative. `BALANCE.md` §M.4 already flagged `SPX_DAILY_CAP_BASE` vs.
`SPX_CURVE_COEFF` as a retune candidate; it is now the top of that list in practice.

### 13.2 Items 5, 2 and 1 — the Bankai, rebuilt against `PoisonDome`

**Item 5, the boundary.** `tickContainment` cancelled outward velocity with `setDeltaMovement`,
which does nothing authoritative to a player — the client keeps sending its own position and the
server accepts it. Participants are now *moved*, and a `ServerPlayer` is moved through
`player.connection.teleport`. Membership was already resolved by UUID rather than by a box search, so
that half was already right and stayed.

Two further fixes came with it, both straight out of `PoisonDome`:

- **The contained volume is now the same shape as the drawn shell** — a hemisphere on the anchor,
  with height above the anchor floored at zero. It was a full sphere, so *below the anchor read as
  outside*: a contained player who stepped one block downhill was shoved back toward the anchor and
  then shoved again the next tick. Being pinned a few steps from where the zone was planted is the
  same bug `PoisonDome` had, and it is worse than escaping.
- **A release safety net** at `SHUNSUI_BANKAI_RELEASE_FACTOR = 5.0` × radius. Being wrongly freed is
  cosmetic; being wrongly pinned ends the play session.

**The shell.** 48 randomly scattered `PressureParticle` points on a full sphere, redrawn every tick.
That particle rises by design (§11), so the shape smeared vertically; half the sphere was
underground; and 48 points over a capped Shunsui's ~14,000 m² of surface is noise, not a surface. It
is now stride-drawn dust: latitude rings, a double-density ground ring, and a terrain skirt so the
rim does not float over ground that falls away. At `SHUNSUI_ZONE_DRAW_STRIDE = 3` on a 3-tick
interval, a 67-block zone costs no more per tick than an 18-block one — which matters, because
`SHUNSUI_BANKAI_ZONE_RADIUS_PER_SL` puts a capped Shunsui's zone at exactly that.

**Item 2, act indication.** The acts were always synced; nothing ever said them out loud. Entry and
every act change now announce in chat, and the running state reports on the action bar once a second
with its own progress — exchanges landed, seconds of rot left, the thread's draw. The acts are named
as Karamatsu Shinjū's own: *Ittan Momen*, *Zanki no Shitone*, *Dangyo no Fuchi*, *Itokiribasami
Chizome no Shitone*. A player who has seen the third one once knows what the blue screen means the
next time.

**Item 1, Act 2's noise.** As diagnosed in §12.2 and no more: one hit per
`SHUNSUI_ACT2_DAMAGE_INTERVAL = 20` carrying the whole second's damage, and nothing zeroes
`invulnerableTime` any more because at 20 ticks the interval already lines up with vanilla's own
immunity window. The blemish particles moved to the same clock. The screen shake was left alone: it
is `REIATSU_SHAKE_AMPLITUDE_PX` on the low-SP overlay and Shunsui does not own it.

Not *quite* identical DPS, and it is worth saying so: a bleed tick that lands inside the immunity
left by someone else's hit is now swallowed rather than forced through, so the rot is slightly
cheaper in a crowded fight. That is the right direction to be wrong in, and much cheaper than twenty
flashes a second.

### 13.3 Item 4 — the SP bar, and why §12.2 sent the next person the wrong way

§12.2's warning was right — draw order was not the cause, and reordering `register()` calls would
have fixed nothing. But the cause was not "something else" either, and it was three lines above the
place §12.2 stopped reading:

```java
graphics.pose().translate(0.0f, 0.0f, OVERLAY_Z);   // OVERLAY_Z = 500.0f
graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), tint);
```

`SpiritualHud` draws at `HUD_Z_DEPTH = 0`. Registration order cannot save a layer that has been
pushed 500 units in front of it — **depth wins**. The bar was drawn, and then painted over, which is
exactly the report. The translate is gone.

The rest of the fix is `PoisonDome`'s presentation, adopted whole: capped at
`SHUNSUI_ZONE_TINT_MAX_ALPHA = 70` rather than 96, faded in and out client-side at 2.5/s rather than
snapping, and act escalation carried by **colour** rather than by opacity — bruise, blood, rot,
Dangyo no Fuchi's blue, the thread's white.

`KaromatsuSyncPayload` changed shape while it was open. It used to broadcast the zone's centre,
radius and caster to every player in the world and let each client decide whether it was inside;
it is now a per-participant edge carrying only the act. Two reasons, both `PoisonDome`'s: a client
that knows the geometry can stand one block outside it, and a client that decides its own membership
can disagree with the server about who is caged — which it did, for anyone dropped from the
participant set while still standing in the zone.

### 13.4 Teardown

Every path that ends a stage now clears membership *and* sends the falling edge: revert, abort,
Shunsui losing Act 3, the Final Act resolving, and a participant dropped for dying, disconnecting or
changing dimension. The tint is edge-driven, so a missed falling edge is a gloom nobody can clear
short of relogging — `PoisonDome`'s `clearAllTints` exists for exactly this and this is the same
lesson applied.

One behaviour changed while fixing that: **a concluded stage now releases everyone.** `CONCLUDED`
used to leave containment and the shell running until Shunsui reverted, so the cage outlived the
performance. The `STAGES` entry survives, because `getCurrentMeleeDmgBonus` still has to answer.

### 13.5 Still unverified in-world

Compile-and-unit-test verified, plus a client launch. The list below is what no unit test reaches,
and it is the whole of this feature:

1. **Does the wall actually hold a player now?** Walk at it, sprint at it, Flash Step through it.
   The old one stopped mobs and never stopped players.
2. **Does stepping one block downhill still pin you?** It should not. This is the below-anchor fix
   and it is the one most likely to be subtly wrong.
3. **Can a participant see their own SP bar?** The whole of item 4.
4. **Is Act 2 one hit a second?** One sound, one flash, one kick.
5. **Do the act announcements and the action bar read at a glance**, and do they survive a
   participant joining mid-sequence?
6. **Does the shell read as a surface** at SL 1 and at a high Soul Level, and does the skirt close
   the arch on a slope?
7. **Does every teardown clear the gloom?** Revert, die, log out, change dimension, and let the
   sequence run to its own conclusion.
8. **`/bleach sl set 100`** — check the gates are still payable, that regen is not infinite, and
   that a capped player still takes half damage rather than none.

---

## 14. Item 7 — the drain taper, built 2026-09-09

§12.4 called item 7 superseded by the user's ruling and recommended telling Adil rather than
building it. **The user chose to build it as well**, so both mechanisms are now live: a levelled
player gets a bigger pool *and* cheaper upkeep.

### 14.1 What was decided, and why not what Adil asked for

He wrote *"the drain reduces up to SL 60/70 then becomes 0"*. Built literally that is the same
defect §12.3 exists to prevent, one layer down — a drain that reaches zero is a stance that is free
forever, and the crossing point moves the instant anybody edits the cap. The user chose the
asymptotic shape with a non-zero floor, reusing `SoulLevelCurve.progress` so the cost of a release
and the value of a level move together when `SL_CURVE_K` is retuned instead of drifting apart.

Shikai floors at 0.15 SP/s (10% of base) from SL 70 — which happens to land exactly on Adil's stated
threshold — and Bankai at 2.5 SP/s (50% of base) from SL 76. Numbers and uptimes are in
`BALANCE.md` §C.1.

The third reading of item 7 — *"only drains when a move is used"* — was **not** built. That is a new
cost mechanism, not a taper: every Shikai move across eleven kits would need a price. It was offered
and declined as out of scope for this pass.

### 14.2 Twenty dead overrides deleted

`TransformAbility.drainPerSecond()` had eleven kits implementing it twice each — twenty overrides in
total, every one returning the flat constant — and **not one caller**. Its javadoc said *"Read by
the ticker"*; the ticker reads `SpiritualData.drainPerSecond()` instead. The interface method and
all twenty overrides are gone. They were a trap: the next person to tune a kit's drain would have
edited one and watched nothing happen.

`Hover.drainPerSecond` and `SpiritualFlex.drainPerSecond` are unrelated statics with real callers
and were left alone.

### 14.3 Still unverified in-world

62 unit tests green, compiles clean. Not verified:

1. **Does a high-SL Shikai actually hold?** `/bleach sl set 65`, enter Shikai, watch the bar. It
   should fall visibly but slowly — 71 minutes to empty, so about 10 SP a minute.
2. **Does `/bleach guide` print the tapered rate?** It now reads the player's level rather than the
   constant, so it should say 0.17/s at SL 65 and 1.5/s at SL 1.
3. **Is Shunsui's Act 3 still payable at low SL and not trivial at high SL?**
4. **Does the floor hold at the cap?** `/bleach sl set 100` — Bankai must still end, in about 7
   minutes and 20 seconds from a full pool.

---

## 15. Item 3 — the two halves that were real, built 2026-09-09

§12.2 marked item 3 **blocked on a repro**. That was half right: "Shikai doesn't work" is still not
actionable as written, but reading the code turned up two defects that are wrong on their own terms
whatever Adil actually saw, and a third thing that is not a defect at all.

### 15.1 What was wrong

**A · Every failure path returned in silence.** `castRules` had three bare `return`s — cooldown,
insufficient SP, no targets — and the only success cue was a note-block chime. From the caster's
seat a failed cast and an ability that does not exist look identical. That alone is enough to
produce the report. Each path now names itself on the action bar, and a success says
`Rules set on N` — **a count, never which rules**, so §12.2's design decision survives intact.

The cooldown is checked before the pool deliberately: a Shunsui who is both on cooldown and broke is
told about the one that clears on its own.

**C · `DONT_JUMP` was close to undetectable.** The old check was
`!onGround() && deltaMovement.y > 0.4`. A vanilla jump starts at 0.42 and is near 0.33 one tick
later, and a remote player's server-side velocity is reconstructed from movement packets rather than
simulated — so the check needed to sample a one-tick window that may never be sampled at all. One
of the three rules was effectively dead while the other two worked.

Replaced with a ground→air edge: `wasOnGround && !onGround && deltaY > 0`. Stepping off a ledge is
excluded by the *sign* of the velocity rather than its size, so no threshold has to be guessed. This
needs a previous tick, so `GROUND_BY_CASTER` runs parallel to `RULES_BY_CASTER` and is pruned by the
same pass and cleared by the same lifecycle.

### 15.2 What was not wrong — tell Adil this

**B · A cast requires a swing that *misses*.** `LivingEntitySwingMixin` gates `castRules` behind
`!MeleeHooks.didHitDirectlyRecently`, the same one-tick deferral Yamamoto's raven uses. **If he
tested by swinging at the person he was testing on, it fired zero times.** This is working as
designed and is the most likely single cause of the whole report. It needs telling him, not fixing.

Also worth telling him: `DONT_ATTACK` only breaks when Shunsui's **back is turned**. Face-to-face
testing would never trigger it. Combined with C, two of the three rules would have looked broken to
anyone testing at close range in front of their target.

### 15.3 What was declined

The second half of item 3 — reveal each player's rule to the caster on look — was **declined by the
user on 2026-09-09**. It reverses `KatenShikaiManager`'s documented decision that the rule is never
revealed, only its consequence. That is a design change and remains available, but it is not a bug
fix and should not be smuggled in as one.

### 15.4 Still unverified in-world

12 new unit tests, 74 green in total. The pure halves are covered; none of the below is.

1. **Does a failed cast now say why?** Swing-and-miss on cooldown, then broke, then alone in a field.
2. **Does a successful cast say `Rules set on N`, with N matching who is actually nearby?**
3. **Does `DONT_JUMP` fire now?** It should break on the first jump, once, not twenty times.
4. **Does walking off a ledge still not count as a jump?**
5. **Does a re-cast onto a target already being tracked break on their next jump** rather than
   missing it because the ground map was stale?

