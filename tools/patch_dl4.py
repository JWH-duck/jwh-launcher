# -*- coding: utf-8 -*-
# 补丁4：版本列表去外圈边框；未选中行也铺浅深紫条（对标稿每行底色）。
import io
P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(P, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

old_list = 'list.setStyle("-fx-background-color:transparent; -fx-control-inner-background:transparent;");'
new_list = 'list.setStyle("-fx-background-color:transparent; -fx-control-inner-background:transparent; -fx-border-color:transparent; -fx-border-width:0; -fx-background-radius:0; -fx-border-radius:0; -fx-padding:4 0;");'
assert old_list in src, "list style not found"
src = src.replace(old_list, new_list)

old_row = ': "-fx-background-color:transparent; -fx-background-radius:8; -fx-padding:12 14;");'
new_row = ': "-fx-background-color:rgba(20,20,20,0.12); -fx-background-radius:8; -fx-padding:12 14;");'
assert old_row in src, "row transparent branch not found"
src = src.replace(old_row, new_row)

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch4")
