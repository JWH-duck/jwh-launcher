# -*- coding: utf-8 -*-
# 补丁7：下载页选中 Tab 去掉蓝色矩形底框/焦点环。
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    css = f.read().replace("\r\n", "\n")

old = """.jwh-download-tabs.tab-pane .tab:selected {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
}"""
new = """.jwh-download-tabs.tab-pane .tab:selected,
.jwh-download-tabs.tab-pane .tab:selected:focused {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-background-radius: 0;
    -fx-focus-color: transparent;
    -fx-faint-focus-color: transparent;
}"""
assert old in css, "selected rule not found"
css = css.replace(old, new)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(css)
print("OK patch7")
