# -*- coding: utf-8 -*-
import io
DV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(DV, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")
old = ('        Button refreshBtn = new Button("刷新");\n'
       '        refreshBtn.getStyleClass().add("ghost-btn");\n')
assert s.count(old) == 1
new = ('        Button refreshBtn = new Button("刷新");\n'
       '        refreshBtn.getStyleClass().add("ghost-btn");\n'
       '        refreshBtn.setMinWidth(72);\n'
       '        refreshBtn.setPrefWidth(72);\n'
       '        refreshBtn.setStyle("-fx-padding:6 14;");\n')
s = s.replace(old, new, 1)
with io.open(DV, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("refresh btn width fixed")
