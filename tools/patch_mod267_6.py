# -*- coding: utf-8 -*-
import io
BASE = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher"
JA = BASE + r"\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
CSS = BASE + r"\src\main\resources\style.css"

# ---- Java 宽度 + 占位 ----
with io.open(JA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")
jpairs = [
    ('        sourceBox.setPrefWidth(135);', '        sourceBox.setPrefWidth(150);'),
    ('        versionBox.setPrefWidth(260);', '        versionBox.setPrefWidth(280);'),
    ('        sortBox.setPrefWidth(130);', '        sortBox.setPrefWidth(150);'),
    ('return type == Type.MOD ? "搜索模组，支持中英文：jei / sodium / 钠 / 小地图" : "搜索材质包，例如：XeKr / 原版高亮";',
     'return type == Type.MOD ? "搜索模组…" : "搜索材质包，例如：XeKr / 原版高亮";'),
]
for old, new in jpairs:
    assert t.count(old) == 1, "java anchor: %s" % old
    t = t.replace(old, new, 1)
with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)

# ---- CSS 压缩下拉内部 padding ----
with io.open(CSS, "r", encoding="utf-8") as f:
    c = f.read().replace("\r\n", "\n")

c = c.replace("""    -fx-border-radius: 10;
    -fx-padding: 7 10;
}""", """    -fx-border-radius: 10;
    -fx-padding: 7 8;
}""", 1)

old_cell = """.jwh-capsule .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-font-size: 15px;
}"""
new_cell = """.jwh-capsule .list-cell {
    -fx-background-color: transparent;
    -fx-text-fill: #ffffff;
    -fx-font-size: 15px;
    -fx-padding: 0 6;
}
.jwh-capsule .arrow-button {
    -fx-background-color: transparent;
    -fx-padding: 0 6;
    -fx-pref-width: 20;
}"""
assert c.count(old_cell) == 1
c = c.replace(old_cell, new_cell, 1)

with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(c)
print("step6 css+java widths ok")
