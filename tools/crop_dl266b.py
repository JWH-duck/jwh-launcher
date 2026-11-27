from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-search2.png"
im = Image.open(src)
c1 = im.crop((120, 60, 1136, 140)); c1 = c1.resize((int(c1.width*1.4), int(c1.height*1.4)))
c1.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266b-tabs.png")
c2 = im.crop((120, 150, 1136, 225)); c2 = c2.resize((int(c2.width*1.4), int(c2.height*1.4)))
c2.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266b-filter.png")
print("saved")
