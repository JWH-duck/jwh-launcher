# -*- coding: utf-8 -*-
# 补丁11：下载页标签 hover / 选中悬停残留的浅色方块基底彻底去掉。
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    css = f.read().replace("\r\n", "\n")

# 1) hover 背景由半透白改为透明
old_hover = """.jwh-download-tabs.tab-pane .tab:hover {
    -fx-background-color: rgba(255, 255, 255, 0.08);
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
}"""
new_hover = """.jwh-download-tabs.tab-pane .tab:hover {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
}"""
assert old_hover in css, "hover rule not found"
css = css.replace(old_hover, new_hover)

# 2) selected 规则选择器补上 :selected:hover，背景/边框/焦点全透明
old_sel = """.jwh-download-tabs.tab-pane .tab:selected,
.jwh-download-tabs.tab-pane .tab:selected:focused {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-background-radius: 0;
    -fx-focus-color: transparent;
    -fx-faint-focus-color: transparent;
}"""
new_sel = """.jwh-download-tabs.tab-pane .tab:selected,
.jwh-download-tabs.tab-pane .tab:selected:focused,
.jwh-download-tabs.tab-pane .tab:selected:hover {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-background-radius: 0;
    -fx-focus-color: transparent;
    -fx-faint-focus-color: transparent;
}"""
assert old_sel in css, "selected rule not found"
css = css.replace(old_sel, new_sel)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(css)
print("OK patch11")
