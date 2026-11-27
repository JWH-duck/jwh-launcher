# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open(r'ui-preview\dl-v14.png').convert('RGB')
px = im.load(); W, H = im.size
print('W', W)
for y in [205, 225, 250, 280]:
    blacks = [x for x in range(W) if px[x, y][0] < 12 and px[x, y][1] < 12 and px[x, y][2] < 12]
    print('y', y, 'black', (blacks[0], blacks[-1]) if blacks else None,
          'last', [px[x, y] for x in range(W - 6, W)])
