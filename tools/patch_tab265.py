# -*- coding: utf-8 -*-
import io
css = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(css, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

old = """.jwh-download-tabs.tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-padding: 23 18 5 18;
    -fx-cursor: hand;
}"""
new = """.jwh-download-tabs.tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-padding: 10 18 10 18;
    -fx-cursor: hand;
}"""
assert t.count(old) == 1, "download tab block not found"
t = t.replace(old, new, 1)

with io.open(css, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("tab padding tightened to 10 18 10 18")
