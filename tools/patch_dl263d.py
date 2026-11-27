# -*- coding: utf-8 -*-
import io
DV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(DV, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")
old = ('        loaderVersionBox.getStyleClass().add("jwh-capsule");\n'
       '        loaderVersionBox.setPromptText("加载器版本（默认最新稳定版）");\n')
assert s.count(old) == 1
new = ('        loaderVersionBox.getStyleClass().add("jwh-capsule");\n'
       '        loaderVersionBox.getStyleClass().add("jwh-capsule-plain");\n'
       '        loaderVersionBox.setPromptText("加载器版本（默认最新稳定版）");\n')
s = s.replace(old, new, 1)
with io.open(DV, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("loaderVersionBox plain added")
