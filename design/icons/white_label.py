"""把图标左下角类型徽标里的黑色字母刷成白色（不重跑模型，纯像素后处理）。

徽标 = 一枚纯色圆角牌 + 黑字。做法：
1. 在左下角窗口里找出现次数最多的"非黑非浅"颜色 → 牌面底色，取一点作种子
2. 从种子做容差洪水填充 → 牌面；再把牌面里的封闭空洞补上（黑字就是洞）
3. 牌面按结构元素闭运算/腐蚀，去掉外圈黑描边，得到"只写字"的内圈
4. 内圈里逐像素求"黑墨覆盖率" t = 1 - p·P / |P|²，再把像素朝白色插值 t 倍
   —— 这样抗锯齿边缘随之过渡，不会出现硬边或黑边

用法：
    python white_label.py            # 就地处理 res/drawable-nodpi/vvfile_ic_*.png
    python white_label.py --preview  # 另外输出 tmp_white_preview.png 对照图
"""
import glob
import os
import sys
from collections import Counter, deque

import numpy as np
from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable-nodpi"))

WHITE = np.array([255.0, 255.0, 255.0], dtype=np.float32)


def _morph(mask, r, grow):
    """方形结构元素的膨胀(grow=True)/腐蚀。"""
    if r <= 0:
        return mask
    im = Image.fromarray(mask.astype(np.uint8) * 255)
    im = im.filter(ImageFilter.MaxFilter(2 * r + 1) if grow else ImageFilter.MinFilter(2 * r + 1))
    return np.asarray(im) > 127


def _flood(ok, seeds):
    """在布尔掩码上做 4 邻域连通填充（BFS，128px 图足够快）。"""
    h, w = ok.shape
    seen = np.zeros_like(ok)
    dq = deque()
    for y, x in seeds:
        if ok[y, x] and not seen[y, x]:
            seen[y, x] = True
            dq.append((y, x))
    while dq:
        y, x = dq.popleft()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and ok[ny, nx] and not seen[ny, nx]:
                seen[ny, nx] = True
                dq.append((ny, nx))
    return seen


def _plate(rgb, alpha):
    """在左下角找徽标牌面，返回 (牌面掩码, 牌面底色)。找不到返回 (None, None)。"""
    h, w = alpha.shape
    win = np.zeros_like(alpha, dtype=bool)
    win[int(h * 0.52):, : int(w * 0.62)] = True
    lum = rgb @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
    cand = win & (alpha > 200) & (lum > 35) & (lum < 185)
    if not cand.any():
        return None, None

    ys, xs = np.where(cand)
    keys = Counter()
    for y, x in zip(ys, xs):
        c = rgb[y, x].astype(int)
        keys[(c[0] // 8, c[1] // 8, c[2] // 8)] += 1
    best = keys.most_common(1)[0][0]
    seeds = [(y, x) for y, x in zip(ys, xs)
             if all(rgb[y, x, i].astype(int) // 8 == best[i] for i in range(3))]
    if not seeds:
        return None, None

    base = rgb[seeds[0]].astype(np.int16)
    ok = (np.abs(rgb.astype(np.int16) - base).max(2) <= 48) & (alpha > 200)
    mask = _flood(ok, seeds)
    if mask.sum() < 80:  # 太小，多半不是徽标
        return None, None
    color = np.median(rgb[mask], axis=0)
    return mask, color


def lighten(im):
    """返回把徽标字刷白后的图像；识别不到徽标就原样返回。"""
    rgba = np.asarray(im.convert("RGBA")).astype(np.float32)
    rgb, alpha = rgba[:, :, :3], rgba[:, :, 3]
    plate, color = _plate(rgb, alpha)
    if plate is None:
        return im

    # 补牌面内部空洞（= 黑字），再闭运算把贴着牌边的笔画收回来
    outside = _flood(~plate, [(0, 0), (0, alpha.shape[1] - 1), (alpha.shape[0] - 1, 0),
                              (alpha.shape[0] - 1, alpha.shape[1] - 1)])
    blob = plate | (~plate & ~outside)
    blob = _morph(blob, 2, grow=True)
    blob = _morph(blob, 2, grow=False)

    # 从牌面边界退 2px，避开外圈黑描边
    inner = _morph(blob, 2, grow=False)

    p = rgb[inner]
    denom = float((color * color).sum()) or 1.0
    t = 1.0 - (p @ color) / denom
    t = np.clip(t, 0.0, 1.0)
    # 覆盖率低的是牌面自身/抗锯齿尾巴，别乱动
    t[t < 0.12] = 0.0
    out = p + t[:, None] * (WHITE - p)

    res = rgba.copy()
    res[inner, 0:3] = out
    res[:, :, 3] = alpha
    return Image.fromarray(np.clip(res, 0, 255).astype(np.uint8), "RGBA")


def main():
    preview = "--preview" in sys.argv[1:]

    def emit(tag, im, x, canvas):
        tile = Image.new("RGBA", (170, 210), (15, 18, 24, 255))
        tile.alpha_composite(im.resize((96, 96), Image.LANCZOS), (37, 14))
        tile.alpha_composite(im.resize((24, 24), Image.LANCZOS), (73, 128))
        canvas.alpha_composite(tile, (x * 170, 0))

    files = sorted(glob.glob(os.path.join(RES, "vvfile_ic_*.png")))
    sheet = None if not preview else Image.new("RGBA", (170 * len(files), 210), (0, 0, 0, 0))
    for i, f in enumerate(files):
        src = Image.open(f)
        dst = lighten(src)
        dst.save(f, optimize=True)
        print("%-24s -> %s" % (os.path.basename(f), "ok" if dst is not src else "skip"))
        if preview:
            emit("new", dst, i, sheet)
    if preview:
        out = os.path.join(HERE, "tmp_white_preview.png")
        sheet.convert("RGB").save(out)
        print("preview ->", out)


if __name__ == "__main__":
    main()
