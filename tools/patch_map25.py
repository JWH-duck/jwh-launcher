# -*- coding: utf-8 -*-
# 补丁25：社区页搜索框跟随标题栏主紫 #733e6f 白字；「热门」下拉去除灰色底板，透明底白描边白箭头。
import io

CV = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\CommunityView.java"
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"

with io.open(CV, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")

# 1) 搜索框：紫底白字
old_search = '''        mapSearch.setStyle("-fx-background-color:#ffffff; -fx-text-fill:#1f2328;"
                + " -fx-prompt-text-fill:#9aa0a8; -fx-background-radius:10; -fx-border-radius:10;"
                + " -fx-border-color:rgba(255,255,255,0.14); -fx-padding:8 12; -fx-font-size:14px;"
                + " -fx-focus-color:transparent; -fx-faint-focus-color:transparent;");'''
new_search = '''        mapSearch.setStyle("-fx-background-color:#733e6f; -fx-text-fill:#ffffff;"
                + " -fx-prompt-text-fill:rgba(255,255,255,0.7); -fx-background-radius:10; -fx-border-radius:10;"
                + " -fx-border-color:rgba(255,255,255,0.18); -fx-padding:8 12; -fx-font-size:14px;"
                + " -fx-focus-color:transparent; -fx-faint-focus-color:transparent;");'''
assert s.count(old_search) == 1, "search style anchor"
s = s.replace(old_search, new_search, 1)

# 2) 下拉：去灰底板，加专用样式类
old_sort = '''        mapSort.setPrefWidth(96);
        mapSort.setStyle("-fx-background-color:#ffffff; -fx-text-fill:#1f2328;"
                + " -fx-background-radius:10; -fx-border-radius:10; -fx-border-color:rgba(255,255,255,0.14);"
                + " -fx-font-size:13px;");'''
new_sort = '''        mapSort.setPrefWidth(96);
        mapSort.getStyleClass().add("jwh-sort-combo");
        mapSort.setStyle("-fx-background-color:transparent; -fx-text-fill:#ffffff;"
                + " -fx-background-radius:10; -fx-border-radius:10; -fx-border-color:rgba(255,255,255,0.18);"
                + " -fx-font-size:13px;");'''
assert s.count(old_sort) == 1, "sort style anchor"
s = s.replace(old_sort, new_sort, 1)

with io.open(CV, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)

# 3) CSS 追加下拉专用规则
with io.open(CSS, "r", encoding="utf-8") as f:
    c = f.read().replace("\r\n", "\n")

css_add = '''

/* ===== 社区页排序下拉：透明底白描边，去除灰色底板与箭头灰块 ===== */
.jwh-sort-combo {
    -fx-background-color: transparent;
    -fx-border-color: rgba(255, 255, 255, 0.18);
    -fx-border-radius: 10;
    -fx-background-radius: 10;
    -fx-text-fill: #ffffff;
}
.jwh-sort-combo .arrow-button {
    -fx-background-color: transparent;
}
.jwh-sort-combo .arrow-button .arrow {
    -fx-background-color: #ffffff;
}
.jwh-sort-combo .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
}
'''
if "jwh-sort-combo" not in c:
    c = c.rstrip("\n") + "\n" + css_add

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)

print("OK patch25")
