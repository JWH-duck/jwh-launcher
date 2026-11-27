from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl263d-shot.png"
im = Image.open(src)
print("size", im.size)
crop = im.crop((330, 165, 600, 235))
crop = crop.resize((int(crop.width*2.2), int(crop.height*2.2)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl263d-zoom.png"
crop.save(out)
print("saved", out)
