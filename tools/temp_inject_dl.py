# -*- coding: utf-8 -*-
import io
P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\LauncherApp.java"
with io.open(P, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")
anchor = "        content.getChildren().add(homeView.root());\n"
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + "        Platform.runLater(this::showDownload); // TEMP-VERIFY\n", 1)
with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("temp download inject")
