# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl-v15-home.png")
w, h = im.size
crop = im.crop((0, int(h*0.78), w, h))
crop.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl-v15-bottom.png")
print(im.size, "saved")
