from PIL import Image
import os

OUT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\target\probe"
GRAY = (150, 150, 150, 255)
skin = Image.new("RGBA", (64, 64), GRAY)
px = skin.load()

def fill(u, v, w, h, c):
    for y in range(v, v + h):
        for x in range(u, u + w):
            px[x, y] = c

RED = (220, 60, 60, 255)     # 帽前 z+
BLUE = (60, 100, 220, 255)   # 帽后 z-
GREEN = (60, 190, 90, 255)   # 帽右 x-
YELLOW = (230, 200, 60, 255) # 帽左 x+
PURPLE = (160, 80, 200, 255) # 帽顶
ORANGE = (230, 140, 50, 255) # 帽底

# 外层帽六面
fill(32, 8, 8, 8, GREEN)
fill(40, 8, 8, 8, RED)
fill(48, 8, 8, 8, YELLOW)
fill(56, 8, 8, 8, BLUE)
fill(40, 0, 8, 8, PURPLE)
fill(48, 0, 8, 8, ORANGE)

skin.save(os.path.join(OUT, "test_hat_color.png"))
print("ok")
