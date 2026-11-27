from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-drop1.png"
im = Image.open(src)
c = im.crop((70, 215, 260, 460)); c = c.resize((int(c.width*2.2), int(c.height*2.2)))
c.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-drop1-zoom.png")
print("saved", im.size)
