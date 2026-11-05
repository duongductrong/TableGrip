"""Builds the app icon's Icon Composer document and renders its macOS appearances with ictool.

    python3 assets/branding/tools/build_icon.py     # writes TableGrip.icon and renders/*.png next to tools/

Run export_app_icon.py afterwards to update the files the app's packaging uses.
"""
import json, math, os, shutil, subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON = os.path.join(ROOT, "TableGrip.icon")
RENDERS = os.path.join(ROOT, "renders")
ICTOOL = "/Applications/Xcode.app/Contents/Applications/Icon Composer.app/Contents/Executables/ictool"
W, C = 1024, 512

BLUE = "srgb:0.03922,0.47843,1.00000,1.00000"      # #0A7AFF
BLACK = "srgb:0.04706,0.04706,0.05098,1.00000"     # #0C0C0D
WHITE = "srgb:1.00000,1.00000,1.00000,1.00000"
PEARL = "srgb:0.92157,0.92941,0.94902,1.00000"     # #EBEDF2

# Background grid: pitch 192 with a line either side of the centre cell, so one cell sits exactly in the middle.
PITCH, LW = 192, 14
LINES = [416 + PITCH * k for k in range(-3, 4) if 0 < 416 + PITCH * k < W]
CELL0, CELL1 = 416 + LW / 2, 608 - LW / 2

# The G: a ring segment open at the top right, and a crossbar that runs from the ring towards the centre cell, which
# sits raised on its own: the G has hold of one cell of the table.
R, SW, START = 282, 116, -38
RO, RI = R + SW / 2, R - SW / 2
GAP = 22                 # between the crossbar and the cell
GLYPH_SCALE = 0.86       # leaves the margin Apple's glyphs keep; the grid stays full-bleed

def svg(body): return f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{W}" viewBox="0 0 {W} {W}">{body}</svg>\n'
def pt(r, deg): return C + r * math.cos(math.radians(deg)), C + r * math.sin(math.radians(deg))

def grid():
    rects = "".join(f'<rect x="{p - LW / 2}" y="0" width="{LW}" height="{W}"/><rect x="0" y="{p - LW / 2}" width="{W}" height="{LW}"/>' for p in LINES)
    return svg(f'<g fill="#FFFFFF">{rects}</g>')

def grip():
    (ox, oy), (ix, iy) = pt(RO, START), pt(RI, START)
    ring = (f'M{ox:.2f},{oy:.2f} A{RO},{RO} 0 1 0 {C + RO},{C} L{C + RI},{C} '
            f'A{RI},{RI} 0 1 1 {ix:.2f},{iy:.2f} Z')
    bar = f'M{CELL1 + GAP},{C - SW / 2} H{C + RO} V{C + SW / 2} H{CELL1 + GAP} Z'
    return svg(f'<path fill="#FFFFFF" d="{ring}"/><path fill="#FFFFFF" d="{bar}"/>')

def cell():
    return svg(f'<rect x="{CELL0}" y="{CELL0}" width="{CELL1 - CELL0}" height="{CELL1 - CELL0}" rx="24" fill="#FFFFFF"/>')

def fill(default, dark): return [{"value": {"solid": default}}, {"appearance": "dark", "value": {"solid": dark}}]
def layer(name, scale=1, **extra): return {"name": name, "image-name": f"{name}.svg", "position": {"scale": scale, "translation-in-points": [0, 0]}, **extra}

def glass_group(name, layer_, shadow="neutral", translucency=None):
    return {"name": name, "layers": [layer_], "shadow": {"kind": shadow, "opacity": 0.6 if shadow == "layer-color" else 0.5},
            "translucency": {"enabled": translucency is not None, "value": translucency or 0}, "specular": True}

ASSETS = {"grid": grid, "grip": grip, "cell": cell}
DOC = {
    "fill-specializations": fill(BLUE, BLACK),
    # Groups run front to back.
    "groups": [
        glass_group("Cell", layer("cell", GLYPH_SCALE, glass=True, **{"fill-specializations": fill(WHITE, BLUE)}), "layer-color"),
        glass_group("Grip", layer("grip", GLYPH_SCALE, glass=True, **{"fill-specializations": fill(WHITE, PEARL)}), translucency=0.2),
        {"name": "Grid", "layers": [layer("grid", glass=False, fill={"solid": WHITE},
                                          **{"opacity-specializations": [{"value": 0.11}, {"appearance": "dark", "value": 0.07}]})],
         "shadow": {"kind": "none", "opacity": 0}, "translucency": {"enabled": False, "value": 0}},
    ],
    "supported-platforms": {"squares": ["macOS"]},
}
RENDITIONS = ("Default", "Dark", "ClearLight", "ClearDark", "TintedLight", "TintedDark")

def main():
    shutil.rmtree(ICON, ignore_errors=True)
    os.makedirs(os.path.join(ICON, "Assets"))
    for name, make in ASSETS.items():
        with open(os.path.join(ICON, "Assets", f"{name}.svg"), "w") as f: f.write(make())
    with open(os.path.join(ICON, "icon.json"), "w") as f: json.dump(DOC, f, indent=2)
    os.makedirs(RENDERS, exist_ok=True)
    for rendition in RENDITIONS:
        subprocess.run([ICTOOL, ICON, "--export-image", "--output-file", os.path.join(RENDERS, f"{rendition}.png"),
                        "--platform", "macOS", "--rendition", rendition, "--width", "1024", "--height", "1024", "--scale", "1"],
                       check=True, stdout=subprocess.DEVNULL)

if __name__ == "__main__":
    main()
