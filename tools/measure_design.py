from PIL import Image

im = Image.open(r'C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\eabeff5e-8018-4e12-97f4-56a00a089382.png').convert('RGB')
W, H = im.size
px = im.load()
out = [f'size {W}x{H}']

def close(p, t, tol):
    return all(abs(p[i]-t[i]) <= tol[i] for i in range(3))

ICON = (61, 38, 64)   # #3D2640
INPUT = (62, 48, 68)  # #3E3044
PANEL = (43, 28, 49)  # #2B1C31

def bbox_for(test, tol, name):
    xs=[]; ys=[]
    for y in range(H):
        for x in range(W):
            if test(px[x,y]):
                xs.append(x); ys.append(y)
    if xs:
        out.append(f'{name}: x {min(xs)}..{max(xs)} ({max(xs)-min(xs)+1})  y {min(ys)}..{max(ys)} ({max(ys)-min(ys)+1})')
    else:
        out.append(f'{name}: none')

bbox_for(lambda p: close(p, ICON, (12,10,12)), None, 'iconbox')
bbox_for(lambda p: close(p, INPUT, (10,10,10)), None, 'input')

# 粉色按钮：分段取左右两块
def pink(p):
    r,g,b=p; return r>185 and 100<=g<=170 and 160<=b<=215 and r>=g
# 左半 / 右半分别 bbox
for side,lo,hi in [('left',0,W//2),('right',W//2,W)]:
    xs=[];ys=[]
    for y in range(H):
        for x in range(lo,hi):
            if pink(px[x,y]): xs.append(x); ys.append(y)
    if xs:
        out.append(f'pink-{side}: x {min(xs)}..{max(xs)} ({max(xs)-min(xs)+1})  y {min(ys)}..{max(ys)} ({max(ys)-min(ys)+1})')

with open(r'C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\measure_design.txt','w',encoding='utf-8') as f:
    f.write('\n'.join(out))
print('done')
