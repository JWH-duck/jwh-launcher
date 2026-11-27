# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open(r'ui-preview\dl-v14.png').convert('RGB')
px = im.load(); W, H = im.size
y = 225
print('--- left ---')
for x in range(60, 130):
    print(x, px[x, y])
print('--- right ---')
for x in range(1126, 1136):
    print(x, px[x, y])
