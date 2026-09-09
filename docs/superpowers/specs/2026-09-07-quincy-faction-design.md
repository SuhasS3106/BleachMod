# Quincy Faction — Design

**Date:** 2026-09-07
**Author:** Quincy owner (see `QUINCY_STATUS.md` §3 for the team split)
**Status:** awaiting approval from Suhas (Soul Reapers, repo owner) and Adil (Hollows/Arrancars)
**Target:** Minecraft 1.21.1 · Fabric Loader 0.19.5 · Fabric API 0.116.17 · Java 21 · Mojang mappings

Companion to `PRD.md` (what the mod does), `BALANCE.md` (every number), `GUIDE.md` (player-facing),
`QUINCY_STATUS.md` (the running decision log this spec was distilled from).

> **Nothing in this document has been implemented.** No code written, no branch created, nothing
> committed, nothing pushed. Implementation begins only after all three of us approve.

---

## 1. What this is

A second playable race. A Quincy picks a **Schrift** the way a Shinigami picks a character, fights
with a **reishi bow** instead of a zanpakutō, and releases through **Schrift → Vollständig** instead
of Shikai → Bankai.

Four Schrifts ship in v1: **T** (The Thunderbolt), **D** (The Deathdealing), **M** (The Miracle),
**Z** (The Zombie). Z ships last and needs a decision from Suhas first — see §10.

**The design's governing constraint is that the Shinigami half must not change behaviour.** Every
structural change below is either additive or defaults to the current behaviour, and existing saves
migrate with no step at all.

### 1.1 Deliberately out of scope

| Deferred | Why |
|---|---|
| **Letzt Stil** | Designed after tiers 1 and 2 are playable. It is an orthogonal overlay stacking on either tier — the slot a Vizard mask would later fill for Shinigami — so it does not block anything here. |
| **Race chosen first, permanently** | The long-term intent. Needs the race model upgraded from B to C (§3). Not in v1. |
| **Animations** | Bow draw, Vollständig entry, counters. Team build order is abilities → animations → maps/UI. One exception falls out for free: see §4.2. |
| **Maps, proper UI, subsystems** | After animations. |
| **Fullbringers** | After all three races. |

---

## 2. Decisions

Twelve, settled in brainstorming. Consequences matter as much as the decisions.

| # | Decision | Consequence |
|---|---|---|
| 1 | **Race is the chassis, Schrift is the kit.** | Bow, Blut, Hirenkyaku and Vollständig are shared Quincy baseline. The letter is the character, the way "Ichigo" is today. |
| 2 | **Two release tiers, reusing the existing `state` byte.** `BASE / release 1 / release 2`, race-specific display names only. | `SpiritualData.state` (0/1/2) is untouched. Exertion, gates, the Bankai loan and claw-back, the HUD notches and the `bleach_mod:released` model predicate all keep working. No state-machine rewrite. |
| 3 | **Letzt Stil is an orthogonal overlay**, not a third tier. | Own flag, own keybind, own gate, parallel to `state`. Deferred (§1.1). |
| 4 | **The bow is the drawn weapon.** Lives in the attachment, swaps into the main hand on `X`. | Structural twin of the zanpakutō, so undroppable / keep-on-death / restore-on-respawn / SPX-requires-drawn-weapon all work unchanged. |
| 5 | **`R` = your Schrift's power. `G` = Vollständig.** | Keybinds unchanged; only what they resolve to differs by race. |
| 6 | **Four Schrifts: T, D, M, Z.** Z last. | Chosen to avoid overlapping the eight Shinigami, who already own fire, ice, assassination-marks, inversion, illusion, sense-denial, piercing range and reach/speed. |
| 7 | **One SP pool, environment-modified regen.** | No second bar. A Quincy's regen is multiplied by ambient reishi density. One hook, no new field, no new HUD. |
| 8 | **Blut is a toggled stance on the `Z` key.** Vene (defence) / Arterie (offence) / off. (Unrelated to the Schrift letter Z — the collision is only in this document.) | Tenth keybind. Needs a synced flag, a HUD tell, and a drain additive with the release drain, exactly as Flex already is. |
| 9 | **The bow fires a real arrow entity.** | Dodgeable — the counterplay a ranged faction needs against a melee roster. Requires the attribution fix (§4.4), which also repairs three existing characters. |
| 10 | **The four universal abilities carry over unchanged.** | Flash Step, Spiritual Flex, Aura Sense, Hover are already race-agnostic and sword-free. Flash Step is merely *named* Hirenkyaku in the guide. Zero code. |
| 11 | **Race model B**: a byte on `SpiritualData` plus a small `Race` lookup. | Chosen over scattering conditionals (A) and over a full `Race` subsystem (C) before a second race has proven the abstraction. Grows into C additively. |
| 12 | **Build Quincy-shaped, reconcile with Adil later.** | Accepted risk. Hedge applied: `Race` carries tier names as a list, not a pair, so Arrancar's single-tier resurrección fits without changing the abstraction. |

---

## 3. Architecture: the race seam

### 3.1 Data

One new field on `SpiritualData`:

```java
public static final byte RACE_SHINIGAMI = 0;
public static final byte RACE_QUINCY    = 1;

public byte race;   // codec: optionalFieldOf("race", RACE_SHINIGAMI)
```

Persisted through the existing codec, and added as a **15th component to
`SpiritualSyncPayload`** (the client needs it for the HUD tell and the guide). Blut (§5.4) adds a
**16th**; the payload's hand-written codec must be extended for both, and its class comment — which
already claims "twelve components" while the record carries fourteen — corrected while we are there.

**The default-to-zero is doing real work.** Every existing save file and every existing player
silently becomes a Shinigami. There is no migration step, no config version bump, and no path where
Suhas's eight kits notice this landed.

### 3.2 The `Race` record

New package `com.bleach.mod.race`, two files.

```java
public record Race(
        byte id,
        String displayName,                  // "Shinigami" · "Quincy"
        List<String> tierNames,              // ["Shikai","Bankai"] · ["Schrift","Vollständig"]
        String weaponPrefix,                 // "zanpakuto" · "heilig_bogen"
        BiFunction<ResourceLocation, Item.Properties, Item> weaponFactory,
        ToDoubleFunction<ServerPlayer> regenMultiplier,
        boolean hasBlut) {}
```

`Races.SHINIGAMI` returns `1.0` from `regenMultiplier` and `false` from `hasBlut`, so **the
Shinigami path is unchanged by construction rather than by us remembering to be careful.**

`tierNames` is a `List`, not a pair, purely as a hedge for Adil: Arrancar resurrección is one
release tier, not two, and this lets him declare one without touching the abstraction (§10.2).

### 3.3 Ownership of the truth

`data.race` is authoritative. `Kit` gains a `Race race` component used **only to validate** that a
player cannot select a kit belonging to a race they are not, and to let the picker filter. Two
sources of truth are avoided, and this is the exact shape the permanent-race-first flow needs when
the model is upgraded to C.

### 3.4 The four hooks

Every race-specific behaviour resolves through the `Race` object rather than a conditional at the
call site. That is the whole difference between model B and model A.

| Site | Change |
|---|---|
| `SpiritualTicker.tickRegen:158` | `regen *= race.regenMultiplier(player)` — where ambient reishi lands |
| `BleachItems.register:49` | weapon name and class come from `race.weaponFactory` instead of a hardcoded `ZanpakutoItem` |
| `AbilityDispatcher` | the Blut action is refused unless `race.hasBlut()` |
| `BleachGuide` / picker | iterate races, then kits within a race |

The last one fixes the §9.5 documentation drift for free: a guide that iterates cannot say "five
characters" while eight are registered.

### 3.5 Ambient reishi

`Races.QUINCY.regenMultiplier` reads the player's position and returns a multiplier in
`[REISHI_MIN_MULT, REISHI_MAX_MULT]` built from: sky visibility, daylight, dimension, and whether
they are submerged. High in the open under sun, low underground, low in the Nether, low in water.

This is the only mechanical expression of "a Quincy draws power from the world rather than making
it". It costs one hook and gives the race real positional play — fight in the open, die in a cave —
without forking the economy into a second resource.

---

## 4. The spirit weapon

### 4.1 `Zanpakuto.java` → `SpiritWeapon.java`

Renamed and made race-aware. **Not copied.** It already owns draw/sheathe, the one-place invariant,
undroppable enforcement across six separate paths, death-stow and respawn-restore — the trickiest
code in the mod. A parallel copy for bows is precisely how those two implementations drift apart and
one of them starts dropping blades on death. Race decides only which item is minted and what it is
called; every guarantee is inherited.

### 4.2 `HeiligBogenItem`

Structural twin of `ZanpakutoItem`: `stacksTo(1)`, `Rarity.EPIC`, `fireResistant()`, `UNBREAKABLE`,
one minted per Schrift from the same `BleachKits.IDS` loop with the prefix from
`race.weaponPrefix()`.

Two differences:

1. **Much weaker melee attributes.** It is a bow, not a sword. New `BOW_MELEE_DAMAGE` /
   `BOW_MELEE_SPEED` keys. Like the zanpakutō's, these are baked into the item's default components
   at registration and so are among the handful of keys `/bleach reload` cannot move.
2. **Vanilla bow use semantics** — `use` / `releaseUsing` / `getUseDuration` / `UseAnim.BOW`.

`UseAnim.BOW` gives the first-person draw-back animation for free. Animations are deferred to a
later phase; this is one the vanilla client hands us today at zero cost.

### 4.3 `ReishiArrow`

`extends AbstractArrow`, registered as `bleach_mod:reishi_arrow` in a new
`entity/BleachEntities.java`.

- Damage and speed scale with draw time, exactly like a vanilla bow.
- `Pickup.DISALLOWED` — condensed reishi leaves nothing on the floor.
- **No model, no texture.** The entity renders as nothing; what you see is a trail of the existing
  `pressure` particle tinted by `Kit.particleColor`. This reuses `PressureParticleOptions` wholesale,
  ships zero new assets, and reads better as spirit energy than a wooden shaft would. A no-op
  `EntityRendererProvider` is registered so the client does not complain about a missing renderer.
- Costs SP per shot, not items. No quiver, no ammo, no inventory management.
- Deals `spirit_pressure`, inheriting the whole §E pipeline: Soul Level scaling on both sides,
  bleach resistance, kill attribution, and a death message that already exists.

**The arrow carries the shooter's Schrift.** It holds the `kitId`, and on impact calls a new
`onProjectileHit(ServerPlayer, LivingEntity, float)` hook on `TransformAbility` — the exact mirror
of the existing `onMeleeHit`, defaulted empty so all eight Shinigami ignore it. That is what lets
Thunderbolt's arrows call lightning without a single kit-id conditional anywhere in the codebase.

### 4.4 The attribution fix, and the trap in it

`progression/KillAttribution.java:96` requires `source.getDirectEntity() == killer`. The taint side
already understands projectiles — `attackerOf:65-75` resolves a projectile's owner correctly — so
only the *payout* side is wrong. Anything that leaves your hand currently awards zero SPX.

**This already silently affects three shipped characters**: Gin's beam, Suì-Fēng's missile and
Yamamoto's cone all pay nothing today. It also contradicts PRD §2.1, which states that "your own
melee **and your own projectiles** both count toward 'you'".

The fix is to accept a projectile whose owner is the killer. **Done naively it is wrong**, because it
would also make *vanilla* bow kills pay SPX — contradicting `SETUP.md` §5 ("a bow kill pays nothing
either") and the "a bow is a bow at every level" rule that keeps gear relevant.

So the condition is narrower: accept the projectile only when `BleachDamage.is(source)` — already the
predicate everything else in the mod uses to mean "this is ours".

> **This is a change to Suhas's half of the mod and needs his explicit sign-off.** It is a bug fix,
> but it is a bug fix that changes the SPX economy for three of his characters, and he may have
> balanced around their current behaviour without knowing why it was happening.

---

## 5. Release tiers

**Tier 1 is your Schrift. Tier 2 is Vollständig.** Both occupy the existing slots, so gates, drains,
exertion, the loan and claw-back, the HUD notches and the released-model predicate are all reused
rather than reimplemented.

### 5.1 `QuincyTransform`

An abstract base implementing `TransformAbility`, holding everything shared. This is the structural
move that makes four Schrifts affordable.

- **Tier 1** — the base holds little; the subclass supplies the letter's active power, its
  `onMeleeHit`, and its `onProjectileHit`.
- **Tier 2 (Vollständig)** — the base supplies the *entire* shared release: the stat package, the
  Hirenkyaku range bonus, and the wings. The subclass supplies only an *amplified* form of its
  tier-1 power.

A Schrift is therefore one power written at two intensities, not two unrelated kits. That is what
keeps four of them from costing four times one.

### 5.2 The wings

An arc of the existing `pressure` particle behind the shoulders, tinted by `Kit.particleColor`. Zero
new assets, consistent with how Flex and Flash Step already draw themselves, and it is the one visual
that reads instantly at range as *that Quincy is in Vollständig*.

### 5.3 Balance parity

Quincy tier 1 takes `GATE_SHIKAI_BASE` / `DRAIN_SHIKAI`; tier 2 takes `GATE_BANKAI_BASE` /
`DRAIN_BANKAI`. **No Quincy-specific gate or drain keys** until playtesting asks for them. Inventing
a parallel set now doubles the tuning surface before anyone has played the race once.

### 5.4 Blut

The **`Z` key** cycles OFF → Vene → Arterie → OFF. (No relation to Schrift Z; the two share a letter
and nothing else.)

| Piece | Design |
|---|---|
| State | `SpiritualData.blut` (byte 0/1/2). **Not persisted** — volatile like `flexing`, cleared on death, logout and respawn. Synced, because the client needs the tell. |
| Vene | Damage taken × `(1 − BLUT_VENE_REDUCTION)`, plus a movement-speed penalty. Defence bought with mobility. |
| Arterie | Bleach damage dealt × `(1 + BLUT_ARTERIE_BONUS)`; the Vene reduction is not available. Offence bought with your skin. |
| Cost | `BLUT_DRAIN_BASE − BLUT_DRAIN_PER_LEVEL × (SL−1)` SP/s, **additive with the release drain**, exactly like Flex. **No exertion** — Blut is circulation, not a release. |
| Where applied | Both multipliers go in `progression/DamageScaling`, beside the existing `meleeDamageBonus` and the Soul Level reductions. 1.21.1 has no damage-taken attribute, and putting it anywhere else scatters the damage math. |
| Refusal | Will not engage below the tick cost; drops itself when it cannot be paid. Same pattern as Flex and Hover — no new failure mode. |
| Tell | The SP bar border tints: blue for Vene, red for Arterie, reusing the existing pulse-border draw. No new HUD element. |
| Wire | One new `AbilityAction.BLUT_CYCLE`, **appended at the end** of the enum. The ordinal is protocol; nothing reorders. |

Gated on `race.hasBlut()`, so pressing the key as a Shinigami does nothing until Suhas wants a
Vizard equivalent there.

---

## 6. T — The Thunderbolt

Tier 1 is a **toggled state** draining 1.5 SP/s, not a one-shot, so it must be a sustained stance —
the way Rukia's Shikai is purely on-hit.

### 6.1 Tier 1 — Galvano

- **Every arrow that lands** calls a bolt on the target for `T_BOLT_DMG`, then **chains** to up to
  `T_CHAIN_COUNT` further entities within `T_CHAIN_RADIUS` of the last struck, each link at
  `T_CHAIN_FALLOFF` of the previous link's damage.
- **Melee hits** get a single bolt at `T_MELEE_BOLT_DMG`, no chain, rate-limited by
  `T_MELEE_COOLDOWN` — the per-attacker map pattern Rukia already uses, so a fast weapon cannot
  machine-gun it.
- **Wet targets take `T_WET_MULT` extra.** Rain, water, a target that just swam. It pairs with the
  ambient-reishi regen from decision #7: the open sky is where a Quincy wants to be, for both reasons.

**The bolts are `setVisualOnly(true)`, and that is load-bearing rather than cosmetic.** Vanilla
lightning deals a flat 5 damage and starts fires. Real bolts would bypass the entire §E pipeline — no
Soul Level scaling in either direction, no SPX attribution — and hand every Thunderbolt player a
forest-burning grief tool that Yamamoto at least pays a 95% gate for. So the bolt is the visual only
and the damage is ours, dealt as `spirit_pressure`. `T_BOLT_VISUAL_ONLY` remains a config key for a
server that deliberately wants fire-starting lightning.

**Chain safety.** Never re-strikes an entity within the same chain, never chains to the shooter, and
filters on `!isAlliedTo` — the guard Ichigo's cleave already uses, so your own wolf does not eat the
arc. The capped count bounds the work at one AABB query per link.

### 6.2 Tier 2 — Vollständig: Galvano Javelin

The shared Vollständig package, plus T amplified: chain count, damage and radius take their
`T_VOLL_*` values.

Its own verb, rather than being tier 1 but larger: **a fully-drawn shot** — draw progress ≥
`T_JAVELIN_MIN_DRAW`, expressed as a fraction of a full draw — fires as a javelin — a straight lightning line to `T_JAVELIN_RANGE` that
**pierces every entity along its path** for `T_JAVELIN_DMG`. A line rather than a web, and it rewards
the draw-time skill the bow already teaches.

### 6.3 SPX

Chain and javelin damage are built with the player as attacker and no intermediary, so
`getDirectEntity() == killer` holds and they pay under the *existing* rule. The §4.4 fix is needed
only for the arrow itself.

### 6.4 Why T is first

The lightest of the four by a distance: no new entity types beyond the shared arrow, no new AI, no
new packets, no new assets. It is `onProjectileHit` + `onMeleeHit` on a `QuincyTransform` subclass,
one chain helper, and about fourteen tuning keys.

That makes it the right probe: it exercises the whole seam — race, bow, arrow, tiers, attribution —
end to end with the least ability-specific code, so if the architecture is wrong we find out cheaply.

---

## 7. D — The Deathdealing

### 7.1 Tier 1 — the dose

Every hit adds to a per-target dose: `D_DOSE_PER_ARROW` from a landed arrow, `D_DOSE_PER_MELEE` from
a swing. Cross that target's threshold and they die outright.

- Threshold is `D_THRESHOLD_BASE + D_THRESHOLD_PER_LEVEL × targetSL` for players; mobs derive theirs
  from max health.
- **Dose decays.** After `D_DOSE_GRACE_TICKS` without a hit it bleeds off at `D_DOSE_DECAY_PER_SEC`.
  That decay *is* the counterplay — disengage and you reset. It is what stops this being a slow
  inevitability at range.
- The kill is dealt as `spirit_mechanic_kill`, inheriting exactly what Suì-Fēng's mark has: bypasses
  armour, Protection, Resistance and shields; **Totem of Undying still works**; Soul Level reductions
  do not apply, because it is a mechanic and not damage.
- **Both sides see the clock.** A particle above the target scaling with dose — the way Suì-Fēng's
  butterfly is visible to its victim — plus a dose readout on the attacker's action bar. An invisible
  kill timer is a bad mechanic; a visible one is a threat you can play against.
- **Cost:** a successful dose-kill dumps `D_KILL_EXERTION` seconds, anchored to the precedent
  Suì-Fēng set at 60.

It does not collide with Suì-Fēng despite both being execute mechanics. Hers is two precise strikes
on one body point, melee, with no time limit. This is many hits anywhere, on a decaying clock, at
range.

### 7.2 Tier 2 — Gift Bad

The shared Vollständig package, plus area denial: a radius of poisoned reishi in which *everything
accumulates dose passively per second*, no hits required. Faster dosing, slower decay. Tier 1 is a
single-target clock; tier 2 is a room you do not want to stand in.

---

## 8. M — The Miracle

### 8.1 Tier 1 — stacks

Damage taken feeds `M_STACK_PER_DAMAGE` stacks, capped at `M_STACK_MAX`. Each stack grants
`M_DMG_PER_STACK` bleach damage dealt and `M_HP_PER_STACK` max health, pushed as an attribute
modifier with the same remove-then-add idempotence `SoulLevel.applyHealth` uses. Stacks decay after
`M_STACK_GRACE_TICKS` without damage, and drop entirely on revert.

Bonus max HP arrives as *empty* hearts rather than healing — headroom, not a heal. That is vanilla
behaviour and it is the correct read: the miracle is that you keep standing, not that your wounds
close.

The tension is deliberate. M is the Quincy who wants to be *in* the fight, on a race built for range.
It is the roster's only bruiser and the race's answer to being dived.

### 8.2 Tier 2 — the miracle itself

Entering cashes current stacks into an immediate burst: a heal plus a knockback shockwave scaled by
stacks. While up, stacks accrue faster and additionally grant damage reduction.

Once per activation, **lethal damage is survived at 1 HP**, consuming every stack. Gated behind
`M_MIRACLE_MIN_STACKS` and a large exertion dump so it cannot be farmed.

**Parity rule, stated so it is not argued about later: the miracle does not save you from Suì-Fēng's
Nigeki Kessatsu or from D's dose kill.** Those are mechanics, not damage — the same reason
`BALANCE.md` already records that Soul Level reductions do not stop Suì-Fēng. Letting a Quincy shrug
off two execute mechanics would delete both characters.

---

## 9. Prerequisite fixes

Verified against the code. Each blocks or materially affects Quincy.

### 9.1 The picker caps at nine — hard blocker

`menu/ZanpakutoSelectMenu.java:46,62` — `ROW = 9` and the fill loop is
`i < choices.size() && i < ROW`. There are already **8** kits. A tenth is **silently unselectable**:
no error, no log, it simply does not appear.

Four Quincy Schrifts take the roster to twelve. **Quincy cannot ship without changing this.**

**Recommendation: a two-step picker** — race first, then kit within that race. It is more code than
widening the menu to `GENERIC_9x3`, but the race choice needs a screen of its own anyway (§1.1), each
screen stays readable, and it is the shape the permanent-race-first flow will want.

### 9.2 Projectile kills pay zero SPX

See §4.4. Needs Suhas's sign-off because it changes the economy for three of his characters.

### 9.3 `Kit` requires both slots

`ability/Kit.java:27-39` throws unless it receives a non-null Shikai *and* Bankai with matching
`state()`. Decision #2 keeps Quincy inside that contract, so **no change is needed** — but it means
every Schrift must define both tiers. A Schrift with only one idea is not shippable.

### 9.4 Bugs worth fixing while we are in these files

Not blockers, but we will be touching the surrounding code.

| Where | Problem |
|---|---|
| `effect/FreezeEffect.java:39,51` | Damage cadence keys off `entity.tickCount % 20` but the method only runs when `duration % 10 == 0`. For many entities the phases never coincide and **Rukia's freeze deals no damage at all**. |
| `effect/FreezeEffect.java:53` | Damage source built with a **null attacker**, so every freeze tick taints the victim. Nothing Rukia freezes can ever pay SPX — including to Rukia. |
| `attachment/SpiritualData.java:282` | `copyVolatileFrom` has **no callers**. Dimension change drops SP/exertion/state to defaults, contradicting PRD §1.2. |
| `tuning/BleachTuning.java:153` | `MOB_SCALE_HEALTH` is a dead knob — declared, documented in PRD §2.5, referenced nowhere. |
| `ServerFlightFix.java:31` | Reflects on the Mojang-mapped field `allowFlight`; throws on a remapped production server. `InaccessibleObjectException` is uncaught and would escape `SERVER_STARTING`. |
| `models/item/zanpakuto_aizen.json` | The `released >= 0.5` override has no upper bound, so **Aizen's Bankai renders the Shikai model**. |
| `mixin/client/GuiHealthMixin.java` | `pushPose` at HEAD, `popPose` at RETURN. If another mod cancels `renderPlayerHealth`, the pose stack leaks. |
| `menu/ZanpakutoSelectMenu.java:128` | `choose` consumes the *first* Asauchi found by inventory scan, not the one that opened the menu. |

### 9.5 Documentation drift

`PRD.md`, `GUIDE.md` and `BleachGuide.basics()` all still say **five characters**; there are eight.
Aizen, Tōsen and Gin have no `/bleach guide` topic and no `/bleach <kit>` readout. §3.4's
race-iterating guide fixes the structural half of this.

---

## 10. Decisions needed from the others

### 10.1 Suhas

1. **The attribution fix (§4.4).** Changes the SPX economy for Gin, Suì-Fēng and Yamamoto.
2. **Z — The Zombie.** The PRD states that "your own tamed pets" tainting a kill is deliberate: it is
   how pet-tanking was killed. Z's entire mechanic is allies damaging your targets, so Z either pays
   zero SPX forever or someone carves an ownership exemption into `KillAttribution`. **That is a
   change to a documented balance rule in his half of the mod.** Hence Z ships last.
3. **The picker fix (§9.1)**, since it touches the flow every new Shinigami player goes through.

### 10.2 Adil

The `Race` seam is shared ground. Decision #12 is to build Quincy-shaped and reconcile afterwards
rather than landing shared groundwork first — an accepted risk, not an oversight.

The specific thing to check with him **before the seam is written**: Arrancar resurrección is one
release tier, not two. `tierNames` being a list is the hedge. If he needs anything else at race level
that Quincy does not, the cost of reconciliation lands on whoever merges second, and it is cheaper to
know now.

---

## 11. Testing

The repo has no test framework and Minecraft mods resist unit testing, so this follows the precedent
already set by `/bleach test clawback` — operator commands that assert an invariant in-world and
return 1 or 0.

### 11.1 Command-driven acceptance checks

| Command | Asserts |
|---|---|
| `/bleach test race` | A fresh player defaults to `RACE_SHINIGAMI`; an existing save loads with race 0; `Races.SHINIGAMI.regenMultiplier` returns exactly 1.0 |
| `/bleach test bow` | The bow obeys every zanpakutō guarantee: cannot be dropped, cannot enter a container, survives death, is restored on respawn, is re-minted if lost |
| `/bleach test attribution` | A reishi-arrow kill on an untainted target pays SPX; a **vanilla** bow kill on the same target pays nothing |
| `/bleach test blut` | Vene and Arterie are mutually exclusive; the drain is additive with the release drain; the stance drops when the pool cannot pay |
| `/bleach test chain` | A T chain never re-strikes an entity, never strikes the shooter, and never exceeds `T_CHAIN_COUNT` |

The attribution test is the important one — it is the single check that catches the §4.4 trap, where
a correct-looking fix silently starts paying for vanilla bows.

### 11.2 Manual matrix

Every check run **as both races**, because the governing constraint is that Shinigami behaviour does
not change:

- Draw/sheathe, death, respawn, dimension change, logout mid-release.
- Enter and leave both tiers; confirm the Bankai loan and claw-back still behave for Shinigami.
- HUD: gate notches at the right thresholds, the Blut tint appearing only for Quincy.
- A Shinigami pressing the Blut key does nothing at all.
- Server tick time under 40 ms during a T chain into a crowd, and during D's Gift Bad.

---

## 12. Build order

Sequenced so the architecture is proven before any ability depends on it.

1. **The race seam** — `data.race`, `Race`/`Races`, the sync component, the regen hook. Shinigami-only
   behaviour verified unchanged.
2. **The picker fix** (§9.1). Blocks everything after it.
3. **`SpiritWeapon` rename** + `HeiligBogenItem` + `ReishiArrow` + `BleachEntities`.
4. **The attribution fix** (§4.4), with its test.
5. **`QuincyTransform`** + the shared Vollständig package and wings.
6. **Blut** (§5.4).
7. **T — The Thunderbolt.** First real Schrift; proves the whole seam.
8. **D — The Deathdealing.**
9. **M — The Miracle.**
10. **Z — The Zombie**, only after §10.1.2 is answered.
11. **Tuning pass** — `BALANCE.md` §P, then playtest.

Steps 1–4 are the risky ones and touch shared code. Steps 7–10 are independent of one another and
touch only Quincy files.

**This likely wants two implementation plans, not one.** Steps 1–6 are foundation work on shared
files that Suhas and Adil both care about and that must land as a coherent whole; steps 7–11 are
per-Schrift work that only touches Quincy files and can be picked up in any order. Splitting them
also means the foundation can be reviewed and merged before four abilities are stacked on top of it.

---

## 13. Where the numbers live

A new **`BALANCE.md` §P**, following the existing convention exactly: symbol, default, unit, meaning,
and the section that justifies it. Subsections `§P.0` shared Quincy, `§P.T`, `§P.D`, `§P.M`, `§P.Z`.

Every rule from `BALANCE.md`'s preamble applies unchanged: **no numeric literal in game logic**, every
constant a `public static` non-final field in `BleachTuning` so `/bleach reload` can move it, and a
row in the table for every field. The two exceptions are `BOW_MELEE_DAMAGE` and `BOW_MELEE_SPEED`,
which are baked into item components at registration and therefore need a restart — the same caveat
`BALANCE.md` §I.1 already records for the zanpakutō.
