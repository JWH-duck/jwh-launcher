# -*- coding: utf-8 -*-
"""按新 UI 目标图重绘三张导航描边图标：社区(翻开的书)、下载(圆环下箭头)、更多(三横线)。
规格与既有 nav_home / nav_skin / nav_settings 一致：64x64 RGBA 透明底，描边色 (201,204,209)，线宽 3。
运行：python tools/gen_nav_icons.py
"""
import os
from PIL import Image, ImageDraw

SIZE = 64
COLOR = (201, 204, 209, 255)
W = 3
HERE = os.path.dirname(os.path.abspath(__file__))
ICONS = os.path.join(HERE, "..", "src", "main", "resources", "icons")


def new_canvas():
    return Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def stroke_line(draw, p1, p2, width=W):
    draw.line([p1, p2], fill=COLOR, width=width, joint="curve")
    # 圆角端点
    r = width / 2.0
    for x, y in (p1, p2):
        draw.ellipse([x - r, y - r, x + r, y + r], fill=COLOR)


def save(img, name):
    path = os.path.join(ICONS, name)
    img.save(path)
    print("saved", name, img.size)


def more_icon():
    img = new_canvas()
    d = ImageDraw.Draw(img)
    for y in (22, 32, 42):
        stroke_line(d, (18, y), (46, y))
    save(img, "nav_about.png")


def download_icon():
    img = new_canvas()
    d = ImageDraw.Draw(img)
    # 圆环
    d.ellipse([16, 16, 48, 48], outline=COLOR, width=W)
    # 向下箭头：竖杆 + 两翼
    stroke_line(d, (32, 25), (32, 39))
    stroke_line(d, (26, 33), (32, 40))
    stroke_line(d, (38, 33), (32, 40))
    save(img, "nav_download.png")


def community_icon():
    img = new_canvas()
    d = ImageDraw.Draw(img)
    cx = 32
    # 书脊中缝（顶 / 底与页沿衔接，不另起尖顶）
    stroke_line(d, (cx, 23), (cx, 38))
    # 左页轮廓：上沿 / 外边 / 下沿
    stroke_line(d, (cx, 23), (21, 26))
    stroke_line(d, (21, 26), (21, 42))
    stroke_line(d, (21, 42), (cx, 38))
    # 右页轮廓（镜像）
    stroke_line(d, (cx, 23), (43, 26))
    stroke_line(d, (43, 26), (43, 42))
    stroke_line(d, (43, 42), (cx, 38))
    save(img, "nav_log.png")


if __name__ == "__main__":
    community_icon()
    download_icon()
    more_icon()
    print("done")
