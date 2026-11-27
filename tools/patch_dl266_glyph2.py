# -*- coding: utf-8 -*-
import io
JA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JA, "r", encoding="utf-8") as f:
    j = f.read().replace("\r\n", "\n")

old = """        javafx.scene.layout.StackPane searchGlyph = new javafx.scene.layout.StackPane(lens, searchHandle);
        searchGlyph.setPrefSize(18, 18);
        searchGlyph.setMinSize(18, 18);
        searchGlyph.setMaxSize(18, 18);"""
new = """        javafx.scene.layout.Pane searchGlyph = new javafx.scene.layout.Pane(lens, searchHandle);
        searchGlyph.setPrefSize(18, 18);
        searchGlyph.setMinSize(18, 18);
        searchGlyph.setMaxSize(18, 18);"""
assert j.count(old) == 1, "stackpane glyph anchor not found"
j = j.replace(old, new, 1)
with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write(j)
print("glyph back to Pane absolute coords")
