from PIL import Image, ImageDraw
import math, os

S = 256
SS = 4
BIG = S * SS
LW = 11 * SS
COLOR = (201, 204, 209, 255)
PREVIEW = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview"
RES = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons"


def canvas():
    return Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))


def sc(p):
    return (p[0] * SS, p[1] * SS)


def line(d, pts, width=LW, close=False):
    q = [sc(p) for p in pts]
    d.line(q, fill=COLOR, width=width, joint="curve")
    for p in q:
        r = width / 2
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=COLOR)
    if close:
        d.line([q[-1], q[0]], fill=COLOR, width=width, joint="curve")


def ellipse_outline(d, box, width=LW):
    b = [box[0] * SS, box[1] * SS, box[2] * SS, box[3] * SS]
    d.ellipse(b, outline=COLOR, width=width)


def finalize(img, preview, resource):
    img = img.resize((S, S), Image.LANCZOS)
    img.save(os.path.join(PREVIEW, preview))
    img.save(os.path.join(RES, resource))
    return img


# 1. 书本（社区）
def draw_log():
    img = canvas(); d = ImageDraw.Draw(img)
    outline = [(56, 196), (56, 82), (128, 64), (200, 82), (200, 196)]
    line(d, outline, close=False)
    line(d, [(56, 196), (200, 196)])
    line(d, [(128, 64), (128, 196)])
    for y in (106, 134, 162):
        line(d, [(70, y), (116, y)])
        line(d, [(140, y), (186, y)])
    return finalize(img, "r_log.png", "nav_log.png")


# 2. 下载：圆环 + 向下箭头
def draw_download():
    img = canvas(); d = ImageDraw.Draw(img)
    ellipse_outline(d, (56, 56, 200, 200))
    line(d, [(128, 86), (128, 150)])
    line(d, [(104, 128), (128, 152), (152, 128)])
    return finalize(img, "r_download.png", "nav_download.png")


# 3. T恤（皮肤）
def draw_skin():
    img = canvas(); d = ImageDraw.Draw(img)
    pts = [(106, 64), (128, 72), (150, 64), (178, 74), (200, 104),
           (190, 134), (162, 120), (162, 200), (94, 200), (94, 120),
           (66, 134), (56, 104), (78, 74)]
    line(d, pts, close=True)
    return finalize(img, "r_skin.png", "nav_skin.png")


# 4. 齿轮（设置）
def draw_settings():
    img = canvas(); d = ImageDraw.Draw(img)
    cx = cy = 128
    r_out, r_in, n = 70, 52, 8
    pts = []
    for i in range(n * 2):
        ang = math.pi / n * i - math.pi / 2
        r = r_out if i % 2 == 0 else r_in
        pts.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    line(d, pts, close=True)
    ellipse_outline(d, (102, 102, 154, 154), width=LW)
    return finalize(img, "r_settings.png", "nav_settings.png")


# 5. 三横线（更多）
def draw_about():
    img = canvas(); d = ImageDraw.Draw(img)
    for y in (68, 128, 188):
        line(d, [(56, y), (200, y)])
    return finalize(img, "r_about.png", "nav_about.png")


imgs = [draw_log(), draw_download(), draw_skin(), draw_settings(), draw_about()]
bg = (31, 21, 41, 255)
sheet = Image.new("RGBA", (S * 5, S), bg)
for i, im in enumerate(imgs):
    sheet.alpha_composite(im, (i * S, 0))
sheet.save(os.path.join(PREVIEW, "nav5-redraw-sheet.png"))
print("saved sheet + resources")
