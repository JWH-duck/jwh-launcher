from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl23-dlpage.png"
im = Image.open(src)
crop = im.crop((250, 280, 860, 470))
crop = crop.resize((int(crop.width*1.6), int(crop.height*1.6)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl23-dialog-zoom.png"
crop.save(out)
print("saved", out)
