from PIL import Image
src = r'C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\bd616877-747c-4e17-b918-dc16b8543037.png'
out = r'C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons\login_cat.png'
im = Image.open(src).convert('RGBA')
px = im.load()
w, h = im.size

# 1) 定位米白/深灰像素的最外边界（猫头方块）
minx, miny, maxx, maxy = w, h, 0, 0
for y in range(280, 430):
    for x in range(600, 800):
        r, g, b, a = px[x, y]
        cream = r > 200 and g > 190 and b > 175
        dark = abs(r - g) < 25 and abs(g - b) < 25 and r < 120
        if cream or dark:
            minx, miny = min(minx, x), min(miny, y)
            maxx, maxy = max(maxx, x), max(maxy, y)
print('content bbox', minx, miny, maxx, maxy)

# 2) 外扩少量边距后裁剪
pad = 6
box = (max(0, minx - pad), max(0, miny - pad), min(w, maxx + pad + 1), min(h, maxy + pad + 1))
crop = im.crop(box)
cw, ch = crop.size
cp = crop.load()

# 3) 紫色面板底 → 透明；米白与深灰猫头保留
for y in range(ch):
    for x in range(cw):
        r, g, b, a = cp[x, y]
        purple = r > 80 and b > 70 and g < 90 and (r - g) > 25 and (b - g) > 15
        if purple:
            cp[x, y] = (r, g, b, 0)

# 4) 缩放到统一尺寸 156x156（保持方形）
crop = crop.resize((156, 156), Image.LANCZOS)
crop.save(out)
print('saved', out, crop.size)
