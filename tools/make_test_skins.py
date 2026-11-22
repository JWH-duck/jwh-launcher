from PIL import Image
import os

OUT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\target\probe"

# 64x64，内层头六面分色，其余区域填中性灰保证全不透明；外层(第二皮肤层)全部留透明
GRAY = (150, 150, 150, 255)
skin = Image.new("RGBA", (64, 64), GRAY)
px = skin.load()

# 标准内层头 UV：右(0,8) 前(8,8) 左(16,8) 后(24,8) 顶(8,0) 底(16,0)
def fill(u, v, w, h, c):
    for y in range(v, v + h):
        for x in range(u, u + w):
            px[x, y] = c

RED = (220, 60, 60, 255)    # 前 z+
BLUE = (60, 100, 220, 255)  # 后 z-
GREEN = (60, 190, 90, 255)  # 右 x-
YELLOW = (230, 200, 60, 255)# 左 x+
PURPLE = (160, 80, 200, 255)# 顶
ORANGE = (230, 140, 50, 255)# 底

fill(8, 8, 8, 8, RED)
fill(24, 8, 8, 8, BLUE)
fill(0, 8, 8, 8, GREEN)
fill(16, 8, 8, 8, YELLOW)
fill(8, 0, 8, 8, PURPLE)
fill(16, 0, 8, 8, ORANGE)

# 外层帽区 (32,0)/(40,8)/(48,8)/(56,8)/(40,0)/(48,0) 留透明，模拟无帽子层
for y in range(0, 16):
    for x in range(32, 64):
        px[x, y] = (0, 0, 0, 0)

skin.save(os.path.join(OUT, "test_head_color.png"))

# 纯色满涂皮：全灰，用于看几何轮廓是否方正、有无缺角/漏缝
solid = Image.new("RGBA", (64, 64), (120, 120, 120, 255))
solid.save(os.path.join(OUT, "test_solid.png"))
print("ok")
