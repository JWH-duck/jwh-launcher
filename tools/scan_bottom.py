# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open(r'ui-preview\dl-v14.png').convert('RGB')
W, H = im.size
print('size', W, H)
c = im.crop((0, H - 130, W, H))
c.save(r'ui-preview\dl-v14-bottom.png')
px = im.load()
for y in range(H - 130, H, 10):
    print(y, px[300, y], px[800, y], px[1100, y])
