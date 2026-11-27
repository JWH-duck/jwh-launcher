# -*- coding: utf-8 -*-
import io
LA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\LauncherApp.java"
with io.open(LA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

anchor = """        javafx.application.Platform.runLater(() -> {
            if (homeView != null) homeView.autoGreet();
        });
"""
inj = anchor + "        javafx.application.Platform.runLater(this::showDownload); // TEMP-VERIFY\n"
assert t.count(anchor) == 1, "anchor not found"
assert "TEMP-VERIFY" not in t
t = t.replace(anchor, inj, 1)
with io.open(LA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("temp inject added")
