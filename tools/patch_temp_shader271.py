# -*- coding: utf-8 -*-
import io
DV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(DV, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")
anchor = "        tabs.getTabs().addAll(versionTab, modTabT, loaderTabT, shaderPackTabT, rpTabT, modpackTabT);\n"
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + "        tabs.getSelectionModel().select(3); // TEMP-SHADER-SELECT\n")
with io.open(DV, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK temp select 3")
