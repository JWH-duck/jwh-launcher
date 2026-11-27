# -*- coding: utf-8 -*-
# 补丁18：去掉挡住列表滚动的 fabLayer 覆盖层；下载钮直接作为 listWrap(StackPane) 最上层子节点，
# 对齐右下。避免父层 mouseTransparent 连带把按钮穿透，导致按钮点不动。
import io

JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

old = ('        // ===== 列表区：空态居中 + 右下绿色圆形下载钮 =====\n'
       '        AnchorPane fabLayer = new AnchorPane();\n'
       '        fabLayer.setMouseTransparent(true);\n'
       '        AnchorPane.setRightAnchor(installBtn, 16.0);\n'
       '        AnchorPane.setBottomAnchor(installBtn, 16.0);\n'
       '        installBtn.setMouseTransparent(false);\n'
       '        fabLayer.getChildren().add(installBtn);\n'
       '        StackPane listWrap = new StackPane(list, emptyLabel, fabLayer);\n'
       '        VBox.setVgrow(listWrap, Priority.ALWAYS);\n'
       '        VBox.setMargin(listWrap, new Insets(0, 12, 0, 12));')

new = ('        // ===== 列表区：空态居中 + 右下绿色圆形下载钮 =====\n'
       '        StackPane listWrap = new StackPane(list, emptyLabel, installBtn);\n'
       '        StackPane.setAlignment(installBtn, Pos.BOTTOM_RIGHT);\n'
       '        StackPane.setMargin(installBtn, new Insets(0, 16, 16, 0));\n'
       '        VBox.setVgrow(listWrap, Priority.ALWAYS);\n'
       '        VBox.setMargin(listWrap, new Insets(0, 12, 0, 12));')

assert old in src, "fab block not found"
src = src.replace(old, new)

with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch18")
