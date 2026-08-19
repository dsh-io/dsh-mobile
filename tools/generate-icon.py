#!/usr/bin/env python3
"""Generate the dsh-mobile app icon: black rounded background + white terminal
prompt ">_" as SOLID shapes (filled triangle + rounded cursor bar).

Solid shapes survive downscaling far better than thin strokes — thin
strokes with round caps bleed into each other and read as a blur at
48px. Draws at 4x then downsamples with LANCZOS.
Usage:
  ./tools/generate-icon.py <outdir>          # mipmap sizes + 512/1024 brand
  ./tools/generate-icon.py <outdir> <size>   # single size
"""
import os
import sys
from PIL import Image, ImageDraw

BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)

# Design on a 108-unit grid, matching the Android adaptive-icon viewport.
# Safe zone for adaptive icons is the central 66 units (21..87 on the grid).
# Layout: solid ">" triangle pointing right, solid "_" cursor bar after it,
# 8 units of gap between them so they never merge at small sizes.
CHEVRON = [(26, 34), (60, 54), (26, 74)]          # filled triangle
CURSOR = (68, 44, 86, 64)                          # rounded bar (x0,y0,x1,y1)
CURSOR_RADIUS = 6                                  # on the 108 grid


def draw_canvas(size: int, radius_ratio: float = 0.18) -> Image.Image:
    scale = 4
    S = size * scale
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    radius = int(S * radius_ratio)
    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=radius, fill=BLACK)

    u = S / 108.0

    def px(v: float) -> float:
        return v * u

    d.polygon([(px(x), px(y)) for x, y in CHEVRON], fill=WHITE)
    d.rounded_rectangle(
        [px(CURSOR[0]), px(CURSOR[1]), px(CURSOR[2]), px(CURSOR[3])],
        radius=px(CURSOR_RADIUS),
        fill=WHITE,
    )
    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    if len(sys.argv) > 2:
        sizes = [int(sys.argv[2])]
    else:
        sizes = [48, 72, 96, 144, 192, 512, 1024]
    for s in sizes:
        draw_canvas(s).save(os.path.join(outdir, f"icon-{s}.png"))
    print(f"wrote {len(sizes)} icons to {outdir}")


if __name__ == "__main__":
    main()
