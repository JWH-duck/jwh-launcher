# -*- coding: utf-8 -*-
import io
F = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")
old = "tabs.getSelectionModel().select(rpTabT); // TEMP-SHADER-SELECT"
new = "tabs.getSelectionModel().select(modpackTabT); // TEMP-SHADER-SELECT"
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new)
with io.open(F, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK select modpackTabT")
