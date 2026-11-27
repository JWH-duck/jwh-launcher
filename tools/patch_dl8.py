# -*- coding: utf-8 -*-
# 补丁8：下载页六个标签字体变大一点、整体下移一点。
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    css = f.read().replace("\r\n", "\n")

# 1) 标签内边距：顶部加大，文字整体下移
old_pad = "    -fx-padding: 9 18;"
new_pad = "    -fx-padding: 15 18 10 18;"
assert old_pad in css, "padding not found"
css = css.replace(old_pad, new_pad)

# 2) 未选中标签字号 13 -> 15
old_norm = """.jwh-download-tabs.tab-pane .tab .tab-label {
    -fx-text-fill: rgba(255, 255, 255, 0.62);
    -fx-font-size: 13px;
}"""
new_norm = """.jwh-download-tabs.tab-pane .tab .tab-label {
    -fx-text-fill: rgba(255, 255, 255, 0.62);
    -fx-font-size: 15px;
}"""
assert old_norm in css, "normal label rule not found"
css = css.replace(old_norm, new_norm)

# 3) 选中标签字号 16 -> 18
old_sel = """.jwh-download-tabs.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #ffffff;
    -fx-font-weight: bold;
    -fx-font-size: 16px;
}"""
new_sel = """.jwh-download-tabs.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #ffffff;
    -fx-font-weight: bold;
    -fx-font-size: 18px;
}"""
assert old_sel in css, "selected label rule not found"
css = css.replace(old_sel, new_sel)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(css)
print("OK patch8")
