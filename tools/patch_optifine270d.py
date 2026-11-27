# -*- coding: utf-8 -*-
import io, sys
MT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(MT, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")
old = '        return new ModrinthApi.Hit(OPTIFINE_ID, "optifine", "OptiFine",\n'
new = '        return new ModrinthApi.Hit(OPTIFINE_ID, OPTIFINE_ID, "OptiFine",\n'
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new)
with io.open(MT, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK slug")
