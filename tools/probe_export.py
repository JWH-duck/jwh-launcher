from PIL import Image
import numpy as np, os

prev = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\vector-sheet"
for n in ["nav_home","nav_log","nav_download","nav_skin","nav_settings","nav_about"]:
    im = Image.open(os.path.join(prev, n+".png")).convert("RGBA")
    a = np.array(im)
    alpha = a[:,:,3]
    solid = alpha > 200
    edge = (alpha>20)&(alpha<=200)
    rgb = a[solid][:,:3]
    if len(rgb):
        print(n, "solid=%d edge=%d"%(solid.sum(), edge.sum()),
              "RGB mean", rgb.mean(axis=0).round(1).tolist(),
              "RGB std", rgb.std(axis=0).round(2).tolist())
    else:
        print(n, "no solid")
