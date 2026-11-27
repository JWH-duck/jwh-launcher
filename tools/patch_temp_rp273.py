# -*- coding: utf-8 -*-
import io
DV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(DV, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")
old = "        tabs.getSelectionModel().select(shaderPackTabT); // TEMP-SHADER-SELECT\n"
new = "        tabs.getSelectionModel().select(rpTabT); // TEMP-SHADER-SELECT\n"
assert s.count(old) == 1, "anchor=%d" % s.count(old)
s = s.replace(old, new)
with io.open(DV, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK select rpTabT")
