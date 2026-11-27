# -*- coding: utf-8 -*-
"""把用户提供的六张导航截图处理成启动器规范图标：
抠除深紫底 #1f1529 与品牌紫竖条，描边统一浅灰 (201,204,209)，
裁掉透明边后等比居中到 64x64 透明底（NEAREST 硬边）。
运行：python tools/import_nav_icons.py
"""
import os
from PIL import Image

CLIP = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp"
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "src", "main", "resources", "icons")

STROKE = (201, 204, 209, 255)
CANVAS = 64
INNER = 48  # 内容最大边长，四周留 8px 安全边距

# 剪贴板文件 -> 目标资源名
MAP = [
    ("1f14bdea-5737-4b97-9586-8013946c9702", "nav_home.png"),
    ("5d8ea3ad-8fb8-4fb1-b342-9aa4ea0d165c", "nav_log.png"),
    ("fb725a81-8851-4e6c-a008-8024843f0030", "nav_download.png"),
    ("c98f82fc-a92b-4b62-bb16-d796ec5dc132", "nav_skin.png"),
    ("434d649d-2e21-4d4e-86bc-fab5a79121c0", "nav_settings.png"),
    ("3d3abcc9-df38-4547-80bd-29e50703b166", "nav_about.png"),
]


def process(src_path):
    im = Image.open(src_path).convert("RGBA")
    px = im.load()
    w, h = im.size
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, _ = px[x, y]
            # 紫色系（深紫底 / 品牌紫竖条）一律判背景
            if g < 90:
                continue
            lum = (r + g + b) / 3.0
            if lum < 80:
                continue
            alpha = int(round(min(1.0, (lum - 80) / 90.0) * 255))
            op[x, y] = (STROKE[0], STROKE[1], STROKE[2], alpha)

    bbox = out.getbbox()
    if bbox is None:
        raise RuntimeError("前景为空: " + src_path)
    out = out.crop(bbox)
    iw, ih = out.size
    scale = INNER / max(iw, ih)
    nw, nh = max(1, round(iw * scale)), max(1, round(ih * scale))
    out = out.resize((nw, nh), Image.NEAREST)

    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    ox = (CANVAS - nw) // 2
    oy = (CANVAS - nh) // 2
    canvas.alpha_composite(out, (ox, oy))
    return canvas


for src, dst in MAP:
    img = process(os.path.join(CLIP, src + ".png"))
    img.save(os.path.join(RES, dst))
    print("saved", dst, img.size)
