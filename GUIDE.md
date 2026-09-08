# Bleach — Player Guide

Everything the mod does, in the order you will meet it. In game, the same text is available to
every player (no operator permission needed) via **`/bleach guide`**, and per topic with
`/bleach guide <topic>`.

---

## Controls

| Key | Does |
|---|---|
| **V** | Flash Step — blink toward where you are looking |
| **R** | Shikai — toggle your first released state |
| **G** | Bankai — toggle your second released state |
| **X** | Draw / sheathe your spirit weapon — zanpakutō, or Heilig Bogen for a Quincy |
| **Left Alt** (hold) | Spiritual Flex — project pressure |
| **C** (hold) | Aura Sense — close your eyes and feel what is around you |
| **Q** (hold, in the air) | Hover — stop falling and fly on your look vector |
| **Z** | Blut — cycle Vene / Arterie / off (**Quincy only**; does nothing otherwise) |
| **K** | Soul stats screen |
| **B** | Master on/off switch — **operators only** |

All rebindable in *Options → Controls → Bleach*.

---

## Getting started

You spawn with an **Asauchi**, a blank blade. Right-click it to open the picker: first your **race**,
then a character within it. **The choice is permanent** — only an operator handing you a Reforged
Asauchi can undo it.

`/bleach guide` lists every character that is actually registered, so it can never fall behind the
roster; the count is not written down here for the same reason.

Your zanpakutō then:

- cannot be dropped, and cannot be put in a chest;
- is **not** lost on death, and is back **in your inventory** when you respawn;
- is minted again automatically if it ever goes missing.

The Asauchi has the same protections — it does not drop when you die, and you get it back on
respawn if you had not chosen yet. A **Reforged Asauchi** you had not spent yet is likewise taken off
you on death rather than dropped, and handed back when you respawn.

**Drawn vs sheathed matters.** Every character technique needs the blade in your *main hand*.
Flash Step and Spiritual Flex do not — they are raw pressure, not techniques of the sword.

---

## Spiritual pressure

The blue bar above your hotbar. It is the fuel for everything.

- **Max**: 100 at Soul Level 1, +10 per level.
- **Regen**: 2% of max per second, rising slightly with level.
- Regen **pauses for 3 seconds** after any spend or any damage taken.

### Shikai and Bankai

|  | Entry gate | Drain | Notes |
|---|---|---|---|
| **Shikai** | 65% of max at SL 1, −1.5%/level | 1.5 SP/s | Sustainable stance |
| **Bankai** | 95% of max at SL 1, −1.5%/level | 5 SP/s | Refills you to full on entry |

Bankai's refill is a **loan**. When you drop out, anything above what you entered with is taken
straight back — you cannot bank it, and you cannot toggle Bankai on and off to top yourself up.

**You do not have to remember those percentages.** The SP bar carries a notch at each gate — the left
one is Shikai, the right one Bankai. A notch goes **bright white once your pressure has passed it**,
and stays dim while you are short. So the answer to "can I go Bankai yet" is just whether the bar has
reached the second notch. Both notches slide left as you level, because the gates get cheaper.

### Exertion

Time spent released builds *exertion*, which throttles your regeneration afterwards. It clears only
when your pool is completely full, and on respawn. Staying in Bankai is cheap in the moment and
expensive for the next several minutes.

Both states end instantly if you sheathe, die, change dimension, or run the pool dry.

---

## Soul Level

1 to 20. Raises max pressure, regeneration, health (+1 heart per two levels), the damage your
zanpakutō deals, and the damage you shrug off.

**Soul Points** are earned by killing. A player is worth 25, plus 5 for every level they had on
you. There is a daily cap (200 base) so nobody clears the ladder in one sitting.

Every award floats up off the left end of the bar as **`+30 SPX`**, so you can see what a kill was
worth without opening anything. Kills landing together are counted into one figure rather than
stacking up the screen. When the figure is smaller than you expected, you are near the daily cap — the
number shown is what you actually banked, not what the kill was nominally worth.

**World Soul Level** is the server's playtime-weighted average. Mobs scale with it, and players
below it earn up to **3× faster** — joining late is a delay, not a permanent disadvantage.

> Only your zanpakutō and your techniques scale with Soul Level. A bow is a bow at every level.

---

## Flash Step — `V`

A blink along your look vector. No sword needed.

- **Range**: `10 + 0.55 × (SL − 1) + 20 × (current SP / max SP)` blocks.
  SL 1 empty → 10 · SL 1 full → 30 · SL 20 full → 40.5 · Suì-Fēng SL 20 full → **60.7**
- **Cost**: 8% of max pressure · **Cooldown**: 0.4 s

The burst of pressure at your destination lands a beat *after* you do — it announces the arrival,
it does not precede it.

Range scales on Soul Level *and* on how full your pool is right now, so it visibly shortens as you
burn through a fight.

Looking at a wall puts you just short of it. Looking at open sky teleports you out over the drop
and lands you under Slow Falling — it is the same instant blink in the air as it is on the ground.

---

## Spiritual Flex — hold `Left Alt`

Project raw pressure in a Soul-Level-scaled radius (8.4 blocks at SL 1, 16 at SL 20). No sword
needed. Costs pressure every tick you hold it.

The field draws a thin ring of pressure at its edge and throws risers up through it, so you and
everyone in it can see exactly where it reaches.

It crushes anything **below your Soul Level**, in four tiers: slowness, then slower attacks, then
weaker attacks, and at a gap of 13 levels it **roots them outright** and adds nausea.

- Mobs count as level 0, so a field always works on them.
- Against a player **within 1 level of you it does nothing at all.** That is not a bug — the tier is
  bought with the level gap and nothing else. Your action bar tells you how many targets your field
  is actually holding.
- Someone you outrank can **flex back** to shave levels off the gap, at a steeper drain than yours.
  If they run dry mid-push they take the full untempered tier.

**Mobs under your pressure cannot use anything but their hands.** No skeleton or pillager arrows, no
witch potions, no blaze or ghast fireballs, no wither skulls, no breeze wind charges, no piglin
bolts, no evoker fangs or vexes, no guardian beam, no warden shout. A creeper's fuse winds *back
down* while it stands in the field, and an enderman cannot blink at all — not to escape, not to
close, not when you hit it. They can still walk up and hit you, slowly and weakly, which is the whole
of what is left to them.

Players are **not** silenced. You are already slowed and weakened inside a field; being disarmed on
top of that would leave nothing to play against, and flexing back would not fix it.

**And standing in a field costs health.** Once a second, scaled by the level gap — half a heart's
worth at a gap of 1, rising to a cap of 4 hearts a second at a gap of 15 or more. It is the same gap
the tier is bought with, so flexing back cuts the bleed exactly as it cuts the tier, and a peer's
field still cannot touch you. It kills, it pays Soul Points, and it does **not** step around your
animals — a field held at home is a slow cull of your farm.

---

## Hover — hold `Q` in the air

Stop falling. Hold the key while you are off the ground and you hang there, and **WASD moves you
along your look vector** — forward is wherever you are pointing, so look up and hold forward to
climb, look down to dive. There is no separate up or down key on purpose: the mouse is your vertical
control.

This is the answer to a Flash Step that left you thirty blocks in the air. No sword needed.

- **It drains pressure the whole time**: 4 SP/s at Soul Level 1, down to about 1.2 at 20. A full SL 1
  pool is about 25 seconds of air — less in Shikai or Bankai, since those drains stack with it.
- **You cannot start one from the ground**, and touching the ground ends it — but the key is patient:
  hold it *before* you run off a ledge and it catches you the moment you are in the air, and land
  while still holding it and your next jump picks it back up.
- It ends the instant you let go, and instantly if you run dry. Both drop you, and after running dry
  you have to press again — it will not stutter back on as your pressure trickles in.
- **You take no fall damage while you hold it** — but the fall starts fresh the moment you let go, so
  releasing at height still hurts. Hover down, or Flash Step down, or take the landing.
- It will not take over while you are swimming, riding something, or flying in creative.

---

## Aura Sense — hold `C`

Close your eyes. The screen goes black, and every soul in reach burns through the dark as a coloured
aura — bright and wide when it is close or strong, a faint spark when it is far. No sword needed,
and **no spiritual pressure** — you pay for it in sight and nothing else.

- **Players are all one colour.** The sense reads a soul, not a face — you know *something* is there
  and roughly how strong, not who.
- **Every other kind of creature has its own colour**, the same for every one of its kind. Two
  zombies look identical; a zombie and a cow do not.
- **Creatures burn according to how big they are.** A bee or a chicken is a spark, a cow or a spider
  is a proper little fire, and an iron golem, a ravager or a ghast is a bonfire you can see across the
  room. Baby animals read smaller than adults, and a big slime reads bigger than a small one.
- **How far a soul carries is that soul's business, not yours.** A Soul Level 1 player is felt at
  about 80 blocks and a Soul Level 20 player at about 350 — from anywhere, at any level. Mobs carry
  about 64 blocks and no further, so a distant aura is always a person.
- Aura size is distance and strength together: a capped soul at three hundred blocks is a pinprick,
  and the same soul at ten blocks fills your view.
- **A released soul burns.** Shikai is roughly double the fire of a sheathed blade, Bankai roughly
  triple that again, and holding Spiritual Flex doubles whatever you are already in — so a released
  Bankai spending everything reads about seven times its resting size and hazes the dark around it. It
  grows on your side of the screen the moment they release, wherever they are. **It scales off Soul
  Level too**, so the same Bankai is a much larger fire on a stronger soul.
- **Reach does not grow with it.** Releasing makes a soul louder, not the room smaller — someone who
  goes Bankai at four hundred blocks is still out of reach entirely.
- **You are blind while you hold it**, and there is a mote of pressure at your head that everyone
  else can see. It is a trade, not a free overlay.
- **Enma Kōrogi shuts it off.** Caught inside Tōsen's Bankai dome, the key does nothing and a sense
  already running dies — the dome takes every sense you have, and this is one of them. Tōsen keeps
  his.

### Legend — reading the fire

Every soul is a **fire**, not a dot. Nothing on the screen is a different kind of marker; a spark at
three hundred blocks and a bonfire at ten are the same fire at different sizes, so you never have to
learn two vocabularies.

| What you see | What it means |
|---|---|
| **Pale blue fire** | A player. All players are this colour — you know *something* is there, not who |
| **Any other colour** | A creature. Fixed per kind: two zombies match, a zombie and a cow do not |
| **Big fire** | Close, strong, released — or, for a creature, simply large. See below |
| **Small fire** | Far away, a weak soul nearby, or something small. Still a fire, just quieter |
| **White-hot core** | The base of any fire, at any size. Not a signal — every soul has one |
| **Colour deepening upward** | Just the fire cooling as it rises. Not a signal either |
| **A fire that swells while you watch** | Someone released, right then. Shikai, Bankai, or a Flex |
| **A fire that shrinks while you watch** | They sheathed, or let a Flex go |
| **Violent, wide, throwing off the top** | High burn. A released soul spending what it has |
| **Coloured haze in the dark around one fire** | The strongest reading on screen — your biggest problem |
| **A fire fading out over a moment** | It left your reach, or died. Fires never blink out |

**Size is three things multiplied, and you cannot separate them from the outside.** Distance, Soul
Level, and burn all feed the same number. A pinprick is *either* a capped soul far away *or* a weak
one nearby, and the sense will not tell you which — that ambiguity is the price of a sense that costs
no pressure. What it *will* tell you honestly is **change**: a fire that grows without moving toward
you is a release, and that is the warning the sense exists to give.

Rough sizes for the same soul, at the same distance:

| State | Reads about |
|---|---|
| Sheathed | ×1 — its resting size |
| Shikai | ×1.8 |
| Bankai | ×3.6 |
| + Spiritual Flex | ×2.1 on top of whichever of the above they are in |
| Bankai + Flex | ×7.6 — the loudest thing a soul can be |

**Creatures are ranked by body, not by soul** — the only thing they have to be ranked by. Roughly,
smallest to largest, at the same distance:

| | |
|---|---|
| Sparks | silverfish, chicken, rabbit, bee |
| Small fires | zombie, skeleton, creeper, pig, breeze, phantom |
| Proper fires | cow, sheep, enderman, spider |
| Big fires | warden, wither, iron golem, ravager |
| Bonfires | ghast, ender dragon |

Even the ender dragon reads a shade **under** a Soul Level 20 player. Being enormous is not the same
as being strong, and the sense will not pretend otherwise. Note also that creatures still only carry
**64 blocks** however big they are — size makes a soul louder, never the room smaller — so anything
you can feel from further away than that is a person, no exceptions.

---

## The characters

### Ichigo Kurosaki — reach and tempo

| | |
|---|---|
| **Shikai — Zangetsu** | +1.5 blocks reach · +25% blade damage · every swing **cleaves** everything within a 90° arc in front of you for 50% of the damage |
| **Bankai — Tensa Zangetsu** | +60% movement speed · +50% attack speed · +75% blade damage |

The reach bonus deliberately does **not** carry into Bankai — that blade is the small one.

### Genryūsai Yamamoto — fire

Immune to fire and lava in both states.

| | |
|---|---|
| **Shikai — Ryūjin Jakka** | Everything within 30 blocks burns for 10 s. Water, ice and snow **boil out of the ground** and the surface catches light, spreading outward in rings from your feet. **The fire follows you** — walk and it is laid again around your new position, and nothing already burning is put out. You leave a trail. |
| **Bankai — Zanka no Tachi** | **Every fire within 30 blocks is pulled into the blade and goes out**, including anyone who was burning — lore-wise the heat is compressed into the sword. Then: +30% blade damage, and targets burn 5 s on hit. |

Shikai sets the world alight; Bankai takes it all back. Burn a field with the first and release the
second to clear it in one go.

> The scorch is destructive — it deletes water and snow and sets the ground on fire. Do not open it
> inside a build you like.

### Suì-Fēng — assassination

Flash Steps 50% further, on half the cooldown.

| | |
|---|---|
| **Shikai — Suzumebachi** | **Nigeki Kessatsu**: your strike brands the exact spot on the body it lands on, shown as a black butterfly parked on that spot. Hit **that same spot** again and the target dies outright — armour, resistance and Soul Level do not help; a Totem of Undying does. Striking elsewhere *moves* the mark instead of killing, and your action bar tells you how far off you were. The mark is a **body part, not a compass direction** — brand their left hand and it stays on their left hand however they turn, walk or crouch, so chase the butterfly rather than the direction you first hit from. A blow that doesn't properly connect marks nothing at all. There is no time limit; marks are wiped when you leave Shikai. |
| **Bankai — Jakuhō Raikōben** | 3 s rooted wind-up, ringed in gold and extremely loud, **which cannot be cancelled once started**. Then it **launches a missile** where you were aiming — it flies, and it can miss. On impact: 60 damage inside 12 blocks, falling to 12 at 24. That is *damage*, scaled by Soul Level on both sides, not an instant kill. Costs half your remaining health, a crater, and your entire pool. |

> Jakuhō Raikōben has **no timer**. Firing empties your bar and leaves you exhausted enough that
> regeneration crawls; you are ready again when the bar says you are, and you can watch it fill.

### Rukia Kuchiki — ice

| | |
|---|---|
| **Shikai — Sode no Shirayuki** | Every landed hit bursts frost 6 blocks around your target: heavy slowness, freeze damage, snow on the ground, water frozen solid. |
| **Bankai — Hakka no Togame** | Everything within 16 blocks is frozen continuously. Snow falls, snow stacks, every water source turns to ice. **The field follows you** — walk and it freezes your new ground too, and what you already froze stays frozen. You leave a trail. |

The ice thaws on its own. The snow does not — bring a shovel.

### Shinji Hirako — inversion

| | |
|---|---|
| **Shikai — Sakanade** | Landed hits reverse your target's WASD for 5 s. |
| **Bankai — Sakashima Yokoshima Happō Fusagari** | Everything living within ~16 blocks (growing to ~32 at SL 20) is inverted — hostile, passive and tamed alike, no exceptions. **Players** get reversed movement *and* a flipped camera. **Mobs** stagger backwards *and* turn on each other instead of on you. |

Leaving the field wears off in about 3 seconds.

---

## Quincy

A Quincy is a different **race**, not a different character — the bow, Blut, Hirenkyaku and
Vollständig are shared by every Quincy, and your **Schrift** (your letter) is what makes you you.

**The Heilig Bogen** is your spirit weapon, and it is the structural twin of a zanpakutō: drawn and
sheathed with `X`, undroppable, kept on death, minted again if it goes missing. Hold right-click to
charge and release to fire an arrow of condensed reishi. It has travel time and drop, so it can be
dodged — and so can you. A draw released too early is refused outright rather than firing a weak
shot, and every shot costs spiritual pressure whatever the draw.

Whacking something with the bow works, badly. It is a club, and deliberately a worse one than a
sword.

**Your two releases** sit on the same keys as everyone else's. `R` is your Schrift's own power. `G`
is **Vollständig** — faster, harder-hitting, wider Hirenkyaku, and an arc of wings behind your
shoulders that everyone can see. It is release 2, so it carries the same loan and claw-back a
Bankai does: entering refills your pool, and leaving takes the surplus back.

**Blut** — `Z` — is a stance, not a release. It stacks on top of whichever release you are in and
drains on top of that drain.

- **Vene** hardens your blood against *everything*, a skeleton's arrow as much as a zanpakutō, and
  slows you down while it is up.
- **Arterie** sharpens what you deal, arrows included.
- Never both. `Z` cycles Vene → Arterie → off.

The SP bar's outline turns blue for Vene and red for Arterie. If you run dry the stance drops
itself rather than pinning you at zero.

**Ambient reishi.** Your pressure regenerates faster where there is more reishi to draw on: open
sky and daylight are the best of it, underground and underwater are worse, and the Nether is worse
still. **Time of day does not matter** — what matters is whether the sky can reach you, so a
Quincy at midnight under open sky regenerates exactly as fast as at noon.

Shinigami are unaffected by all of this: their regeneration is the same everywhere.

---

## For server operators

Ordinary play needs **no permissions at all**. `/bleach guide` and `/bleach help` are open to
everyone; every other `/bleach` subcommand is operator-level debug tooling.

- `/bleach reload` — re-read `config/bleach_mod/tuning.json` live
- `/bleach toggle [true|false]` — master switch (also the `B` key, for operators)
- `/bleach kit set <name>` / `clear`, `/bleach sl set`, `/bleach sp set`, `/bleach spx add`
- `/bleach wsl [set <n>|clear]` — pin or release the World Soul Level
- `/bleach fs`, `flex`, `cleave`, `yama`, `rukia`, `suifeng`, `shinji` — live tuning readouts

Every number in this guide comes from `BleachTuning`, and the in-game guide prints your server's
*current* values — retune `tuning.json`, run `/bleach reload`, and the manual updates with it.
`tuning.json` holds only what you have changed; `tuning-defaults.json` beside it lists every key
and its shipped default. See `BALANCE.md` for the full table.
