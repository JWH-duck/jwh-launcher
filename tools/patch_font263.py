# -*- coding: utf-8 -*-
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    c = f.read().replace("\r\n", "\n")

# 胶囊下拉文字加大到 14px
old1 = ('.jwh-capsule {\n'
        '    -fx-background-color: rgba(255, 255, 255, 0.10);\n'
        '    -fx-text-fill: #ffffff;\n')
assert c.count(old1) == 1
new1 = ('.jwh-capsule {\n'
        '    -fx-background-color: rgba(255, 255, 255, 0.10);\n'
        '    -fx-text-fill: #ffffff;\n'
        '    -fx-font-size: 14px;\n')
c = c.replace(old1, new1, 1)

# 刷新按钮文字加大到 14px
old2 = ('.ghost-btn {\n'
        '    -fx-background-color: rgba(255, 255, 255, 0.08);\n'
        '    -fx-text-fill: #e8eaed;\n')
assert c.count(old2) == 1
new2 = ('.ghost-btn {\n'
        '    -fx-background-color: rgba(255, 255, 255, 0.08);\n'
        '    -fx-text-fill: #e8eaed;\n'
        '    -fx-font-size: 14px;\n')
c = c.replace(old2, new2, 1)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)
print("font size bumped")
