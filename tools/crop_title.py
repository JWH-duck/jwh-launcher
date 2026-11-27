from PIL import Image
src = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\99c55f5a-fff6-4813-8d88-e7bb60a8e30e.png"
im = Image.open(src)
print("size", im.size)
crop = im.crop((150, 20, 766, 150))
crop = crop.resize((crop.width*2, crop.height*2))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl19-crop-titlebar.png"
crop.save(out)
print("saved", out)
