from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl263-shot.png"
im = Image.open(src)
print("size", im.size)
crop = im.crop((60, 150, 820, 240))
crop = crop.resize((int(crop.width*1.7), int(crop.height*1.7)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl263-bar-zoom.png"
crop.save(out)
print("saved", out)
