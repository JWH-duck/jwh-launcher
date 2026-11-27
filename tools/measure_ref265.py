from PIL import Image
src = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\dc4e55d5-0b1b-460a-ab4b-2255a1bf0045.png"
im = Image.open(src).convert("RGB")
W, H = im.size
print("size", W, H)

def cat(r, g, b):
    if r < 30 and g < 30 and b < 30: return "black"
    if r < 60 and g < 50 and b < 70: return "dark-panel"
    if r > 90 and b > 80 and g < 95: return "purple"
    if r > 180 and g > 180 and b > 180: return "white"
    return f"({r},{g},{b})"

for x in (120, 300, 500):
    print(f"--- column x={x} ---")
    prev = None
    for y in range(0, H):
        c = cat(*im.getpixel((x, y)))
        if c != prev:
            print(f"y={y}: {c}")
            prev = c
