"""Generates the launcher icon variants from every PNG in icons/.

For each source (a rounded-square app icon on black, any size):
  * the rounded tile is found and its black corners are filled from neighbouring pixels, so the art
    is full-bleed as adaptive icons require;
  * the art is centred on its route (the white line and stop rings) and scaled so the whole route
    stays inside the circular launcher mask (radius 36dp of the 108dp layer), the strictest shape;
  * whatever lies beyond the source is edge-extended and blurred, so no seam shows in other masks.

Outputs (resource names use the file name in snake case, e.g. "Flowing Forest" -> flowing_forest):
  app/src/main/res/mipmap-<density>/ic_launcher_<slug>_foreground.png   adaptive foreground layers
  app/src/main/res/mipmap-anydpi/ic_launcher_<slug>.xml                 adaptive icon
  app/src/main/res/drawable-nodpi/icon_preview_<slug>.png               round preview for Settings

Run from the repository root:  python tools/generate_icons.py   (needs Pillow and numpy)
"""
import glob
import os
import re

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RES = 'app/src/main/res'
LAYER = 432                      # 108dp at xxxhdpi
MASK_RADIUS = 144                # 36dp: the visible circle of the layer
ROUTE_RADIUS = 132               # route and rings must end inside the circle, with a little air
DENSITIES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}


def slug(path):
    return re.sub(r'[^a-z0-9]+', '_', os.path.splitext(os.path.basename(path))[0].lower()).strip('_')


def tile_bounds(img):
    """Bounding box of the icon tile, ignoring the black frame and stray noise along it."""
    dark = img.max(axis=2) < 30
    rows = np.where((~dark).mean(axis=1) > 0.5)[0]
    cols = np.where((~dark).mean(axis=0) > 0.5)[0]
    return rows[0], rows[-1] + 1, cols[0], cols[-1] + 1


def fill_corners(img, radius_ratio=0.24, inset=6):
    """Replaces everything outside the rounded tile (its corners) with the nearest art in the same row."""
    h, w, _ = img.shape
    r = radius_ratio * min(h, w)
    ys, xs = np.mgrid[0:h, 0:w]
    cx = np.clip(xs, r + inset, w - 1 - r - inset)
    cy = np.clip(ys, r + inset, h - 1 - r - inset)
    inside = (xs - cx) ** 2 + (ys - cy) ** 2 <= (r) ** 2
    inside &= (xs >= inset) & (xs < w - inset) & (ys >= inset) & (ys < h - inset)
    # Tiles are not perfectly regular: near-black pixels in the outer band are frame, not art.
    band = (np.minimum(np.minimum(xs, w - 1 - xs), np.minimum(ys, h - 1 - ys)) < 0.15 * min(h, w))
    inside &= ~(band & (img.max(axis=2) < 45))
    out = img.copy()
    cols = np.arange(w)
    for y in range(h):
        valid = np.where(inside[y])[0]
        if 0 < len(valid) < w:
            idx = np.clip(np.searchsorted(valid, cols), 0, len(valid) - 1)
            left = valid[np.clip(idx - 1, 0, len(valid) - 1)]
            right = valid[idx]
            pick = np.where(np.abs(left - cols) < np.abs(right - cols), left, right)
            out[y, ~inside[y]] = img[y, pick[~inside[y]]]
    good = np.where(inside.sum(axis=1) > 0)[0]
    for y in np.where(inside.sum(axis=1) == 0)[0]:
        out[y] = out[good[np.argmin(np.abs(good - y))]]
    return out


def route_geometry(img):
    """Centre and reach of the white route (line and stop rings) in the art. The centre ignores route
    pixels near the tile edge (a line that runs off the tile must not pull it outwards); the reach
    includes them, so stop rings close to the edge still end up inside the mask."""
    h, w, _ = img.shape
    white = img.min(axis=2) > 235

    def inner(margin):
        m = int(min(h, w) * margin)
        masked = white.copy()
        masked[:m, :] = masked[-m:, :] = False
        masked[:, :m] = masked[:, -m:] = False
        return np.nonzero(masked)

    ys, xs = inner(0.09)
    if len(xs) < 50:                                     # no route found: centre the tile
        return np.array([w / 2, h / 2]), min(h, w) * 0.45
    center = np.array([(xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2])
    ys, xs = inner(0.035)
    reach = np.sqrt((xs - center[0]) ** 2 + (ys - center[1]) ** 2).max()
    return center, reach


def without_route(tile):
    """The tile with its route and stops painted over from the surrounding landscape (normalized
    blur of the remaining pixels, computed at low resolution since it is only used blurred)."""
    h, w, _ = tile.shape
    small = 256
    img = Image.fromarray(tile.astype(np.uint8)).resize((small, small), Image.LANCZOS)
    arr = np.asarray(img).astype(np.float32)
    # Route = near-pure white (pale skies are bright but tinted), grown to cover the coloured stop dots.
    route = Image.fromarray((((arr.min(axis=2) > 225) & (arr.max(axis=2) - arr.min(axis=2) < 20)) * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(13))
    keep = 1.0 - np.asarray(route).astype(np.float32) / 255.0
    radius = small * 0.06
    weights = np.asarray(Image.fromarray((keep * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius))).astype(np.float32) / 255.0
    channels = []
    for c in range(3):
        layer = Image.fromarray((arr[:, :, c] * keep).astype(np.uint8)).filter(ImageFilter.GaussianBlur(radius))
        channels.append(np.asarray(layer).astype(np.float32) / np.maximum(weights, 1e-3))
    filled = np.stack(channels, axis=2)
    out = arr * keep[:, :, None] + filled * (1 - keep[:, :, None])
    return np.asarray(Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).resize((w, h), Image.LANCZOS)).astype(np.float32)


def foreground(src):
    img = np.asarray(src.convert('RGB')).astype(np.float32)
    top, bottom, left, right = tile_bounds(img)
    tile = fill_corners(img[top:bottom, left:right])
    size = min(tile.shape[:2])
    center, reach = route_geometry(tile)
    span = LAYER * reach * 1.04 / ROUTE_RADIUS          # source pixels covered by the layer (+4% for ring anti-aliasing)
    span = float(np.clip(span, size * 0.55, size * 1.9))
    pad = int(span)
    # Beyond the art: the landscape without its route (inpainted), mirrored and blurred, so it reads
    # as a soft continuation in any mask shape instead of showing blurred copies of the stops.
    padded = Image.fromarray(np.pad(tile, ((pad, pad), (pad, pad), (0, 0)), mode='symmetric').astype(np.uint8))
    background = np.pad(without_route(tile), ((pad, pad), (pad, pad), (0, 0)), mode='symmetric')
    blurred = Image.fromarray(background.astype(np.uint8)).filter(ImageFilter.GaussianBlur(size * 0.05))
    mask = Image.new('L', padded.size, 0)
    feather = int(size * 0.05)
    mask.paste(255, (pad + feather, pad + feather, pad + tile.shape[1] - feather, pad + tile.shape[0] - feather))
    mask = mask.filter(ImageFilter.GaussianBlur(feather))
    merged = Image.composite(padded, blurred, mask)
    cx, cy = center + pad
    box = (int(cx - span / 2), int(cy - span / 2), int(cx + span / 2), int(cy + span / 2))
    return merged.crop(box).resize((LAYER, LAYER), Image.LANCZOS)


def preview(layer):
    """What a round launcher shows: the visible 72dp circle of the layer."""
    inner = layer.crop((LAYER // 2 - MASK_RADIUS, LAYER // 2 - MASK_RADIUS, LAYER // 2 + MASK_RADIUS, LAYER // 2 + MASK_RADIUS)).resize((192, 192), Image.LANCZOS)
    circle = Image.new('L', (192 * 4, 192 * 4), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, 192 * 4 - 1, 192 * 4 - 1), fill=255)
    out = Image.new('RGBA', (192, 192), (0, 0, 0, 0))
    out.paste(inner, (0, 0), circle.resize((192, 192), Image.LANCZOS))
    return out


ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/generate_icons.py from icons/{name}. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_{slug}_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''


def main():
    sources = sorted(glob.glob('icons/*.png'))
    for path in sources:
        s = slug(path)
        layer = foreground(Image.open(path))
        for density, px in DENSITIES.items():
            d = f'{RES}/mipmap-{density}'
            os.makedirs(d, exist_ok=True)
            layer.resize((px, px), Image.LANCZOS).save(f'{d}/ic_launcher_{s}_foreground.png', optimize=True)
        os.makedirs(f'{RES}/mipmap-anydpi', exist_ok=True)
        with open(f'{RES}/mipmap-anydpi/ic_launcher_{s}.xml', 'w', encoding='utf-8', newline='\n') as f:
            f.write(ADAPTIVE.format(name=os.path.basename(path), slug=s))
        os.makedirs(f'{RES}/drawable-nodpi', exist_ok=True)
        preview(layer).save(f'{RES}/drawable-nodpi/icon_preview_{s}.png', optimize=True)
        print('generated', s)


if __name__ == '__main__':
    main()
