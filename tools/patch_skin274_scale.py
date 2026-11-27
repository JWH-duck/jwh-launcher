# -*- coding: utf-8 -*-
import io

SC = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\SkinCanvas.java"
SV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\SkinsView.java"

# ---- SkinCanvas ----
with io.open(SC, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")

# 1) 字段
anchor1 = "    private Boolean slimOverride;\n"
assert s.count(anchor1) == 1, ("field", s.count(anchor1))
if "private double modelScale" not in s:
    s = s.replace(anchor1, anchor1 + "    /** 模型整体缩放倍率：1.0 为默认取景，皮肤库主预览可调小。 */\n    private double modelScale = 1.0;\n")

# 2) setter（构造后）
anchor2 = "    public SkinCanvas(double w, double h) {\n        super(w, h);\n    }\n"
assert s.count(anchor2) == 1, ("ctor", s.count(anchor2))
if "setModelScale" not in s:
    s = s.replace(anchor2, anchor2 + "\n    /** 设置模型整体缩放倍率（1.0 默认；小于 1 缩小）。 */\n    public void setModelScale(double scale) { this.modelScale = scale; }\n")

# 3) scale 乘倍率
anchor3 = "        double scale = Math.min(H * 0.80 / 32.0, W * 0.76 / 22.0);\n"
assert s.count(anchor3) == 1, ("scale", s.count(anchor3))
s = s.replace(anchor3, "        double scale = Math.min(H * 0.80 / 32.0, W * 0.76 / 22.0) * modelScale;\n")

with io.open(SC, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)

# ---- SkinsView：主预览设 0.92 ----
with io.open(SV, "r", encoding="utf-8", newline="") as f:
    t = f.read().replace("\r\n", "\n")
anchor4 = "        this.preview = new SkinCanvas(302.0, 300.0);\n"
assert t.count(anchor4) == 1, ("sv", t.count(anchor4))
if "setModelScale" not in t:
    t = t.replace(anchor4, anchor4 + "        this.preview.setModelScale(0.92);\n")
with io.open(SV, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)

print("OK modelScale 0.92 on skins preview")
