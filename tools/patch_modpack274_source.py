# -*- coding: utf-8 -*-
import io
F = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModpackTab.java"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")
old = '        sourceBox.getItems().addAll("Modrinth");\n'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, '        sourceBox.getItems().addAll("Modrinth（推荐源）");\n')
with io.open(F, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK source label")
