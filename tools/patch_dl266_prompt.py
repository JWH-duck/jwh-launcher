# -*- coding: utf-8 -*-
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    c = f.read().replace("\r\n", "\n")

old = """.jwh-search .prompt-text {
    -fx-text-fill: rgba(255, 255, 255, 0.55);
}"""
new = """.jwh-search .prompt-text {
    -fx-prompt-text-fill: rgba(255, 255, 255, 0.55);
}"""
assert c.count(old) == 1, "prompt rule not found"
c = c.replace(old, new, 1)
with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)
print("prompt fill fixed to -fx-prompt-text-fill")
