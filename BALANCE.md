# Bleach Mod — Balance Constants Registry

**This file is the single source of truth for every number in the mod.**

Rules, and they are not optional:

1. **No numeric literal appears anywhere in game logic.** Every constant lives in `com.bleach.mod.tuning.BleachTuning` as a mutable `public static` field. Continuous values are `double`; counts, tick durations, packed RGB colours and flags are declared `int` / `boolean` / `double[]` so call sites do not cast. All four kinds are config-overridable and reflected over by the same loader.
2. **Every constant in `BleachTuning` appears in a table below**, with its symbol, default, unit, and the PRD section that justifies it.
3. `BleachTuning` loads from `config/bleach_mod/tuning.json` at startup and on `/bleach reload`. **The file holds overrides only** — every key absent from it takes the default below, so a changed default reaches every install that had not deliberately overridden that key, automatically and without touching the keys that had. `config/bleach_mod/tuning-defaults.json` is a generated reference listing of every key and its shipped default; it is rewritten each launch and never read back.
4. When you change a default here, change it in `BleachTuning` in the same commit. When you change one there, change it here. **Do not bump `CURRENT_CONFIG_VERSION` for a default change** — that stamp now tracks the file *format*, and bumping it costs players their tuning for no benefit. Bump it only when a key is renamed or re-typed, or a value's encoding changes.

Every formula in `PRD.md` cites the constant symbols from this file rather than raw numbers.

---

## A. Spiritual Pressure — pool and regen · *PRD §1.1–1.2*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `SP_BASE_MAX` | 100.0 | SP | Max SP at SL 1 |
| `SP_MAX_PER_LEVEL` | 10.0 | SP/level | Added max SP per level above 1 |
| `SP_REGEN_BASE_PCT` | 0.020 | frac of max /s | Regen at SL 1 |
| `SP_REGEN_PCT_PER_LEVEL` | 0.0011 | frac of max /s /level | Regen growth per level |
| `SP_REGEN_PAUSE_TICKS` | 60 | ticks | Regen freeze after any spend or damage |

```
maxSp(SL)   = SP_BASE_MAX + SP_MAX_PER_LEVEL × (SL − 1)
regen(SL)   = maxSp(SL) × (SP_REGEN_BASE_PCT + SP_REGEN_PCT_PER_LEVEL × (SL − 1))
```

---

## B. Exertion · *PRD §1.3*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `EXERTION_RATE_BANKAI` | 1.00 | exertion/s | Accrual while in Bankai |
| `EXERTION_RATE_SHIKAI` | 0.35 | exertion/s | Accrual while in Shikai |
| `EXERTION_K_BASE` | 0.060 | — | Penalty coefficient at SL 1 |
| `EXERTION_K_PER_LEVEL` | 0.0024 | /level | Coefficient reduction per level |
| `EXERTION_K_FLOOR` | 0.005 | — | Floor under the coefficient. Small, positive, never zero |
| `EXERTION_MULT_FLOOR` | 0.20 | — | Hard floor on the regen multiplier |

```
k(SL)         = max(EXERTION_K_FLOOR, EXERTION_K_BASE − EXERTION_K_PER_LEVEL × (SL − 1))
regenMult(SL) = max(EXERTION_MULT_FLOOR, 1 / (1 + k(SL) × exertion))
```

Exertion clears to zero **only** when SP reaches 100% of max. Nowhere else.

`EXERTION_K_FLOOR` became load-bearing when `SL_MAX` moved to 100. The unfloored coefficient goes
**negative at SL 26**, which puts a pole in `regenMult`'s denominator: at exactly
`exertion = 1 / |k|` regeneration is infinite, and past it, negative. The floor keeps exertion
mattering less and less at high level without ever inverting.

---

## C. Transformation gates and drains · *PRD §1.4–1.5*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `GATE_BANKAI_BASE` | 0.95 | frac of max | Bankai entry threshold at SL 1 |
| `GATE_SHIKAI_BASE` | 0.65 | frac of max | Shikai entry threshold at SL 1 |
| `GATE_REDUCTION_PER_LEVEL` | 0.015 | frac/level | Threshold reduction per level (both) |
| `GATE_FLOOR_PCT` | 0.10 | frac of max | Floor under both gates |
| `DRAIN_BANKAI` | 5.0 | SP/s | Bankai drain **at SL 1** |
| `DRAIN_SHIKAI` | 1.5 | SP/s | Shikai drain **at SL 1** |
| `SHIKAI_DRAIN_TAPER_CAP` | 0.99 | frac | Most of the Shikai drain the curve may remove |
| `SHIKAI_DRAIN_FLOOR` | 0.10 | frac of base | Floor under the Shikai drain — 0.15 SP/s, reached at SL 70 |
| `BANKAI_DRAIN_TAPER_CAP` | 0.54 | frac | Most of the Bankai drain the curve may remove |
| `BANKAI_DRAIN_FLOOR` | 0.50 | frac of base | Floor under the Bankai drain — 2.5 SP/s, reached at SL 76 |

```
gate(SL)  = max(GATE_FLOOR_PCT, GATE_*_BASE − GATE_REDUCTION_PER_LEVEL × (SL − 1))
drain(SL) = DRAIN_* × max(FLOOR, 1 − TAPER_CAP × progress(SL))
```

### C.1 Why the drains taper — Adil's item 7

He asked for a Shikai drain that reaches **zero** by SL 60–70. Built literally that is the §E defect
one layer down: a drain that reaches zero is a stance that is free forever, and free-forever is what
the asymptotic damage curve exists to prevent. So the drain rides the same `progress(SL)` the damage
multipliers do and stops on a floor instead of on nothing.

| SL | Pool | Shikai/s | Shikai holds for | Bankai/s | Bankai holds for |
|---|---|---|---|---|---|
| 1 | 100 | 1.50 | 67s | 5.00 | 20s |
| 20 | 290 | 0.78 | 6.2 min | 3.69 | 79s |
| 65 | 740 | 0.17 | 71 min | 2.59 | 4.8 min |
| 100 | 1090 | 0.15 | 121 min | 2.50 | 7.3 min |

At SL 65 Shikai reads as free and is not. Bankai's taper is deliberately shallower and floors at
half the posted rate, the same half-measure as `SL_DMG_TAKEN_FLOOR`: Shikai is the sustainable
stance and Bankai the committed burn, and a Bankai that tapered as hard as Shikai would collapse
that distinction exactly where the ladder is longest.

Regen and the release drain are mutually exclusive in `SpiritualTicker`, so every figure above is a
hard clock rather than an asymptote — a high-level Shikai still ends.

`SHUNSUI_ACT3_SP_DRAIN_PER_SEC` **replaces** `DRAIN_BANKAI` rather than adding to it, so it carries
the same taper via `SoulLevelCurve.bankaiDrainMultiplier`. Left flat it would have made the
strongest part of the kit the one a levelled player could least afford. The Act 3 drain applied to
*participants* is deliberately **not** tapered: that is a debuff Shunsui imposes, not upkeep they
pay, and tapering it would have quietly weakened his Bankai against high-SL targets.

Bankai's entry refill is a **loan, not a gift** — see §1.4. No constant; the claw-back is `sp = min(spOnEntry, sp)` on revert.

`GATE_FLOOR_PCT` is the other constant `SL_MAX = 100` made load-bearing. Unfloored, Bankai's gate
crosses zero at **SL 64** and Shikai's at **SL 44**, and a gate at or below zero is one an empty pool
clears — release would be free for the top third of the ladder. Releasing always costs something.

---

## D. Soul Points · *PRD §2.1–2.3*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `SPX_DAILY_CAP_BASE` | 200 | SPX/MC-day | Base daily cap before scalars |
| `CATCHUP_PER_LEVEL_GAP` | 0.15 | /level | Catch-up bonus per level below WSL |
| `CATCHUP_MAX` | 3.0 | × | Catch-up multiplier ceiling |
| `WORLD_SCALAR_PER_LEVEL` | 0.08 | /level | World scalar growth per WSL point |
| `SPX_PLAYER_BASE` | 25 | SPX | Base value of a player kill |
| `SPX_PLAYER_PER_LEVEL_GAP` | 5 | SPX/level | Bonus per level the victim is above you |
| `SPX_CURVE_COEFF` | 12.0 | — | Level curve coefficient |
| `SPX_CURVE_EXPONENT` | 1.6 | — | Level curve exponent |
| `SL_MAX` | 100 | level | Level cap |

```
worldScalar   = 1 + WORLD_SCALAR_PER_LEVEL × (WSL − 1)
catchUp       = clamp(1 + CATCHUP_PER_LEVEL_GAP × (WSL − SL), 1.0, CATCHUP_MAX)
mobSpx        = baseSpx × worldScalar × catchUp
playerSpx     = (SPX_PLAYER_BASE + SPX_PLAYER_PER_LEVEL_GAP × max(0, theirSL − yourSL)) × catchUp
dailyCap      = SPX_DAILY_CAP_BASE × worldScalar × catchUp
spxToNext(L)  = round(SPX_CURVE_COEFF × L ^ SPX_CURVE_EXPONENT)
```

`worldScalar` does **not** apply to player kills — those already scale on the level gap.

### D.1 Mob base SPX table

Lives in `BleachTuning.MOB_SPX` as a `Map<ResourceLocation, Integer>`, config-overridable per entity type.

| Entity | Base |
|---|---|
| Zombie / Skeleton / Spider / Husk / Stray / Drowned | 1 |
| Creeper / Enderman / Witch | 2 |
| Wither Skeleton / Blaze / Piglin Brute / Evoker | 5 |
| Iron Golem / Ravager / Elder Guardian | 8 |
| Wither / Ender Dragon / Warden | 100 |
| *(unlisted)* | `SPX_DEFAULT_MOB` = 1 |

---

## E. Soul Level scaling · *PRD §2.4*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `SL_CURVE_K` | 0.035 | — | How fast §E scaling approaches its ceiling |
| `SL_BLEACH_DMG_DEALT_CAP` | 0.60 | frac | Ceiling on the bleach damage **dealt** bonus |
| `SL_BLEACH_DMG_TAKEN_CAP` | 0.3333 | frac | Ceiling on the bleach damage **taken** reduction |
| `SL_GENERAL_DMG_TAKEN_CAP` | 0.25 | frac | Ceiling on the **all-source** damage reduction |
| `SL_DMG_TAKEN_FLOOR` | 0.50 | frac | Hard floor on incoming damage after both reductions |
| `SL_HP_PER_TWO_LEVELS` | 1.0 | HP | Max health per 2 levels |
| `SL_LEVELUP_SOUND_VOLUME` | 0.7 | — | Level-up sound volume |
| `SL_LEVELUP_SOUND_PITCH` | 0.6 | — | Level-up sound pitch. Below 1.0 — deeper than the vanilla XP chime it borrows |

```
progress(SL)     = 1 − exp(−SL_CURVE_K × (SL − 1))          0 at SL 1, asymptotically 1
bleachDealt(SL)  = 1 + SL_BLEACH_DMG_DEALT_CAP  × progress(SL)
bleachTaken(SL)  = 1 − SL_BLEACH_DMG_TAKEN_CAP  × progress(SL)
generalTaken(SL) = 1 − SL_GENERAL_DMG_TAKEN_CAP × progress(SL)
damageTaken(SL)  = max(SL_DMG_TAKEN_FLOOR, generalTaken × (bleach ? bleachTaken : 1))
bonusHp(SL)      = floor(SL / 2) × SL_HP_PER_TWO_LEVELS
```

Incoming bleach damage is multiplied by **both** `generalTaken` and `bleachTaken`, and the product is
then held at `SL_DMG_TAKEN_FLOOR`. Damage **dealt** scales for bleach sources only. Vanilla weapons
never scale.

| SL | general taken | bleach taken (both) | bleach dealt | SP | bonus HP |
|---|---|---|---|---|---|
| 1 | — | — | — | 100 | +0 |
| 10 | −6.8% | −15.2% | +16.2% | 190 | +5 |
| 20 | −12.1% | −26.4% | +29.1% | 290 | +10 |
| 50 | −20.5% | −42.2% | +49.2% | 590 | +25 |
| 100 | −24.2% | −48.7% | +58.1% | 1090 | +50 |

### E.1 Why the curve is not a straight line

Every row above used to be linear in the level and floored at zero, which is fine at a cap of 20 and
indefensible at a cap of 100. `SL_BLEACH_DMG_TAKEN_PER_LEVEL = 0.015` crossed zero at **SL 68** —
a player two-thirds up the new ladder took **no damage at all** from anything in the mod — and
`SL_BLEACH_DMG_DEALT_PER_LEVEL = 0.020` reached **+198%** at SL 100. Both are properties of a line
that nobody had yet run far enough to see.

Asymptotic scaling has no such cliff: each multiplier approaches a ceiling it cannot pass at any
level, and `SL_DMG_TAKEN_FLOOR` guarantees the rest. The two taken-caps are chosen so their product
lands on that floor — `0.75 × 0.6667 = 0.500` — so at shipped values the floor is a statement of
intent rather than a clamp that bites; it exists for the config file, which can hold any number
somebody types into it.

**The same level is now worth less, deliberately.** SL 20 gives −26.4% where it used to give −42%,
because the same progression is spread over five times as many levels. The compensation is that
`SP_MAX_PER_LEVEL` and `SL_HP_PER_TWO_LEVELS` stay linear, so the cap raise alone takes a capped
player from 290 SP to **1090** and from +10 HP to **+50** — which is the point of raising it.

### E.2 Sanity check — what the cap raise does to every *other* per-level constant

§E is now safe at any cap. Nothing else in this file is. Every row below is linear in the level and
was written against a cap of 20; these are the values a capped player gets today. **None of them is
a bug** — each is the constant doing exactly what it says — but several are large enough that the
kit no longer plays the way its own section describes.

| Constant | SL 20 | SL 100 | Note |
|---|---|---|---|
| `SP_REGEN_PCT_PER_LEVEL` | 11.9 SP/s | **140.5 SP/s** | Against `DRAIN_BANKAI` 5/s. Every drain in the mod is now free |
| `AURA_RANGE_PER_LEVEL` | 350 blocks | **1486 blocks** | Past any render distance — Aura Sense becomes world-wide |
| `YAMA_BANKAI_CONE_DMG_PER_SL` | 65 | **225** | A one-shot on anything |
| `YAMA_BANKAI_CONE_RANGE_PER_SL` | 40 blocks | **139 blocks** | …delivered from off-screen |
| `SHINJI_RADIUS_PER_LEVEL` | 32 blocks | **96 blocks** | Inverted controls over a 96-block sphere |
| `SHUNSUI_BANKAI_ZONE_RADIUS_PER_SL` | 27.5 blocks | **67.5 blocks** | Why §J.9's shell is stride-drawn rather than drawn whole |
| `FS_RANGE_PER_LEVEL` | 40 blocks | **84 blocks** | Flash Step at full SP |
| `FLEX_RADIUS_PER_LEVEL` | 16 blocks | **48 blocks** | — |
| `AIZEN_ILLUSION_MOB_PER_SL` | +9 mobs | **+49 mobs** | — |

The first row is deliberate and is the user's own ruling — *"at higher SL levels we get a lot more
reiatsu, but the drain remains the same"* — so it is recorded rather than corrected. **Superseded in
part:** the release drain no longer remains the same, because Adil's item 7 was built too (§C.1).
The two now stack in the same direction — a bigger pool *and* cheaper upkeep — which is why item 7's
floors are the load-bearing part of it and not a detail. The rest want a
retune pass of their own; the two worth doing first are Yamamoto's cone and Aura Sense's range,
because both are already past the point where the other player can see what is happening to them.

Drains that scale *down* per level (`BLUT_DRAIN_PER_LEVEL`, `HOVER_DRAIN_PER_LEVEL`,
`FLEX_DRAIN_PER_LEVEL`) were checked and are all `Math.max`-floored already, so none of them reaches
zero or inverts. The two that were **not** floored — the entry gates and the exertion coefficient —
are fixed in §B and §C above.

> **Open: the level curve was not retuned with the cap.** `SPX_CURVE_COEFF × L^SPX_CURVE_EXPONENT`
> totals ~10,400 SPX to reach SL 20 and **~722,000** to reach SL 100 — 69× the grind, against a
> 200/day base cap. The scaling curve above is correct at every level whether or not anyone reaches
> them, but SL 100 is currently decorative. Retuning §D is its own pass; see §M.4.

`bonusHp` is the one value on this list that is **pushed rather than derived on read**: it is an
`AttributeModifier` the game holds, keyed `bleach_mod:soul_level_health`. Anything that changes a
Soul Level — a level-up, `/bleach sl set`, a respawn, a join, or `/bleach reload` moving
`SL_HP_PER_TWO_LEVELS` — has to re-apply it. `SoulLevel#applyHealth` removes before it adds, so
calling it twice is free and never stacks.

---

## F. World scaling of mobs · *PRD §2.5*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `MOB_SCALE_PER_LEVEL` | 0.08 | /level | Mob outgoing damage growth |
| `MOB_SCALE_LEVEL_HEADROOM` | 5 | levels | Max levels a mob may out-scale its victim |
| `MOB_SCALE_HEALTH` | false | bool | Also scale mob max health (off by default) |

```
effectiveLevel = min(WSL, victimSL + MOB_SCALE_LEVEL_HEADROOM)
mobScalar      = max(1.0, 1 + MOB_SCALE_PER_LEVEL × (effectiveLevel − 1))
```

**The headroom clamp is load-bearing.** Without it, a fresh SL-1 player joining a WSL-18 server takes 2.36× mob damage with none of the defense, and cannot leave spawn. With it, they face at most 1.40×, while an SL-20 player on that same world faces the unclamped 2.36×. Mobs scale to the world, but never further ahead of *you* than five levels.

The ceiling of **2.52×** is the value at `effectiveLevel` 20 and therefore needs a WSL-20 world, not a WSL-18 one — `min(18, 25)` is 18. Earlier drafts of this line, of PRD §2.5 and of the Phase 5 acceptance test all quoted 2.52× against WSL 18; the formula was right and the worked example was not.

---

## G. Flash Step · *PRD §4*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `FS_RANGE_BASE` | 10.0 | blocks | Range floor |
| `FS_RANGE_PER_LEVEL` | 0.55 | blocks/level | SL contribution |
| `FS_RANGE_SP_TERM` | 20.0 | blocks | Contribution at 100% SP |
| `FS_COST_PCT` | 0.08 | frac of max | SP cost |
| `FS_COOLDOWN_TICKS` | 8 | ticks | Base cooldown (0.4 s) |
| `FS_SKY_RANGE_PCT` | 1.0 | frac | Sky-branch range vs ground range (impulse branch only) |
| `FS_STOP_SHORT` | 0.5 | blocks | Distance held back from the hit point |
| `FS_CLEARANCE_STEP` | 0.25 | blocks | Step size when walking a blocked destination back toward the eye |
| `FS_SKY_TELEPORT` | true | flag | Sky branch teleports instead of applying an impulse |
| `FS_SKY_CARRY_MOMENTUM` | 0.35 | blocks/tick | Momentum kept along the look vector after an airborne step |
| `FS_GROUND_PROBE` | 0.2 | blocks | How far below the arrival box ground is probed for |
| `FS_SKY_LEVITATION_TICKS` | 0 | ticks | Sky branch — Levitation I duration (0 disables) |
| `FS_SKY_SLOW_FALLING_TICKS` | 40 | ticks | Slow Falling applied on any airborne arrival |
| `FS_NO_FALL_DAMAGE` | `true` | — | Flash Step cancels fall damage until the player next lands |
| `FS_SKY_IMPULSE_PER_BLOCK` | 0.05 | blocks/tick per block | Impulse branch — initial velocity per block of sky range |
| `FS_ARRIVAL_BURST_DELAY_TICKS` | 2 | ticks | Delay on the arrival burst so it never precedes the player |
| `FS_PARTICLE_COUNT` | 45 | count | Particles at each endpoint |
| `FS_PARTICLE_SPREAD` | 1.1 | blocks | Particle scatter around each endpoint |
| `FS_PARTICLE_SCALE` | 1.2 | — | Particle scale |
| `FS_SOUND_VOLUME` | 0.8 | — | Teleport sound volume |
| `FS_SOUND_PITCH` | 1.4 | — | Teleport sound pitch |

```
range(SL, sp) = (FS_RANGE_BASE + FS_RANGE_PER_LEVEL × (SL − 1)
                 + FS_RANGE_SP_TERM × sp/maxSp) × kit.flashStepRangeMult
```
SL 1 empty → 10.0 blocks · SL 1 full → 30.0 · SL 20 full → 40.5 · Sui-Feng SL 20 full → 60.7

With `FS_SKY_TELEPORT` set — the default — there is no separate sky branch: a miss with the look
pitch above the horizon teleports to the ray end exactly as the ground branch does, keeps
`FS_SKY_CARRY_MOMENTUM` along the look vector, and lands under Slow Falling.

The impulse branch behind that flag is kept for configs that want the old shape. It fires only on a
genuine miss **with the look pitch above the horizon**, and its velocity is
`range × FS_SKY_RANGE_PCT × FS_SKY_IMPULSE_PER_BLOCK` blocks per tick along the look vector.
`FS_SKY_LEVITATION_TICKS` now defaults to 0 there: Levitation overwrites vertical velocity with a
fixed crawl every tick, so it ate the impulse it was meant to hold up and made the airborne step
travel slower than the ground one, not further.

---

## H. Spiritual Flex · *PRD §5*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `FLEX_RADIUS_BASE` | 8.0 | blocks | Radius floor |
| `FLEX_RADIUS_PER_LEVEL` | 0.40 | blocks/level | Radius growth |
| `FLEX_DRAIN_BASE` | 3.0 | SP/s | Flexer drain at SL 1 |
| `FLEX_DRAIN_PER_LEVEL` | 0.10 | SP/s/level | Flexer drain reduction |
| `FLEX_COUNTER_DRAIN_BASE` | 4.0 | SP/s | Counterer drain at SL 1 |
| `FLEX_COUNTER_DRAIN_PER_LEVEL` | 0.13 | SP/s/level | Counterer drain reduction |
| `FLEX_COUNTER_GAP_BASE` | 2 | levels | Gap reduction floor when countering |
| `FLEX_COUNTER_GAP_DIVISOR` | 4 | — | `floor(SL / divisor)` added to the above |
| `FLEX_EFFECT_DURATION_TICKS` | 60 | ticks | Reiatsu refresh duration (3 s) |
| `FLEX_BASELINE_MIN_GAP` | 0 | levels | Minimum gap for a flex to register at all; 0 means peers feel it |
| `FLEX_BASELINE_DURATION_TICKS` | 15 | ticks | Baseline pressure on a target the gap does not out-rank; 0 disables |

### H.1 Reiatsu tier thresholds

`gap = flexerSL − targetSL − counterReduction`. Mobs count as SL 0 and cannot counter.

| Symbol | Default | Resulting amplifier |
|---|---|---|
| `REIATSU_TIER_1_GAP` | 1 | amp 0 |
| `REIATSU_TIER_2_GAP` | 4 | amp 1 |
| `REIATSU_TIER_3_GAP` | 8 | amp 2 |
| `REIATSU_TIER_4_GAP` | 13 | amp 3 |

### H.2 Reiatsu effect strength per amplifier

One custom effect, `bleach_mod:reiatsu`. No vanilla effect name is ever shown.

| Amp | `REIATSU_SPEED_MULT` | `REIATSU_ATK_SPEED_MULT` | `REIATSU_ATK_DMG_MULT` | Extra |
|---|---|---|---|---|
| 0 | −0.15 | 0 | 0 | — |
| 1 | −0.30 | −0.20 | 0 | — |
| 2 | −0.45 | −0.35 | −0.25 | — |
| 3 | −1.00 (rooted) | −0.35 | −0.40 | hidden Nausea |

Stored as three `double[]` **indexed by amplifier, never multiplied by it.** The table is not
linear — attack damage is untouched at the first two tiers, and attack speed does not move between
the third and the fourth — so the vanilla `amplifier + 1` scaling would produce a different debuff
at every tier than the one written above.

The rooted tier is identified by `REIATSU_SPEED_MULT[amp] ≤ −1.00`, not by "amplifier 3". Hidden
Nausea hangs off the same test, so retuning the table moves both together rather than leaving the
mixin and this table disagreeing about which tier is which.

| Symbol | Default | Meaning |
|---|---|---|
| `REIATSU_NAUSEA_AMPLIFIER` | 0 | Amplifier of the hidden vanilla Nausea at the crushing tier |
| `REIATSU_EFFECT_COLOR` | `0x7C3AED` | Effect icon colour. **Read once at registration — needs a restart, like §I.1** |

### H.4 Silence · *what a mob may not do under pressure*

A mob standing in a field loses **every special action it has.** Melee is deliberately left alone: a
silenced mob is helpless at range and still dangerous in your face, which is a fight rather than an
execution.

| Symbol | Default | Meaning |
|---|---|---|
| `REIATSU_SILENCE_MOBS` | true | Master switch for the silence |
| `REIATSU_SILENCE_MIN_AMP` | 0 | Lowest amplifier that silences. 0 is any tier at all |
| `REIATSU_SILENCE_PLAYERS` | false | Whether players are silenced too — see below |

What that covers, and where:

| Enforced at | Covers |
|---|---|
| `ConjuredEntityMixin` (`ServerLevel#addFreshEntity`) | Every projectile or entity a mob conjures: skeleton and pillager arrows, witch potions, blaze and ghast fireballs, wither skulls, breeze wind charges, shulker bullets, piglin crossbow bolts, llama spit, evoker fangs, summoned vexes |
| `CreeperSwellMixin` | The creeper's fuse — walked *back* every tick, and the explosion itself refused |
| `EnderManTeleportMixin` | Every enderman blink: idle, daylight, water, on-hit dodge, and the lunge at whoever looked |
| `LivingEntityDamageMixin` | Any damage a silenced attacker deals that is **not** a melee type — a guardian's beam, a warden's sonic boom, anything that touches you without spawning something |

The first row is one rule rather than a mixin per mob, and it is the rule that was already true of all
of them: *an entity being added to the world that remembers who made it.* Mobs from future versions
and from other mods are silenced by it without anyone having to find them first. The last row is an
**allow-list** for the same reason — an unrecognised damage type is treated as an ability and
refused, so the failure mode is a mob that hits for less rather than one that walks through the
mechanic.

**Players are not silenced by default.** A player under a field is already slowed, weakened and slow
to swing by §H.2, and taking their bow away on top of that turns a level gap into a lockout with no
answer — while pushing back, which *is* the counterplay, would still leave them disarmed. Mobs have
no such answer and are not owed one.

Turning `REIATSU_SILENCE_PLAYERS` on **breaks Sui-Feng's Bankai** (§J.3): its wind-up roots the caster
by applying Reiatsu to *herself*, so a silence that reached players would refuse the missile she just
paid half her health for. That has to be dealt with first.

### H.5 Pressure damage · *the bleed*

Standing in a field you are outranked in costs health, once a second, scaled by the level gap:

`damage = REIATSU_DAMAGE_BASE + REIATSU_DAMAGE_PER_GAP × (gap − REIATSU_DAMAGE_MIN_GAP)`, capped at
`REIATSU_DAMAGE_MAX`.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `REIATSU_DAMAGE_INTERVAL_TICKS` | 20 | ticks | Ticks between damage ticks — 20 is once a second |
| `REIATSU_DAMAGE_MIN_GAP` | 1 | levels | Gap below which a field costs no health at all |
| `REIATSU_DAMAGE_BASE` | 0.5 | HP | Damage at exactly the minimum gap |
| `REIATSU_DAMAGE_PER_GAP` | 0.5 | HP/level | Added per level beyond it |
| `REIATSU_DAMAGE_MAX` | 8.0 | HP | Ceiling on one tick, whatever the gap |
| `REIATSU_DAMAGE_HOSTILE_ONLY` | false | — | Whether the bleed spares non-hostiles — see below |

Gap 1 → 0.5/s · gap 4 → 2.0/s · gap 8 → 4.0/s · gap 13 → 6.5/s · gap 15+ → capped at 8.0/s.

**It is the same gap the tier was bought with**, after any counter reduction — so pushing back cuts
the bleed by exactly the levels it cuts the tier by, and a peer's field still cannot hurt you at all.
The cadence is keyed to the *flexer's* tick count, so standing in two hostile fields bleeds twice.

Dealt as `bleach_mod:spirit_pressure`, so it inherits the whole §E pipeline: bleach resistance
applies, the kill is attributed and pays SPX, and the death message already exists. It also inherits
vanilla's invulnerability window — a target being hit by a sword in the same second may shrug off
that second's tick, which is correct for a bleed rather than a burst.

**`REIATSU_DAMAGE_HOSTILE_ONLY` is off**, which means the bleed does not step around the livestock:
cows, horses, villagers and a tamed wolf are all inside the radius and all outranked. A field held at
home is a slow cull of everything you own.

### H.3 Flex presentation

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `FLEX_PARTICLE_INTERVAL_TICKS` | 2 | ticks | Ticks between particle rings; above 1 so a held channel does not flood the client |
| `FLEX_PARTICLE_BASE` | 10 | count | Riser particles before the spend term |
| `FLEX_PARTICLE_PER_SP` | 3.0 | count per SP/s | Riser density scaling · PRD §5.3 |
| `FLEX_RING_POINTS_PER_BLOCK` | 3.5 | count/block | Ring particles per block of field radius |
| `FLEX_RING_Y_OFFSET` | 0.2 | blocks | Ring height above the flexer's feet |
| `FLEX_FEEDBACK_TICKS` | 20 | ticks | How often the flexer's action bar reports the field |
| `FLEX_PARTICLE_RISE` | 1.20 | blocks/tick | Upward velocity of riser particles |
| `FLEX_PARTICLE_RING_JITTER` | 0.35 | blocks | In/out scatter on the ring |
| `FLEX_PARTICLE_SCALE` | 0.6 | — | Pressure particle quad size — the field line reads thin |

The ring is drawn **on the field radius**, not around the flexer's ankles. The edge of the field is
the one thing a target needs to be able to see, and drawing it there costs nothing extra.

---

## I. Kit multipliers · *PRD §6*

Symbols are `KIT_<NAME>_FS_RANGE_MULT`, `KIT_<NAME>_FS_COOLDOWN_MULT`, `KIT_<NAME>_PARTICLE_COLOR`, feeding the `Kit` record's `flashStepRangeMult` / `flashStepCooldownMult` / `particleColor` components.

| Kit | `…_FS_RANGE_MULT` | `…_FS_COOLDOWN_MULT` | `…_PARTICLE_COLOR` |
|---|---|---|---|
| `KIT_ICHIGO_…` | 1.0 | 1.0 | `0x1E3A8A` |
| `KIT_YAMAMOTO_…` | 1.0 | 1.0 | `0xB91C1C` |
| `KIT_SUIFENG_…` | 1.5 | 0.5 | `0xFACC15` |
| `KIT_RUKIA_…` | 1.0 | 1.0 | `0xBFDBFE` |
| `KIT_SHINJI_…` | 1.0 | 1.0 | `0x7E22CE` |
| `KIT_AIZEN_…` | 1.0 | 1.0 | `0x6366F1` |
| `KIT_TOSEN_…` | 1.0 | 1.0 | `0x8B5CF6` |
| `KIT_GIN_…` | 1.2 | 0.8 | `0xE2E8F0` |

### I.1 The zanpakutō item

| Symbol | Default | Meaning |
|---|---|---|
| `ZANPAKUTO_ATTACK_DAMAGE` | 3 | Attack damage on top of the `Tiers.IRON` bonus — i.e. an iron sword |
| `ZANPAKUTO_ATTACK_SPEED` | −2.4 | Attack speed modifier; the vanilla sword swing rate |

The blade is deliberately unremarkable. A character's power lives in Soul Level scaling (§E) and the released states (§J), so a zanpakutō that outclassed vanilla gear on its own stats would make the progression it exists to gate irrelevant. It has no durability at all — an item with no max damage never breaks, which is the simplest way to honour "cannot be lost".

**These two are the only constants `/bleach reload` cannot move.** Item attribute modifiers are baked into the item's default component map when `BleachItems` builds its `Item.Properties` at registration, and that happens exactly once per launch. Changing either needs a restart.

---

## J. Per-kit ability constants

### J.1 Ichigo · *PRD §6.1*

| Symbol | Default | Meaning |
|---|---|---|
| `ICHIGO_SHIKAI_REACH` | +1.5 | `ENTITY_INTERACTION_RANGE` add-value (the big cleaver) |
| `ICHIGO_SHIKAI_DMG` | +0.25 | Bleach melee bonus |
| `ICHIGO_SHIKAI_CLEAVE_ARC` | 90.0 | degrees — arc of the widened sweep |
| `ICHIGO_SHIKAI_CLEAVE_PCT` | 0.50 | frac of primary damage dealt to secondary targets |
| `ICHIGO_BANKAI_SPEED` | +0.60 | `MOVEMENT_SPEED` multiplied-total |
| `ICHIGO_BANKAI_ATK_SPEED` | +0.50 | `ATTACK_SPEED` multiplied-total |
| `ICHIGO_BANKAI_DMG` | +0.75 | Bleach melee bonus |

### J.2 Yamamoto · *PRD §6.2*

| Symbol | Default | Meaning |
|---|---|---|
| `YAMA_SHIKAI_RADIUS` | 30.0 | Ignite radius (blocks) |
| `YAMA_SHIKAI_BURN_TICKS` | 200 | Fire ticks applied |
| `YAMA_BANKAI_RADIUS` | 36.0 | Radius the blade absorbs fire from |
| `YAMA_BANKAI_DMG` | +0.30 | Melee bonus while active |
| `YAMA_BANKAI_ONHIT_BURN_TICKS` | 100 | Fire-Aspect-equivalent on hit |
| `YAMA_SCORCH_COLUMNS_PER_TICK` | 60 | Columns scorched per tick, in radial order from the caster |
| `YAMA_TRAIL_RADIUS` | 8.0 | blocks — each follow-up disc as the Shikai flame is carried |
| `YAMA_BLOCK_SWEEP_BUDGET` | 2500 | Positions checked per tick by Bankai's 3D spherical extinguish sweep |
| `YAMA_EXTINGUISH_CAMPFIRES` | `true` | Whether Bankai's fire-absorbing sweep extinguishes lit campfires and fireplaces |
| `YAMA_EXTINGUISH_TORCHES` | `true` | Whether Bankai's fire-absorbing sweep snuffs out torches |
| `YAMA_BANKAI_INHALE_PARTICLES` | 200 | Flames drawn collapsing inward spherically when Bankai absorbs the fire |
| `YAMA_SCORCH_BLOCK_CAP` | 12000 | Total blocks touched per activation |
| `YAMA_EVAPORATE_DEPTH` | 4 | Blocks below the surface water, ice and snow are boiled out |
| `YAMA_FIRE_CHANCE` | 0.55 | frac of scorched columns that catch light |
| `YAMA_SHIKAI_RING_PARTICLES` | 120 | Particles along Shikai perimeter ring |
| `YAMA_RING_PARTICLE_Y_OFFSET` | 0.20 | Height offset above caster for flame ring (blocks) |
| `YAMA_BANKAI_CONE_SP_COST` | 30.0 | SP cost to unleash Bankai raven conical destruction on swing miss |
| `YAMA_BANKAI_CONE_RANGE_BASE` | 15.0 | Base reach of Bankai conical destruction (blocks) |
| `YAMA_BANKAI_CONE_RANGE_PER_SL` | 1.25 | Additional reach per Soul Level (blocks/level) |
| `YAMA_BANKAI_CONE_ANGLE_DEG` | 35.0 | Half-angle of Bankai destruction cone (degrees) |
| `YAMA_BANKAI_CONE_DMG_BASE` | 27.0 | Base damage of Bankai conical destruction blast (HP) |
| `YAMA_BANKAI_CONE_DMG_PER_SL` | 2.0 | Additional damage per Soul Level (HP/level) |
| `YAMA_BANKAI_CONE_BLOCK_CAP` | 8000 | Hard cap on blocks obliterated by one raven conical destruction |
| `YAMA_BANKAI_CONE_BLOCKS_PER_TICK` | 400 | Tick-slice budget for Bankai conical destruction |
| `YAMA_BANKAI_SWING_COOLDOWN_TICKS` | 15 | Cooldown between Bankai raven swings (ticks) |
| `YAMA_BANKAI_CONE_CANCEL_ON_HIT` | `true` | Cone skips all block destruction if the blast strikes a living entity |
| `YAMA_BANKAI_CONE_BLOCK_RANGE_MULT` | 0.55 | Excavated reach as a fraction of the strike reach |
| `YAMA_BANKAI_CONE_BLOCK_ANGLE_DEG` | 20.0 | Half-angle of the excavated cone (strike cone stays 35°) |

### J.3 Sui-Feng · *PRD §6.3*

| Symbol | Default | Meaning |
|---|---|---|
| `SUI_MARK_TOLERANCE` | 0.35 | **blocks** on a player-sized body — second-strike kill window, scaled uniformly by target size |
| `SUI_SHIKAI_KILL_EXERTION` | 60.0 | seconds of exertion dumped on a landed Nigeki Kessatsu — **this is the cost of the kill** |
| `SUI_BANKAI_WINDUP_TICKS` | 60 | 3 s telegraph |
| `SUI_BANKAI_LETHAL_RADIUS` | 12.0 | blocks — full-damage core, no falloff |
| `SUI_BANKAI_FALLOFF_RADIUS` | 24.0 | blocks — outer edge |
| `SUI_BANKAI_DMG_INNER` | 60.0 | damage inside the core · **a mechanic** — bypasses armour, enchantments and Soul Level reductions |
| `SUI_BANKAI_DMG_OUTER` | 12.0 | damage at the outer edge · ordinary bleach damage, mitigable and Soul Level scaled |
| `SUI_MISSILE_SPEED` | 2.5 | blocks/tick — flight speed |
| `SUI_MISSILE_SUBSTEPS` | 5 | collision slices per tick |
| `SUI_MISSILE_RANGE` | 120.0 | blocks before it detonates in the air |
| `SUI_MISSILE_HIT_RADIUS` | 0.5 | blocks — hitbox inflation when testing what it struck |
| `SUI_BANKAI_SELF_DMG_PCT` | 0.25 | frac of *current* health, never lethal — cut from 0.50 on 2026-09-09 |
| `SUI_CRATER_RADIUS` | 18.0 | blocks — spherical excavation radius |
| `SUI_CRATER_BLOCK_CAP` | 28000 | hard cap on blocks changed (~24,400 sphere + outer rim shell) |
| `SUI_CRATER_BLOCKS_PER_TICK` | 400 | tick-slice budget |
| `SUI_MARK_PARTICLE_COLOR` | `0x050505` | packed RGB black for the butterfly mark particle |
| `SUI_MARK_PARTICLE_SCALE` | 0.80 | quad scale multiplier for mark particle |
| `SUI_BANKAI_RING_PARTICLES` | 100 | particles per expanding telegraph warning ring |
| `SUI_MARK_RAYCAST_REACH` | 5.0 | blocks — raycast reach for mark detection |

#### J.3.1 The core is a mechanic — fixed 2026-09-09

`BleachDamage`'s class note and PRD §2.4 both say Suì-Fēng's two-strike kill **and her Bankai's
inner radius** are mechanics rather than damage. Only the Shikai kill was ever built that way. The
detonation fired `SPIRIT_PRESSURE` for both rings, which is in the `bleach` tag and in none of the
`bypasses_*` tags — so the core ran the full vanilla mitigation pipeline:

| Stage | Damage |
|---|---|
| Raw core | 60.0 |
| After netherite armour (20 armour, 12 toughness) | 40.8 |
| After Protection IV ×4 (EPF 16) | 14.7 |
| After Soul Level reduction (≈0.78) | **≈11.5** |

Roughly a fifth of its value against the only targets it is ever aimed at, while an unarmoured mob
standing beside them took the whole 60 — and everyone is in Protection IV netherite, which was the
entire premise of Adil's item 6. Reported in play as *"does no player damage"*.

The core now fires `SPIRIT_MECHANIC_KILL` and the falloff keeps `SPIRIT_PRESSURE`. The split is
deliberate: the 12-block core is the thing you were supposed to not be standing in, and the outer
ring is a shove rather than a sentence.

**Parity, stated so it is not argued about later:** this puts the core in the same class as Nigeki
Kessatsu, so M — The Miracle must not save you from it either, exactly as the design spec's §8.2
already rules for the Shikai kill and D's dose kill.

### J.4 Rukia · *PRD §6.4*

| Symbol | Default | Meaning |
|---|---|---|
| `RUKIA_BANKAI_RADIUS` | 16.0 | blocks |
| `RUKIA_FREEZE_DMG_PER_SEC` | 2.0 | freeze tick damage |
| `RUKIA_FREEZE_SPEED_MULT` | -0.50 | movement speed multiplier while frozen |
| `RUKIA_FREEZE_EFFECT_COLOR` | `0x93C5FD` | effect icon color (packed RGB) |
| `RUKIA_FREEZE_DURATION_TICKS` | 60 | freeze effect refresh duration (3 s) |
| `RUKIA_VIGNETTE_COLOR` | `0x93C5FD` | frost vignette border color (packed RGB) |
| `RUKIA_VIGNETTE_ALPHA` | 0.25 | frost vignette maximum opacity |
| `RUKIA_VIGNETTE_DEPTH_PCT` | 0.20 | frost vignette depth as fraction of screen height |
| `RUKIA_SNOW_BLOCKS_PER_TICK` | 40 | snow-layer placement budget |
| `RUKIA_SNOW_MAX_LAYERS` | 4 | layer height cap |
| `RUKIA_SNOW_BLOCK_CAP` | 3000 | total blocks touched per activation |
| `RUKIA_SNOWFALL_PARTICLES` | 60 | particles/tick faking local weather |
| `RUKIA_SNOWFALL_HEIGHT` | 12.0 | height above caster for snowfall particle spawns (blocks) |
| `RUKIA_SNOWFALL_SPEED` | 0.20 | downward velocity of falling snowflake particles (blocks/t) |
| `RUKIA_SHIKAI_ONHIT_RADIUS` | 6.0 | blocks — localized burst on melee hit |
| `RUKIA_TRAIL_RADIUS` | 7.0 | blocks — each follow-up disc as the Bankai field is carried |
| `RUKIA_TRAIL_STEP` | 3.0 | blocks the caster must move before the next disc |
| `RUKIA_SHIKAI_ONHIT_COOLDOWN` | 20 | ticks between on-hit bursts |

### J.5 Shinji · *PRD §6.5*

| Symbol | Default | Meaning |
|---|---|---|
| `SHINJI_SHIKAI_ONHIT_DURATION` | 100 | ticks — single-target inversion on melee hit (5 s) |
| `SHINJI_SHIKAI_ONHIT_COOLDOWN` | 40 | ticks between on-hit applications |
| `SHINJI_RADIUS_BASE` | 16.0 | blocks |
| `SHINJI_RADIUS_PER_LEVEL` | 0.80 | blocks/level |
| `SHINJI_EFFECT_DURATION_TICKS` | 60 | 3 s refresh |
| `SHINJI_MOB_INVERT_CHANCE` | 0.70 | frac of rolls that invert |
| `SHINJI_MOB_REROLL_TICKS` | 10 | ticks between rolls |
| `SHINJI_BANKAI_RETARGET_TICKS` | 20 | ticks between re-pointing afflicted mobs at each other |
| `SHINJI_SHIKAI_AMPLIFIER` | 0 | Sakanade tier applied by Shikai — movement only |
| `SHINJI_BANKAI_AMPLIFIER` | 1 | Sakanade tier applied by Bankai — movement plus camera |
| `SAKANADE_CAMERA_FLIP_AMPLIFIER` | 1 | tier at and above which mouse look is inverted |
| `SHINJI_EFFECT_COLOR` | `0x7E22CE` | Sakanade effect icon colour (packed RGB purple) |
| `SHINJI_BANKAI_RING_PARTICLES` | 64 | particles per Bankai aura perimeter ring |

Flex is **no longer silent against peers.** A target the level gap does not out-rank still takes a short baseline weight, refreshed while they stand in the field and gone moments after they leave. Every tier above the first is still bought with the gap. **Countering cancels the baseline outright** — the answer to a peer's pressure is your own, not your level.

### J.6 Aizen · *Kyōka Suigetsu*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `AIZEN_SHIKAI_RADIUS` | 100.0 | blocks | Line-of-sight raycast radius on Shikai release |
| `AIZEN_ILLUSION_MOB_COUNT` | 3 | count | Unkillable illusion mobs spawned per victim |
| `AIZEN_ILLUSION_MOB_PER_SL` | 0.5 | count/level | Extra illusions per Soul Level above 1, off the caster's level |
| `AIZEN_ILLUSION_MOB_MAX` | 12 | count | Ceiling on the scaled illusion count per victim |
| `AIZEN_BANKAI_DMG_BONUS` | +0.35 | frac | Bleach melee bonus while in Bankai |

Kyōka Suigetsu's reversion has one non-numeric rule:

- **Aizen dying cancels the damage reflection.** Victims are still freed, returned to their start position and given the shatter, but the damage they accumulated under hypnosis is discarded. Reflecting it would make his death detonate — strongest at the moment he loses, and aimed at the players who just beat him. Manual revert and SP exhaustion still reflect in full.

### J.7 Tōsen · *Suzumushi & Enma Kōrogi*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `TOSEN_SHIKAI_RADIUS` | 26.0 | blocks | Shikai high-pitch chime and SP drain radius |
| `TOSEN_SHIKAI_SOUND_INTERVAL_TICKS` | 11 | ticks | Ticks between resonant chimes |
| `TOSEN_SHIKAI_SOUND_PITCH` | 2.0 | — | High pitch of the resonant chime |
| `TOSEN_SHIKAI_SP_DRAIN_PER_TICK` | 1.0 | SP/tick | SP drain rate (20 SP/s) applied to nearby players |
| `TOSEN_SHIKAI_NAUSEA_TICKS` | 90 | ticks | Nausea refreshed each tick inside the radius; 0 disables |
| `TOSEN_SHIKAI_DEPRIVE_TICKS` | 60 | ticks | Blindness + Freeze refresh of the second stage, for players and mobs alike |
| `TOSEN_SHIKAI_MOB_RESIST_TICKS` | 100 | ticks | Exposure a mob must accumulate before the chime breaks it, and the ceiling on that counter |
| `TOSEN_BANKAI_RADIUS` | 25.0 | blocks | Radius of Enma Kōrogi sensory deprivation dome |
| `TOSEN_BANKAI_MOB_BLIND_TICKS` | 40 | ticks | Blindness refreshed on mobs inside the dome |

Suzumushi's Shikai applies **Nausea** to anyone inside the radius from the first second. Blindness and Freeze remain strictly gated on the victim's pool reaching zero — the two stages stay separate.

**Both releases reach mobs and bosses, not only players.** Neither can use the mechanism its player half runs on, so each gets a translation rather than a copy:

- **Suzumushi stages off an exposure clock.** A mob has no pool to drain, so exposure accumulates one tick per tick inside the radius and drains one per tick outside it, breaking at `TOSEN_SHIKAI_MOB_RESIST_TICKS`. That default is not free-standing: at `TOSEN_SHIKAI_SP_DRAIN_PER_TICK` it is exactly how long the aura needs to empty a Soul Level 1 player's `SP_BASE_MAX`, so both halves of a mixed fight break at the same moment. Capping the counter at the same number is what bounds recovery — no length of exposure makes a mob take more than five seconds to shake off. Nausea is skipped for mobs deliberately: it is a camera effect with no server-side meaning, so it would be an icon and nothing else.
- **Enma Kōrogi blinds and severs instead of blacking out.** The dome is purely client-side for a player — the client stops drawing and stops hearing. A mob has no screen, so its senses come off in the AI: Blindness for the look, and a target lock plus revenge memory cleared **every tick** for the substance. Cleared every tick rather than on Shinji's interval because this denies targets rather than reassigning them, and a gap between sweeps is a gap in which the dome does not work.
- **Bosses are not filtered out.** The target severance works on anything carrying an AI goal, the Wither included. The status effects go through `addEffect`, so a mob vanilla declares immune to effects keeps only the severed aim — that is vanilla's call about that mob, and against a boss the severance is the half that matters anyway.
- **Both auras hit everything living except the caster**, hostile and passive alike, following the same call Shinji's Bankai aura makes in §J.5. A sound in the air does not ask what is standing in it.

Enma Kōrogi carries two costs and one deliberate leak, none of them numeric:

- **The caster cannot sprint** while the dome is up. Refused client-side in `canStartSprinting`, with the server clearing the flag each tick as backstop.
- **Hurt and death sounds still play** inside the dome. Everything else is muted, so a victim knows something is dying without knowing what, where, or whether it is them.
- **The blackout draws in front of the whole HUD** (hotbar, chat, SP bar included), not merely after it.

### J.8 Gin · *Shinsō & Kamishini no Yari*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `GIN_SHIKAI_RANGE` | 55.0 | blocks | Range of Shikai single piercing thrust |
| `GIN_SHIKAI_DMG` | 18.0 | HP | Damage dealt by Shikai thrust |
| `GIN_SHIKAI_COOLDOWN_TICKS` | 20 | ticks | Cooldown between Shikai thrusts |
| `GIN_SHIKAI_SP_COST` | 30.0 | SP | Charged on **every** swing, **on top of** `DRAIN_SHIKAI` — 3 thrusts from a full SL 1 pool, with no cheaper melee mode to fall back on. The swing is refused outright when it cannot be paid |
| `GIN_BANKAI_BEAM_RANGE` | 70.0 | blocks | Range of Bankai continuous beam |
| `GIN_BANKAI_BEAM_DMG` | 15.0 | HP | Damage dealt per slice of Bankai beam |
| `GIN_MIN_SWIPE_SPEED` | 6.0 | deg/t | Minimum mouse angular velocity to deal slice damage |
| `GIN_BANKAI_ENTITY_SLICE_COOLDOWN_TICKS` | 8 | ticks | Per-entity slice cooldown |
| `GIN_BANKAI_BEAM_PARTICLE_STEP` | 0.6 | blocks | Beam particle spacing; smaller is a more continuous stream |

Kamishini no Yari's beam is **not rendered for Gin himself**. It leaves the eye along the exact look vector, so drawn locally it becomes an opaque wall over the crosshair. Every other player within 160 blocks is sent it per-player.

---

### J.9 Shunsui · *Katen Kyōkotsu & Karamatsu Shinjū*

Arrived with the `origin/main` merge and was never written down here. The constants existed and the
section did not, which is how `SHUNSUI_BANKAI_ZONE_TINT` sat unused for a whole release.

#### J.9.1 Shikai — Katen Kyōkotsu

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `SHUNSUI_SHIKAI_CLEAVE_ARC` | 55.0 | degrees | Arc the second blade sweeps for secondary targets |
| `SHUNSUI_SHIKAI_CLEAVE_PCT` | 0.40 | frac | Fraction of primary damage dealt to arc targets |
| `SHUNSUI_SHIKAI_DUAL_HIT_CHANCE` | 0.45 | frac | Chance the second blade also strikes the primary target |
| `SHUNSUI_IROONI_CAST_SP_COST` | 15.0 | SP | Cost of declaring a colour rule |
| `SHUNSUI_IROONI_CAST_RADIUS` | 20.0 | blocks | Who the rule is declared over |
| `SHUNSUI_IROONI_CAST_COOLDOWN_TICKS` | 60 | ticks | Between declarations |
| `SHUNSUI_IROONI_PUNISH_DURATION_TICKS` | 80 | ticks | How long breaking a rule costs |
| `SHUNSUI_IROONI_PUNISH_WEAKNESS_AMP` | 0 | amp | Weakness applied on a break |
| `SHUNSUI_IROONI_PUNISH_SLOWNESS_AMP` | 0 | amp | Slowness applied on a break |
| `SHUNSUI_IROONI_BREAK_PARTICLE_COLOR` | `0xF9A8D4` | RGB | The break burst |
| `SHUNSUI_IDLE_REGEN_BONUS` | 0.8 | SP/s | Flat regen bonus, base state only, and only with `regenPauseTicks == 0` |

**The rule assigned to each enemy is never revealed — only the consequence.** That is a design
decision in `KatenShikaiManager`'s own javadoc, not an oversight, and reversing it is a design change
rather than a bug fix.

#### J.9.2 Bankai — Karamatsu Shinjū

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `SHUNSUI_BANKAI_ZONE_RADIUS_BASE` | 18.0 | blocks | Zone radius at SL 1 |
| `SHUNSUI_BANKAI_ZONE_RADIUS_PER_SL` | 0.5 | blocks/level | Added per level above 1 — **67.5 blocks at SL 100**, see §E.2 |
| `SHUNSUI_BANKAI_CONTAIN_MARGIN` | 0.6 | blocks | How far back inside the shell a crosser is put |
| `SHUNSUI_BANKAI_RELEASE_FACTOR` | 5.0 | × radius | Past this, a participant is released rather than dragged back |
| `SHUNSUI_ACT1_EXCHANGE_THRESHOLD` | 12 | exchanges | Act 1 → Act 2 |
| `SHUNSUI_ACT1_DEATH_FLOOR_HP` | 1.0 | HP | The mirror alone can never drop anyone below this |
| `SHUNSUI_ACT2_DURATION_TICKS` | 300 | ticks | Act 2 → Act 3 |
| `SHUNSUI_ACT2_BLEED_DPS` | 1.5 | HP/s | Starting bleed |
| `SHUNSUI_ACT2_BLEED_GROWTH` | 1.25 | × | Applied to the bleed every 5 s |
| `SHUNSUI_ACT2_DAMAGE_INTERVAL` | 20 | ticks | One hit per interval, carrying the whole interval's damage |
| `SHUNSUI_ACT3_SP_DRAIN_PER_SEC` | 3.5 | SP/s | Drains both sides; **replaces** `DRAIN_BANKAI` for Shunsui |
| `SHUNSUI_ACT3_SLOWNESS_AMP` | 0 | amp | Applied to targets only — Shunsui has home-turf immunity |
| `SHUNSUI_ACT3_LOSS_SP_THRESHOLD` | 0.10 | frac | Fall to this and you have lost the act |
| `SHUNSUI_FINAL_ACT_CHARGE_TICKS` | 40 | ticks | The thread's draw |
| `SHUNSUI_FINAL_ACT_DMG` | 200.0 | HP | Tagged `SPIRIT_MECHANIC_KILL` — not reduced by §E |
| `SHUNSUI_SECOND_BLADE_DMG_BONUS` | 0.20 | frac | Shunsui's melee bonus from Act 2 onward |

#### J.9.3 The shell and the gloom

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `SHUNSUI_ZONE_RINGS` | 10 | rings | Latitude rings from ground to crown |
| `SHUNSUI_ZONE_POINT_SPACING` | 0.8 | blocks | Arc-length spacing between points on a ring |
| `SHUNSUI_ZONE_DRAW_STRIDE` | 3 | — | One point in every stride per pass, phase advancing |
| `SHUNSUI_ZONE_PARTICLE_INTERVAL` | 3 | ticks | Between drawing passes |
| `SHUNSUI_ZONE_SKIRT_DEPTH` | 12.0 | blocks | How far the skirt chases the ground below the rim |
| `SHUNSUI_ZONE_PARTICLE_SCALE` | 1.8 | — | Dust scale |
| `SHUNSUI_ZONE_TINT_MAX_ALPHA` | 70 | 0–255 | Peak alpha of the gloom |
| `SHUNSUI_ZONE_TINT_FADE_PER_SECOND` | 2.5 | frac/s | Fade in and out |
| `SHUNSUI_TINT_PRE_ACT` | `0x301828` | RGB | Bruise |
| `SHUNSUI_TINT_ACT_1` | `0x4A1E30` | RGB | Blood — shared wounds |
| `SHUNSUI_TINT_ACT_2` | `0x201020` | RGB | Rot |
| `SHUNSUI_TINT_ACT_3` | `0x3040A0` | RGB | The water of Dangyo no Fuchi |
| `SHUNSUI_TINT_FINAL_ACT` | `0xFFFFFF` | RGB | The thread |

The zone is a **hemisphere standing on the anchor**, and the volume that contains is the same shape
as the shell that is drawn — including below the anchor, which counts as inside. Three things this
replaced, each of which had shipped:

- **Containment did nothing to players.** It cancelled outward velocity with `setDeltaMovement`,
  which a client simply overwrites with its own next position packet. Participants are now moved,
  and a `ServerPlayer` is moved through `player.connection.teleport`.
- **The shell was 48 randomly scattered `PressureParticle` points on a full sphere, every tick.**
  That particle rises, so the shape smeared; half the sphere was underground; and a scatter that
  sparse is noise rather than a surface. It is now stride-drawn dust rings with a double-density
  ground ring and a terrain skirt, which is what makes a 67-block zone affordable at all.
- **The gloom was drawn in front of the HUD.** `KaromatsuOverlay` filled the viewport at up to
  `0x60` after a `pose().translate(0, 0, 500)`, past `HUD_Z_DEPTH = 0` — which is the whole of
  "players affected by the Bankai cannot see their SP". The bar was drawn and then painted over.

Membership is the **server's alone**. `KaromatsuSyncPayload` is now a per-participant edge carrying
only the act, not a world broadcast carrying the zone's centre and radius; a client that knows the
geometry can stand one block outside it, and a client that decides its own membership can disagree
with the server about who is caged.

---

## K. Networking and presentation

| Symbol | Default | Meaning |
|---|---|---|
| `SYNC_KEEPALIVE_TICKS` | 20 | S2C stat sync heartbeat |
| `HUD_COLOR_BASE` | `0x3B82F6` | blue |
| `HUD_COLOR_SHIKAI` | `0xF97316` | orange |
| `HUD_COLOR_BANKAI` | `0xDC2626` | red |
| `HUD_BOTTOM_OFFSET` | 28 | SP bar height above the bottom screen edge, scaled px — lower placement beneath hearts & hunger |
| `HUD_BAR_GAP` | 1 | Gap between the SP bar and the XP bar, px |
| `HUD_ROW_LIFT` | 0 | How far vanilla's health/food/armour/air rows are lifted, px; 0 = one full vanilla row pitch (10) |
| `HUD_TEXT_GAP` | 1 | Gap between the SP figure and the top of the bar, px |
| `HUD_LEVEL_TEXT_GAP` | 4 | Gap between the Soul Level figure and the left end of the bar, px |
| `HUD_Z_DEPTH` | 0.0 | Depth the bar is lifted to — kept at 0 to respect 2D rendering and avoid clipping action messages |
| `HUD_COLOR_LEVEL_TEXT` | `0xFFFFFF` | Soul Level figure colour |
| `HUD_GATE_WIDTH_PX` | 1 | Width of a gate notch, px |
| `HUD_GATE_OVERHANG_PX` | 1 | How far a notch overhangs the bar above and below, px |
| `HUD_COLOR_GATE_OPEN` | `0xFFFFFF` | Notch colour once the gate is met |
| `HUD_COLOR_GATE_SHUT` | `0x64748B` | Notch colour while the gate is short |
| `HUD_GATE_SHUT_ALPHA` | `0x9A` | Notch alpha while the gate is short |
| `HUD_SPX_GAIN_DURATION_MILLIS` | 1400.0 | How long one `+N SPX` figure lives — the full rise and fade |
| `HUD_SPX_GAIN_RISE_PX` | 13 | How far a figure rises over that life, scaled px |
| `HUD_SPX_GAIN_HOLD` | 0.45 | Fraction of the life at full opacity before the fade starts |
| `HUD_SPX_GAIN_MERGE_MILLIS` | 350.0 | Awards landing within this of the last merge into it instead of stacking |
| `HUD_SPX_GAIN_STACK_PX` | 10 | Vertical pitch between two figures on screen at once, scaled px |
| `HUD_COLOR_SPX_GAIN` | `0x60A5FA` | SPX gain figure colour |
| `STATS_COLOR_PANEL` | `0x0F1420` | Stats screen panel background |
| `STATS_COLOR_ROW` | `0x1E2635` | Stats screen inset row background |
| `STATS_COLOR_ACCENT` | `0x3B82F6` | Stats screen border and progress fill |
| `REIATSU_VIGNETTE_ALPHA` | `[0.10, 0.22, 0.36, 0.55]` | Reiatsu overlay alpha by amplifier |
| `REIATSU_VIGNETTE_DEPTH_PCT` | 0.22 | Overlay band depth as a fraction of screen height |
| `REIATSU_VIGNETTE_COLOR` | `0x2E1065` | Reiatsu overlay colour |
| `REIATSU_SHAKE_AMPLITUDE_PX` | 4.0 | Peak band wobble, scaled px |
| `REIATSU_SHAKE_PERIOD_MILLIS` | 130.0 | Wobble period — a shudder, not a sway |
| `WSL_RECOMPUTE_DAYS` | 1 | MC days between WSL recomputes |
| `WSL_PLAYTIME_WINDOW_DAYS` | 7 | MC days of playtime that count toward WSL |
| `MOD_ENABLED` | `true` | Master on/off switch. Not a balance value — a diagnostic one |

**The bar carries gate notches at the Shikai and Bankai thresholds (§C).** A notch the fill has passed
is lit; one it has not is dim. "Can I go Bankai yet" should be answerable by looking at the bar rather
than by remembering a percentage that moves with every Soul Level — the pool is the resource and the
bar is the picture of it.

Both thresholds are **sent by the server**, for the reason the payload's other derived fields are:
they are read off `GATE_BANKAI_BASE` / `GATE_SHIKAI_BASE` / `GATE_REDUCTION_PER_LEVEL`, and tuning is
never synced. A client computing them itself would mark the bar in places a tuned server's own checks
disagreed with — a HUD confidently lying about the one thing it exists to report. A gate of `0` means
the player has no character, and nothing is drawn.

**The `+N SPX` popup is pushed by the server, not inferred by the client.** The obvious alternative —
watch the synced `spx` figure and show the difference — is wrong, and quietly so: SPX is a bank that
is **filled and spent in the same tick**, because a kill awards into it and `SoulLevel#levelUp`
immediately draws levels out of it. A kill worth 30 that buys a 40-point level syncs a bank that fell
by ten, so the difference would read `−10 SPX` on the single occasion the player most wants to be told
they gained something. Reconstructing the award from the level-up would need the client to know the
cost of every level crossed, and the curve lives in server-side tuning that is deliberately never
synced. So `SpxGainPayload` carries one int, sent only when there is an award to report.

**Both flank figures are clamped on screen.** The bar is vanilla's fixed 182 pixels and cannot shrink,
so below a GUI width of about 210 the flanks run out of room and a figure walks off the edge — which
is what was clipping the Soul Level at high GUI scales and in narrow windows. Clamping trades the gap
for legibility: the number closes on the bar's end and, at worst, touches it.

`MOD_ENABLED` lives here only because this file already gives it persistence, a config key and
`/bleach reload`. It is owned at runtime by `ModToggle`, which is what game code reads. Switched
off, the ticker does nothing, the dispatcher drops every keypress, and the HUD does not render —
the mod is meant to be indistinguishable from not being installed. Toggling writes the value back
to `tuning.json`, so it survives a restart; `/bleach reload` restores whatever the file says.

---

## N. Aura Sense · *universal, hold `C`*

A hold-to-channel reading taken with the eyes shut. The sensor's screen goes black and every soul in
reach is drawn on it as a coloured blob.

**It costs no SP.** The price is sight, and that is the whole of it — there is no drain, so there is
no pool state that can refuse or end the channel. The two things that end it are the key coming up
and the sense being taken away: death, spectator mode, or standing inside someone else's **Enma
Kōrogi** dome (§J.7), which strips every sense including this one. The dome's own caster keeps it.

**Reach belongs to the target, not the sensor.** `reach(SL) = AURA_RANGE_BASE +
AURA_RANGE_PER_LEVEL × (SL − 1)` — 80 blocks at SL 1, 349.8 at the SL 20 cap. Anything without a
Soul Level (every mob) carries `AURA_RANGE_UNRANKED` and no further.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `AURA_RANGE_BASE` | 80.0 | blocks | Reach of an SL 1 aura |
| `AURA_RANGE_PER_LEVEL` | 14.2 | blocks/level | Reach growth · SL 20 → 349.8 |
| `AURA_RANGE_UNRANKED` | 64.0 | blocks | Reach of anything with no Soul Level |
| `AURA_SYNC_INTERVAL_TICKS` | 2 | ticks | Ticks between sweeps — one scan and one packet each |
| `AURA_MAX_ENTRIES` | 48 | count | Auras per packet. Over the cap, the nearest are kept |
| `AURA_COLOR_PLAYER` | `0x8AE6FF` | RGB | Every player's aura. The sense reads souls, not faces |
| `AURA_MOB_SATURATION` | 0.80 | 0..1 | Saturation of the hue derived from a mob's type id |
| `AURA_MOB_VALUE` | 1.0 | 0..1 | Value of that hue |
| `AURA_TELL_INTERVAL_TICKS` | 6 | ticks | Pressure mote at the sensor's head — the outward tell |

Mob colour is **derived, not listed**: `hue = hash(entity type id) mod 360`, at the fixed saturation
and value above. Same type, same colour, forever and on both sides; different type, different
colour; no table to maintain against mobs this mod has never heard of.

### N.2 Burn · *what a soul is doing, not how big it is*

Soul Level says how big a soul is. **Burn** says how hard it is pushing right now, and the two
**multiply**: `size = (AURA_SIZE_BASE + AURA_SIZE_PER_LEVEL × SL) × burn`. Multiplying is what keeps
the reading proportional to Soul Level all the way up — the same release reads bigger on a bigger
soul, and a Bankai at the cap is a different object from a Bankai at SL 3.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `AURA_BURN_SHIKAI` | 1.8 | × | Multiplier while in Shikai |
| `AURA_BURN_BANKAI` | 3.6 | × | Multiplier while in Bankai |
| `AURA_BURN_FLEX` | 2.1 | × | Further multiplier while exerting Spiritual Flex (§H) |

State and Flex compound, so the ceiling is `3.6 × 2.1 = 7.56` — a released Bankai spending everything
it has. Anything without a Soul Level burns at a flat ×1; a mob has no state to release and no pool
to exert.

**Reach is deliberately not multiplied.** Burn changes how loud a soul is, not how far the room is.
Letting it widen reach as well would turn every release into a map-wide ping and make the reach table
above meaningless.

### N.1 Aura Sense presentation

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `AURA_EYELID_CLOSE_MILLIS` | 260.0 | ms | Time the lid takes to fall |
| `AURA_EYELID_OPEN_MILLIS` | 170.0 | ms | Time the lid takes to lift — faster, as it is |
| `AURA_VISION_THRESHOLD` | 0.86 | fraction | How far the lid must fall before any aura is drawn |
| `AURA_SIZE_BASE` | 1.6 | world units | Aura radius before Soul Level — the floor for a player at SL 1 |
| `AURA_SIZE_PER_LEVEL` | 0.32 | world units/level | Added radius per Soul Level |
| `AURA_MOB_SIZE_BASE` | 0.55 | world units | Aura radius floor for any creature |
| `AURA_MOB_SIZE_PER_BLOCK` | 1.35 | world units/block | Added radius per block of measured body size |
| `AURA_MOB_SIZE_CAP` | 5.0 | blocks | Ceiling on the measured size fed into the above |
| `AURA_MIN_RADIUS_PX` | 2.5 | scaled px | Floor on the drawn radius — a far aura is a spark, never nothing |
| `AURA_MAX_RADIUS_PX` | 110.0 | scaled px | Ceiling **at rest**, so a neighbour cannot white out the screen |
| `AURA_MAX_BURN_RADIUS_PX` | 430.0 | scaled px | Ceiling once burn is applied — a released Bankai on top of you is meant to fill the view |
| `AURA_ANCHOR_DROP_BLOCKS` | 0.5 | blocks | How far below a soul's centre of mass its fire is anchored |
| `AURA_CORE_ALPHA` | 0.85 | 0..1 | Alpha at the centre. The rim always fades to zero |
| `AURA_SEGMENTS` | 20 | count | Rim segments per ellipse |
| `AURA_SMOOTHING_PER_SECOND` | 0.9995 | 0..1 | Fraction of the gap to the newest reported position closed per second |
| `AURA_FADE_SECONDS` | 0.35 | s | How long an aura keeps being drawn, fading, after it leaves the packet |
| `AURA_SNAP_DISTANCE` | 8.0 | blocks | Jump beyond which an aura is snapped instead of smoothed |

**Apparent size is the projection's job, not a formula's.** `AURA_SIZE_*` is a radius in world
units; the perspective divide is what makes it shrink with distance. So "inversely proportional to
distance, proportional to Soul Level" falls out of drawing the blob where it actually is, and the
two pixel clamps exist only to stop the far end rounding to zero and the near end filling the
screen. Burn (§N.2) scales that world radius before the divide, so it stays proportional to both.

**The anchor sits below centre of mass, because a fire rises from it.** A blob centred on the body
reads as centred; a fire does not, so the point that was right for a disc put the flames above the
soul with the body sitting in the gap underneath. `AURA_ANCHOR_DROP_BLOCKS` sets the base low on the
body, which is what makes the soul look like the thing that is burning.

**The eyelid finishes closing at `AURA_VISION_THRESHOLD`, not at the end of its travel.** Drawn to its
raw progress it did not: vision opens at 0.86 of the animation, so for the last fraction of the close
there were souls burning over a strip of live world at the bottom of the screen — the eye visibly
still open under a sense meant to have replaced it, which read as the picture being cut in half.
Mapping the wipe onto the threshold leaves the animation's character untouched and reaches full black
exactly as the first aura can appear; the remainder is the hold while vision resolves. The invariant
that no aura is ever drawn over an unblacked pixel is then structural, rather than two constants
happening to agree.

**A creature is sized by its body, because it has no soul to be sized by.** The measure is the cube
root of its bounding box volume — an effective diameter, so a wide flat spider and a tall thin breeze
both land mid-scale rather than one of them being scored on the single dimension it happens to be
large in. Cube-rooting compresses the range honestly: an ender dragon is two thousand chickens by
volume and about twenty-six by this measure, which is a number a radius can be built from.

| Creature | Measure | Aura radius |
|---|---|---|
| Silverfish | 0.36 | 1.04 |
| Chicken | 0.48 | 1.20 |
| Bee | 0.67 | 1.45 |
| Zombie, skeleton | 0.89 | 1.75 |
| Pig, breeze | 0.90 | 1.77 |
| Cow, sheep, enderman | 1.04 | 1.96 |
| Spider | 1.21 | 2.18 |
| Warden | 1.33 | 2.35 |
| Iron golem | 1.74 | 2.90 |
| Ravager | 2.03 | 3.29 |
| Ghast | 4.00 | 5.95 |
| Ender dragon | 12.7 → capped 5.0 | 7.30 |

For scale, a player runs **1.92 at Soul Level 1 to 8.00 at Soul Level 20**. So a bee sits under the
weakest shinigami and the largest creature alive sits just under the strongest — which is the line
`AURA_MOB_SIZE_CAP` exists to hold. Physical bulk is not spiritual weight, however much of it there
is, and a sense that let a ghast outshine a Bankai would be reporting the wrong thing.

The measure is read off the **live** bounding box rather than the entity type, so a baby zombie reads
smaller than an adult and a size-4 magma cube reads bigger than a size-1 — no table to maintain, and
correct for modded mobs the sense has never heard of. It rides the wire as one byte in eighths of a
block; a float would be three bytes an entry for precision nothing downstream can use.

**Reach is not affected.** A creature is felt at `AURA_RANGE_UNRANKED` whatever its size, for the same
reason burn does not widen reach (§N.2): size says how loud, not how far. A ghast is a bonfire inside
64 blocks and nothing at all outside them, so "a distant aura is always a person" still holds.

### N.3 Aura Sense flame · *the particle fire*

**Fire is not a shape, it is a population.** Every attempt to *draw* a flame here — a teardrop, then a
cluster of tapering chains — read as a glowing decal, because the look of fire lives in the history of
each particle rather than in the outline. So a reading's drawn radius is not a shape to fill; it is
the size of a live particle system that persists between frames. Particles are born hot and white at
the base of a soul, rise, cool through the aura's own colour, tear apart at the top and die. The
taper, the flicker, the wisps coming off the tip are consequences of that, not things drawn on
purpose.

The **colour ramp** does most of the visual work. A flame drawn in one colour reads as a blob no
matter how good the motion is, because real fire is a temperature gradient before it is anything else.
Each particle walks white → the aura colour → a deep cooled version of it over its own life. The white
phase runs to 5% of that life and no further: blending is additive, so a wide white band stacks into a
featureless white pill, while the same ramp held brief reads as a hot core.

The simulation is **normalised to the reading's own radius**, so one set of numbers describes a
bonfire at ten blocks and a spark at three hundred alike, and a soul swelling into Bankai grows its
fire smoothly instead of teleporting every particle in it. Only the spawn rate looks at the drawn
pixel size — which is what fixed distant souls rendering as plain round dots. **There is no threshold
below which a reading becomes a simpler shape.** Any such threshold produces dots by definition; the
same fire simply runs sparser.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `AURA_PARTICLE_RATE` | 620.0 | /s | Particles per second before burn, radius and gust scaling |
| `AURA_PARTICLE_LIFE` | 0.95 | s | Mean lifetime; each varies around it |
| `AURA_PARTICLE_BUOYANCY` | 13.0 | radii/s² | Upward acceleration. **Fades with age**, so the top stalls and breaks up |
| `AURA_PARTICLE_TURBULENCE` | 4.4 | — | Noise strength. **Ramped with age** — applied flat it lays the whole fire down |
| `AURA_PARTICLE_SWIRL` | 2.4 | — | Rotation about the axis; without it turbulence reads as jitter, not motion |
| `AURA_PARTICLE_DRAG` | 0.45 | /s | Velocity lost per second |
| `AURA_PARTICLE_TAPER` | 0.95 | — | Pull back toward the axis, growing with age — this is what gives a flame its point |
| `AURA_PARTICLE_GROW` | 0.7 | fraction | Expansion over a particle's life — cooling gas |
| `AURA_PARTICLE_DISC` | 1.1 | × radius | Radius of the spawn volume |
| `AURA_PARTICLE_DOME` | 0.80 | 0..1 | 0 a flat disc, 1 a half-sphere |
| `AURA_PARTICLE_BLOOM` | 0.60 | — | Outward speed off the dome |
| `AURA_PARTICLE_OPACITY` | 0.30 | 0..1 | Alpha of one particle at its brightest |
| `AURA_PARTICLE_GRAIN` | 0.26 | × radius | Particle size — small and many, never big and few |
| `AURA_PARTICLE_STRETCH` | 1.75 | — | Stretch along the particle's own velocity |
| `AURA_PARTICLE_GUST` | 0.65 | 0..1 | Surge depth — a constant burn reads as a machine |
| `AURA_PARTICLE_CHURN` | 1.9 | × | How fast the turbulence field boils |
| `AURA_PARTICLE_SEGMENTS` | 5 | count | Fan segments per particle |
| `AURA_PARTICLE_MIN_SIZE_PX` | 0.35 | scaled px | Below this a particle is skipped, not emitted sub-pixel |
| `AURA_PARTICLE_MAX_PER_SOUL` | 260 | count | Hard cap on live particles for one soul |
| `AURA_PARTICLE_BUDGET` | 4200 | count | Live particles across every soul on screen |
| `AURA_EMBER_RADIUS_PX` | 2.0 | scaled px | Below this a reading is too small to bother hazing |
| `AURA_HAZE_ALPHA` | 0.12 | 0..1 | Ambient haze behind the strongest reading on screen |
| `AURA_HAZE_SCALE` | 3.0 | × radius | Haze size |

**`BLOOM` against `BUOYANCY` is the whole shape control.** Bloom wins and the reading is a ball of
fire; buoyancy wins and it is a jet. `DOME` sets what they act on: a flat spawn disc collapses to a
line the moment the camera is level with it — a column standing on a plate — while the dome wraps the
soul so the fire is round from every angle. `STRETCH` is the single thing that turns a column of round
dots into fire: fast particles become streaks leaning along their own velocity, slow ones at the top
stay puffs. Area is held constant across the stretch (widen by *s*, thin by 1/√*s*) so it does not
also brighten.

Particles live in a **world-aligned** local frame, so the fire has real volume: turning your head
orbits it and looking down shows you the top of it. Everything is emitted into **one batched draw
call** with additive blending, which is why no depth sorting is needed at any range.

Crowding is handled by a **proportional throttle**, not a cap: exceeding `AURA_PARTICLE_BUDGET` thins
every fire on screen in step, so a packed room reads as a dimmer, sparser version of the same picture
rather than having some readings vanish because of what other readings were doing. The throttle steers
off last frame's count, which makes it a feedback loop that settles instead of a hard limit that
oscillates.

These constants were settled in `tools/flame-prototype.html` — the same maths in a canvas with a
slider per value. **Retune there before touching them here.**

---

## O. Hover · *universal, hold `Q`*

Hold the key while airborne and you stop falling. WASD still moves you, but **along the look vector
rather than the ground plane** — forward is wherever the crosshair points, so looking up and holding
forward climbs and looking down dives. There is deliberately no ascend or descend key: the mouse is
the vertical control, which is what keeps the whole ability on one binding.

It exists because §G leaves you thirty blocks up with Slow Falling and forty ticks of waiting. This
is the answer to that, and the reason it is a drain rather than an activation cost: **the pool is
what decides how long you get to stay up there.** `drain(SL) = HOVER_DRAIN_BASE −
HOVER_DRAIN_PER_LEVEL × (SL − 1)` — 4.0 SP/s at SL 1, 1.15 at the SL 20 cap. A full SL 1 pool with
nothing else spending it is 25 seconds of air; the same pool in Shikai is 18, since the drains add.

Four things end it: the key comes up, the pool cannot cover a tick, the player touches the ground,
or something takes their movement away (death, spectator, a vehicle, creative flight). It cannot be
*started* on the ground at all — a hover that could would be a jump nobody pressed.

**The key is intent, not a demand.** A press that lands while the player is still standing is
remembered rather than spent, and the channel opens on the first tick they are actually in the air —
so holding the key as you run off a ledge catches you, which is how anyone who has fallen once will
use it. A landing does the same thing in reverse: the channel closes, the held key does not, and a
jump out of the same hold picks straight back up. Running dry is the one ending that takes the intent
with it, because a channel that restarted on the first tick of regen would stutter the player down
the sky a metre at a time.

**Fall distance is cleared every tick of the hold and not one tick longer.** Hovering costs SP so
that a landing does not cost health; letting go and dropping the last twenty blocks anyway still
hurts exactly as much as it always did.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `HOVER_DRAIN_BASE` | 4.0 | SP/s | Drain at SL 1 |
| `HOVER_DRAIN_PER_LEVEL` | 0.15 | SP/s per level | Drain reduction · SL 20 → 1.15/s |
| `HOVER_SPEED` | 0.22 | blocks/tick | Top speed. Under a sprint (0.28) on purpose |
| `HOVER_RESPONSE` | 0.45 | 0..1 | Fraction of the gap to the target velocity closed per tick |
| `HOVER_REST_EPSILON` | 0.002 | blocks/tick | Velocity below which the hover snaps to a dead stop |
| `HOVER_PARTICLE_INTERVAL_TICKS` | 4 | ticks | Ticks between the spill of pressure under the player |
| `HOVER_PARTICLE_COUNT` | 3 | count | Particles per burst |
| `HOVER_PARTICLE_SCALE` | 0.5 | scale | Particle size |
| `HOVER_PARTICLE_SPREAD` | 0.35 | blocks | Scatter around the feet |
| `HOVER_PARTICLE_FALL` | 0.35 | blocks/tick | Downward velocity — the pressure holding them up |

**`HOVER_RESPONSE` is the whole feel of the ability.** At 1.0 the player is a cursor: instant starts,
dead stops, no weight. At 0.1 they are a balloon that drifts past everything they aim at. The
default settles a direction change in about five ticks.

**Where the movement happens is a balance fact, not just an implementation one.** Minecraft's player
movement is client-authoritative, so the motion is computed on the client
(`mixin/client/LocalPlayerHoverMixin`) and the server owns the permission and the bill
(`ability/common/Hover`). The client hovers only while the last sync packet says the channel is
live, so a pool that runs dry drops the player within a round trip; it cannot hold itself up.

The server also sets `NoGravity` on a hovering player, which is not physics — vanilla kicks anyone
airborne for eighty ticks with *"Flying is not enabled on this server"*, and skips that check
entirely for an entity whose gravity is zero. That flag is the exemption, and it is cleared on every
path out of the channel.

---

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
| `REISHI_MULT_ABSOLUTE_FLOOR` | 0.01 | × | Absolute floor the final multiplier can never drop below |

The two weights are a share of the span between floor and ceiling and should sum to 1.0. They are
separate keys rather than one because sky *access* and sky *light* differ at night: standing outside
at midnight still beats standing in a cave, which is the distinction a Quincy should feel.

### P.1 Reishi Arrow · *design §4.3*

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `REISHI_ARROW_PARTICLE_SCALE` | 0.5 | × | Quad size of the in-flight pressure-particle trail |
| `REISHI_ARROW_WIDTH` | 0.5 | blocks | Hitbox width. Vanilla arrow's own value, kept as the default |
| `REISHI_ARROW_HEIGHT` | 0.5 | blocks | Hitbox height. Vanilla arrow's own value, kept as the default |
| `REISHI_ARROW_TRACKING_RANGE` | 4 | chunks | How far a client must be to have the arrow sent to it at all |
| `REISHI_ARROW_UPDATE_INTERVAL` | 20 | ticks | How often tracking clients get a position/velocity resync |

Vanilla's own `EntityType.ARROW` uses these same four defaults; they are kept as *defaults*, not
hardcoded, because this mod's arrow is not vanilla's — a fast, long-range Quincy shot is exactly
the kind of thing `REISHI_ARROW_TRACKING_RANGE` in particular may need to raise past vanilla's 4
chunks (~64 blocks) once it's fired from further away than a bow ever draws.

**These four are the only constants in §P.1 that `/bleach reload` cannot move.** `EntityType.Builder`
bakes them into the built `EntityType` exactly once, when `BleachEntities` registers it at startup
— the same restart caveat §I.1 records for the zanpakutō's attack attributes. Changing any of the
four needs a restart; `REISHI_ARROW_PARTICLE_SCALE` above is read fresh every particle spawn and
is not affected.

### P.2 Heilig Bogen · *design §4.3*

The Heilig Bogen is the structural twin of the zanpakutō (§I.1): single stack, unbreakable,
undroppable, kept on death, restored on respawn. It uses vanilla bow draw semantics rather than a
release, so its numbers split the same way the zanpakutō's do — a deliberately weak melee stat and
the real power in what it fires.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `BOW_MELEE_DAMAGE` | 1 | — | Melee damage of the bow used as a club. Deliberately far under the zanpakutō's 3 |
| `BOW_MELEE_SPEED` | −2.8 | — | Melee attack speed modifier for the bow |
| `BOW_SHOT_SP_COST` | 6.0 | SP | Charged per shot, whatever the draw |
| `BOW_FULL_DRAW_TICKS` | 20 | ticks | Draw time for a full-power shot |
| `BOW_ARROW_DAMAGE` | 7.0 | hp | Arrow damage at a full draw, before Soul Level scaling |
| `BOW_ARROW_VELOCITY` | 3.0 | blocks/tick | Arrow launch velocity at a full draw |
| `BOW_MIN_DRAW` | 0.15 | frac | Minimum draw fraction below which the shot is refused outright |

**`BOW_MELEE_DAMAGE` and `BOW_MELEE_SPEED` are baked into the item's default attribute modifiers at
registration** — the same restart caveat §I.1 records for the zanpakutō's attack attributes.
Changing either needs a restart; the other five are read fresh on every draw and shot and
`/bleach reload` moves them immediately.

### P.3 Vollständig · *design §5.1*

Vollständig is release 2 for **every** Schrift, so its package lives on the shared
`QuincyTransform` base rather than in any one letter. These numbers are therefore the Quincy
equivalent of a Bankai's stat block, and they are read fresh every tick — `/bleach reload` moves
all of them immediately.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `VOLL_SPEED` | 0.35 | frac | Movement speed bonus, `ADD_MULTIPLIED_TOTAL` |
| `VOLL_DMG` | 0.45 | frac | Melee and arrow damage bonus while in Vollständig |
| `VOLL_FS_RANGE_MULT` | 1.35 | × | Hirenkyaku (Flash Step) range multiplier, stacked on the kit's own |
| `VOLL_WING_FEATHERS` | 5 | count | Feathers per wing |
| `VOLL_WING_SEGMENTS` | 4 | count | Points drawn along each feather |
| `VOLL_WING_INTERVAL` | 2 | ticks | Gap between wing redraws |
| `VOLL_WING_OFFSET` | 0.45 | blocks | How far behind the shoulder line the fan starts |
| `VOLL_WING_RADIUS` | 1.1 | blocks | Length of the longest feather |
| `VOLL_WING_PARTICLE_SCALE` | 0.45 | × | Size of a single wing particle |
| `VOLL_WING_ZIGZAG` | 0.22 | blocks | Sideways kick per zigzag step, jagged-winged Schrifts only |
| `VOLL_WING_STILL_THRESHOLD` | 0.0016 | blocks² | Movement per tick above which the wings furl |
| `VOLL_AURA_PARTICLES` | 10 | count | Aura particles per tick |
| `VOLL_AURA_RADIUS` | 0.85 | blocks | Radius of the aura cylinder |
| `VOLL_AURA_HEIGHT` | 2.0 | blocks | Height of the aura cylinder |
| `VOLL_AURA_PARTICLE_SCALE` | 0.6 | × | Size of a single aura particle |
| `VOLL_BELL_VOLUME` | 1.0 | × | Volume of the bell struck on entry |
| `VOLL_BELL_PITCH` | 0.7 | × | Pitch of that bell; below 1 tolls rather than chimes |
| ~~`VOLL_WING_PARTICLES`~~ | 6 | count | **Dead.** Superseded by feathers × segments; kept only so an existing `tuning.json` still loads |

The wings are drawn in vanilla's **dust** particle, not the mod's own pressure needle. That is not a
style choice: `PressureParticle` accelerates upward across its 10–22 tick life, because its job
everywhere else is to be a column venting off a player. Any static shape drawn with it smears into a
vertical sprinkle within a few ticks — no redraw rate fixes a mark that leaves as soon as it lands.
Dust takes an arbitrary RGB tint, so the per-kit colour survives the swap.

Cost is `feathers × segments × 2` particles every `VOLL_WING_INTERVAL` ticks — 168 per 2 ticks at
defaults. Raise the interval before lowering the feather count if it ever needs trimming; the shape
degrades much faster than the refresh rate does.

**The wings only unfurl when the player is standing still.** Moving, they furl and the aura carries
the release on its own. That is a presentation decision first — it is how Vollständig reads on
screen — but it also means a Quincy running around costs the aura's 10 particles a tick rather than
the wings' 84.

The wing *silhouette* is per-Schrift, not shared: `QuincyTransform.wingJagged()` defaults to smooth
feathers, and a Schrift overrides it to get lightning instead. Colour comes from the kit's own
`particleColor` through `wingColour()`. Between them the four letters can be told apart at range
without any of them needing a model.

Entering Vollständig **tolls a bell**, which is the release's only audio cue and is broadcast rather
than sent to the releasing player alone.

`VOLL_FS_RANGE_MULT` multiplies the kit's `flashStepRangeMult()` rather than replacing it, so a
Schrift that is already fast stays proportionally fast in Vollständig. It reaches `FlashStep`
through `TransformAbility.flashStepRangeMult()`, a defaulted-to-identity hook, which is why no
Shinigami kit moves.

### P.4 Blut · *design §5.4*

A toggled stance on its own key, orthogonal to the release tiers: it stacks on top of one rather
than replacing it, and its drain **adds** to the release drain the way Flex's does. Vene and
Arterie are mutually exclusive by construction — no stance moves both multipliers.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `BLUT_VENE_REDUCTION` | 0.25 | frac | Damage taken reduction while Vene is up |
| `BLUT_VENE_SPEED_PENALTY` | −0.15 | frac | Movement speed penalty while Vene is up, `ADD_MULTIPLIED_TOTAL` |
| `BLUT_ARTERIE_BONUS` | 0.30 | frac | Bleach damage dealt bonus while Arterie is up |
| `BLUT_DRAIN_BASE` | 2.0 | SP/s | Drain at SL 1, additive with the release drain |
| `BLUT_DRAIN_PER_LEVEL` | 0.06 | SP/s | Drain reduction per Soul Level; floored at zero |
| `BLUT_COLOR_VENE` | `0x60A5FA` | RGB | SP bar border colour while Vene is up |
| `BLUT_COLOR_ARTERIE` | `0xDC2626` | RGB | SP bar border colour while Arterie is up |

`BLUT_VENE_REDUCTION` is applied as `max(0, 1 − r)` and `BLUT_ARTERIE_BONUS` as `max(0, 1 + b)`, so
no config value can turn incoming damage into healing or damage dealt into a heal. Vene is
deliberately **not** gated on the damage being bleach — hardened blood stops a skeleton's arrow as
well as a zanpakutō — while Arterie only moves bleach damage, which keeps "a bow is a bow at every
level" (§E) intact.

### P.5 Schrift T · The Thunderbolt · *design: `QUINCY_STATUS.md` §9*

The first Schrift, and the first kit of any race whose power fires off a **projectile** rather than
a swing. Both tiers are the same power at two intensities: tier 1 calls one bolt on the struck
target, Vollständig chains that bolt outward and shortens the clock. Nothing here is activated —
`BleachKeybinds` has no free key, so a tier is a stance and its power rides `onProjectileHit`.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `THUNDER_BOLT_DAMAGE` | 6.0 | HP | Bolt damage on the struck target, before Soul Level scaling |
| `THUNDER_COOLDOWN_TICKS` | 60 | ticks | Minimum gap between bolts in the Schrift tier |
| `THUNDER_VOLL_COOLDOWN_TICKS` | 20 | ticks | Minimum gap between bolts in Vollständig |
| `THUNDER_CHAIN_COUNT` | 3 | count | Further entities a Vollständig bolt chains to; 0 disables chaining |
| `THUNDER_CHAIN_RADIUS` | 5.0 | blocks | Radius searched around the struck target for chain links |
| `THUNDER_CHAIN_FALLOFF` | 0.5 | × | Damage retained by each successive chain link |
| `KIT_THUNDERBOLT_PARTICLE_COLOR` | `0xA5F3FC` | RGB | Electric cyan; the one hue unused by the eight Shinigami |

The bolt damage **stacks on the arrow's own** `BOW_ARROW_DAMAGE` (§P.2, 7.0), so a bolted full-draw
shot lands 13 before scaling — roughly a zanpakutō combo, at range, once every three seconds, for
the 6.0 SP the shot already costs. Vollständig's shorter clock is the real increase: three times the
bolt rate plus up to 3 chain links at 3.0 / 1.5 / 0.75.

Bolts are **visual-only** `LightningBolt` entities. That buys the flash, the thunderclap and the
dynamic lighting for free while suppressing every vanilla side effect, and damage is then applied by
hand as `SPIRIT_PRESSURE` — already in the `BLEACH` tag, so Soul Level scaling applies and these
numbers stay governed here rather than escaping into vanilla's flat 5. **The deliberate cost is that
mob conversions do not happen**: no charged creepers, no witches, no zombified piglins, and no fire.

`THUNDER_CHAIN_FALLOFF` is clamped at zero before exponentiation, so no config value can make a
chain link amplify rather than decay, or flip its sign.

### P.6 Schrift D · The Deathdealing · *design: `QUINCY_STATUS.md` §10*

Askin Nakk Le Vaar. The letter is about how much of a thing a body can take, so the mechanic is a
**dose**: every landed arrow leaves one, and every dose raises the damage that target takes from
*everything, from anyone*. Doses bleed off if nobody keeps applying them, which makes D a clock —
commit to a target and finish it while the stack is up.

**It is deliberately not an outright kill.** A guaranteed death threshold makes one kit a mandatory
pick and every fight against it un-fun. The stack is a vulnerability multiplier instead, which keeps
the identity — things die faster the longer you work them — without the delete button. That is also
what separates it in play from Suì-Fēng, whose two-strike kill *is* outright: hers is a position,
this is a countdown.

| Symbol | Default | Unit | Meaning |
|---|---|---|---|
| `DOSE_DAMAGE_PER` | 0.09 | frac | Damage-taken increase per dose |
| `DOSE_MAX` | 10 | count | Dose cap; also caps the multiplier at ×1.90 |
| `DOSE_DECAY_TICKS` | 60 | ticks | Time without a fresh dose before one bleeds off |
| `DOSE_PER_ARROW` | 1 | count | Doses per landed arrow in the Schrift tier |
| `DOSE_PER_ARROW_VOLL` | 2 | count | Doses per landed arrow in Vollständig |
| `DOME_RADIUS` | 7.0 | blocks | Radius of Gift Bad Sonnenschein |
| `DOME_DAMAGE` | 1.5 | HP | Bleach damage per damage tick inside the dome |
| `DOME_DAMAGE_INTERVAL` | 20 | ticks | Gap between damage-and-dose passes |
| `DOME_DOSES_PER_TICK` | 1 | count | Doses applied by each pass |
| `DOME_POISON_TICKS` | 40 | ticks | Duration of the cosmetic Poison effect |
| `DOME_PARTICLES` | 40 | count | Shell particles per draw pass |
| `DOME_PARTICLE_INTERVAL` | 4 | ticks | Gap between draw passes |
| `DOME_PARTICLE_SCALE` | 1.0 | × | Size of a single dome particle |
| `DOME_COLOR` | `0xA855F7` | RGB | Askin's purple |
| `KIT_DEATHDEALING_PARTICLE_COLOR` | `0xA855F7` | RGB | The same purple, for arrows and Flash Step |

**The dome does not follow the player.** It anchors where they released and stays there — walk out
and you leave it behind. Every other tier in this mod is a stance that travels with you; this one is
terrain you make and then have to fight around, which is the whole reason the letter plays
differently. It also means `onTierRevert` has real work to do, because nothing else in the world
holds a reference to a dome.

Vanilla's Poison effect is applied inside the dome as the **on-screen tell only**. Poison cannot
kill — it stops at half a heart — so it can never be the damage itself; `DOME_DAMAGE` is.

A dome whose owner logs out is dropped rather than left running. A permanent poison field nobody can
switch off is the worst available failure mode for this feature.

The dose multiplier is applied in `DamageScaling` **outside** the `ServerPlayer` block, because doses
land on mobs too and only players carry a `SpiritualData`. It is a property of the victim rather than
of whoever is hitting them, which is what makes D a setup power a whole team benefits from rather
than a personal damage buff.

---

## L. Where each constant is consumed

Kept current so a balance change never requires a codebase search.

| Group | Consumed by |
|---|---|
| A, B, C | `attachment/SpiritualData`, `SpiritualTicker` |
| D | `progression/SpxTable`, `SoulLevel`, `WorldSoulLevel` |
| E | `progression/SoulLevelCurve` (the curve), `progression/DamageScaling` (applies it), `SoulLevel` (health modifier, level-up sound) |
| F | `progression/DamageScaling`, `SoulLevel` (`mobScalar`, for the stats screen) |
| G | `ability/common/FlashStep` |
| H | `ability/common/SpiritualFlex`, `effect/ReiatsuEffect`, `mixin/LivingEntityTravelMixin` |
| H.4 | `effect/ReiatsuEffect`, `mixin/ConjuredEntityMixin`, `mixin/CreeperSwellMixin`, `mixin/EnderManTeleportMixin`, `mixin/LivingEntityDamageMixin` |
| H.5 | `effect/ReiatsuEffect` (the formula), `ability/common/SpiritualFlex` (the tick) |
| I | `ability/KitRegistry` |
| J.1–J.9 | `ability/kits/*` |
| J.9.3 | `ability/kits/KaromatsuManager` (the shell), `client/KaromatsuOverlay`, `client/ClientKaromatsuState` |
| K | `network/BleachNetworking`, `client/SpiritualHud`, `client/SoulStatsScreen`, `client/ScreenShake`, `progression/WorldSoulLevel` |
| N | `ability/common/AuraSense` |
| N.1, N.3 | `client/AuraSenseOverlay`, `client/ClientAuraSenseState` |
| N.2 | `ability/common/AuraSense` (`burn`), `client/AuraSenseOverlay` (applies it) |
| O | `ability/common/Hover`, `mixin/client/LocalPlayerHoverMixin`, `client/ClientHoverState` |
| P.0 | `race/ReishiDensity` |
| P.1 | `entity/ReishiArrow` (particle scale), `entity/BleachEntities` (hitbox, tracking range, update interval — registration-time) |
| P.2 | `item/HeiligBogenItem` |
| P.3 | `ability/kits/QuincyTransform`, `ability/common/FlashStep` (range multiplier) |
| P.4 | `ability/common/Blut`, `progression/DamageScaling` (both multipliers), `client/SpiritualHud` (border colours) |
| P.5 | `ability/kits/ThunderboltTransform`, `ability/kits/BleachKits` (kit registration) |
| P.6 | `ability/kits/DeathdealingTransform`, `ability/kits/Doses`, `ability/kits/PoisonDome`, `progression/DamageScaling` (the dose multiplier), `attachment/SpiritualTicker` (both tick passes) |

---

## M. Retune priority

When playtesting says something is off, these are the constants most likely to be wrong, in order:

1. `SHINJI_MOB_INVERT_CHANCE`, `SHINJI_MOB_REROLL_TICKS`
2. `REIATSU_DAMAGE_PER_GAP` / `REIATSU_DAMAGE_MAX`
3. `FLEX_COUNTER_GAP_BASE` / `FLEX_COUNTER_GAP_DIVISOR`
4. `SPX_DAILY_CAP_BASE` vs. `SPX_CURVE_COEFF` / `SPX_CURVE_EXPONENT` — **now the top of this list in practice**: `SL_MAX` moved to 100 without the curve moving with it, so the ladder costs ~722,000 SPX end to end. See §E.1
5. `EXERTION_K_BASE` / `EXERTION_K_PER_LEVEL`
6. `SUI_MARK_TOLERANCE`, `SUI_SHIKAI_KILL_EXERTION`
7. `MOB_SCALE_PER_LEVEL`, `MOB_SCALE_LEVEL_HEADROOM`
8. `WORLD_SCALAR_PER_LEVEL`
9. `HOVER_DRAIN_BASE` / `HOVER_RESPONSE`
10. `REIATSU_SILENCE_MIN_AMP`
