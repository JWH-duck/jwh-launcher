from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl265-current.png"
im = Image.open(src).convert("RGB")
W, H = im.size
# 在文字间隙取两列：x=300（游戏版本/Mod模组之间），x=700（光影包/材质包之间）
for x in (300, 700):
    print(f"--- column x={x} ---")
    prev = None
    for y in range(40, 170):
        r, g, b = im.getpixel((x, y))
        if r < 30 and g < 30 and b < 30:
            cat = "black"
        elif r > 90 and b > 70 and g < 90:
            cat = "purple"
        elif r > 200 and g > 200 and b > 200:
            cat = "white-text"
        else:
            cat = f"({r},{g},{b})"
        if cat != prev:
            print(f"y={y}: {cat}")
            prev = cat
