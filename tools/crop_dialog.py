from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl21-dlpage.png"
im = Image.open(src)
print("size", im.size)
# 弹窗大致区域（截图内坐标）
crop = im.crop((440, 270, 660, 480))
crop = crop.resize((crop.width*3, crop.height*3))
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\dl21-dialog-zoom.png"
crop.save(out)
print("saved", out)
