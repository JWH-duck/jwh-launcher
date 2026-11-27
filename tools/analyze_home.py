from PIL import Image
import os
p = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons\nav_home.png"
im = Image.open(p).convert("RGBA")
w, h = im.size
print("size", w, h)
px = im.load()
# 非透明像素
xs, ys = [], []
colors = {}
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if a > 12:
            xs.append(x); ys.append(y)
            colors[(r, g, b)] = colors.get((r, g, b), 0) + 1
print("bbox x", min(xs), max(xs), "y", min(ys), max(ys))
top = sorted(colors.items(), key=lambda kv: -kv[1])[:8]
for c, n in top:
    print("color", c, "n", n)
# 行/列亮像素计数，便于看轮廓
print("--- row bright counts ---")
for y in range(h):
    c = sum(1 for x in range(w) if px[x, y][3] > 12)
    if c:
        print(y, c)
