# -*- coding: utf-8 -*-
# 补丁14：page 左右内边距清零，让版本 Tab 筛选条铺满两端；移除失效的负 margin。
import io
JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

# 1) page 左右 padding 清零（上下保留）
old_page = "        page.setPadding(new Insets(0, 12, 12, 12));"
new_page = "        page.setPadding(new Insets(0, 0, 12, 0));"
assert old_page in src, "page padding not found"
src = src.replace(old_page, new_page)

# 2) 移除 filterBar 负 margin（page 已无左右 padding，不再需要）
old_margin = "        VBox.setMargin(filterBar, new Insets(0, -12, 0, -12));\n"
assert old_margin in src, "filterBar margin not found"
src = src.replace(old_margin, "")

with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch14")
