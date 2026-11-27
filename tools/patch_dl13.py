# -*- coding: utf-8 -*-
# 补丁13：版本 Tab 深紫筛选条基底左右扩充至内置窗口两端（直角铺满），列表保留缩进。
import io
JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

# 1) 版本 Tab 外层 box 左右内边距清零（上下保留）
old_box = "        box.setPadding(new Insets(12));"
new_box = "        box.setPadding(new Insets(12, 0, 12, 0));"
assert old_box in src, "version box padding not found"
src = src.replace(old_box, new_box, 1)

# 2) 筛选条：圆角改直角 + 负左右 margin 抵消 page 的 12 内边距，铺到窗口两端
old_bar = ("        HBox filterBar = new HBox(10);\n"
           "        filterBar.setAlignment(Pos.CENTER_LEFT);\n"
           "        filterBar.setStyle(\"-fx-background-color:#1f1529; -fx-background-radius:12; -fx-padding:10 12;\");")
new_bar = ("        HBox filterBar = new HBox(10);\n"
           "        filterBar.setAlignment(Pos.CENTER_LEFT);\n"
           "        filterBar.setStyle(\"-fx-background-color:#1f1529; -fx-background-radius:0; -fx-padding:10 12;\");\n"
           "        VBox.setMargin(filterBar, new Insets(0, -12, 0, -12));")
assert old_bar in src, "filterBar block not found"
src = src.replace(old_bar, new_bar)

# 3) 列表区补回左右 12 缩进，避免跟随筛选条贴边
old_list = ("        StackPane listWrap = new StackPane(list, emptyLabel, fabLayer);\n"
            "        VBox.setVgrow(listWrap, Priority.ALWAYS);")
new_list = ("        StackPane listWrap = new StackPane(list, emptyLabel, fabLayer);\n"
            "        VBox.setVgrow(listWrap, Priority.ALWAYS);\n"
            "        VBox.setMargin(listWrap, new Insets(0, 12, 0, 12));")
assert old_list in src, "listWrap block not found"
src = src.replace(old_list, new_list)

with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch13")
