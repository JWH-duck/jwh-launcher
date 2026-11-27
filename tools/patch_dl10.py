# -*- coding: utf-8 -*-
# 补丁10：下载页标签文字再往下移（加大顶部内边距、收紧底部）。
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    css = f.read().replace("\r\n", "\n")

old = "    -fx-padding: 15 18 10 18;"
new = "    -fx-padding: 23 18 5 18;"
assert old in css, "padding not found"
css = css.replace(old, new)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(css)
print("OK patch10")
