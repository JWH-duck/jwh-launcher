# -*- coding: utf-8 -*-
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    c = f.read().replace("\r\n", "\n")

old = """.combo-box-popup .list-view {
    -fx-background-color: rgba(30, 35, 44, 0.96);
    -fx-background-radius: 6;
    -fx-border-color: rgba(255, 255, 255, 0.14);
}
.combo-box-popup .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #e8eaed;
}
.combo-box-popup .list-cell:hover {
    -fx-background-color: rgba(63, 169, 245, 0.12);
}
.combo-box-popup .list-cell:filled:selected {
    -fx-background-color: #3FA9F5;
    -fx-text-fill: white;
}"""

new = """.combo-box-popup .list-view {
    -fx-background-color: #2c2336;
    -fx-background-radius: 10;
    -fx-border-color: rgba(255, 255, 255, 0.14);
    -fx-padding: 4;
}
.combo-box-popup .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #e8eaed;
    -fx-background-radius: 8;
    -fx-padding: 8 12;
}
.combo-box-popup .list-cell:hover {
    -fx-background-color: rgba(255, 255, 255, 0.08);
}
.combo-box-popup .list-cell:filled:selected {
    -fx-background-color: #d982c2;
    -fx-text-fill: #ffffff;
}"""

assert c.count(old) == 1, "popup block not found"
c = c.replace(old, new, 1)
with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)
print("combo popup dark + pink selected")
