# -*- coding: utf-8 -*-
import io
css = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(css, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")
old = "    -fx-padding: 10 18 10 18;\n    -fx-cursor: hand;\n}"
new = "    -fx-padding: 6 18 6 18;\n    -fx-cursor: hand;\n}"
assert t.count(old) == 1, "padding anchor not found"
t = t.replace(old, new, 1)
with io.open(css, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("padding -> 6 18 6 18")
