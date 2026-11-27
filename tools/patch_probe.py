# -*- coding: utf-8 -*-
# 临时探针：在下载钮上挂 mouseClicked 与 Action 日志，定位点击事件是否到达按钮。
import io
JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

old = ('        Button installBtn = new Button("下载");\n'
       '        installBtn.getStyleClass().add("jwh-fab");\n'
       '        installBtn.setOnAction(e -> {\n'
       '            VersionManifest.Version v = list.getSelectionModel().getSelectedItem();\n')
new = ('        Button installBtn = new Button("下载");\n'
       '        installBtn.getStyleClass().add("jwh-fab");\n'
       '        installBtn.setOnMouseClicked(e -> System.out.println("[DEBUG] fab mouseClicked " + e.getButton()));\n'
       '        installBtn.setOnAction(e -> {\n'
       '            System.out.println("[DEBUG] fab action fired, selected=" + (list.getSelectionModel().getSelectedItem()==null ? "null" : list.getSelectionModel().getSelectedItem().id()));\n'
       '            VersionManifest.Version v = list.getSelectionModel().getSelectedItem();\n')
assert old in src
src = src.replace(old, new)
with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK probe")
