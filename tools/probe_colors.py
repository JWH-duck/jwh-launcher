from PIL import Image
import numpy as np, os, glob

dirs = {
 "icons": r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons",
 "backup": r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\tools\icon-backup",
}
for tag, d in dirs.items():
    print("====", tag)
    for f in sorted(glob.glob(os.path.join(d, "*.png"))):
        im = Image.open(f)
        arr = np.array(im.convert("RGBA"))
        a = arr[:, :, 3]
        ink = a > 40
        if ink.sum() == 0:
            print(os.path.basename(f), im.size, im.mode, "no ink"); continue
        rgb = arr[ink][:, :3]
        print(os.path.basename(f), im.size, im.mode,
              "ink=%d" % ink.sum(),
              "meanRGB=", rgb.mean(axis=0).round(1).tolist(),
              "alphaRange", int(a.min()), int(a.max()))
