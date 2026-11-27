from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl265-after.png"
im = Image.open(src).convert("RGB")
for x in (300, 700):
    print(f"--- column x={x} ---")
    prev = None
    for y in range(40, 150):
        r, g, b = im.getpixel((x, y))
        if r < 30 and g < 30 and b < 30:
            cat = "black"
        elif r > 90 and b > 70 and g < 90:
            cat = "purple"
        elif r > 200 and g > 200 and b > 200:
            cat = "white-text"
        elif r < 60 and g < 50 and b < 70:
            cat = "dark-panel"
        else:
            cat = f"({r},{g},{b})"
        if cat != prev:
            print(f"y={y}: {cat}")
            prev = cat

crop = im.crop((60, 40, 1100, 135))
crop = crop.resize((int(crop.width*1.6), int(crop.height*1.6)))
crop.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl265-after-zoom.png")
print("zoom saved")
