# -*- coding: utf-8 -*-
import io, re
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

old_search = """.jwh-search {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-background-radius: 10;
    -fx-border-radius: 10;
    -fx-padding: 0;
    -fx-font-size: 15px;
}"""
new_search = """.jwh-search {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-background-radius: 10;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-border-radius: 10;
    -fx-padding: 0 4;
    -fx-font-size: 15px;
}
.jwh-search:focused {
    -fx-background-color: transparent;
    -fx-border-color: transparent;
    -fx-border-width: 0;
}"""
assert t.count(old_search) == 1, "jwh-search block not found"
t = t.replace(old_search, new_search, 1)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("css search border cleared")

JA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JA, "r", encoding="utf-8") as f:
    j = f.read().replace("\r\n", "\n")

anchor = "        HBox.setHgrow(searchBox, Priority.ALWAYS);\n"
add = anchor + "        HBox.setHgrow(searchWrap, Priority.ALWAYS);\n"
assert j.count(anchor) == 1, "searchBox hgrow anchor not found"
j = j.replace(anchor, add, 1)

# 放大镜线条提亮到 0.75
j = j.replace("Color.rgb(255, 255, 255, 0.5)", "Color.rgb(255, 255, 255, 0.75)")

with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write(j)
print("java search wrap hgrow + glyph brightened")
