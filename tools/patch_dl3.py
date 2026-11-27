# -*- coding: utf-8 -*-
# 补丁3：修正补丁2 漏加的字符串引号（#733e6f 必须是字面量 "#733e6f"）。
import io
P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(P, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")
src = src.replace('+ #733e6f +', '+ "#733e6f" +')
with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch3")
