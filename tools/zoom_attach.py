# -*- coding: utf-8 -*-
from PIL import Image
p = r'C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\d74ba53d-bc8c-40a1-83db-a4e0c1d52257.png'
im = Image.open(p).convert('RGB')
W, H = im.size
print('size', W, H)
im.resize((W * 3, H * 3), Image.NEAREST).save(r'ui-preview\attach-bottom-zoom.png')
px = im.load()
for y in range(0, H, 12):
    print(y, px[20, y], px[W // 2, y], px[W - 20, y])
