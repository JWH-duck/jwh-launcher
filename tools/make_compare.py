import os
from PIL import Image, ImageDraw, ImageFont

probe = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\target\probe"

def font(size):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

def make(before, after, title, out):
    a = Image.open(os.path.join(probe, before)).convert("RGBA")
    b = Image.open(os.path.join(probe, after)).convert("RGBA")
    w, h = a.size
    title_h = 34
    label_h = 28
    pad = 16
    top = title_h + label_h
    canvas = Image.new("RGBA", (w * 2 + pad * 3, h + top + pad), (235, 239, 243, 255))
    d = ImageDraw.Draw(canvas)
    f_title = font(22)
    f_label = font(18)
    d.text((pad, 8), title, fill=(30, 30, 30, 255), font=f_title)
    canvas.paste(a, (pad, top), a)
    canvas.paste(b, (pad * 2 + w, top), b)
    d.text((pad + w // 2 - 40, title_h), "修复前", fill=(180, 40, 40, 255), font=f_label)
    d.text((pad * 2 + w + w // 2 - 40, title_h), "修复后", fill=(30, 130, 60, 255), font=f_label)
    canvas.convert("RGB").save(os.path.join(probe, out))
    print("OK", out)

make("before_prem_135.png", "after_prem_135.png",
     "正版皮肤 · 细手臂斜后视角", "compare_prem.png")
make("before_cow_135.png", "after_cow_135.png",
     "奶牛斑皮肤 · 细手臂斜后视角", "compare_cow.png")
