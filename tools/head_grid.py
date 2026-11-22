import os
from PIL import Image, ImageDraw, ImageFont

probe = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\target\probe"
yaws = ['0','45','90','135','180','225','270','315']
# 裁头部区域（302x300 帧）
box = (95, 10, 210, 125)
cw, ch = box[2]-box[0], box[3]-box[1]
scale = 2
cols, rows = 4, 2
label_h = 24
canvas = Image.new("RGB", (cols*cw*scale, rows*(ch*scale+label_h)), (235,239,243))
d = ImageDraw.Draw(canvas)
try:
    f = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 16)
except Exception:
    f = ImageFont.load_default()
for i, y in enumerate(yaws):
    p = os.path.join(probe, f"head_{y}.png")
    im = Image.open(p).convert("RGB").crop(box).resize((cw*scale, ch*scale), Image.NEAREST)
    r, c = divmod(i, cols)
    x = c*cw*scale; y0 = r*(ch*scale+label_h)
    canvas.paste(im, (x, y0+label_h))
    d.text((x+4, y0+3), f"yaw={y}", fill=(20,20,20), font=f)
out = os.path.join(probe, "head_grid.png")
canvas.save(out)
print("saved", out)
