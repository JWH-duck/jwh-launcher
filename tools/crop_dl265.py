from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl265-current.png"
im = Image.open(src)
print("size", im.size)
crop = im.crop((60, 40, 1100, 165))
crop = crop.resize((int(crop.width*1.6), int(crop.height*1.6)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl265-top-zoom.png"
crop.save(out)
print("saved", out)
