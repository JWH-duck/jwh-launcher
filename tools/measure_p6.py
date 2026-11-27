from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl265-p6.png"
im = Image.open(src).convert("RGB")
def cat(r, g, b):
    if r < 30 and g < 30 and b < 30: return "black"
    if r < 60 and g < 50 and b < 70: return "dark-panel"
    if r > 90 and b > 80 and g < 95: return "purple"
    if r > 180 and g > 180 and b > 180: return "white"
    return f"({r},{g},{b})"
for x in (300, 700):
    print(f"--- x={x} ---")
    prev = None
    for y in range(40, 150):
        c = cat(*im.getpixel((x, y)))
        if c != prev:
            print(f"y={y}: {c}")
            prev = c
crop = im.crop((60, 40, 1100, 135))
crop = crop.resize((int(crop.width*1.6), int(crop.height*1.6)))
crop.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl265-p6-zoom.png")
print("zoom saved")
