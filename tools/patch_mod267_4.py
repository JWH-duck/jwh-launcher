# -*- coding: utf-8 -*-
import io
JA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(JA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

pairs = [
    ('versionBox.setPromptText("安装到的版本（必选）");',
     'versionBox.setPromptText("目标安装版本（必选）");'),
    ('sourceBox.getItems().addAll("Modrinth（推荐）", "CurseForge");',
     'sourceBox.getItems().addAll("Modrinth（推荐源）", "CurseForge");'),
    ('        sourceBox.setPrefWidth(150);', '        sourceBox.setPrefWidth(180);'),
    ('sortBox.getItems().addAll("下载量（高→低）", "下载量（低→高）", "名称 A–Z");',
     'sortBox.getItems().addAll("下载量·高到低", "下载量·低到高", "名称 A–Z");'),
    ('        sortBox.setPrefWidth(150);', '        sortBox.setPrefWidth(150);'),
    ('if ("下载量（低→高）".equals(s)) {', 'if ("下载量·低到高".equals(s)) {'),
]
for old, new in pairs:
    assert t.count(old) == 1, "anchor not unique/missing: %s" % old
    t = t.replace(old, new, 1)

with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("step4 filter labels/widths ok")
