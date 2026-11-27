from PIL import Image, ImageDraw

S = 256
SS = 4
BIG = S * SS
line_w = round(11 * SS)
color = (201, 204, 209, 255)

img = Image.new("RGBA", (BIG, BIG), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 256 基准坐标
apex = (128, 40)
roof_l = (48, 120)
roof_r = (208, 120)
wall_bl = (48, 216)
wall_br = (208, 216)
door_l = (112, 216)
door_rt = (112, 152)
door_r = (144, 152)
door_rb = (144, 216)

def sc(p):
    return (p[0] * SS, p[1] * SS)

# 屋顶三角
d.line([sc(apex), sc(roof_l), sc(roof_r), sc(apex)], fill=color, width=line_w, joint="curve")
# 墙体两侧 + 底边
d.line([sc(roof_l), sc(wall_bl), sc(wall_br), sc(roof_r)], fill=color, width=line_w, joint="curve")
# 门
d.line([sc(door_l), sc(door_rt), sc(door_r), sc(door_rb)], fill=color, width=line_w, joint="curve")

# 拐角补点，避免线宽接头缺口
for pt in (apex, roof_l, roof_r, wall_bl, wall_br, door_l, door_rt, door_r, door_rb):
    x, y = sc(pt)
    r = line_w / 2
    d.ellipse([x - r, y - r, x + r, y + r], fill=color)

img = img.resize((S, S), Image.LANCZOS)
out_preview = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\home-redraw.png"
out_resource = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\icons\nav_home.png"
img.save(out_preview)
img.save(out_resource)
print("saved", out_preview)
print("saved", out_resource)
