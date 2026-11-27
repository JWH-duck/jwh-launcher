# -*- coding: utf-8 -*-
import io, os
p = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(p, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")
old = " * 通用 Modrinth 搜索下载 Tab（Mod / 材质包 复用；光影包由 ShaderTab 组合）。\n"
new = " * 通用 Modrinth 搜索下载 Tab（Mod / 光影加载器 / 光影包 / 材质包 复用）。\n"
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new)
with io.open(p, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK comment")
