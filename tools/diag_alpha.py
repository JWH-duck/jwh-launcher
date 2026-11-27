from PIL import Image
import numpy as np, os
icons = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons"
for n in ["nav_home","nav_download","nav_settings"]:
    im = Image.open(os.path.join(icons, n+".png")).convert("RGBA")
    a = np.array(im)
    alpha = a[:,:,3]
    # 取最宽的一行（图标上半横边），打印 alpha 剖面
    row = alpha[110]
    nz = np.where(row>0)[0]
    print("====", n, "size", im.size)
    if len(nz):
        seg = row[nz.min():nz.max()+1]
        print("row alpha:", list(seg))
    # alpha 分层占比
    solid=(alpha>200).sum(); edge=((alpha>20)&(alpha<=200)).sum(); low=(alpha>0).sum()-solid-edge
    print("solid=%d edge=%d low=%d total=%d"%(solid,edge,low,(alpha>0).sum()))
