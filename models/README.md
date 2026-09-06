# Adding a Blockbench model

Export from Blockbench into this folder — the `.json` and its `.png`, whatever they are called — then
run one command:

```
python tools/import_model.py models/<YourExport>.json <item_name>
```

That is the whole procedure. It copies both files into `src/main/resources/assets/bleach_mod/`,
rescales the UVs, repoints the texture, and writes a display pose. Then `./gradlew runClient` and
look at it; **F3+T** reloads models without restarting.

```
python tools/import_model.py models/Asauchi.json asauchi
python tools/import_model.py models/zanpakto_ichigo_bankai.json zanpakuto_ichigo_bankai
```

## What `<item_name>` has to be

The filename *is* the wiring — Minecraft looks up an item's model by its registered id, with no table
in between. So the name must be one of the slots that already exist:

```
asauchi                reforged_asauchi
zanpakuto_ichigo       zanpakuto_yamamoto     zanpakuto_rukia
zanpakuto_suifeng      zanpakuto_shinji       zanpakuto_tosen
zanpakuto_aizen        zanpakuto_gin
```

...or a **released form**, which is a new name and needs one extra step — see below.

## Released forms

Every blade is the Asauchi's shape until it is released; that reveal is most of a character's visual
identity and is worth keeping. So a kit's own model file only says which model to swap to, and when:

```json
{
	"parent": "bleach_mod:item/asauchi",
	"overrides": [
		{"predicate": {"bleach_mod:released": 1}, "model": "bleach_mod:item/zanpakuto_ichigo_bankai"}
	]
}
```

`bleach_mod:released` is **0 base, 0.5 Shikai, 1 Bankai** — halves, not 0/1/2, because Minecraft
clamps predicate values into `[0, 1]` and 1 and 2 would both come out as 1. So the predicate for a
Shikai model is `0.5` and for a Bankai model is `1`.

To add one: import the model under a new name, then add an `overrides` entry to that kit's file.
`zanpakuto_ichigo.json` is the worked example. Nothing in Java changes.

## If you want to know what the script is doing

- **Checks the UVs, and usually leaves them alone.** Blockbench writes `"texture_size": [32, 32]`;
  Minecraft has no such field — it does not exist in the 1.21.1 model format. What that means is
  that Minecraft *always* stretches UV 0–16 across the whole image, whatever its resolution, so a
  32×32 texture with UVs already in 0–16 space is correct exactly as exported. `texture_size` is
  telling you the PNG is 32 pixels, not that the UVs are in 32-space. UVs are rescaled only when one
  exceeds 16 and so cannot be a Minecraft UV, and the result is then checked against where the
  texture is actually painted — if the UVs cover a corner of the artwork instead of the artwork,
  you get a warning. Get this backwards and a quarter of the texture is smeared over the whole
  blade, which looks like a bad export rather than a unit mistake.
- **Repoints the texture** at `bleach_mod:item/<name>` and copies the PNG in. The export points at a
  vanilla `block/` path, which resolves to the purple checkerboard.
- **Recentres the geometry** on the block centre, and only then writes a display block. Blockbench
  builds a sword standing on the floor of the block, hilt at y=0 — so a 26-tall blade has its centre
  at y=13, while Minecraft rotates a held item about y=8. That 5-pixel offset is a lever: every
  display rotation swings the sword around a point near the hilt instead of through the blade, and
  it leaves the screen. The geometry is moved instead of the pose being fudged, because translation
  is applied *after* rotation and so cannot cancel a model-space offset. Elements may run −16..32,
  so there is plenty of room.
- **Writes a display block** if you didn't pose one, copied from vanilla `item/handheld` with only
  the scale derived — a blade longer than 26 pixels is pulled back so it does not swallow the
  screen. If you *did* pose one in Blockbench it is kept, and the geometry is left where you posed
  it against.

## If the blade is held at the wrong angle

If the sword is held flat-side forward, or the cutting edge is up instead of down, that is the
**roll** — how far the blade is turned about its own length. Re-run with a quarter turn:

```
python tools/import_model.py models/Asauchi.json asauchi --roll 90
```

`0`, `90`, `180`, `270` are the four options and one of them is right. This is safe to fiddle with:
because a blade's long axis is Y and the roll is the Y part of the rotation, it spins the sword
about itself and *cannot* change where the sword points or where it sits in the hand. Only which
face you are looking at.

**The rest of the generated pose is a starting point, not a verified one.** If it sits wrong in the
fist in some other way, pose it properly in Blockbench's **Display** tab and re-export — the script
keeps a `display` block that is already there and will not overwrite your work.

## Gotchas

- Geometry may run from **−16 to 32** on each axis, so a 29-tall blade is fine. Outside that the
  model fails to load, with a log line.
- **Do not add `"parent": "minecraft:item/handheld"`** to a model with elements. You would inherit
  `gui_light: front` — the flat-sprite lighting mode, which makes a 3D model look like a paper
  cut-out — plus display transforms built for a diagonal 2D sprite.
- Element rotations are limited to **±45° in 22.5° steps, one axis**. Blockbench enforces this in the
  Java Block/Item format but not if you converted from another format.
- `reforged_asauchi` still uses the vanilla iron sword sprite. If you want it to share the Asauchi
  model, its file can just be `{"parent": "bleach_mod:item/asauchi"}`.
