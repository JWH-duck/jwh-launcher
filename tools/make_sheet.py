from PIL import Image
import os

d = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons"
names = ["nav_log.png", "nav_download.png", "nav_skin.png", "nav_settings.png", "nav_about.png"]
scale = 4
cell = 64 * scale
bg = (31, 21, 41, 255)
sheet = Image.new("RGBA", (cell * len(names), cell), bg)
for i, n in enumerate(names):
    im = Image.open(os.path.join(d, n)).convert("RGBA")
    im = im.resize((cell, cell), Image.NEAREST)
    sheet.alpha_composite(im, (i * cell, 0))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\nav5-sheet.png"
sheet.save(out)
print("saved", out, sheet.size)
