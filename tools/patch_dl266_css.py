# -*- coding: utf-8 -*-
import io
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

def rep(old, new, tag):
    global t
    assert t.count(old) == 1, f"anchor not unique/found: {tag}"
    t = t.replace(old, new, 1)
    print("ok:", tag)

# 标签栏背景：紫 → 透明
rep(
    '''.jwh-download-tabs.tab-pane .tab-header-background {
    -fx-background-color: #733e6f;
    -fx-background-insets: 0;
}''',
    '''.jwh-download-tabs.tab-pane .tab-header-background {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
}''',
    "tab header transparent")

# 标签：竖线分隔 + 紧凑间距
rep(
    '''.jwh-download-tabs.tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-padding: 6 18 6 18;
    -fx-cursor: hand;
}''',
    '''.jwh-download-tabs.tab-pane .tab {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent rgba(255,255,255,0.14) transparent transparent;
    -fx-border-width: 0 1 0 0;
    -fx-padding: 10 18 10 18;
    -fx-cursor: hand;
}
.jwh-download-tabs.tab-pane .tab:last-child {
    -fx-border-color: transparent;
}''',
    "tab separator")

# 选中标签：粉色下划线
rep(
    '''.jwh-download-tabs.tab-pane .tab:selected,
.jwh-download-tabs.tab-pane .tab:selected:focused,
.jwh-download-tabs.tab-pane .tab:selected:hover {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent;
    -fx-border-width: 0;
    -fx-background-radius: 0;
    -fx-focus-color: transparent;
    -fx-faint-focus-color: transparent;
}''',
    '''.jwh-download-tabs.tab-pane .tab:selected,
.jwh-download-tabs.tab-pane .tab:selected:focused,
.jwh-download-tabs.tab-pane .tab:selected:hover {
    -fx-background-color: transparent;
    -fx-background-insets: 0;
    -fx-border-color: transparent transparent #d982c2 transparent;
    -fx-border-width: 0 0 2 0;
    -fx-background-radius: 0;
    -fx-focus-color: transparent;
    -fx-faint-focus-color: transparent;
}''',
    "tab selected underline")

# 标签文字配色
rep(
    '''.jwh-download-tabs.tab-pane .tab .tab-label {
    -fx-text-fill: rgba(255, 255, 255, 0.62);
    -fx-font-size: 17px;
}
.jwh-download-tabs.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #ffffff;
    -fx-font-weight: bold;
    -fx-font-size: 20px;
}''',
    '''.jwh-download-tabs.tab-pane .tab .tab-label {
    -fx-text-fill: rgba(255, 255, 255, 0.55);
    -fx-font-size: 16px;
}
.jwh-download-tabs.tab-pane .tab:selected .tab-label {
    -fx-text-fill: #ffffff;
    -fx-font-weight: bold;
    -fx-font-size: 17px;
}''',
    "tab label colors")

# 下拉胶囊：深色控件底
rep(
    '''.jwh-capsule {
    -fx-background-color: rgba(255, 255, 255, 0.10);
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
    -fx-background-radius: 10;
    -fx-border-color: transparent;
    -fx-border-radius: 10;
    -fx-padding: 7 14;
}
.jwh-capsule .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
}''',
    '''.jwh-capsule {
    -fx-background-color: #2c2336;
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
    -fx-background-radius: 10;
    -fx-border-color: transparent;
    -fx-border-radius: 10;
    -fx-padding: 7 14;
}
.jwh-capsule .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-font-size: 16px;
}''',
    "capsule dark")

# 搜索框：包装层深色底，输入框透明
rep(
    '''/* 白色搜索框 */
.jwh-search {
    -fx-background-color: #ffffff;
    -fx-text-fill: #1f1529;
    -fx-background-radius: 10;
    -fx-border-radius: 10;
    -fx-padding: 7 12;
    -fx-font-size: 13px;
}
.jwh-search .text {
    -fx-text-fill: #1f1529;
}
.jwh-search .prompt-text {
    -fx-text-fill: rgba(0, 0, 0, 0.30);
}''',
    '''/* 深色搜索框：包装层提供圆角底，输入框透明 */
.jwh-search-wrap {
    -fx-background-color: #2c2336;
    -fx-background-radius: 10;
    -fx-border-radius: 10;
    -fx-padding: 7 12;
}
.jwh-search {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-background-radius: 10;
    -fx-border-radius: 10;
    -fx-padding: 0;
    -fx-font-size: 15px;
}
.jwh-search .text {
    -fx-text-fill: #ffffff;
}
.jwh-search .prompt-text {
    -fx-text-fill: rgba(255, 255, 255, 0.40);
}''',
    "search dark")

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("css done")
