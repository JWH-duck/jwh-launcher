# -*- coding: utf-8 -*-
import io
JA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(JA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

pairs = [
    ('sourceBox.getItems().addAll("Modrinth（推荐源）", "CurseForge");',
     'sourceBox.getItems().addAll("Modrinth", "CurseForge");'),
    ('        sourceBox.setPrefWidth(180);', '        sourceBox.setPrefWidth(135);'),
    ('        sortBox.setPrefWidth(150);', '        sortBox.setPrefWidth(130);'),
]
for old, new in pairs:
    assert t.count(old) == 1, "anchor: %s" % old
    t = t.replace(old, new, 1)

# 版本实例名下拉给足宽度，避免长实例名被省略号截断
anchor = '        versionBox.setPromptText("目标安装版本（必选）");\n'
assert t.count(anchor) == 1
t = t.replace(anchor, anchor + "        versionBox.setPrefWidth(260);\n", 1)

with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("step5 widths ok")
