"""把 raw/ 里生成的 1024px 原图规整成 App 用的 128px 图标，并出一张带编号的对照表。

规整内容：
1. 清掉 alpha<10 的噪点、把 alpha>250 归一到 255（模型给的边缘有 1~4 的毛边）
2. 补一圈**统一的黑描边**：模型时有时无，靠它保证整套图标线宽一致
3. 按 alpha 包围盒裁切，统一放进 128x128 画布，最长边占 112px（87.5%），居中
4. 缩放走预乘 alpha，避免半透明边缘混出黑边
"""
import json
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
RES = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable-nodpi"))
PREVIEW = os.path.join(HERE, "preview_sheet.png")

SIZE = 128
INNER = int(SIZE * 0.95)
# 1024px 原图上补 7px 描边，折到 128px 大约是 0.9px，24dp 下就是一条实线
STROKE = 18


def clean(im):
    im = im.convert("RGBA")
    a = im.getchannel("A").point(lambda v: 0 if v < 10 else (255 if v > 250 else v))
    im.putalpha(a)
    return im


def outline(im, r=STROKE):
    """在剪影外面补一圈纯黑描边；图案内部不动。"""
    solid = im.getchannel("A").point(lambda v: 255 if v > 40 else 0)
    grown = solid.filter(ImageFilter.MaxFilter(r * 2 + 1))
    base = Image.new("RGBA", im.size, (0, 0, 0, 255))
    base.putalpha(grown)
    base.alpha_composite(im)
    return base


def normalize(im):
    im = outline(clean(im))
    box = im.getbbox()
    if not box:
        raise RuntimeError("empty image")
    im = im.crop(box)
    w, h = im.size
    side = max(w, h)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(im, ((side - w) // 2, (side - h) // 2))
    # 预乘 alpha 再缩放，半透明边缘不会渗黑
    small = square.convert("RGBa").resize((INNER, INNER), Image.LANCZOS).convert("RGBA")
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    off = (SIZE - INNER) // 2
    canvas.alpha_composite(small, (off, off))
    return canvas


def candidates(name):
    d = os.path.join(RAW, name)
    return [os.path.join(d, f) for f in sorted(os.listdir(d)) if f.endswith(".png")]


def pick(name):
    """同名多张时按 picks.json 选（下标），没指定就用最后一张。"""
    files = candidates(name)
    if not files:
        raise RuntimeError("no candidates for " + name)
    idx = {}
    p = os.path.join(HERE, "picks.json")
    if os.path.exists(p):
        idx = json.load(open(p, encoding="utf-8"))
    i = idx.get(name)
    return (files[i] if i is not None else files[-1]), i


def main():
    names = sorted(os.listdir(RAW)) if os.path.isdir(RAW) else []
    only = sys.argv[1:]
    if only:
        names = [n for n in names if n in only]
    os.makedirs(RES, exist_ok=True)
    made = []
    for name in names:
        src, idx = pick(name)
        out = os.path.join(RES, "vvfile_ic_%s.png" % name)
        normalize(Image.open(src)).save(out, optimize=True)
        made.append((name, os.path.basename(src), os.path.getsize(out)))
    for name, fn, size in made:
        print("%-8s <- %-22s %5d B" % (name, fn, size))

    # 对照表：上排 96px，下排真实 24dp；深色/浅色底各一行
    if made:
        cols = len(made)
        cw, ch = 150, 200
        sheet = Image.new("RGBA", (cols * cw, ch * 2), (0, 0, 0, 0))
        for i, (name, _, _) in enumerate(made):
            ic = Image.open(os.path.join(RES, "vvfile_ic_%s.png" % name))
            for row, bg in ((0, (15, 18, 24, 255)), (1, (247, 248, 250, 255))):
                tile = Image.new("RGBA", (cw, ch), bg)
                tile.alpha_composite(ic.resize((92, 92), Image.LANCZOS), ((cw - 92) // 2, 16))
                tile.alpha_composite(ic.resize((24, 24), Image.LANCZOS), ((cw - 24) // 2, 140))
                sheet.alpha_composite(tile, (i * cw, row * ch))
        sheet.save(PREVIEW)
        print("preview ->", PREVIEW)


if __name__ == "__main__":
    main()
