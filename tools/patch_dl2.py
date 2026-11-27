# -*- coding: utf-8 -*-
# 补丁2：下载内容区铺紫底、放宽列表行高。逐行替换，避开跨行字符串。
import io

P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(P, "r", encoding="utf-8") as f:
    lines = f.read().replace("\r\n", "\n").split("\n")

DL_BG = "#733e6f"
out = []
for ln in lines:
    if ("page.setStyle" in ln or "wrap.setStyle" in ln or "tabs.setStyle" in ln) and "BG_LIGHT" in ln:
        ln = ln.replace("Ui.BG_LIGHT", DL_BG)
    out.append(ln)
src = "\n".join(out)

old_sel = '''? "-fx-background-color:rgba(20,20,20,0.25); -fx-background-radius:8;"
                        : "-fx-background-color:transparent; -fx-background-radius:8;");'''
new_sel = '''? "-fx-background-color:rgba(20,20,20,0.25); -fx-background-radius:8; -fx-padding:12 14;"
                        : "-fx-background-color:transparent; -fx-background-radius:8; -fx-padding:12 14;");'''
assert old_sel in src, "row style block not found"
src = src.replace(old_sel, new_sel)

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch2")
