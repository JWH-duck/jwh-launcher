from PIL import Image
src = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\d5381efb-11b0-4f1b-a53d-c2bfd1e29a6a.png"
im = Image.open(src).convert("RGB")
W, H = im.size

def cat(c):
    r,g,b = c
    if r>200 and g>200 and b>200: return "W"   # white text
    if r>150 and g>110 and b>150: return "P"   # pink/purple text
    if r<45 and g<35 and b<55: return "D"      # dark bg
    if r<55 and g<45 and b<65: return "C"      # control bg
    return "."

def scan(y, label):
    print(f"--- {label} y={y} ---")
    prev=None; start=0
    for x in range(0, W):
        c=cat(im.getpixel((x,y)))
        if c!=prev:
            if prev is not None:
                print(f"  x {start}-{x-1}: {prev}")
            prev=c; start=x
    print(f"  x {start}-{W-1}: {prev}")

scan(60, "tab row")
scan(86, "tab underline row")
scan(150, "filter row")
scan(250, "list row1")
scan(290, "list divider")
