# -*- coding: utf-8 -*-
import io
SV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\SkinsView.java"
with io.open(SV, "r", encoding="utf-8", newline="") as f:
    t = f.read().replace("\r\n", "\n")
old = "        this.preview.setModelScale(0.92);\n"
assert t.count(old) == 1, t.count(old)
t = t.replace(old, "        this.preview.setModelScale(0.80);\n")
with io.open(SV, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("OK modelScale 0.80")
