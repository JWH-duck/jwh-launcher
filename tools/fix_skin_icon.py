# -*- coding: utf-8 -*-
"""
单独修复皮肤库 T恤导航图标：
  - 从原图按亮度阈值分离黑色轮廓，去除棋盘格背景
  - 连通域只保留中央最大的轮廓块，剔除边缘孤立杂点，裁剪框贴紧主体
  - 主体按最大边对齐到 52，放大到与其余导航图标一致的占比
  - 缩放后对 alpha 通道做一次 MaxFilter 膨胀，把轮廓线条加粗到与房子/下载/齿轮一致
输出覆盖 src/main/resources/icons/nav_skin.png
"""
from PIL import Image, ImageFilter
import os
import collections

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\343ab7dc-5b09-483a-a0c1-602865f361ff.png"
OUT = os.path.join(PROJ, "src", "main", "resources", "icons", "nav_skin.png")

SIZE = 64
CONTENT = 52
DARK = 100
LIGHT = 150
DILATE = 1
WORK = 768  # 连通域定位用的缩小边长，提速


def largest_component(alpha, w, h):
    """返回最大连通前景块的坐标集合（4 邻域）。"""
    visited = [[False] * w for _ in range(h)]
    best = []
    for sy in range(h):
        for sx in range(w):
            if alpha[sy][sx] <= 40 or visited[sy][sx]:
                continue
            q = collections.deque([(sx, sy)])
            visited[sy][sx] = True
            comp = []
            while q:
                x, y = q.popleft()
                comp.append((x, y))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h and not visited[ny][nx] and alpha[ny][nx] > 40:
                        visited[ny][nx] = True
                        q.append((nx, ny))
            if len(comp) > len(best):
                best = comp
    return best


def main():
    im = Image.open(SRC).convert("RGB")
    # 缩小到 WORK 边长做分离与连通域，足够出 64 图标
    scale = WORK / max(im.size)
    im = im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
    w, h = im.size
    px = im.load()

    alpha = [[0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            mx = max(r, g, b)
            if mx > LIGHT:
                a = 0
            elif mx < DARK:
                a = 255
            else:
                a = int(255 * (LIGHT - mx) / (LIGHT - DARK))
            alpha[y][x] = a

    comp = largest_component(alpha, w, h)
    if not comp:
        raise RuntimeError("未识别到 T恤主体")
    minx = min(x for x, _ in comp)
    maxx = max(x for x, _ in comp)
    miny = min(y for _, y in comp)
    maxy = max(y for _, y in comp)

    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    for x, y in comp:
        op[x, y] = (0, 0, 0, alpha[y][x])
    crop = out.crop((minx, miny, maxx + 1, maxy + 1))

    # 按最大边对齐 CONTENT，另一方向等比
    side = max(crop.width, crop.height)
    nw = round(CONTENT * crop.width / side)
    nh = round(CONTENT * crop.height / side)
    crop = crop.resize((nw, nh), Image.LANCZOS)

    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.paste(crop, ((SIZE - nw) // 2, (SIZE - nh) // 2), crop)

    if DILATE > 0:
        a = canvas.split()[3]
        for _ in range(DILATE):
            a = a.filter(ImageFilter.MaxFilter(3))
        black = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        black.putalpha(a)
        canvas = black

    canvas.save(OUT)
    bbox = canvas.split()[3].point(lambda v: 255 if v > 8 else 0).getbbox()
    pxn = sum(1 for v in canvas.split()[3].getdata() if v > 8)
    print(f"OK nav_skin.png bbox={bbox} w={bbox[2]-bbox[0]} h={bbox[3]-bbox[1]} opaque={pxn}")


if __name__ == "__main__":
    main()
