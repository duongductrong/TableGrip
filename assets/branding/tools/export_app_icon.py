"""Exports assets/branding/TableGrip.icon into the files the app's packaging uses, under app/src/icons/.

    python3 assets/branding/tools/export_app_icon.py    # needs Xcode 26 (actool and Icon Composer's ictool)

- Assets.car      macOS 26 Liquid Glass icon, looked up through CFBundleIconName
- TableGrip.icns  the same icon flattened, for macOS 12–15 (and the Info.plist's CFBundleIconFile)
- TableGrip.ico   Windows installer, executable and shortcuts
- TableGrip.png   Linux package (512 px)
and app/src/main/resources/icon/TableGrip.png, the window icon Windows and Linux show in the taskbar,
and app/src/main/resources/icon/TableGrip-macOS.png, the same icon on Apple's icon grid (artwork 412 px inside a 512 px
canvas, with a drop shadow) for a copy run from source: the Dock icon (-Xdock:icon) and the About panel. The renders and
the other PNGs fill their whole canvas, which in the Dock looks bigger than every other app.

The outputs are committed, so building the app needs neither Xcode 26 nor this script.
"""
import os, shutil, subprocess, tempfile

BRANDING = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(os.path.dirname(BRANDING))
ICTOOL = "/Applications/Xcode.app/Contents/Applications/Icon Composer.app/Contents/Executables/ictool"
ICO_SIZES = (16, 20, 24, 32, 40, 48, 64, 256)

def render(icon, out):
    """The Default appearance at 1024 px; the smaller sizes are scaled down from it."""
    subprocess.run([ICTOOL, icon, "--export-image", "--output-file", out, "--platform", "macOS", "--rendition", "Default",
                    "--width", "1024", "--height", "1024", "--scale", "1"], check=True, stdout=subprocess.DEVNULL)

def resized(source, size, out):
    subprocess.run(["magick", source, "-filter", "Lanczos", "-resize", f"{size}x{size}", out], check=True)

def mac_grid(source, out):
    """Apple's macOS icon grid at 512 px: the 412 px rounded square centred, over a soft shadow like the system's."""
    subprocess.run(["magick", source, "-filter", "Lanczos", "-resize", "412x412",
                    "(", "+clone", "-background", "black", "-shadow", "30x5+0+5", ")", "+swap",
                    "-background", "none", "-layers", "merge", "+repage",
                    "-gravity", "center", "-extent", "512x512", "-depth", "8", out], check=True)

def main():
    icon = os.path.join(BRANDING, "TableGrip.icon")
    out = os.path.join(ROOT, "app", "src", "icons")
    os.makedirs(out, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["xcrun", "actool", icon, "--compile", tmp, "--app-icon", "TableGrip", "--platform", "macosx",
                        "--minimum-deployment-target", "12.0", "--output-partial-info-plist", os.path.join(tmp, "partial.plist")],
                       check=True, stdout=subprocess.DEVNULL)
        for name in ("Assets.car", "TableGrip.icns"):
            shutil.copyfile(os.path.join(tmp, name), os.path.join(out, name))
        flat = os.path.join(tmp, "flat.png")
        render(icon, flat)
        pngs = []
        for size in ICO_SIZES:
            pngs.append(os.path.join(tmp, f"{size}.png"))
            resized(flat, size, pngs[-1])
        subprocess.run(["magick", *pngs, os.path.join(out, "TableGrip.ico")], check=True)
        resized(flat, 512, os.path.join(out, "TableGrip.png"))
        window = os.path.join(ROOT, "app", "src", "main", "resources", "icon")
        os.makedirs(window, exist_ok=True)
        resized(flat, 256, os.path.join(window, "TableGrip.png"))
        mac_grid(flat, os.path.join(window, "TableGrip-macOS.png"))

if __name__ == "__main__":
    main()
