# -*- coding: utf-8 -*-
import io
F = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(F, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")

old = """.jwh-download-tabs.tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent rgba(255,255,255,0.14) transparent transparent;
    -fx-border-width: 0 1 0 0;
    -fx-padding: 10 18 10 18;
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
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new)
with io.open(F, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK remove tab right divider")
