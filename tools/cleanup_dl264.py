# -*- coding: utf-8 -*-
import io
LA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\LauncherApp.java"
with io.open(LA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")
inj = "        javafx.application.Platform.runLater(this::showDownload); // TEMP-VERIFY\n"
assert t.count(inj) == 1, "inject line not found"
t = t.replace(inj, "", 1)
with io.open(LA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("temp inject removed")
