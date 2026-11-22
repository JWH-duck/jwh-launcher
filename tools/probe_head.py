import sys, os
from PIL import Image

path = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else None
im = Image.open(path).convert("RGBA")
w, h = im.size
print("size", im.size)

# 头部相关区域：内层头 0..32 x, 0..16 y；外层帽 32..64 x, 0..16 y
crop = im.crop((0, 0, 64, 16))
scale = 8
big = crop.resize((64 * scale, 16 * scale), Image.NEAREST)
# 棋盘格衬底，方便看清透明
bg = Image.new("RGBA", big.size, (200, 200, 200, 255))
bg.alpha_composite(big)
if out:
    bg.convert("RGB").save(out)
    print("saved", out)

# 逐区域透明度/主色统计
def region(name, u, v, rw, rh):
    solid = 0
    total = 0
    colors = {}
    for y in range(v, v + rh):
        for x in range(u, u + rw):
            if x >= w or y >= h: continue
            r, g, b, a = im.getpixel((x, y))
            total += 1
            if a >= 128:
                solid += 1
                colors[(r, g, b)] = colors.get((r, g, b), 0) + 1
    top = sorted(colors.items(), key=lambda kv: -kv[1])[:3]
    print(f"  {name:8s} {solid:3d}/{total:3d} {solid*100//max(total,1):3d}%  top={top}")

print("-- 内层头 --")
region("前", 8, 8, 8, 8); region("后", 24, 8, 8, 8)
region("右", 0, 8, 8, 8);  region("左", 16, 8, 8, 8)
region("顶", 8, 0, 8, 8);  region("底", 16, 0, 8, 8)
print("-- 外层帽 --")
region("帽前", 40, 8, 8, 8); region("帽后", 56, 8, 8, 8)
region("帽右", 32, 8, 8, 8); region("帽左", 48, 8, 8, 8)
region("帽顶", 40, 0, 8, 8); region("帽底", 48, 0, 8, 8)
