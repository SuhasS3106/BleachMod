"""Import a Blockbench export into the mod's resources.

    python tools/import_model.py models/Asauchi.json asauchi

Takes the raw export and the item name it should render as, and does the three things a Blockbench
file always needs and never has:

  1. Drops "texture_size", which does not exist in the 1.21.1 model format, and checks the UVs
     against it rather than trusting it. Minecraft always maps UV 0-16 across the whole image
     whatever its resolution, so a high-resolution texture usually needs no UV change at all --
     "texture_size": [32, 32] means the PNG is 32 pixels, not that the UVs are in 32-space. UVs are
     only rescaled when one exceeds 16 and so cannot be in Minecraft's space, and the result is
     cross-checked against where the texture is actually painted.
  2. Recentres the geometry on the block centre. Blockbench stands a sword on the floor of the
     block, but Minecraft rotates a held item about the centre, so the offset throws it out of
     frame -- and it cannot be fixed in the pose, because translation applies after rotation.
  3. Repoints the texture ids at bleach_mod:item/<name>, and copies the PNG in beside them. The
     export points at a vanilla block/ path, which resolves to the missing-texture checkerboard.
  4. Writes a display block if the export has none, copied from vanilla item/handheld with only the
     scale derived, so a long blade does not swallow the screen.

The texture is found next to the JSON by reading the export's own texture id, so there is nothing to
pass for it. Pass --texture only if it lives somewhere else.

Anything already correct is left alone: a display block you posed in Blockbench is never overwritten,
and a 16x16 texture has its UVs left untouched.
"""

import argparse
import json
import pathlib
import re
import shutil
import struct
import zlib
import sys

# Minecraft's UV space, always, whatever the texture's real resolution is.
MC_UV = 16.0

RESOURCES = pathlib.Path("src/main/resources/assets/bleach_mod")
FACES = ("north", "east", "south", "west", "up", "down")


def compact(text):
    """Put numeric arrays back on one line.

    json.dumps with an indent explodes every [0, 5.5, 0.75] over five lines, which turns a model
    into a thousand lines of one number each and makes a diff between two poses unreadable.
    """
    return re.sub(r"\[\s*((?:-?[\d.]+,\s*)*-?[\d.]+)\s*\]",
                  lambda m: "[" + ", ".join(p.strip() for p in m.group(1).split(",")) + "]", text)


def png_size(path):
    with open(path, "rb") as handle:
        header = handle.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        sys.exit(f"{path} is not a PNG")
    return struct.unpack(">II", header[16:24])


def png_painted(path):
    """How far the painted pixels reach, as a fraction of the texture's width.

    Used only to sanity-check the UVs. Whether an export's UVs are in Minecraft's space or the
    texture's cannot be told from the numbers alone, but it can be told from where they land: UVs
    that cover the painted area are right, and UVs that cover a corner of it are not.
    """
    data = open(path, "rb").read()
    idat, width, height, colour, depth = b"", 0, 0, 0, 0
    offset = 8
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind, body = data[offset + 4:offset + 8], data[offset + 8:offset + 8 + length]
        if kind == b"IHDR":
            width, height, depth, colour = struct.unpack(">IIBB", body[:10])
        elif kind == b"IDAT":
            idat += body
        offset += 12 + length
    if depth != 8 or colour not in (0, 2, 4, 6):
        return None  # paletted or 16-bit; not worth a decoder, skip the check

    channels = {0: 1, 2: 3, 4: 2, 6: 4}[colour]
    raw, stride = zlib.decompress(idat), width * channels
    rows, previous = [], bytearray(stride)
    for y in range(height):
        filt = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for x in range(stride):
            left = line[x - channels] if x >= channels else 0
            up = previous[x]
            corner = previous[x - channels] if x >= channels else 0
            if filt == 1:
                line[x] = (line[x] + left) & 255
            elif filt == 2:
                line[x] = (line[x] + up) & 255
            elif filt == 3:
                line[x] = (line[x] + (left + up) // 2) & 255
            elif filt == 4:
                pa, pb, pc = abs(up - corner), abs(left - corner), abs(left + up - 2 * corner)
                best = left if pa <= pb and pa <= pc else (up if pb <= pc else corner)
                line[x] = (line[x] + best) & 255
        rows.append(line)
        previous = line

    opaque = channels in (1, 3)
    reach = 0
    for y, line in enumerate(rows):
        for x in range(width):
            if opaque or line[x * channels + channels - 1] > 0:
                reach = max(reach, x + 1, y + 1)
    return reach


def find_texture(model, source, override):
    """Locate the PNG, trusting the filename over the export's own texture id.

    The id is the obvious source and is the less reliable one. A Blockbench project duplicated from
    another model keeps the original's texture id unless it is repointed by hand, and if that other
    model's PNG is still sitting in this folder the id resolves -- to the wrong image, silently. The
    file named after the export is what the person actually put there, so it wins, and a
    disagreement is worth saying out loud rather than resolving quietly.
    """
    if override:
        return pathlib.Path(override)

    def flatten(name):
        return "".join(c for c in name.lower() if c.isalnum())

    by_name = [p for p in source.parent.glob("*.png") if flatten(p.stem) == flatten(source.stem)]

    # Blockbench writes the id as "<folder>/<file>" with no extension.
    by_id = [source.parent / (v.split("/")[-1] + ".png") for v in model.get("textures", {}).values()]
    by_id = [p for p in by_id if p.exists()]

    if by_name:
        if by_id and by_id[0].name != by_name[0].name:
            print(f"  ! export still points at {by_id[0].name}, using {by_name[0].name}"
                  f" (duplicated Blockbench project?)")
        return by_name[0]

    if by_id:
        return by_id[0]

    sys.exit(f"No texture found next to {source} -- pass --texture")


def uv_reach(model):
    """The largest UV coordinate in the model."""
    faces = [f["uv"] for e in model.get("elements", []) for f in e.get("faces", {}).values()
             if "uv" in f]
    return max((max(uv) for uv in faces), default=0.0)


def rescale_uvs(model, factor):
    if factor == 1.0:
        return 0
    rescaled = 0
    for element in model.get("elements", []):
        for name in FACES:
            face = element.get("faces", {}).get(name)
            if not face or "uv" not in face:
                continue
            face["uv"] = [round(v * factor, 4) for v in face["uv"]]
            rescaled += 1
    return rescaled


def bounds(model):
    """Bounding box of the geometry, as (low, high) triples."""
    elements = model.get("elements", [])
    if not elements:
        return [0.0, 0.0, 0.0], [MC_UV, MC_UV, MC_UV]
    low = [min(e["from"][i] for e in elements) for i in range(3)]
    high = [max(e["to"][i] for e in elements) for i in range(3)]
    return low, high


def recentre(model):
    """Move the geometry so its bounding box sits on the block centre.

    Blockbench builds a sword standing on the floor of the block -- hilt at y=0, tip at y=26 --
    so the shape's own centre is at y=13. Minecraft rotates a held item about the block centre
    (8, 8, 8), so that 5-pixel offset becomes a lever: every display rotation swings the sword
    around a point down near the hilt instead of through the middle of the blade, and it flies out
    of frame. Compensating in the translation does not work either, because translation is applied
    after the rotation and so is in the hand's frame, not the model's.

    Moving the geometry is the fix that makes the problem go away rather than cancel out. Elements
    may legally run -16..32, so a 26-tall blade recentres to -5..21 with room to spare, and every
    vanilla display transform then behaves the way it was written to.
    """
    low, high = bounds(model)
    shift = [round(8.0 - (low[i] + high[i]) / 2.0, 4) for i in range(3)]
    if shift == [0.0, 0.0, 0.0]:
        return shift

    for element in model.get("elements", []):
        for key in ("from", "to"):
            element[key] = [round(v + shift[i], 4) for i, v in enumerate(element[key])]
        origin = element.get("rotation", {}).get("origin")
        if origin:
            element["rotation"]["origin"] = [round(v + shift[i], 4) for i, v in enumerate(origin)]

    low, high = bounds(model)
    if min(low) < -16 or max(high) > 32:
        sys.exit(f"Recentred geometry runs {low} .. {high}, outside Minecraft's -16..32 limit")
    return shift


# A vanilla sword's blade runs the diagonal of its 16x16 sprite, so it reads as about 22 pixels
# long. Blades are given a little more reach than that, and anything longer is scaled back to it.
BLADE_LENGTH = 26.0


def display_for(model, roll=0):
    """A starting pose, built on vanilla's own handheld transforms.

    Every number here except the scale is copied from item/handheld and item/generated. Those
    transforms are correct and well tested; they only misbehave on a model whose centre is not the
    block centre, which recentre() has already dealt with. So the only thing left to derive is a
    size, from the model's longest dimension -- a blade at BLADE_LENGTH keeps vanilla's scale
    exactly, and a longer one is pulled back so it does not swallow the screen.

    The one number vanilla cannot supply is the roll. Its transforms turn -90 degrees about Y
    because a sprite item is a flat quad in the XY plane and that is what puts its face to the
    camera; a blade built in the YZ plane -- 1 pixel thick in X, several wide in Z, which is how
    Blockbench lays a sword out -- is a quarter turn off, and comes out held flat-side forward.
    Because the blade's long axis IS Y, the Y component is a pure roll: it spins the sword about
    itself and cannot change where the sword points. So the four quarter turns are safe to try,
    and the right one is whichever puts the cutting edge down.
    """
    low, high = bounds(model)
    longest = max(high[i] - low[i] for i in range(3))

    fit = round(min(1.0, BLADE_LENGTH / max(longest, 1.0)), 3)
    hand = round(0.85 * fit, 3)
    first = round(0.68 * fit, 3)
    gui = round(min(1.0, MC_UV / max(longest, 1.0)), 3)

    return {
        "thirdperson_righthand": {"rotation": [0, roll, 55], "translation": [0, 4, 0.5], "scale": [hand, hand, hand]},
        "thirdperson_lefthand": {"rotation": [0, -roll, -55], "translation": [0, 4, 0.5], "scale": [hand, hand, hand]},
        "firstperson_righthand": {"rotation": [0, roll, 25], "translation": [1.13, 3.2, 1.13], "scale": [first, first, first]},
        "firstperson_lefthand": {"rotation": [0, -roll, -25], "translation": [1.13, 3.2, 1.13], "scale": [first, first, first]},
        "gui": {"rotation": [30, 135, 0], "translation": [0, 0, 0], "scale": [gui, gui, gui]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [gui, gui, gui]},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", help="the Blockbench export, e.g. models/Asauchi.json")
    parser.add_argument("name", help="item name it renders as, e.g. zanpakuto_ichigo_bankai")
    parser.add_argument("--texture", help="override the PNG, if it is not beside the export")
    parser.add_argument("--roll", type=int, default=0, choices=(0, 90, 180, 270),
                        help="quarter turns about the blade's own long axis, if the edge sits wrong")
    args = parser.parse_args()

    source = pathlib.Path(args.source)
    model = json.loads(source.read_text(encoding="utf-8"))
    texture = find_texture(model, source, args.texture)

    width, height = png_size(texture)
    if width != height:
        print(f"  ! {texture.name} is {width}x{height}; UVs assume a square texture")

    # texture_size is a Blockbench field with no meaning to Minecraft, so it is dropped. What it
    # does NOT mean is that the UVs need converting: because Minecraft has no such field, it maps
    # UV 0-16 across the whole image whatever its resolution, so a 32x32 texture with UVs already
    # in 0-16 space is correct untouched. Rescaling those "into" 16-space quarters the model's
    # texture -- which reads as a smeared, wrong-coloured blade rather than as a unit mistake.
    #
    # So the trigger is the UVs themselves, not the declared size. A UV above 16 cannot be in
    # Minecraft's space and must be in the texture's; anything at or below 16 is already valid and
    # is left alone.
    declared = model.pop("texture_size", [width, height])
    reach = uv_reach(model)
    factor = MC_UV / declared[0] if reach > MC_UV else 1.0
    rescaled = rescale_uvs(model, factor)

    # Cross-check against the image: the UVs should cover roughly the painted area. If they land
    # on a fraction of it, the guess above went the wrong way and the blade will be miscoloured.
    painted = png_painted(texture)
    if painted:
        covered = uv_reach(model) * width / MC_UV
        if not 0.75 * painted <= covered <= 1.4 * painted:
            print(f"  ! UVs cover {covered:.0f}px of a texture painted out to {painted}px"
                  f" -- check the UV scale, the model may render miscoloured")

    reference = f"bleach_mod:item/{args.name}"
    model["textures"] = {key: reference for key in model.get("textures", {})} or {"0": reference}

    # Only recentre when generating a pose. A display block posed in Blockbench was tuned against
    # the geometry where it sits, and its Display tab previews the real result -- moving the
    # geometry out from under it would break a pose that was already right.
    posed = "display" in model
    shift = [0.0, 0.0, 0.0]
    if not posed:
        shift = recentre(model)
        model["display"] = display_for(model, args.roll)

    model.pop("credit", None)
    model.pop("format_version", None)

    # Ordered so the file reads the way it is thought about: what it draws with, how it is held,
    # then the geometry that is 90% of the lines.
    ordered = {key: model[key] for key in ("textures", "display", "elements") if key in model}
    ordered.update({k: v for k, v in model.items() if k not in ordered})

    model_out = RESOURCES / "models" / "item" / f"{args.name}.json"
    texture_out = RESOURCES / "textures" / "item" / f"{args.name}.png"
    texture_out.parent.mkdir(parents=True, exist_ok=True)

    model_out.write_text(compact(json.dumps(ordered, indent="\t")) + "\n", encoding="utf-8")
    shutil.copyfile(texture, texture_out)

    print(f"  {source} -> {model_out}")
    print(f"  {texture} -> {texture_out}")
    print(f"  texture {width}x{height}, "
          + (f"{rescaled} UVs rescaled by {factor:g}" if rescaled else "UVs already in 0-16 space, left alone"))
    if posed:
        print("  display: kept from the export")
    else:
        print(f"  display: generated at roll {args.roll}, geometry recentred by {shift}")


if __name__ == "__main__":
    main()
