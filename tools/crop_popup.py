from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-prompt.png"
im = Image.open(src)
c = im.crop((760, 150, 1136, 225)); c = c.resize((int(c.width*2.5), int(c.height*2.5)))
c.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl266-popup-zoom.png")
px = im.convert("RGB")
print("bg capsule ~", px.getpixel((900, 188)))
print("prompt ~", px.getpixel((850, 188)))
print("saved")

