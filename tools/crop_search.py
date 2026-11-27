from PIL import Image
src = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\29f3f298-3ef8-4c99-b943-d49a4c10c68c.png"
im = Image.open(src)
print("size", im.size)
crop = im.crop((0, 0, im.width, int(im.height*0.45)))
crop = crop.resize((int(crop.width*2.2), int(crop.height*2.2)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\map24-search-zoom.png"
crop.save(out)
print("saved", out)
