# -*- coding: utf-8 -*-
# 补丁5：去除下载面板外圈浅色描边；版本列表加专属类清零边框。
import io
P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(P, "r", encoding="utf-8") as f:
    lines = f.read().replace("\r\n", "\n").split("\n")

out = []
for ln in lines:
    if "page.setStyle" in ln and "Ui.BORDER" in ln:
        ln = ln.replace("Ui.BORDER", '"transparent"')
    if "list.setStyle(" in ln and "jwh-dl-list" not in ln:
        ln = ln.replace("list.setStyle(", 'list.getStyleClass().add("jwh-dl-list");\n        list.setStyle(', 1)
    out.append(ln)
src = "\n".join(out)
with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)

# CSS：版本列表专属类，彻底清零外框边框/背景
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    css = f.read().replace("\r\n", "\n")
rule = """
/* 下载页版本列表：去除外圈浅色描边，背景透明透出紫底 */
.jwh-dl-list, .jwh-dl-list .list-cell {
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-background-color: transparent;
}
"""
if ".jwh-dl-list" not in css:
    css = css.rstrip() + "\n" + rule
with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(css)

print("OK patch5")
