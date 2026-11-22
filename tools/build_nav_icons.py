# -*- coding: utf-8 -*-
"""
把用户提供的四张导航草图处理成统一的透明 PNG：
  - 去除棋盘格背景与右下角浅灰水印（亮度阈值分离黑色线条）
  - 按线条包围盒裁剪主体，统一边距，输出 64x64 高清导航图标
输入为临时素材路径，输出到 src/main/resources/icons/。
"""
from PIL import Image
import os

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(PROJ, "src", "main", "resources", "icons")

# 草图 -> 输出文件名
ICONS = {
    "nav_home.png": r"C:\Users\33095\Documents\xwechat_files\wxid_vz5ngw1tchx412_7462\temp\RWTemp\2026-11\638a1b5ba14a26a44a218ea2e2501337\dc60147eec6f0bfd63711a5f1a1a7c90.png",
    "nav_download.png": r"C:\Users\33095\Documents\xwechat_files\wxid_vz5ngw1tchx412_7462\temp\RWTemp\2026-11\638a1b5ba14a26a44a218ea2e2501337\b1fae60c2e49819b8a0a3bb6165c00f2.png",
    "nav_skin.png": r"C:\Users\33095\AppData\Local\Doubao\User Data\ClipboardTemp\aa6bf8e1-bdbc-4edd-8d83-70682eb8b4f7.png",
    "nav_settings.png": r"C:\Users\33095\Documents\xwechat_files\wxid_vz5ngw1tchx412_7462\temp\RWTemp\2026-11\638a1b5ba14a26a44a218ea2e2501337\a3771e2dea66590c393c4798ae66dd2b.png",
}

SIZE = 64          # 输出画布边长
CONTENT = 52       # 主体在画布内的边长（留统一边距）
DARK = 90          # 低于此亮度视为纯黑线条
LIGHT = 170        # 高于此亮度视为背景/水印，全透明


def process(src: str) -> Image.Image:
    im = Image.open(src).convert("RGB")
    w, h = im.size
    px = im.load()
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    op = out.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            mx = max(r, g, b)
            if mx > LIGHT:
                a = 0
            elif mx < DARK:
                a = 255
            else:
                a = int(255 * (LIGHT - mx) / (LIGHT - DARK))
            if a > 8:
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
            op[x, y] = (0, 0, 0, a)
    if maxx < 0:
        raise RuntimeError(f"未识别到线条主体：{src}")
    crop = out.crop((minx, miny, maxx + 1, maxy + 1))
    # 贴到正方形透明画布，再等比缩放到 CONTENT，居中到 SIZE 画布
    side = max(crop.width, crop.height)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(crop, ((side - crop.width) // 2, (side - crop.height) // 2), crop)
    square = square.resize((CONTENT, CONTENT), Image.LANCZOS)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.paste(square, ((SIZE - CONTENT) // 2, (SIZE - CONTENT) // 2), square)
    return canvas


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    tiles = []
    for name, src in ICONS.items():
        icon = process(src)
        icon.save(os.path.join(OUT_DIR, name))
        tiles.append((name, icon))
        print(f"OK {name} {icon.size}")

    # 自查拼贴：棋盘格底并排四张
    checker = Image.new("RGBA", (SIZE * len(tiles) + 20 * (len(tiles) - 1), SIZE), (255, 255, 255, 255))
    cp = checker.load()
    c = 12
    for y in range(checker.height):
        for x in range(checker.width):
            if (x // c + y // c) % 2 == 0:
                cp[x, y] = (225, 225, 225, 255)
    for i, (_, icon) in enumerate(tiles):
        x = i * (SIZE + 20)
        checker.paste(icon, (x, 0), icon)
    preview = os.path.join(os.environ.get("TEMP", "."), "jwh_nav_icons_preview.png")
    checker.save(preview)
    print("PREVIEW=" + preview)


if __name__ == "__main__":
    main()
