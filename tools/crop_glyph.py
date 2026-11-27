from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-glyph3.png"
im = Image.open(src)
print(im.size)
c = im.crop((760, 150, 1136, 225)); c = c.resize((int(c.width*2.5), int(c.height*2.5)))
c.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-glyph-zoom.png")
print("saved")

