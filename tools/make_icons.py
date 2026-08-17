"""Regenerate the launcher icons from a single square source image.

    python tools/make_icons.py path/to/logo.png

Needs Pillow once:  pip install pillow

Writes every density bucket Android wants, plus the inset foreground layer used
by adaptive icons, and prints the background colour to paste into colors.xml.

Source images with transparency are used as-is. Ones drawn on a white field are
cropped to the artwork and given rounded corners as real transparency, since a
launcher will otherwise show white corners around the icon.
"""
import os
import sys
from PIL import Image, ImageDraw

# Legacy icons (API 24-25) and adaptive foreground canvases (26+).
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# How much of the adaptive canvas the artwork may fill. Launchers mask a 108dp
# canvas down to roughly a 66dp circle, so artwork that reaches the corners gets
# clipped. Lower this if your logo has detail near its edges.
ART_RATIO = 256 / 432

# Corner rounding applied to opaque sources, as a fraction of the width.
CORNER_RADIUS = 0.21

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")


def load(path):
    """Returns the artwork as a square RGBA image with transparent surroundings."""
    image = Image.open(path)

    if image.mode in ("RGBA", "LA") and image.getchannel("A").getextrema()[0] < 255:
        # Already has transparency: trust it and crop to the visible pixels.
        image = image.convert("RGBA")
        box = image.getchannel("A").getbbox()
        art = image.crop(box) if box else image
        side = max(art.size)
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.paste(art, ((side - art.size[0]) // 2, (side - art.size[1]) // 2))
        return square

    # Opaque source: crop the flat surround, then cut rounded corners.
    image = image.convert("RGB")
    mono = image.convert("L").point(lambda p: 255 if p < 245 else 0)
    box = mono.getbbox()
    art = image.crop(box) if box else image

    side = max(art.size)
    square = Image.new("RGB", (side, side), (255, 255, 255))
    square.paste(art, ((side - art.size[0]) // 2, (side - art.size[1]) // 2))

    supersample = 4
    mask = Image.new("L", (side * supersample, side * supersample), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, side * supersample - 1, side * supersample - 1],
        radius=int(CORNER_RADIUS * side * supersample),
        fill=255,
    )
    result = square.convert("RGBA")
    result.putalpha(mask.resize((side, side), Image.LANCZOS))
    return result


def background_colour(icon):
    """Samples the artwork's own backdrop so the adaptive background matches."""
    side = icon.size[0]
    patch = icon.convert("RGB").crop(
        (int(side * 0.05), int(side * 0.30), int(side * 0.12), int(side * 0.48))
    )
    r, g, b = patch.resize((1, 1), Image.LANCZOS).getpixel((0, 0))
    return f"#{r:02X}{g:02X}{b:02X}"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1

    source = sys.argv[1]
    if not os.path.isfile(source):
        print(f"No such file: {source}")
        return 1

    icon = load(source)
    print(f"artwork {icon.size[0]}px square")

    for bucket, size in LEGACY.items():
        folder = os.path.join(RES, f"mipmap-{bucket}")
        os.makedirs(folder, exist_ok=True)
        icon.resize((size, size), Image.LANCZOS).save(
            os.path.join(folder, "ic_launcher.png"), optimize=True
        )

    for bucket, canvas in FOREGROUND.items():
        folder = os.path.join(RES, f"mipmap-{bucket}")
        os.makedirs(folder, exist_ok=True)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        art_size = int(round(canvas * ART_RATIO))
        scaled = icon.resize((art_size, art_size), Image.LANCZOS)
        offset = (canvas - art_size) // 2
        layer.paste(scaled, (offset, offset), scaled)
        layer.save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)

    print(f"wrote {len(LEGACY) + len(FOREGROUND)} files under app/src/main/res/mipmap-*/")
    print()
    print("Set this in app/src/main/res/values/colors.xml:")
    print(f'    <color name="launcher_background">{background_colour(icon)}</color>')
    return 0


if __name__ == "__main__":
    sys.exit(main())
