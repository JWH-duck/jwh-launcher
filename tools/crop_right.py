# -*- coding: utf-8 -*-
from PIL import Image
im = Image.open(r'ui-preview\dl-v14.png').convert('RGB')
c = im.crop((1040, 170, 1136, 300))
c = c.resize((c.width * 3, c.height * 3), Image.NEAREST)
c.save(r'ui-preview\dl-v14-right-strip.png')
print('saved', c.size)
