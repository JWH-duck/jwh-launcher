from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\map24-home.png"
im = Image.open(src)
crop = im.crop((70, 380, 570, 690))
crop = crop.resize((int(crop.width*1.5), int(crop.height*1.5)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\map24-cards-zoom.png"
crop.save(out)
print("saved", out)
