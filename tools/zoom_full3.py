from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\vector-nav-shot3.png"
im = Image.open(src)
# 左侧导航条整列
strip = im.crop((0, 50, 130, 720))
strip = strip.resize((strip.width*3, strip.height*3), Image.LANCZOS)
strip.save(r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\nav-strip-full3.png")
print("ok", strip.size)
