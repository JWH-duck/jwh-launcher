from PIL import Image
src = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\vector-nav-shot3.png"
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\nav-strip-zoom3.png"
im = Image.open(src)
print("size", im.size)
strip = im.crop((0, 40, 110, 739))
strip = strip.resize((strip.width*3, strip.height*3), Image.NEAREST)
strip.save(out)
print(out, strip.size)
