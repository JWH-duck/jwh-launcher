from PIL import Image
src = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\ea23bf6c-f39a-46e5-a875-3b825cd38f43.png"
im = Image.open(src)
print("size", im.size)
crop = im.crop((0, 0, 560, im.height))
crop = crop.resize((int(crop.width*1.6), int(crop.height*1.6)))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl-capsule-zoom.png"
crop.save(out)
print("saved", out)
