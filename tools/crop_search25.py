from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\map25-community-shot.png"
im = Image.open(src)
crop = im.crop((90, 300, 580, 380))
crop = crop.resize((int(crop.width*1.8), int(crop.height*1.8)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\map25-search-zoom.png"
crop.save(out)
print("saved", out)
