"""生成启明书院 Android 品牌图标与启动图（无外部素材）。"""
from __future__ import annotations

import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android" / "app" / "src" / "main" / "res"

PAPER = (244, 240, 231)
PAPER_RAISED = (251, 248, 241)
INK = (25, 24, 22)
INK_2 = (98, 93, 84)
ACCENT = (194, 71, 47)
CREAM = (247, 242, 232)
LINE = (214, 205, 191)

SERIF = "C:/Windows/Fonts/simsun.ttc"
SANS = "C:/Windows/Fonts/msyh.ttc"


def rounded(draw: ImageDraw.ImageDraw, box, radius, fill, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def seal(size: int, transparent: bool = True) -> Image.Image:
    """朱印：红色方章 + 纸色横线 + 小圆点。"""
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0) if transparent else PAPER + (255,))
    draw = ImageDraw.Draw(image)
    if not transparent:
        rounded(draw, (0, 0, size - 1, size - 1), int(size * 0.16), PAPER)

    pad = int(size * 0.27)
    box = (pad, pad, size - pad, size - pad)
    rounded(draw, box, int(size * 0.055), ACCENT)
    inset = int(size * 0.018)
    rounded(
        draw,
        (box[0] + inset, box[1] + inset, box[2] - inset, box[3] - inset),
        int(size * 0.045),
        None,
        CREAM + (150,),
        max(1, int(size * 0.006)),
    )

    left = box[0] + int(size * 0.095)
    right = box[2] - int(size * 0.095)
    top = box[1] + int(size * 0.11)
    gap = int(size * 0.075)
    stroke = max(2, int(size * 0.028))
    for index in range(3):
        y = top + index * gap
        width = right - left if index != 1 else int((right - left) * 0.82)
        draw.rounded_rectangle((left, y, left + width, y + stroke), radius=stroke // 2, fill=CREAM)

    dot = int(size * 0.032)
    draw.ellipse((right - dot, box[3] - int(size * 0.10) - dot, right, box[3] - int(size * 0.10)), fill=CREAM + (210,))
    return image


def icon_canvas(size: int, rounded_bg: bool = True) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    if rounded_bg:
        rounded(draw, (0, 0, size - 1, size - 1), int(size * 0.20), PAPER)
        draw.rounded_rectangle(
            (int(size * 0.035), int(size * 0.035), int(size * 0.965), int(size * 0.965)),
            radius=int(size * 0.17),
            outline=LINE + (255,),
            width=max(1, int(size * 0.006)),
        )
    else:
        draw.rectangle((0, 0, size, size), fill=PAPER)
    seal_img = seal(size)
    image.alpha_composite(seal_img, (0, 0))
    return image


def round_icon(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    base = Image.new("RGBA", (size, size), PAPER + (255,))
    draw = ImageDraw.Draw(base)
    draw.ellipse((0, 0, size - 1, size - 1), outline=LINE + (255,), width=max(1, int(size * 0.006)))
    base.alpha_composite(seal(size), (0, 0))
    base.putalpha(mask)
    image.alpha_composite(base, (0, 0))
    return image


def draw_tracked(draw: ImageDraw.ImageDraw, text: str, center_x: int, y: int, font, fill, tracking: int):
    widths = []
    for char in text:
        box = draw.textbbox((0, 0), char, font=font)
        widths.append(box[2] - box[0])
    total = sum(widths) + tracking * (len(text) - 1)
    x = center_x - total / 2
    for char, width in zip(text, widths):
        draw.text((x, y), char, font=font, fill=fill)
        x += width + tracking


def splash(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height), PAPER)
    draw = ImageDraw.Draw(image)
    # 极淡的纸纹与朱砂晕染，避免纯色平板感。
    for index in range(18):
        y = int(height * (index + 1) / 19)
        draw.line((0, y, width, y), fill=(238, 232, 221), width=1)
    draw.ellipse(
        (-int(width * 0.25), -int(height * 0.32), int(width * 0.55), int(height * 0.30)),
        fill=(245, 231, 223),
    )

    unit = min(width, height)
    mark = int(unit * 0.23)
    mark_img = icon_canvas(mark, rounded_bg=False)
    # icon_canvas 的纸底与 splash 同色，直接贴朱印更干净。
    mark_img = seal(mark)
    cx, cy = width // 2, int(height * 0.40)
    image.paste(mark_img, (cx - mark // 2, cy - mark // 2), mark_img)

    title_font = ImageFont.truetype(SERIF, int(unit * 0.072))
    title = "启明书院"
    title_box = draw.textbbox((0, 0), title, font=title_font)
    title_w = title_box[2] - title_box[0]
    draw.text((cx - title_w / 2, cy + mark * 0.78), title, font=title_font, fill=INK)

    sub_font = ImageFont.truetype(SANS, int(unit * 0.022))
    draw_tracked(draw, "LUMIN ACADEMY", cx, cy + mark * 0.78 + int(unit * 0.10), sub_font, INK_2, int(unit * 0.005))

    caption_font = ImageFont.truetype(SANS, int(unit * 0.020))
    caption = "把故事写下去"
    caption_box = draw.textbbox((0, 0), caption, font=caption_font)
    caption_w = caption_box[2] - caption_box[0]
    draw.text((cx - caption_w / 2, cy + mark * 0.78 + int(unit * 0.155)), caption, font=caption_font, fill=INK_2)
    return image


def main():
    # 旧版图标：48 / 72 / 96 / 144 / 192
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        folder = RES / f"mipmap-{density}"
        icon_canvas(size).save(folder / "ic_launcher.png")
        round_icon(size).save(folder / "ic_launcher_round.png")

    # 自适应图标前景：108dp 基准，透明底，只保留朱印。
    for density, size in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        seal(size).save(RES / f"mipmap-{density}" / "ic_launcher_foreground.png")

    splash_sizes = {
        "drawable": (480, 320),
        "drawable-land-mdpi": (480, 320),
        "drawable-land-hdpi": (800, 480),
        "drawable-land-xhdpi": (1280, 720),
        "drawable-land-xxhdpi": (1600, 960),
        "drawable-land-xxxhdpi": (1920, 1280),
    }
    for folder, (w, h) in splash_sizes.items():
        splash(w, h).save(RES / folder / "splash.png")

    for density, (w, h) in {
        "mdpi": (320, 480),
        "hdpi": (480, 800),
        "xhdpi": (720, 1280),
        "xxhdpi": (960, 1600),
        "xxxhdpi": (1280, 1920),
    }.items():
        splash(w, h).save(RES / f"drawable-port-{density}" / "splash.png")

    print("generated icons and splash images")


if __name__ == "__main__":
    main()
