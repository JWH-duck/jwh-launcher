# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl-v19-fab.png")
w, h = im.size
crop = im.crop((int(w*0.78), int(h*0.72), w, h))
crop = crop.resize((crop.width*2, crop.height*2))
crop.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl-v19-fab-zoom.png")
print(im.size, "saved")
