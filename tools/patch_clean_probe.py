# -*- coding: utf-8 -*-
# 清理临时探针代码，恢复干净的按钮注册。
import io
JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

old = ('        installBtn.getStyleClass().add("jwh-fab");\n'
       '        installBtn.setOnMouseClicked(e -> System.out.println("[DEBUG] fab mouseClicked " + e.getButton()));\n'
       '        installBtn.setOnAction(e -> {\n'
       '            System.out.println("[DEBUG] fab action fired, selected=" + (list.getSelectionModel().getSelectedItem()==null ? "null" : list.getSelectionModel().getSelectedItem().id()));\n'
       '            VersionManifest.Version v = list.getSelectionModel().getSelectedItem();\n')
new = ('        installBtn.getStyleClass().add("jwh-fab");\n'
       '        installBtn.setOnAction(e -> {\n'
       '            VersionManifest.Version v = list.getSelectionModel().getSelectedItem();\n')
assert old in src, "probe block not found"
src = src.replace(old, new)
with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK cleaned probe")
