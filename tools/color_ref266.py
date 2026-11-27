from PIL import Image
src = r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\d5381efb-11b0-4f1b-a53d-c2bfd1e29a6a.png"
im = Image.open(src).convert("RGB")
W, H = im.size
print("size", W, H)
pts = {
    "page-bg(空白)": (500, 400),
    "list-card-row": (500, 250),
    "tab-selected-text": (70, 60),
    "tab-underline": (70, 88),
    "tab-unselected-text": (160, 60),
    "tab-separator-line": (118, 60),
    "combo-bg": (100, 150),
    "search-bg": (600, 150),
    "version-id-text": (130, 250),
    "type-tag": (175, 250),
    "meta-time": (130, 272),
    "row-divider": (500, 290),
    "panel-edge": (60, 200),
}
for name, (x, y) in pts.items():
    print(f"{name:22s} ({x},{y}) = {im.getpixel((x,y))}")
