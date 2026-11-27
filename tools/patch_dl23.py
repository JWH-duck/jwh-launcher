# -*- coding: utf-8 -*-
# 补丁23：JwhDialog 面板恢复固定宽度 520（与原 Alert 520 一致），不被内容压窄。
import io
JD = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\JwhDialog.java"
with io.open(JD, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")

old = """        VBox box = new VBox(14, titleRow, body, btnRow);
        box.setPadding(new Insets(0, 20, 18, 20));"""
new = """        VBox box = new VBox(14, titleRow, body, btnRow);
        box.setPadding(new Insets(0, 20, 18, 20));
        box.setPrefWidth(520);
        box.setMinWidth(520);"""
if old not in s:
    raise SystemExit("box block not found")
s = s.replace(old, new, 1)
with io.open(JD, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK JwhDialog width")
