# -*- coding: utf-8 -*-
import io
P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\CommunityView.java"
with io.open(P, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")
bad = '        System.out.println("[map] loadMaps reset=" + reset + " q=" + q);\n'
assert s.count(bad) == 1
s = s.replace(bad, "", 1)
anchor = '        String q = mapSearch == null ? "" : mapSearch.getText();\n'
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + '        System.out.println("[map] loadMaps reset=" + reset + " q=" + q);\n', 1)
with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("fixed")
