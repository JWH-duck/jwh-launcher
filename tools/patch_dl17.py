# -*- coding: utf-8 -*-
# 补丁17：去除绿色下载钮外围默认圆角底板（边框/背景层/内边距清零），并把圆钮放大到 76。
import io

CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

old = """.jwh-fab {
    -fx-background-color: #41cc3a;
    -fx-text-fill: #ffffff;
    -fx-font-weight: bold;
    -fx-font-size: 15px;
    -fx-background-radius: 32;
    -fx-min-width: 64;
    -fx-min-height: 64;
    -fx-max-width: 64;
    -fx-max-height: 64;
    -fx-cursor: hand;
}"""

new = """.jwh-fab {
    -fx-background-color: #41cc3a;
    -fx-background-insets: 0;
    -fx-background-radius: 38;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-border-radius: 38;
    -fx-padding: 0;
    -fx-text-fill: #ffffff;
    -fx-font-weight: bold;
    -fx-font-size: 17px;
    -fx-min-width: 76;
    -fx-min-height: 76;
    -fx-max-width: 76;
    -fx-max-height: 76;
    -fx-cursor: hand;
}"""

assert old in src, "fab block not found"
src = src.replace(old, new)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch17 css")
