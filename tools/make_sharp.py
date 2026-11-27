from PIL import Image
import numpy as np, os
icons = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons"
prev  = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\vector-sheet\sharp"
os.makedirs(prev, exist_ok=True)
names = ["nav_home","nav_log","nav_download","nav_skin","nav_settings","nav_about"]
GAMMA, THR = 1.5, 0.08

def sharpen(im):
    a = np.array(im).astype(np.float32)
    al = a[:,:,3]/255.0
    al = np.clip((al-THR)/(1-THR),0,1)
    al = np.power(al, GAMMA)
    a[:,:,3] = (al*255).round()
    return Image.fromarray(a.astype(np.uint8),"RGBA")

for n in names:
    im = Image.open(os.path.join(icons,n+".png")).convert("RGBA")
    out = sharpen(im)
    out.save(os.path.join(prev,n+".png"))

# 34px 对照 sheet：上排原始，下排提锐
BG=(31,21,41,255); SIZE=34; ZOOM=7; cell=SIZE*ZOOM
sheet = Image.new("RGBA",(cell*3+40, cell*2+30),BG)
for i,n in enumerate(names):
    raw = Image.open(os.path.join(icons,n+".png")).convert("RGBA")
    sh  = Image.open(os.path.join(prev,n+".png")).convert("RGBA")
    for row,im in enumerate((raw,sh)):
        z = im.resize((SIZE,SIZE),Image.LANCZOS).resize((cell,cell),Image.NEAREST)
        sheet.alpha_composite(z,(10+(i%3)*(cell+10),10+row*(cell+10)))
sheet.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\sharp-compare.png")
print("done")
