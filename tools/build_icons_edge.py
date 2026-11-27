from PIL import Image
import numpy as np, os

src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\edge-full.png"
prev = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\vector-sheet\edge"
os.makedirs(prev, exist_ok=True)

LINE=(201,204,209); CANVAS=256; FILL=0.78
GAMMA, THR = 1.5, 0.08

im = Image.open(src).convert("RGB")
arr = np.array(im); gray = arr[:,:,:3].mean(axis=2)
mask = gray > 128

def runs(v, thresh):
    out=[]; on=False
    for i,x in enumerate(v):
        if x>thresh and not on: on=True;s=i
        elif x<=thresh and on: on=False;out.append((s,i-1))
    if on: out.append((s,len(v)-1))
    return out

cols = runs(mask.sum(axis=0), 2)
rows = runs(mask.sum(axis=1), 2)
print("cols", cols); print("rows", rows)

names=[["nav_home","nav_log","nav_download"],
       ["nav_skin","nav_settings","nav_about"]]

def tighten(g):
    m=g>12; ys,xs=np.where(m)
    return xs.min(),ys.min(),xs.max()+1,ys.max()+1

tiles=[]
for ri,(y0,y1) in enumerate(rows):
    for ci,(x0,x1) in enumerate(cols):
        g=gray[y0:y1,x0:x1]
        tx0,ty0,tx1,ty1=tighten(g)
        sub=g[ty0:ty1,tx0:tx1]
        h,w=sub.shape
        scale=(CANVAS*FILL)/max(w,h)
        nw,nh=max(1,round(w*scale)),max(1,round(h*scale))
        tile=Image.fromarray(sub).resize((nw,nh),Image.LANCZOS)
        ta=np.array(tile).astype(np.float32)/255.0
        ta=np.clip((ta-THR)/(1-THR),0,1); ta=np.power(ta,GAMMA)
        rgba=np.zeros((nh,nw,4),np.uint8)
        rgba[:,:,0]=LINE[0];rgba[:,:,1]=LINE[1];rgba[:,:,2]=LINE[2]
        rgba[:,:,3]=(ta*255).round().astype(np.uint8)
        tr=Image.fromarray(rgba,"RGBA")
        canvas=Image.new("RGBA",(CANVAS,CANVAS),(0,0,0,0))
        canvas.alpha_composite(tr,((CANVAS-nw)//2,(CANVAS-nh)//2))
        n=names[ri][ci]
        canvas.save(os.path.join(prev,n+".png"))
        tiles.append((n,canvas))
        print(n,"glyph",w,h,"->",nw,nh)

# 34px 深紫对照
BG=(31,21,41,255); SIZE=34; ZOOM=7; cell=SIZE*ZOOM
sheet=Image.new("RGBA",(cell*3+40,cell*2+30),BG)
order=["nav_home","nav_log","nav_download","nav_skin","nav_settings","nav_about"]
for i,n in enumerate(order):
    cv=dict(tiles)[n]
    z=cv.resize((SIZE,SIZE),Image.LANCZOS).resize((cell,cell),Image.NEAREST)
    sheet.alpha_composite(z,(10+(i%3)*(cell+10),10+(i//3)*(cell+10)))
sheet.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\edge-compare.png")
print("done")
