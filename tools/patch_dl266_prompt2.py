# -*- coding: utf-8 -*-
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    c = f.read().replace("\r\n", "\n")

old = """.jwh-search {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-background-radius: 10;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-border-radius: 10;
    -fx-padding: 0 4;
    -fx-font-size: 15px;
}"""
new = """.jwh-search {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-prompt-text-fill: rgba(255, 255, 255, 0.55);
    -fx-background-radius: 10;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-border-radius: 10;
    -fx-padding: 0 4;
    -fx-font-size: 15px;
}"""
assert c.count(old) == 1, "jwh-search block not found"
c = c.replace(old, new, 1)

old2 = """.jwh-search .prompt-text {
    -fx-prompt-text-fill: rgba(255, 255, 255, 0.55);
}"""
new2 = """.jwh-search .prompt-text {
    -fx-text-fill: rgba(255, 255, 255, 0.55);
}"""
assert c.count(old2) == 1, "prompt node rule not found"
c = c.replace(old2, new2, 1)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)
print("prompt fill on control + node fallback")
