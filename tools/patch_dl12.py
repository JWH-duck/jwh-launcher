# -*- coding: utf-8 -*-
# 补丁12：下载页标题栏与内容标签行之间，加一条近黑分隔色块（对标设计稿）。
import io
JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

# 1) 补 Region import
old_imp = "import javafx.scene.layout.Priority;\nimport javafx.scene.layout.StackPane;"
new_imp = "import javafx.scene.layout.Priority;\nimport javafx.scene.layout.Region;\nimport javafx.scene.layout.StackPane;"
assert old_imp in src, "import block not found"
src = src.replace(old_imp, new_imp)

# 2) 字段：顶部黑色分隔条
old_fld = "    /** 版本列表关键词搜索框（按版本 id 过滤）。 */\n    private TextField searchBox;"
new_fld = ("    /** 版本列表关键词搜索框（按版本 id 过滤）。 */\n    private TextField searchBox;\n"
           "    /** 标题栏与内容标签行之间的近黑分隔色块（对标设计稿）。 */\n"
           "    private final Region topDivider = new Region();")
assert old_fld in src, "field block not found"
src = src.replace(old_fld, new_fld)

# 3) 构造：分隔条初始化 + page 顶部 padding 归零（标签行紧贴黑条）
old_pad = ("        page.setStyle(\"-fx-background-color:\" + \"#733e6f\" + \"; -fx-background-radius:20; -fx-background-insets:0;\"\n"
            "                + \"-fx-border-radius:20; -fx-border-color:\" + \"transparent\" + \";\");\n"
            "        page.setPadding(new Insets(12));\n"
            "        page.getChildren().add(tabs);")
new_pad = ("        page.setStyle(\"-fx-background-color:\" + \"#733e6f\" + \"; -fx-background-radius:20; -fx-background-insets:0;\"\n"
           "                + \"-fx-border-radius:20; -fx-border-color:\" + \"transparent\" + \";\");\n"
           "        page.setPadding(new Insets(0, 12, 12, 12));\n"
           "        topDivider.setStyle(\"-fx-background-color:#0d0d0e; -fx-background-insets:0;\");\n"
           "        topDivider.setPrefHeight(18);\n"
           "        page.getChildren().add(tabs);")
assert old_pad in src, "page padding block not found"
src = src.replace(old_pad, new_pad)

# 4) root()：分隔条置于 page 之上，铺满内容宽
old_root = ("    public Node root() {\n"
            "        // 垫层：与面板同色铺满整个下载中心区域，让圆角弧线外的角落显示同色而非窗口底色\n"
            "        StackPane wrap = new StackPane();\n"
            "        wrap.setStyle(\"-fx-background-color:\" + \"#733e6f\" + \";\");\n"
            "        wrap.getChildren().add(page);\n"
            "        return wrap;\n"
            "    }")
new_root = ("    public Node root() {\n"
            "        // 垫层：与面板同色铺满整个下载中心区域，让圆角弧线外的角落显示同色而非窗口底色\n"
            "        StackPane wrap = new StackPane();\n"
            "        wrap.setStyle(\"-fx-background-color:\" + \"#733e6f\" + \";\");\n"
            "        VBox box = new VBox(0);\n"
            "        box.getChildren().addAll(topDivider, page);\n"
            "        wrap.getChildren().add(box);\n"
            "        return wrap;\n"
            "    }")
assert old_root in src, "root block not found"
src = src.replace(old_root, new_root)

with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch12")
