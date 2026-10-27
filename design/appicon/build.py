"""把生成的主体图形做成 Android 自适应图标。

自适应图标是 108dp 画布、中间 72dp 安全区（系统会按圆形/方圆裁切），
所以这里把内容缩到画布的 60%，四周留出余量；最终按 5 个密度出 PNG。

输出：
  mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png
  mipmap-{...}/ic_launcher_monochrome.png        纯白剪影（Android 13 主题图标）
  drawable/ic_launcher_background.xml            紫色渐变底
"""
import json
import os

from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
RES = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP = 108
CONTENT_RATIO = 0.54

BACKGROUND_XML = """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:angle="315"
        android:startColor="#8B7CFF"
        android:centerColor="#6C5BFF"
        android:endColor="#4A3ACB"
        android:type="linear" />
</shape>
"""

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
"""


def clean(im):
    im = im.convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < 12 else (255 if v > 245 else v))
    im.putalpha(a)
    return im


def centered(im, size):
    """裁到 alpha 包围盒，按最长边缩到 size*CONTENT_RATIO，居中放到 size 画布。"""
    im = clean(im)
    box = im.getbbox()
    if not box:
        raise RuntimeError("empty artwork")
    im = im.crop(box)
    target = int(size * CONTENT_RATIO)
    w, h = im.size
    scale = target / max(w, h)
    im = im.convert("RGBa").resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.LANCZOS).convert("RGBA")
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(im, ((size - im.size[0]) // 2, (size - im.size[1]) // 2))
    return canvas


def monochrome(fg):
    """主题图标用的纯白剪影：保留形状，把彩色字标（高饱和区域）挖空。"""
    px = fg.load()
    w, h = fg.size
    out = Image.new("RGBA", (w, h), (255, 255, 255, 0))
    dst = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < 100:
                continue
            # 纸面是白/极浅灰（亮度高），字标是近黑（亮度低）→ 只保留亮的部分，字标自然被挖空
            luminance = 0.299 * r + 0.587 * g + 0.114 * b
            if luminance >= 200:
                dst[x, y] = (255, 255, 255, a)
    return out


def preview(fg, path):
    """三种遮罩下的预览：圆形、方圆（自适应默认）、方形。"""
    size = 240
    tile = Image.new("RGBA", (size * 3 + 40, size + 40), (15, 18, 24, 255))
    bg = Image.new("RGBA", (size, size), (108, 91, 255, 255))
    for i in range(size):
        for j in range(size):
            pass
    # 简单的对角渐变底
    grad = Image.new("RGBA", (size, size))
    px = grad.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size)
            px[x, y] = (int(139 - 55 * t), int(124 - 59 * t), int(255 - 40 * t), 255)
    comp = grad.copy()
    comp.alpha_composite(fg.resize((size, size), Image.LANCZOS))
    for idx, mask in enumerate(("circle", "squircle", "square")):
        m = Image.new("L", (size, size), 0)
        d = ImageDraw.Draw(m)
        if mask == "circle":
            d.ellipse((0, 0, size - 1, size - 1), fill=255)
        elif mask == "squircle":
            d.rounded_rectangle((0, 0, size - 1, size - 1), radius=size // 4, fill=255)
        else:
            d.rectangle((0, 0, size - 1, size - 1), fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(comp, (0, 0), m)
        tile.alpha_composite(out, (10 + idx * (size + 10), 20))
    tile.save(path)


def flattened(art, size=512, radius_ratio=0.22):
    """给应用内用的整枚图标（渐变底 + 主体 + 圆角），抽屉标题栏和关于页复用。"""
    grad = Image.new("RGBA", (size, size))
    px = grad.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * size)
            px[x, y] = (int(139 - 55 * t), int(124 - 59 * t), int(255 - 40 * t), 255)
    grad.alpha_composite(centered(art, size))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=int(size * radius_ratio), fill=255
    )
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out


def main():
    picks = {}
    p = os.path.join(HERE, "picks.json")
    if os.path.exists(p):
        picks = json.load(open(p, encoding="utf-8"))
    src_name = picks.get("art", "inverse_00001_.png")
    art_dir = picks.get("dir", "inverse")
    src = os.path.join(RAW, art_dir, src_name)
    art = Image.open(src)
    print("artwork <-", os.path.relpath(src, HERE))

    for name, factor in DENSITIES.items():
        size = int(CANVAS_DP * factor)
        fg = centered(art, size)
        d = os.path.join(RES, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        fg.save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        monochrome(fg).save(os.path.join(d, "ic_launcher_monochrome.png"), optimize=True)
        print("  %-8s %3dpx" % (name, size))

    flat = os.path.join(RES, "drawable-nodpi", "vvfile_app_icon.png")
    flattened(art).save(flat, optimize=True)
    print("  app icon (drawer) 512px ->", os.path.relpath(flat, RES))

    with open(os.path.join(RES, "drawable", "ic_launcher_background.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(BACKGROUND_XML)
    for xml in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(RES, "mipmap-anydpi-v26", xml)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(ADAPTIVE_XML)

    preview(centered(art, 240 * 2), os.path.join(HERE, "preview.png"))
    print("preview -> preview.png")


if __name__ == "__main__":
    main()
