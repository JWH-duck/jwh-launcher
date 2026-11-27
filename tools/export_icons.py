from PIL import Image
import numpy as np, os, shutil

src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\svg-full.png"
prev_dir = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\vector-sheet"
os.makedirs(prev_dir, exist_ok=True)

LINE = (201, 204, 209)
CANVAS = 256
FILL = 0.78  # 内容占画布比例

im = Image.open(src).convert("RGB")
arr = np.array(im)
gray = arr[:, :, :3].mean(axis=2)

rows = [(321, 689), (898, 1298)]
cols = [(528, 896), (1256, 1624), (1972, 2339)]
names = [["nav_home", "nav_log", "nav_download"],
         ["nav_skin", "nav_settings", "nav_about"]]

def crop_tight(mask):
    ys, xs = np.where(mask)
    if len(xs) == 0: return None
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1

tiles = []
for r in range(2):
    for c in range(3):
        x0, x1 = cols[c]
        y0, y1 = rows[r]
        g = gray[y0:y1, x0:x1]
        mask = g > 12
        bb = crop_tight(mask)
        tx0, ty0, tx1, ty1 = bb
        sub = g[ty0:ty1, tx0:tx1]
        h, w = sub.shape
        scale = (CANVAS * FILL) / max(w, h)
        nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
        tile = Image.fromarray(sub).resize((nw, nh), Image.LANCZOS)
        ta = np.array(tile).astype(np.float32) / 255.0
        rgba = np.zeros((nh, nw, 4), dtype=np.uint8)
        rgba[:, :, 0] = LINE[0]; rgba[:, :, 1] = LINE[1]; rgba[:, :, 2] = LINE[2]
        rgba[:, :, 3] = (ta * 255).round().astype(np.uint8)
        tile_rgba = Image.fromarray(rgba, "RGBA")
        canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
        ox = (CANVAS - nw) // 2; oy = (CANVAS - nh) // 2
        canvas.alpha_composite(tile_rgba, (ox, oy))
        out = os.path.join(prev_dir, names[r][c] + ".png")
        canvas.save(out)
        tiles.append((names[r][c], w, h, canvas))
        print(names[r][c], "glyph", w, h, "->", nw, nh)

# 深底对照 sheet（两行三列）
BG = (38, 40, 45, 255)
sheet = Image.new("RGBA", (CANVAS * 3 + 40, CANVAS * 2 + 30), BG)
for i, (n, w, h, cv) in enumerate(tiles):
    r, c = divmod(i, 3)
    sheet.alpha_composite(cv, (10 + c * (CANVAS + 10), 10 + r * (CANVAS + 10)))
sheet_path = os.path.join(prev_dir, "vector-sheet-dark.png")
sheet.save(sheet_path)
print("sheet", sheet_path)
