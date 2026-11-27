from svglib.svglib import svg2rlg
from reportlab.graphics import renderPM

svg = r"C:\Users\33095\Documents\xwechat_files\wxid_vz5ngw1tchx412_7462\msg\file\2026-11\tubiao.svg"
out = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\ui-preview\svg-full.png"
drawing = svg2rlg(svg)
print("drawing", drawing.width, drawing.height)
renderPM.drawToFile(drawing, out, fmt="PNG", dpi=300, bg=0x000000)
print("saved", out)
