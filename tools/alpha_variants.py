from PIL import Image
import numpy as np, os
icons = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons"
out_dir = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview"
names = ["nav_home","nav_log","nav_download","nav_skin","nav_settings","nav_about"]
BG = (31,21,41,255); SIZE=34; ZOOM=7; cell=SIZE*ZOOM

def sharpen(im, gamma, thr):
    a = np.array(im).astype(np.float32)
    al = a[:,:,3]/255.0
    al = np.clip((al-thr)/(1-thr),0,1)
    al = np.power(al, gamma)
    a[:,:,3] = (al*255).round()
    return Image.fromarray(a.astype(np.uint8),"RGBA")

variants = [
    ("orig", 1.0, 0.0),
    ("g15", 1.5, 0.08),
    ("g20", 2.0, 0.12),
]
# 行=变体, 列=图标
sheet = Image.new("RGBA",(cell*3+40, cell*3+30), BG)
for vi,(vn,g,th) in enumerate(variants):
    for ci,n in enumerate(names):
        im = Image.open(os.path.join(icons,n+".png")).convert("RGBA")
        ims = sharpen(im,g,th)
        im34 = ims.resize((SIZE,SIZE), Image.LANCZOS)
        imz = im34.resize((cell,cell), Image.NEAREST)
        sheet.alpha_composite(imz,(10+ci*(cell+10),10+vi*(cell+10)))
p=os.path.join(out_dir,"alpha-variants.png"); sheet.save(p); print(p)
