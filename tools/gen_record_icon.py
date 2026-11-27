# -*- coding: utf-8 -*-
"""主页右上角唱片/音乐图标（64x64 RGBA 透明底，线宽 3，浅灰描边）。
造型：外圆环（右侧两处细缺口）+ d 形音符（符干+顶旗+音符头小环）+ 内上/下短弧。
运行：python tools/gen_record_icon.py
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "icons", "nav_music.png")

W = 64
STROKE = (201, 204, 209, 255)
TRANSP = (0, 0, 0, 0)

img = Image.new("RGBA", (W, W), TRANSP)
d = ImageDraw.Draw(img)

# 外圆环 r27，居中 (32,32)
OUT_BOX = (5, 5, 59, 59)
d.arc(OUT_BOX, start=0, end=360, fill=STROKE, width=3)
# 右侧两处细缺口（擦透明弧）
d.arc(OUT_BOX, start=52, end=66, fill=TRANSP, width=5)
d.arc(OUT_BOX, start=118, end=132, fill=TRANSP, width=5)

# 音符符干：竖线偏右，顶斜旗
d.line((38, 14, 38, 38), fill=STROKE, width=3)
d.line((38, 14, 46, 10), fill=STROKE, width=3)
# 音符头小环（贴符干下端左侧）
d.ellipse((22, 33, 36, 47), outline=STROKE, width=3)

# 内圈唱片纹短弧：左上一段、左下一段（同心 r16）
IN_BOX = (16, 16, 48, 48)
d.arc(IN_BOX, start=150, end=180, fill=STROKE, width=3)
d.arc(IN_BOX, start=188, end=210, fill=STROKE, width=3)

img.save(OUT)
print("saved", OUT, img.size)
