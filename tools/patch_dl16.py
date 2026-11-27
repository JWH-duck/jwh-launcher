# -*- coding: utf-8 -*-
# 补丁16：fabLayer 浮动下载钮层鼠标穿透，避免它盖住 ListView 拦截滚轮与滚动条拖拽；
# 仅下载钮保留可点；空态标签也穿透，不挡列表事件。
import io

JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

# 1) 空态标签穿透
old_empty = 'emptyLabel.setVisible(false);'
new_empty = 'emptyLabel.setVisible(false);\n        emptyLabel.setMouseTransparent(true);'
assert old_empty in src
src = src.replace(old_empty, new_empty, 1)

# 2) fabLayer 穿透，按钮保留可点
old_fab = ('AnchorPane fabLayer = new AnchorPane();\n'
           '        AnchorPane.setRightAnchor(installBtn, 16.0);\n'
           '        AnchorPane.setBottomAnchor(installBtn, 16.0);\n'
           '        fabLayer.getChildren().add(installBtn);')
new_fab = ('AnchorPane fabLayer = new AnchorPane();\n'
           '        fabLayer.setMouseTransparent(true);\n'
           '        AnchorPane.setRightAnchor(installBtn, 16.0);\n'
           '        AnchorPane.setBottomAnchor(installBtn, 16.0);\n'
           '        installBtn.setMouseTransparent(false);\n'
           '        fabLayer.getChildren().add(installBtn);')
assert old_fab in src, "fab block not found"
src = src.replace(old_fab, new_fab)

with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch16")
