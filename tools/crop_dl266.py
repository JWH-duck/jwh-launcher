from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-dark.png"
im = Image.open(src)
c1 = im.crop((180, 60, 1136, 140)); c1 = c1.resize((int(c1.width*1.5), int(c1.height*1.5)))
c1.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-tabs.png")
c2 = im.crop((180, 150, 1136, 220)); c2 = c2.resize((int(c2.width*1.5), int(c2.height*1.5)))
c2.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-filter.png")
c3 = im.crop((180, 230, 1136, 420)); c3 = c3.resize((int(c3.width*1.3), int(c3.height*1.3)))
c3.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-list.png")
print("saved")
