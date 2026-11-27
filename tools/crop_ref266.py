from PIL import Image
src = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\d5381efb-11b0-4f1b-a53d-c2bfd1e29a6a.png"
im = Image.open(src).convert("RGB")
outdir = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview"
crops = {
    "ref266-tabs": (30, 30, 620, 105),
    "ref266-filter": (30, 120, 1360, 185),
    "ref266-list": (30, 200, 1360, 340),
}
for name, box in crops.items():
    c = im.crop(box)
    c = c.resize((int(c.width*1.4), int(c.height*1.4)))
    c.save(f"{outdir}\\{name}.png")
    print("saved", name)
