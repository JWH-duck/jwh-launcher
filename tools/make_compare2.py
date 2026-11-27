from PIL import Image
import os

prev = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\vector-sheet"
bak = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\tools\icon-backup"
CANVAS = 256

pairs = [
 ("nav_home", "nav_home.64bak.png"),
 ("nav_log", "nav_log.png.64bak.png"),
 ("nav_download", "nav_download.png.64bak.png"),
 ("nav_skin", "nav_skin.png.64bak.png"),
 ("nav_settings", "nav_settings.png.64bak.png"),
 ("nav_about", "nav_about.png.64bak.png"),
]
BG = (38, 40, 45, 255)
sheet = Image.new("RGBA", (CANVAS*3+40, CANVAS*2+30), BG)
for i,(vn, bn) in enumerate(pairs):
    r,c = divmod(i,3)
    b = Image.open(os.path.join(bak, bn)).convert("RGBA").resize((CANVAS,CANVAS), Image.NEAREST)
    v = Image.open(os.path.join(prev, vn+".png")).convert("RGBA")
    sheet.alpha_composite(b, (10+c*(CANVAS+10), 10))
    sheet.alpha_composite(v, (10+c*(CANVAS+10), 10+CANVAS+10))
out = os.path.join(prev, "compare-old-new.png")
sheet.save(out)
print(out)
