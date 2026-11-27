# -*- coding: utf-8 -*-
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

anchor = ".jwh-download-tabs.tab-pane .tab-header-background {\n    -fx-background-color: #733e6f;\n    -fx-background-insets: 0;\n}\n"
add = anchor + "\n.jwh-download-tabs .tab-content-area {\n    -fx-background-color: transparent;\n}\n"
assert anchor in src, "css anchor not found"
if ".jwh-download-tabs .tab-content-area" not in src:
    src = src.replace(anchor, add)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK css patch15")
