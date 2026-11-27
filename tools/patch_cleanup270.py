# -*- coding: utf-8 -*-
import io

DV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(DV, "r", encoding="utf-8", newline="") as f:
    d = f.read().replace("\r\n", "\n")
line = "        tabs.getSelectionModel().select(2); // TEMP-LOADER-SELECT\n"
assert d.count(line) == 1, ("DV", d.count(line))
d = d.replace(line, "")
with io.open(DV, "w", encoding="utf-8", newline="\n") as f:
    f.write(d)

LA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\LauncherApp.java"
with io.open(LA, "r", encoding="utf-8", newline="") as f:
    s = f.read().replace("\r\n", "\n")
vline = "        javafx.application.Platform.runLater(this::showDownload); // TEMP-VERIFY\n"
assert s.count(vline) == 1, ("LA", s.count(vline))
s = s.replace(vline, "")
with io.open(LA, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)

print("OK cleanup temp")
