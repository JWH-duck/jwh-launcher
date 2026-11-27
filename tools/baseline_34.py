from PIL import Image
import os
icons = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons"
out_dir = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview"
names = ["nav_home","nav_log","nav_download","nav_skin","nav_settings","nav_about"]
BG = (31, 21, 41, 255)      # 导航底 #1f1529
SIZE = 34
ZOOM = 7
cell = SIZE*ZOOM
sheet = Image.new("RGBA", (cell*3+40, cell*2+30), BG)
for i,n in enumerate(names):
    im = Image.open(os.path.join(icons,n+".png")).convert("RGBA")
    im34 = im.resize((SIZE,SIZE), Image.LANCZOS)
    imz = im34.resize((cell,cell), Image.NEAREST)
    r,c = divmod(i,3)
    sheet.alpha_composite(imz, (10+c*(cell+10), 10+r*(cell+10)))
p = os.path.join(out_dir, "baseline-34px.png")
sheet.save(p)
print(p, sheet.size)
