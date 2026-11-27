# -*- coding: utf-8 -*-
import io
css = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(css, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

old_capsule = """.jwh-capsule {
    -fx-background-color: rgba(255, 255, 255, 0.10);
    -fx-text-fill: #ffffff;
    -fx-font-size: 14px;
    -fx-background-radius: 10;
    -fx-border-color: transparent;
    -fx-border-radius: 10;
    -fx-padding: 6 12;
}
.jwh-capsule .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
}"""
new_capsule = """.jwh-capsule {
    -fx-background-color: rgba(255, 255, 255, 0.10);
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
    -fx-background-radius: 10;
    -fx-border-color: transparent;
    -fx-border-radius: 10;
    -fx-padding: 7 14;
}
.jwh-capsule .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
}"""
assert t.count(old_capsule) == 1, "capsule block not found"
t = t.replace(old_capsule, new_capsule, 1)

old_ghost = """.ghost-btn {
    -fx-background-color: rgba(255, 255, 255, 0.08);
    -fx-text-fill: #e8eaed;
    -fx-font-size: 14px;"""
new_ghost = """.ghost-btn {
    -fx-background-color: rgba(255, 255, 255, 0.08);
    -fx-text-fill: #e8eaed;
    -fx-font-size: 16px;"""
assert t.count(old_ghost) == 1, "ghost block not found"
t = t.replace(old_ghost, new_ghost, 1)

with io.open(css, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("font bumped to 16px")
