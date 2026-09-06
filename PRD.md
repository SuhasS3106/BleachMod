# Bleach Mod — Product Requirements Document

**Target:** Minecraft 1.21.1 · Fabric Loader 0.19.5 · Fabric API 0.116.17 · Java 21 · **Official Mojang mappings**
**Mod ID:** `bleach_mod` · **Group:** `com.bleach.mod`
**Audience:** PvP-first, small servers (2–8 players). PvE works but is not the balance target.
**Constraint:** Solo developer. No custom 3D models, no rigging, no animation. Vanilla mechanics only — raycasts, status effects, attribute modifiers, particles, mixins.

> **Every number in this document is a symbol defined in `BALANCE.md`.** Formulas here cite symbols; defaults live there and only there. Changing a value means editing `BALANCE.md` and `BleachTuning.java` together — never a literal in game logic.

---

## 0. Glossary

| Term | Meaning |
|---|---|
| **SP** | Spiritual Pressure — the per-player consumable resource |
| **SPX** | Soul Points — the XP currency earned from kills |
| **SL** | Soul Level — player progression tier, 1–`SL_MAX` |
| **WSL** | World Soul Level — playtime-weighted average SL across the server |
| **Exertion** | Accumulated transformation debt that suppresses SP regen |
| **Bleach damage** | Damage from a zanpakutō swing or an ability. Distinct from vanilla damage. |
| **Reiatsu** | The mod's single custom debuff, used by Spiritual Flex. Never surfaces a vanilla effect name. |

---

## 1. Core Resource: Spiritual Pressure (SP)

Stored per-player via Fabric's `AttachmentType` API. Never a capability, never NBT-on-player.

### 1.1 Pool and regeneration — *constants: `BALANCE.md` §A*

```
maxSp(SL) = SP_BASE_MAX + SP_MAX_PER_LEVEL × (SL − 1)
regen(SL) = maxSp(SL) × (SP_REGEN_BASE_PCT + SP_REGEN_PCT_PER_LEVEL × (SL − 1))
```

| | SL 1 | SL 20 |
|---|---|---|
| Max SP | 100 | 290 |
| Regen | 2.00 /s | 11.86 /s |
| Full refill, no debuff | 50 s | 24 s |

Regen is a **percentage of max SP**, not a flat rate. A flat rate would mean each level enlarges the pool without speeding the refill, so every level would *lengthen* your downtime — progression would make the game worse. This form makes higher SL strictly better in both absolute and relative terms.

### 1.2 Regen gating

- Paused for `SP_REGEN_PAUSE_TICKS` after any spend, and after dealing or taking damage.
- Multiplied by the exertion multiplier (§1.3).
- SP persists across dimension change and logout. **Refills to 100% on respawn**, and exertion clears.

### 1.3 Exertion — the anti-spam mechanic — *constants: §B*

This replaces all cooldowns on Shikai and Bankai. There is no transformation cooldown timer anywhere in the mod.

**Accrual.** While transformed, exertion accumulates in seconds at `EXERTION_RATE_BANKAI` or `EXERTION_RATE_SHIKAI`.

**Persistence.** Exertion does **not** decay on exit. It clears **only when SP reaches 100% of max** — nowhere else. Re-entering before your bar fills carries the whole debt forward and stacks on top of it.

```
k(SL)         = EXERTION_K_BASE − EXERTION_K_PER_LEVEL × (SL − 1)
regenMult(SL) = max(EXERTION_MULT_FLOOR, 1 / (1 + k(SL) × exertion))
```

| Scenario | Exertion | Mult | Effective regen | Time to Bankai gate |
|---|---|---|---|---|
| SL 1, one 19 s Bankai burn to zero | 19 | 0.47 | 0.93 /s | ~102 s |
| SL 1, re-enters at gate, second burn | 38 | 0.31 | 0.61 /s | ~156 s |
| SL 1, third consecutive burn | 57 | 0.23 | 0.45 /s | ~211 s |
| SL 20, one full 58 s Bankai burn | 58 | 0.55 | 6.47 /s | ~30 s |

The spammer's punishment compounds; the veteran's does not.

### 1.4 Transformation entry gates — *constants: §C*

```
bankaiGate(SL) = maxSp × (GATE_BANKAI_BASE − GATE_REDUCTION_PER_LEVEL × (SL − 1))
shikaiGate(SL) = maxSp × (GATE_SHIKAI_BASE − GATE_REDUCTION_PER_LEVEL × (SL − 1))
```
SL 1 → 95% / 65% · SL 20 → 66.5% / 36.5%

**The Bankai refill is a loan, not a gift.** Entering Bankai sets SP to 100% of max, giving you a full pool to burn. On revert, the surplus is clawed back:

```
onEnter:  spOnEntry = sp;  sp = maxSp()
onRevert: sp = min(spOnEntry, sp)
```

Without the claw-back, toggling Bankai for one tick is a free full heal of the resource — the single worst exploit in the design. With it, the refill is genuinely a *drain budget* and never a bankable gain. Entering Shikai does not refill.

Note the second-order consequence: exiting Bankai early no longer preserves SP (you drop to your entry value either way), so the incentive to bank out early now comes **entirely from exertion** — less time transformed, less debt. That is the cleaner mechanism anyway.

### 1.5 Drain rates — *constants: §C, §H*

Flat absolute values, **not** percentages — a larger pool buys a longer window, which is the reward for leveling.

| State | Drain | Window at SL 1 | Window at SL 20 |
|---|---|---|---|
| Shikai | `DRAIN_SHIKAI` = 1.5 /s | ~66 s | ~193 s |
| Bankai | `DRAIN_BANKAI` = 5.0 /s | ~20 s | ~58 s |
| Spiritual Flex | `FLEX_DRAIN_BASE − FLEX_DRAIN_PER_LEVEL × (SL−1)` | 3.0 /s | 1.1 /s |
| Countering a Flex | `FLEX_COUNTER_DRAIN_BASE − …_PER_LEVEL × (SL−1)` | 4.0 /s | 1.53 /s |

Drains are **additive**. Flexing in Bankai at SL 1 costs 8.0 SP/s and empties a full bar in 12 seconds.

### 1.6 Hitting zero

SP reaching 0 forces an immediate revert. All transformation buffs are removed and the §1.4 claw-back applies (a burn to zero leaves you at zero). There is **no** exhaustion debuff effect — the suppressed regen *is* the penalty, and because exertion clears only at 100%, hitting zero guarantees the longest climb back.

---

## 2. Progression: Soul Points and Soul Level

### 2.1 Earning SPX — *constants: §D*

SPX is awarded **only if every point of damage the entity took came from you, and only if the killing blow came from your drawn zanpakutō.** The first time damage from any other source lands — another mob, another player, fall damage, cactus, drowning, suffocation, fire you did not apply, your own tamed pets — a taint flag is set and that entity's SPX becomes permanently zero. This kills drop farms, grinder splash kills, and pet-tanking. Your own melee and your own projectiles both count toward "you".

**Base values scale with the world.** As WSL rises, mobs get stronger (§2.5), so they must also pay more:

```
worldScalar = 1 + WORLD_SCALAR_PER_LEVEL × (WSL − 1)
catchUp     = clamp(1 + CATCHUP_PER_LEVEL_GAP × (WSL − SL), 1.0, CATCHUP_MAX)

mobSpx      = base(entityType) × worldScalar × catchUp
playerSpx   = (SPX_PLAYER_BASE + SPX_PLAYER_PER_LEVEL_GAP × max(0, theirSL − yourSL)) × catchUp
```

`worldScalar` does **not** apply to player kills — those already scale on the level gap, and applying both would double-count.

Base table per entity type: `BALANCE.md` §D.1.

Being above WSL is neutral, never penalised. Punishing your strongest players for winning is how a PvP server empties out.

### 2.2 Daily cap

```
dailyCap = SPX_DAILY_CAP_BASE × worldScalar × catchUp
```

The cap is multiplied by both scalars for the same reason the awards are — otherwise the cap silently cancels the catch-up bonus and a new player gains nothing from being behind.

Reset uses a day stamp in the attachment: compare `level.getDayTime() / 24000` against the stored value. No timers, no rolling windows, immune to sleep-skipping abuse.

### 2.3 Level curve

```
spxToNext(L) = round(SPX_CURVE_COEFF × L ^ SPX_CURVE_EXPONENT)
```

| Level | Next | Cumulative |
|---|---|---|
| 1 → 2 | 12 | 12 |
| 5 → 6 | 143 | 380 |
| 10 → 11 | 478 | 2,320 |
| 15 → 16 | 895 | 5,700 |
| 19 → 20 | 1,310 | ~11,100 |

At the base cap on a WSL-1 world that is roughly **56 in-game days** to cap; on a mature server a newcomer at full catch-up reaches it in **8–10 days**. That asymmetry is the point.

### 2.4 What Soul Level grants — *constants: §E*

**Damage dealt scales for bleach sources only** — zanpakutō melee and abilities. Vanilla weapons never scale; a bow is a bow at every level, which keeps gear relevant and stops a capped player from dominating at every range.

**Damage taken scales for everything.** Mobs get stronger as the world advances (§2.5), so general defense has to advance with it or high-WSL worlds become unplayable regardless of level.

```
bleachDealt(SL)  = 1 + SL_BLEACH_DMG_DEALT_PER_LEVEL  × (SL − 1)     → +38% at SL 20
generalTaken(SL) = 1 − SL_GENERAL_DMG_TAKEN_PER_LEVEL × (SL − 1)     → −19% at SL 20
bleachTaken(SL)  = 1 − SL_BLEACH_DMG_TAKEN_PER_LEVEL  × (SL − 1)     → −28.5% at SL 20
bonusHp(SL)      = floor(SL / 2) × SL_HP_PER_TWO_LEVELS              → +10 HP at SL 20
```

Incoming **bleach** damage is multiplied by both reductions → **−42%** at SL 20. Incoming vanilla damage takes only `generalTaken`. Order of operations: vanilla armour and resistance first, these multipliers last.

| Stat | SL 20 |
|---|---|
| Max SP | 290 |
| SP regen | 4.09 %/s of max |
| Bleach damage dealt | +38% |
| All damage taken | −19% |
| Bleach damage taken | −42% (combined) |
| Max health | +10 HP |
| Flex radius | 16 blocks |
| Flash Step range (full SP) | 23.7 blocks |
| Gates | 66.5% / 36.5% |

**Neither reduction stops Sui-Feng's two-strike kill.** That is a mechanic, not damage. Letting a capped player shrug it off deletes the character.

### 2.5 World Soul Level and world scaling — *constants: §D, §F*

```
WSL = Σ(SL_i × playtimeTicks_i) / Σ(playtimeTicks_i)
```

Playtime-weighted, over players active in the last `WSL_PLAYTIME_WINDOW_DAYS`, so a one-time visitor from months ago contributes nothing. Stored in `SavedData` on the overworld, recomputed **once per Minecraft day** — this is a multiplier input and a display value, never a hot-path computation.

**Mobs scale with the world.** Mob-dealt damage to a player is multiplied by:

```
effectiveLevel = min(WSL, victimSL + MOB_SCALE_LEVEL_HEADROOM)
mobScalar      = max(1.0, 1 + MOB_SCALE_PER_LEVEL × (effectiveLevel − 1))
```

**The headroom clamp is load-bearing and not optional.** Uncapped, a fresh SL-1 player joining a WSL-18 server takes 2.4× mob damage with none of the defense from §2.4 and cannot leave spawn — the catch-up multiplier fixes their *progression*, not their *survival*. With the clamp, a newcomer on that world faces at most 1.40× while a capped player faces the unclamped 2.36×. (The often-quoted 2.52× is the value at `effectiveLevel` 20 and so needs a WSL-20 world — `min(18, 25)` is 18.) Mobs scale to the world, but never more than `MOB_SCALE_LEVEL_HEADROOM` levels ahead of whoever they are hitting.

Mob **health** scaling is behind `MOB_SCALE_HEALTH`, default off. Damage scaling alone is one mixin site; health scaling needs a spawn hook and makes every fight longer rather than tenser. Turn it on only if playtesting asks.

---

## 3. Zanpakutō and Character Kits

### 3.1 Acquisition

New players spawn with an **Asauchi**. Right-click opens a `ChestMenu` containing the five zanpakutō — zero custom GUI code, no screen class, no rendering. Selecting one writes the character ID to the attachment and hands over the real sword.

The choice is **permanent** unless the player obtains a **Reforged Asauchi** (rare drop / expensive craft), which re-opens the selection.

### 3.2 Draw / sheathe

There is **no custom equipment slot.** Adding one to 1.21.1 requires an inventory attachment, an `InventoryScreen` mixin for drawing and hit-testing, a `Slot` injected into `InventoryMenu`, container sync, plus creative-mode and death-drop edge cases — realistically more code than all five kits combined. *(Deferred to future scope by decision.)*

Instead: a **draw/sheathe keybind**. The zanpakutō lives in the attachment — invisible, undroppable, unstealable, kept on death, auto-restored if lost. The key swaps it into the main hand and whatever was there into the attachment; pressing again reverses it.

| | Drawn | Sheathed |
|---|---|---|
| Shikai / Bankai | ✅ | ❌ |
| Bleach damage **dealt** scaling | ✅ | n/a (no bleach source available) |
| Bleach damage **taken** scaling | ✅ | ✅ **always applies** |
| General damage taken scaling | ✅ | ✅ **always applies** |
| Kills award SPX | ✅ | ❌ |
| Flash Step | ✅ | ✅ |
| Spiritual Flex | ✅ | ✅ |

**Defense is never conditional on the sword.** Your Soul Level is what you *are*, not what you are holding — sheathing must not make you fodder for a bleach attack. Only *offense* and *SPX* require the drawn blade. Flash Step and Flex are raw spiritual pressure, not techniques, and never require it either.

### 3.3 Ability architecture

A **Java registry**, not datapack JSON. None of these abilities are expressible as data — a raycast, a rotation-relative hit mark, a mixin-backed control flip. A JSON schema would degenerate into `{"type": "bleach:flash_step"}` pointing at hardcoded Java, having cost a codec layer and a sync packet for nothing. Extensibility comes from the interface and the registry; tunable *numbers* live in `BALANCE.md` and `BleachTuning`.

```java
public interface Ability {
    ResourceLocation id();
    boolean canActivate(ServerPlayer player, SpiritualData data);
    void onActivate(ServerPlayer player, SpiritualData data);
    int cooldownTicks();
    double spCost();
    default boolean requiresDrawnSword() { return true; }
}

public interface TransformAbility extends Ability {
    double drainPerSecond();
    double entryGatePercent(int soulLevel);
    void onEnter (ServerPlayer player, SpiritualData data);
    void onTick  (ServerPlayer player, SpiritualData data);
    void onRevert(ServerPlayer player, SpiritualData data);
    /** Melee hook, active only while this transformation is. */
    default void onMeleeHit(ServerPlayer player, LivingEntity target, float dmg) {}
}

public record Kit(ResourceLocation id, String displayName,
                  TransformAbility shikai,   // never null
                  TransformAbility bankai,   // never null
                  double flashStepRangeMult, double flashStepCooldownMult,
                  int particleColor) {}
```

**Every kit must define both a Shikai and a Bankai.** `KitRegistry.register` throws on a null in either slot. A character with only one released state is a character that plays half a game — and the Shikai/Bankai split *is* the mod's core tension (sustainable stance vs. committed burn). Adding a sixth character = one `Kit` entry plus its two abilities. No other file changes.

---

## 4. Flash Step — *constants: §G*

Universal. Available to every player at all times, drawn or sheathed.

```
range(SL, sp) = (FS_RANGE_BASE + FS_RANGE_PER_LEVEL × (SL − 1)
                 + FS_RANGE_SP_TERM × (sp / maxSp)) × kit.flashStepRangeMult
```

SL 1 empty → 6.0 · SL 1 full → 18.0 · SL 20 full → 23.7 · Sui-Feng SL 20 full → 35.6 blocks.

Range scales on **both** SL and current SP: your ceiling rises with mastery, your reach right now depends on what you have left in the tank.

- Raycast along the look vector with `Level#clip()` / `ClipContext` (`Block.COLLIDER`, `Fluid.NONE`).
- **Block hit:** teleport `FS_STOP_SHORT` blocks short of the hit point; validate the destination is non-suffocating (step up to 2 blocks, else walk the point back toward the eye until it clears, else abort and refund).
- **No hit (open sky):** capped Levitation + Slow Falling plus a horizontal impulse along the look vector, at `FS_SKY_RANGE_PCT` of ground range.
- **Cost:** `FS_COST_PCT` of max SP. **Cooldown:** `FS_COOLDOWN_TICKS × kit.flashStepCooldownMult`.

---

## 5. Spiritual Flex — *constants: §H*

**Hold-to-channel** on its own keybind. Releases instantly — no toggle state to desync. Requires no sword.

- **Radius:** `FLEX_RADIUS_BASE + FLEX_RADIUS_PER_LEVEL × SL`
- **Drain:** `FLEX_DRAIN_BASE − FLEX_DRAIN_PER_LEVEL × (SL − 1)`, additive with any transformation.
- Applies/refreshes **Reiatsu** at `FLEX_EFFECT_DURATION_TICKS` every tick to every entity in radius. Walking out lets it lapse in ~3 s. Free vanilla tracked-data sync, range-based counterplay, no custom packet.

### 5.1 Reiatsu — one custom effect, four tiers

**No vanilla status effect name or icon is ever shown to the player.** There is exactly one registered effect, `bleach_mod:reiatsu`, whose strength comes from its amplifier. It carries its own attribute modifiers rather than stacking vanilla debuffs, so the effect list reads "Reiatsu III" and nothing else.

`gap = flexerSL − targetSL − counterReduction`. Mobs count as SL 0 and cannot counter.

| Gap | Amp | Movement | Attack speed | Attack damage | Extra |
|---|---|---|---|---|---|
| ≤ 0 | — | none | | | |
| `REIATSU_TIER_1_GAP` (1) | 0 | −15% | — | — | — |
| `REIATSU_TIER_2_GAP` (4) | 1 | −30% | −20% | — | — |
| `REIATSU_TIER_3_GAP` (8) | 2 | −45% | −35% | −25% | — |
| `REIATSU_TIER_4_GAP` (13) | 3 | **rooted** | −35% | −40% | hidden Nausea |

Rooting is applied in the same `LivingEntity#travel` mixin that Sakanade uses — one mixin, two consumers. Nausea has no attribute equivalent, so tier 3 applies vanilla Nausea with `showIcon = false, visible = false`; it will still list in the inventory effect panel. Replacing it with a client-side screen distortion is tracked in §11.

Mining Fatigue is deliberately absent from the tier table. 1.21.1 has no mining-speed attribute, and dig speed is irrelevant in a fight — attack speed is the meaningful analogue.

### 5.2 Countering

A target inside a hostile field may hold their own flex key to push back, reducing the effective gap by `FLEX_COUNTER_GAP_BASE + floor(targetSL / FLEX_COUNTER_GAP_DIVISOR)` — up to 7 at SL 20 — at a cost of `FLEX_COUNTER_DRAIN_BASE − FLEX_COUNTER_DRAIN_PER_LEVEL × (targetSL − 1)` SP/s.

Countering is deliberately **more expensive than exerting**: pushing back against someone stronger should bleed you. If a countering player's SP hits zero they take the full untempered tier and enter the §1.6 forced revert. Mobs cannot counter.

### 5.3 Visual

- **One custom 8×8 particle PNG** — a soft vertical streak. No model, no rig, no animation. Tinted per kit via `Kit.particleColor`, which buys the mod its whole visual identity for one tiny asset.
- Spawned in a ring at the flexer's feet with high upward velocity and short lifetime; density scales with SP spent.
- **Screen shake on affected players.** Ships first as a HUD-layer substitute (see the implementation plan) and upgrades to a real camera hook only if that reads flat.

---

## 6. Character Kits

**Every kit has both a Shikai and a Bankai.** The pairing is the design: Shikai is the sustainable stance you fight in, Bankai is the committed burn you spend a whole recovery cycle on.

### 6.1 Ichigo — *Zangetsu* — *constants: §J.1*

**Shikai — the cleaver.** Zangetsu released is an oversized blade, so Shikai buys **reach and weight**: `ENTITY_INTERACTION_RANGE` +`ICHIGO_SHIKAI_REACH`, bleach melee +`ICHIGO_SHIKAI_DMG`, and every melee swing widens into a cleave — all entities within `ICHIGO_SHIKAI_CLEAVE_ARC` degrees in front and inside your reach take `ICHIGO_SHIKAI_CLEAVE_PCT` of the primary damage. Vanilla already has sweep attacks; this is a wider, angle-tested version of the same idea. No projectile, no models.

**Bankai** (the Byakuya-fight version only; no later forms). The flip: Shikai is reach and power, Bankai is **speed**. `MOVEMENT_SPEED` +`ICHIGO_BANKAI_SPEED`, `ATTACK_SPEED` +`ICHIGO_BANKAI_ATK_SPEED`, bleach melee +`ICHIGO_BANKAI_DMG`. The reach bonus does **not** carry over — Bankai's blade is the small one. No Getsuga, deliberately.

### 6.2 Yamamoto — *Ryūjin Jakka* — *constants: §J.2*

**Shikai:** ignites every entity within `YAMA_SHIKAI_RADIUS` for `YAMA_SHIKAI_BURN_TICKS`, re-applied on a slow pulse while the state is held. Caster is immune.

**Bankai:** extinguishes all fire in `YAMA_BANKAI_RADIUS`, clears the caster's own burning, and grants +`YAMA_BANKAI_DMG` melee plus a Fire-Aspect-equivalent `YAMA_BANKAI_ONHIT_BURN_TICKS` burn on hit for the duration.

The block sweep is budgeted at `YAMA_BLOCK_SWEEP_BUDGET` positions per tick — a naive 30-radius box is 226,981 positions and would be a worse tick spike than the nuke.

### 6.3 Sui-Feng — *Suzumebachi* — *constants: §J.3*

**Shikai — Nigeki Kessatsu.** The mark lands only while Shikai is active.

- On hit, store the hit point from `EntityHitResult#getLocation()` **relative to the target's position and rotation**. Rotation-relative is required, or the target scrubs the mark for free by turning around.
- A second hit within `SUI_MARK_TOLERANCE` of the stored point and within `SUI_MARK_WINDOW_TICKS` kills outright.
- Bypasses armour. **Totem of Undying still works** — the one counterplay item.
- **Neither damage reduction from §2.4 applies.** This is a mechanic, not damage.
- The mark renders as a black butterfly particle parked on the target so the victim can see it and play around it.

**Bankai — Jakuhō Raikōben.**

- **Wind-up** `SUI_BANKAI_WINDUP_TICKS`: caster rooted, loud charge sound, expanding particle ring drawing the true radius so bystanders can sprint clear.
- **Entity damage:** unconditional kill within `SUI_BANKAI_LETHAL_RADIUS` (bypasses armour, Totem still works); from there to `SUI_BANKAI_FALLOFF_RADIUS`, damage lerps `SUI_BANKAI_DMG_INNER` → `SUI_BANKAI_DMG_OUTER`, reduced by the target's §2.4 multipliers.
- **No `Explosion` object is constructed anywhere.** A real explosion at this radius means tens of thousands of block updates, neighbour cascades, falling gravel, and item entities — that is the server-death scenario.
- **Crater:** precomputed shallow bowl, `SUI_CRATER_RADIUS` × `SUI_CRATER_DEPTH`, noisy edge. `setBlock(AIR, flag 2 | 16)` — flag 16 suppresses the neighbour cascade. Zero drops, capped at `SUI_CRATER_BLOCK_CAP`, applied `SUI_CRATER_BLOCKS_PER_TICK` per tick. Rim scorches rather than clears (grass → coarse dirt, stone → blackstone). Bedrock and block entities blacklisted. Wide and shallow reads far more nuclear than deep and narrow, at a fraction of the block count.
- **Cost:** SP → 0 (maximum exertion debt), `SUI_BANKAI_COOLDOWN_TICKS` hard cooldown, and a caster inside their own radius takes `SUI_BANKAI_SELF_DMG_PCT` of *current* health — never lethal to yourself.

### 6.4 Rukia — *Sode no Shirayuki* — *constants: §J.4*

**Shikai — localized frost on hit.** Every bleach melee hit triggers the Bankai's effect in miniature at the point of impact: within `RUKIA_SHIKAI_ONHIT_RADIUS`, apply the freeze effect, convert water sources, and lay a snow layer. Rate-limited by `RUKIA_SHIKAI_ONHIT_COOLDOWN` so a fast weapon cannot carpet a base.

**Bankai — the frozen field.** Within `RUKIA_BANKAI_RADIUS`, while the state is held:

1. **Freeze effect** — a custom `MobEffect` doing `RUKIA_FREEZE_DMG_PER_SEC` and heavy slowness, refreshed each tick. Affected players get a client-side blue tint and frost particles.
2. **Local snowfall** — `RUKIA_SNOWFALL_PARTICLES` snowflake particles per tick falling through the radius. This *fakes* localized weather; real per-region weather needs a client biome mixin and is not worth it. Visually it is indistinguishable.
3. **Snow accumulation** — `Blocks.SNOW` layers placed and incremented on top-solid blocks, budgeted `RUKIA_SNOW_BLOCKS_PER_TICK`, capped at `RUKIA_SNOW_MAX_LAYERS` height and `RUKIA_SNOW_BLOCK_CAP` total. Layers **persist** after the Bankai ends — a permanent scar on the battlefield, which is the point.
4. **Frozen water** — water source blocks in radius become `Blocks.FROSTED_ICE`, not `ICE`. Frosted ice melts on its own via vanilla ticking, so the field thaws naturally once you leave and needs **zero cleanup code or state tracking**. This is the whole reason to pick that block.

### 6.5 Shinji — *Sakanade* — *constants: §J.5*

**Shikai — targeted inversion.** A bleach melee hit applies the Sakanade effect to **that one target** for `SHINJI_SHIKAI_ONHIT_DURATION`, rate-limited by `SHINJI_SHIKAI_ONHIT_COOLDOWN`. Same effect, same mixins, single-target instead of AOE — the natural lesser form, and zero new systems.

**Bankai — proximity inversion.**

- **Aura, not a duration.** Every tick, apply/refresh the effect at `SHINJI_EFFECT_DURATION_TICKS` on every entity within `SHINJI_RADIUS_BASE + SHINJI_RADIUS_PER_LEVEL × SL`. Leaving range lets it lapse. Free vanilla tracked-data sync, no custom packet.
- **Players:** mixin into `KeyboardInput#tick`, flipping `forwardImpulse` / `leftImpulse` (and the matching boolean flags) while the effect is active. **WASD only — never mouse-look** — paired with Nausea. Disorienting but playable.
- **Mobs:** mixin at the **movement-vector layer** (`LivingEntity#travel`), not the navigation-target layer. Inverting the destination makes mobs coherently path *away*, which is exactly the "they just leave" failure. Inverting applied motion keeps them trying to reach you and failing, which reads as drunk.
- `SHINJI_MOB_INVERT_CHANCE` of rolls invert, re-rolled every `SHINJI_MOB_REROLL_TICKS`. Per-tick rerolling averages into jittery noise that looks like lag; a 10-tick hold makes each stagger legible. *Provisional — retune after playtesting.*

---

## 7. Client Presentation

### 7.1 HUD

`GuiGraphics#fill` and `drawString` only. **Zero texture assets.**

- SP bar above the hotbar, right side, mirroring the vanilla armour/health rows.
- Colour by state: `HUD_COLOR_BASE` / `HUD_COLOR_SHIKAI` / `HUD_COLOR_BANKAI`.
- **Pulses while the exertion multiplier is below 1.0**, so the regen penalty is legible without opening a menu.
- Soul level as a small number at the bar's end.

### 7.2 Stats screen

A real `Screen` subclass on its own keybind, styled after the enchanting table. Shows WSL, your SL, SPX, progress to next level, today's remaining cap, your catch-up multiplier, and the current mob scalar you are facing.

**Needs no networking of its own** — the client already holds every number from the §8 payload. No menu type, no container, no round-trip.

---

## 8. Networking

Fabric attachments in 1.21.1 (FAPI 0.116.x) have **no built-in client sync**. The client cannot see its own SP unless it is sent.

**C2S** — `AbilityActivatePayload { int abilityIndex }` via `PayloadTypeRegistry`. The server checks everything: sword drawn, correct kit, SP cost, gate, cooldown, state legality. Nothing is trusted from the client; the client never predicts.

**S2C** — `SpiritualSyncPayload { float sp, float maxSp, int soulLevel, int spx, int spxToNext, byte state, float regenMult, float worldSoulLevel }`. Owning player only, on change plus a `SYNC_KEEPALIVE_TICKS` heartbeat. ~28 bytes; no one else needs your bar.

**Keybinds:** Flash Step · Shikai toggle · Bankai toggle · Spiritual Flex (hold) · Aura Sense (hold) · Draw/Sheathe · Stats screen (client-only, no packet).

---

## 9. Package Layout

```
com.bleach.mod
├── BleachMod.java / BleachModClient.java
├── tuning/      BleachTuning            ← every constant, loaded from config
├── attachment/  SpiritualData, BleachAttachments, SpiritualTicker
├── ability/     Ability, TransformAbility, Kit, KitRegistry, AbilityDispatcher
│   ├── kits/    IchigoKit, YamamotoKit, SuiFengKit, RukiaKit, ShinjiKit
│   └── common/  FlashStep, SpiritualFlex
├── effect/      BleachEffects, ReiatsuEffect, SakanadeEffect, FreezeEffect
├── item/        AsauchiItem, ReforgedAsauchiItem, ZanpakutoItem, BleachItems
├── menu/        ZanpakutoSelectMenu
├── progression/ SoulLevel, SpxTable, WorldSoulLevel, DamageAttribution
├── network/     AbilityActivatePayload, SpiritualSyncPayload, BleachNetworking
├── client/      SpiritualHud, StatsScreen, ScreenShake, BleachKeybinds, ParticleRegistry
└── mixin/       LivingEntityDamageMixin, LivingEntityTravelMixin
    └── client/  KeyboardInputMixin
```

**Mixins: 3.**
- `KeyboardInput#tick` (client) — Sakanade WASD flip
- `LivingEntity#travel` — Sakanade mob inversion **and** Reiatsu rooting
- `LivingEntity#hurt` — SPX attribution, bleach/general damage scaling, mob world scaling

---

## 10. Build Order

Detailed in `IMPLEMENTATION_PLAN.md`. Critical path is **1 → 2 → 4 → 6**; phases 7, 9, 10, 11 are mutually independent.

1. Foundation — `BleachTuning`, attachment, SP/regen/exertion, sync, HUD
2. Input — keybinds, C2S payload, dispatcher
3. Flash Step
4. Zanpakutō, selection menu, draw/sheathe, kit registry
5. Progression — attribution, cap, curve, WSL, world scaling, stats screen
6. Transformations + **Ichigo** (both states)
7. **Yamamoto**
8. Spiritual Flex + Reiatsu
9. **Rukia**
10. **Sui-Feng**
11. **Shinji**
12. Tuning pass

---

## 11. Open Items

| # | Item | Current default | Trigger to revisit |
|---|---|---|---|
| 1 | Sakanade mob invert ratio and reroll interval | 0.70 / 10 ticks | after first playtest |
| 2 | Tier-3 Reiatsu uses hidden vanilla Nausea, which still lists in the effect panel | accepted | if the leaked name breaks immersion — replace with a client-side distortion |
| 3 | Custom zanpakutō equipment slot | deferred; draw/sheathe instead | future scope |
| 4 | Screen shake needs a camera mixin | HUD-layer substitute first | if the flex reads flat |
| 5 | `MOB_SCALE_HEALTH` | off | if fights feel too short at high WSL |
| 6 | `SPX_DAILY_CAP_BASE` vs. curve total | ~56 days at WSL 1 | if progression drags |
| 7 | Flex counter gap reduction | `2 + floor(SL/4)` | if countering feels mandatory or useless |
| 8 | Rukia's snow layers persist permanently | persist | if servers complain about terrain litter — add a decay tick |
| 9 | Boss SPX flat at 100 | flat | if boss farming becomes the meta |
