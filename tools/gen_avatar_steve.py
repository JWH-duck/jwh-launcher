# -*- coding: utf-8 -*-
"""生成未登录占位头像：经典 Steve 棕发脸（8x8 逻辑像素硬边放大 48x48，透明底）。
运行：python tools/gen_avatar_steve.py
"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "avatar_steve.png")

HAIR = (90, 60, 36, 255)      # 棕发
SKIN = (222, 178, 140, 255)   # 肤色
WHITE = (255, 255, 255, 255)  # 眼白
EYE = (90, 160, 210, 255)     # 眼睛蓝
BEARD = (90, 60, 36, 255)     # 胡子/嘴棕

# 8x8 Steve 正面脸
FACE = [
    [HAIR, HAIR, HAIR, HAIR, HAIR, HAIR, HAIR, HAIR],
    [HAIR, SKIN, SKIN, SKIN, SKIN, SKIN, SKIN, HAIR],
    [HAIR, SKIN, SKIN, SKIN, SKIN, SKIN, SKIN, HAIR],
    [SKIN, SKIN, WHITE, EYE, EYE, WHITE, SKIN, SKIN],
    [SKIN, SKIN, WHITE, EYE, EYE, WHITE, SKIN, SKIN],
    [HAIR, SKIN, SKIN, SKIN, SKIN, SKIN, SKIN, HAIR],
    [HAIR, SKIN, BEARD, BEARD, BEARD, BEARD, SKIN, HAIR],
    [HAIR, SKIN, BEARD, BEARD, BEARD, BEARD, SKIN, HAIR],
]

OUT_PX = 48
CELL = OUT_PX // 8
img = Image.new("RGBA", (OUT_PX, OUT_PX), (0, 0, 0, 0))
for y, row in enumerate(FACE):
    for x, color in enumerate(row):
        for dy in range(CELL):
            for dx in range(CELL):
                img.putpixel((x * CELL + dx, y * CELL + dy), color)

img.save(OUT)
print("saved", OUT, img.size)
