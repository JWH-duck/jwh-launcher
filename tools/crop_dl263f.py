from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl263-final.png"
im = Image.open(src)
crop = im.crop((60, 165, 830, 235))
crop = crop.resize((int(crop.width*1.7), int(crop.height*1.7)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl263-final-zoom.png"
crop.save(out)
print("saved", out)
