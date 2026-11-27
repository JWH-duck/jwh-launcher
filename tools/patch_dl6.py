# -*- coding: utf-8 -*-
# 补丁6：page 续行上的 Ui.BORDER 边框改为透明。
import io
P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(P, "r", encoding="utf-8") as f:
    lines = f.read().replace("\r\n", "\n").split("\n")
out = []
for ln in lines:
    if "border-color" in ln and "Ui.BORDER" in ln:
        ln = ln.replace("Ui.BORDER", '"transparent"')
    out.append(ln)
with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(out))
print("OK patch6")
