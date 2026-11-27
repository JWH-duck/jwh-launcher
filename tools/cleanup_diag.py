# -*- coding: utf-8 -*-
import io

# 1) 去掉 CommunityView 三处诊断日志
CV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\CommunityView.java"
with io.open(CV, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")
for line in [
    '        System.out.println("[map] loadMaps reset=" + reset + " q=" + q);\n',
    '            System.out.println("[map] ok res=" + (res == null ? "null" : res.items.size() + " total=" + res.total));\n',
    '            System.out.println("[map] err: " + err);\n',
]:
    assert s.count(line) == 1, line
    s = s.replace(line, "", 1)
with io.open(CV, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)

# 2) 去掉 LauncherApp 临时注入行
LA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\LauncherApp.java"
with io.open(LA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")
inj = "        Platform.runLater(this::showCommunity); // TEMP-VERIFY\n"
assert t.count(inj) == 1
t = t.replace(inj, "", 1)
with io.open(LA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)

print("diagnostics + temp inject removed")
