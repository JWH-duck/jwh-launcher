# -*- coding: utf-8 -*-
import io
DV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(DV, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")
old = '                    app.setStatus("加载器版本列表获取失败：" + e.getMessage() + "（可点「刷新加载器列表」重试，或稍后重试）");'
assert s.count(old) == 1
new = '                    app.setStatus("加载器版本列表获取失败：" + e.getMessage() + "（可稍后重试）");'
s = s.replace(old, new, 1)
with io.open(DV, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("status text fixed")
