# -*- coding: utf-8 -*-
import io
JA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JA, "r", encoding="utf-8") as f:
    j = f.read().replace("\r\n", "\n")

old = """        javafx.scene.shape.Circle lens = new javafx.scene.shape.Circle(6.5, 6.5, 5.5);
        lens.setFill(null);
        lens.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.75));
        lens.setStrokeWidth(1.4);
        javafx.scene.shape.Line searchHandle = new javafx.scene.shape.Line(10.5, 10.5, 14.0, 14.0);
        searchHandle.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.75));
        searchHandle.setStrokeWidth(1.4);
        javafx.scene.layout.Pane searchGlyph = new javafx.scene.layout.Pane(lens, searchHandle);
        searchGlyph.setPrefSize(18, 18);
        searchGlyph.setMinSize(18, 18);"""

new = """        javafx.scene.shape.Circle lens = new javafx.scene.shape.Circle(9, 9, 5.5);
        lens.setFill(null);
        lens.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.75));
        lens.setStrokeWidth(1.4);
        javafx.scene.shape.Line searchHandle = new javafx.scene.shape.Line(12.6, 12.6, 16.0, 16.0);
        searchHandle.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.75));
        searchHandle.setStrokeWidth(1.4);
        javafx.scene.layout.StackPane searchGlyph = new javafx.scene.layout.StackPane(lens, searchHandle);
        searchGlyph.setPrefSize(18, 18);
        searchGlyph.setMinSize(18, 18);
        searchGlyph.setMaxSize(18, 18);"""

assert j.count(old) == 1, "glyph block not found"
j = j.replace(old, new, 1)
with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write(j)
print("glyph centered in StackPane")

CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    c = f.read().replace("\r\n", "\n")
old_prompt = ".jwh-search .prompt-text {\n    -fx-text-fill: rgba(255, 255, 255, 0.40);\n}"
new_prompt = ".jwh-search .prompt-text {\n    -fx-text-fill: rgba(255, 255, 255, 0.55);\n}"
assert c.count(old_prompt) == 1, "prompt rule not found"
c = c.replace(old_prompt, new_prompt, 1)
with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)
print("prompt brightened")
