#!/usr/bin/env python3
"""Build the README's pictures and clips from raw device captures.

Usage: scripts/fork/readme_media.py <raw dir>

<raw dir> holds full-resolution screenshots named as in SHOTS below (adb exec-out screencap -p) and
two screen recordings (adb shell screenrecord): tablet_scroll.mp4, phone_lookup.mp4. Output goes to
docs/fork/media/: device-framed WebP pictures, the header image, a 1280x640 social preview and two
animated WebP clips, all sized for GitHub's README column at 2x. Needs Pillow, ffmpeg and the Noto
CJK fonts (Fedora: google-noto-serif-cjk-vf-fonts, google-noto-sans-cjk-vf-fonts).

System bars are painted over with the row next to them, so notification icons never reach the
repository. Re-shoot guide: tablet in landscape, phone in portrait, the reader full screen.
"""
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/fork/media"
SERIF = "/usr/share/fonts/google-noto-serif-cjk-vf-fonts/NotoSerifCJK-VF.ttc"
SANS = "/usr/share/fonts/google-noto-sans-cjk-vf-fonts/NotoSansCJK-VF.ttc"
ICON = ROOT / "app/src/nightly/res/mipmap-xxxhdpi/ic_launcher.webp"

INK, INK_SOFT, VERMILION = (35, 31, 27), (98, 86, 72), (164, 58, 42)
PAPER_TOP, PAPER_BOTTOM = (248, 243, 234), (238, 228, 210)

# raw name -> (output name, device, paint over system bars, output width)
SHOTS = {
    "P_lookup": ("phone-lookup", "phone", False, 540),
    "P_page": ("phone-page", "phone", False, 540),
    "P_settings": ("phone-reader-settings", "phone", True, 540),
    "P_dict": ("phone-dictionary", "phone", True, 540),
    "P_darkv": ("phone-vertical-dark", "phone", False, 540),
    "P_intro": ("phone-first-open", "phone", False, 540),
    "T_lookup": ("tablet-lookup", "tablet", False, 1400),
    "T_page": ("tablet-vertical", "tablet", False, 1400),
    "T_dark": ("tablet-vertical-dark", "tablet", False, 1400),
    "T_japanese": ("tablet-settings", "tablet", True, 1400),
    "T_stats": ("tablet-statistics", "tablet", True, 1400),
}
# Rows (at capture resolution) holding the status bar and the navigation bar or taskbar.
BARS = {"phone": ((0, 92), (2206, None)), "tablet": ((0, 60), (1350, None))}
FRAME = {"phone": dict(radius=72, bezel=34), "tablet": dict(radius=40, bezel=46)}


def font(path, size, weight="Regular"):
    f = ImageFont.truetype(path, size, index=0)
    f.set_variation_by_name(weight)
    return f


def paint_over_bars(im, device):
    w, h = im.size
    for top, bottom in BARS[device]:
        bottom = bottom or h
        src = bottom if top == 0 else top - 1
        im.paste(im.crop((0, src, w, src + 1)).resize((w, bottom - top)), (0, top))
    return im


def rounded(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def framed(screen, device, hole=False):
    """The screen inside a plain dark bezel with a faint rim, so it reads on light and dark pages.
    hole=True leaves the screen transparent, for laying a video underneath."""
    r, b = FRAME[device]["radius"], FRAME[device]["bezel"]
    w, h = screen.size
    body = Image.new("RGBA", (w + 2 * b, h + 2 * b), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((0, 0, body.width - 1, body.height - 1), r + b, fill=(28, 29, 33, 255))
    d.rounded_rectangle((1, 1, body.width - 2, body.height - 2), r + b - 1, outline=(78, 80, 88, 255), width=3)
    if hole:
        body.paste((0, 0, 0, 0), (b, b), rounded((w, h), r))
    else:
        body.paste(screen.convert("RGBA"), (b, b), rounded((w, h), r))
    return body


def with_shadow(img, blur=38, dy=22, alpha=90):
    """img on a transparent canvas grown by shadow_pad(blur, dy) on every side, with a soft shadow."""
    pad = shadow_pad(blur, dy)
    canvas = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", img.size, (20, 14, 8, alpha))
    canvas.paste(shadow, (pad, pad + dy), img.split()[3])
    canvas = canvas.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(img, (pad, pad))
    return canvas


def shadow_pad(blur, dy):
    return blur * 3 + dy


def fit_width(img, width):
    return img.resize((width, round(img.height * width / img.width)), Image.LANCZOS)


def paper(size):
    w, h = size
    top, bottom = PAPER_TOP, PAPER_BOTTOM
    grad = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / (h - 1)
        grad.putpixel((0, y), tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return grad.resize((w, h)).convert("RGBA")


def device_image(raw, name, device, bars=False):
    im = Image.open(raw / f"{name}.png").convert("RGB")
    return framed(paint_over_bars(im, device) if bars else im, device)


def header(raw):
    W, H = 2560, 1280
    canvas = paper((W, H))
    d = ImageDraw.Draw(canvas)
    icon = Image.open(ICON).convert("RGBA").resize((150, 150), Image.LANCZOS)
    canvas.alpha_composite(icon, (150, 250))
    d.text((146, 420), "Reikai JP", font=font(SERIF, 150, "Bold"), fill=INK)
    d.text((152, 628), "読む。引く。覚える。", font=font(SERIF, 64, "Medium"), fill=VERMILION)
    sans = font(SANS, 42, "Regular")
    d.text((152, 752), "A manga and light-novel reader", font=sans, fill=INK_SOFT)
    d.text((152, 810), "for reading Japanese on Android.", font=sans, fill=INK_SOFT)
    x, chip = 152, font(SANS, 29, "Medium")
    for label in ("Yomitan lookup", "Anki mining", "Vertical text"):
        tw = d.textlength(label, font=chip)
        d.rounded_rectangle((x, 918, x + tw + 48, 980), 31, outline=(196, 180, 154), width=3, fill=(251, 247, 240))
        d.text((x + 24, 928), label, font=chip, fill=INK)
        x += tw + 48 + 18
    tablet = with_shadow(fit_width(device_image(raw, "T_page", "tablet"), 1300), blur=40, dy=26)
    phone = with_shadow(fit_width(device_image(raw, "P_lookup", "phone"), 470), blur=36, dy=24)
    canvas.alpha_composite(tablet, (1200 - shadow_pad(40, 26), 215 - shadow_pad(40, 26)))
    canvas.alpha_composite(phone, (1010 - shadow_pad(36, 24), 150 - shadow_pad(36, 24)))
    canvas = canvas.convert("RGB")
    fit_width(canvas, 1800).save(OUT / "header.webp", quality=88, method=6)
    fit_width(canvas, 1280).save(OUT / "social-preview.jpg", quality=90, optimize=True)


def clip(raw, video, device, out, screen_w, start, end, crop_bottom=0, fps=15, quality=62):
    """A screen recording inside the same bezel, on a paper card."""
    probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=width,height", "-of", "csv=p=0", str(raw / video)],
                           capture_output=True, text=True, check=True).stdout.strip().split(",")
    vw, vh = int(probe[0]), int(probe[1]) - crop_bottom
    screen_h = round(vh * screen_w / vw) // 2 * 2
    scale = screen_w / {"phone": 1080, "tablet": 2304}[device]
    r, b = (round(FRAME[device][k] * scale * 1.6) for k in ("radius", "bezel"))
    body = Image.new("RGBA", (screen_w + 2 * b, screen_h + 2 * b), (0, 0, 0, 0))
    dd = ImageDraw.Draw(body)
    dd.rounded_rectangle((0, 0, body.width - 1, body.height - 1), r + b, fill=(28, 29, 33, 255))
    dd.rounded_rectangle((1, 1, body.width - 2, body.height - 2), r + b - 1, outline=(78, 80, 88, 255), width=2)
    body.paste((0, 0, 0, 0), (b, b), rounded((screen_w, screen_h), r))
    margin = 48
    card = paper((body.width + 2 * margin, body.height + 2 * margin))
    card.alpha_composite(with_shadow(body, blur=12, dy=8, alpha=70), (margin - shadow_pad(12, 8),) * 2)
    with tempfile.TemporaryDirectory() as tmp:
        bg, fg = Path(tmp) / "bg.png", Path(tmp) / "fg.png"
        card.convert("RGB").save(bg)
        overlay = Image.new("RGBA", card.size, (0, 0, 0, 0))
        overlay.alpha_composite(body, (margin, margin))
        overlay.save(fg)
        cw, ch = card.size
        subprocess.run([
            "ffmpeg", "-y", "-v", "error", "-i", str(raw / video),
            "-loop", "1", "-i", str(bg), "-loop", "1", "-i", str(fg), "-filter_complex",
            # screenrecord writes a frame only when the screen changes: fps first repeats the last
            # one, so the cut never starts on an empty screen
            f"[0]fps={fps},trim=start={start}:end={end},setpts=PTS-STARTPTS,"
            f"crop={vw}:{vh}:0:0,scale={screen_w}:{screen_h}[v];"
            f"[1][v]overlay={margin + b}:{margin + b}:shortest=1[a];[a][2]overlay=0:0:shortest=1,"
            f"crop={cw // 2 * 2}:{ch // 2 * 2}:0:0",
            "-c:v", "libwebp_anim", "-loop", "0", "-quality", str(quality), "-compression_level", "6",
            "-an", str(OUT / out)], check=True)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    raw = Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (out, device, bars, width) in SHOTS.items():
        img = with_shadow(fit_width(device_image(raw, name, device, bars), width), blur=24 if device == "phone" else 30,
                          dy=14, alpha=80)
        img.save(OUT / f"{out}.webp", quality=86, method=6, alpha_quality=90)
    header(raw)
    clip(raw, "tablet_scroll.mp4", "tablet", "clip-tablet-scroll.webp", 880, 1.3, 10.3, crop_bottom=26)
    clip(raw, "phone_lookup.mp4", "phone", "clip-phone-lookup.webp", 330, 1.0, 10.5)
    for f in sorted(OUT.iterdir()):
        print(f"{f.stat().st_size / 1024:7.0f} KB  {f.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
