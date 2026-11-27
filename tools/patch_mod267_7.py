# -*- coding: utf-8 -*-
import io
CV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\CommunityView.java"
with io.open(CV, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

pairs = [
    ('mapStatus.setStyle("-fx-text-fill:#6b7280; -fx-font-size:11px;");',
     'mapStatus.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:11px;");'),
    ('empty.setStyle("-fx-text-fill:#6b7280; -fx-font-size:12px;");',
     'empty.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");'),
]
for old, new in pairs:
    assert t.count(old) == 1, "anchor: %s" % old
    t = t.replace(old, new, 1)

with io.open(CV, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("step7 community dim text ok")
