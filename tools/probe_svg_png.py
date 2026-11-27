from PIL import Image
import numpy as np

p = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\svg-full.png"
im = Image.open(p)
print("mode", im.mode, "size", im.size)
arr = np.array(im)
print("shape", arr.shape)
if arr.shape[2] == 4:
    a = arr[:, :, 3]
    print("alpha min/max", a.min(), a.max(), "opaque px", (a > 10).sum())
    print("corner alpha", a[0,0], a[0,-1], a[-1,0], a[-1,-1])
else:
    print("no alpha channel")
# 白色/亮色像素行列投影
gray = arr[:, :, :3].mean(axis=2)
mask = gray > 128
col = mask.sum(axis=0)
row = mask.sum(axis=1)
def runs(v, thresh):
    out=[]; on=False
    for i,x in enumerate(v):
        if x>thresh and not on: on=True;s=i
        elif x<=thresh and on: on=False;out.append((s,i-1))
    if on: out.append((s,len(v)-1))
    return out
print("col runs", runs(col, 2))
print("row runs", runs(row, 2))
