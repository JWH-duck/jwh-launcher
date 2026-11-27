# -*- coding: utf-8 -*-
import io
MT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(MT, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")

# 新网格卡片（357/361）
old_t = '        Label titleLabel = new Label(ModNames.display(h.slug(), h.title()));\n'
new_t = '        Label titleLabel = new Label(isBuiltin(h) ? h.title() : ModNames.display(h.slug(), h.title()));\n'
old_d = '        Label descLabel = new Label(ModNames.desc(h.slug(), h.title(), h.description()));\n'
new_d = '        Label descLabel = new Label(isBuiltin(h) ? h.description() : ModNames.desc(h.slug(), h.title(), h.description()));\n'
# 两处卡片各出现一次 title / desc
assert s.count(old_t) == 2, s.count(old_t)
assert s.count(old_d) == 2, s.count(old_d)
s = s.replace(old_t, new_t).replace(old_d, new_d)

with io.open(MT, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK bypass names")
