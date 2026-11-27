# -*- coding: utf-8 -*-
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

pairs = [
    ("""    -fx-background-color: #2c2336;
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
    -fx-background-radius: 10;
    -fx-border-color: transparent;
    -fx-border-radius: 10;
    -fx-padding: 7 14;""",
     """    -fx-background-color: #2c2336;
    -fx-text-fill: #ffffff;
    -fx-font-size: 15px;
    -fx-background-radius: 10;
    -fx-border-color: transparent;
    -fx-border-radius: 10;
    -fx-padding: 7 10;"""),
    (""".jwh-capsule .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
}""",
     """.jwh-capsule .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-font-size: 15px;
}"""),
]
for old, new in pairs:
    assert t.count(old) == 1, "css anchor missing"
    t = t.replace(old, new, 1)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("step5b capsule css ok")
