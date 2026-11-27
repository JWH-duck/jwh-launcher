# -*- coding: utf-8 -*-
import io
P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\CommunityView.java"
with io.open(P, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")

a1 = '        mapStatus.setText("正在加载…");\n'
assert s.count(a1) == 1
s = s.replace(a1, a1 + '        System.out.println("[map] loadMaps reset=" + reset + " q=" + q);\n', 1)

a2 = '            mapLoading = false;\n            if (res == null) return;\n'
assert s.count(a2) == 1
s = s.replace(a2, '            mapLoading = false;\n            System.out.println("[map] ok res=" + (res == null ? "null" : res.items.size() + " total=" + res.total));\n            if (res == null) return;\n', 1)

a3 = '        }, err -> {\n            mapLoading = false;\n'
assert s.count(a3) == 1
s = s.replace(a3, '        }, err -> {\n            mapLoading = false;\n            System.out.println("[map] err: " + err);\n', 1)

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("diag logs inserted")
