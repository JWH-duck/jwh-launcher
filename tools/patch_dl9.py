# -*- coding: utf-8 -*-
# 补丁9：下载页标签字号再大一点（仅字号，位置不变）。
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    css = f.read().replace("\r\n", "\n")

old_norm = """.jwh-download-tabs.tab-pane .tab .tab-label {
    -fx-text-fill: rgba(255, 255, 255, 0.62);
    -fx-font-size: 15px;
}"""
new_norm = """.jwh-download-tabs.tab-pane .tab .tab-label {
    -fx-text-fill: rgba(255, 255, 255, 0.62);
    -fx-font-size: 17px;
}"""
assert old_norm in css
css = css.replace(old_norm, new_norm)

old_sel = """.jwh-download-tabs.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #ffffff;
    -fx-font-weight: bold;
    -fx-font-size: 18px;
}"""
new_sel = """.jwh-download-tabs.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #ffffff;
    -fx-font-weight: bold;
    -fx-font-size: 20px;
}"""
assert old_sel in css
css = css.replace(old_sel, new_sel)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(css)
print("OK patch9")
