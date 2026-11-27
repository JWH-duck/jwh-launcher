# -*- coding: utf-8 -*-
import io
F = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModpackTab.java"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")
anchor = "import javafx.scene.control.ComboBox;\n"
assert s.count(anchor) == 1
if "import javafx.scene.control.Button;\n" not in s:
    s = s.replace(anchor, "import javafx.scene.control.Button;\n" + anchor)
with io.open(F, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK Button import")
