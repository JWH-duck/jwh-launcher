# -*- coding: utf-8 -*-
# 补丁15：下载页底层换成与主页/社区同源的群像壁纸 + 黑罩压暗；page/tabs 整铺紫底改透明，
# 只保留标签行紫条、深紫筛选条、列表行深色条，列表下方与间隙透出壁纸。
import io, re

JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

# 1) 补充 import
old_imp = "import javafx.scene.layout.VBox;\n"
new_imp = ("import javafx.scene.layout.VBox;\n"
           "import javafx.scene.paint.Color;\n"
           "import javafx.scene.shape.Rectangle;\n"
           "import java.net.URL;\n")
assert old_imp in src
src = src.replace(old_imp, new_imp, 1)

# 2) tabs 整铺紫底改透明（标签行紫条由 CSS tab-header-background 提供）
old_tabs = 'tabs.setStyle("-fx-background-color:" + "#733e6f" + ";");'
new_tabs = 'tabs.setStyle("-fx-background-color:transparent;");'
assert old_tabs in src
src = src.replace(old_tabs, new_tabs)

# 3) page 整铺紫底改透明
old_page_style = ('page.setStyle("-fx-background-color:" + "#733e6f" + "; -fx-background-radius:20; -fx-background-insets:0;"\n'
                  '                + "-fx-border-radius:20; -fx-border-color:" + "transparent" + ";");')
new_page_style = ('page.setStyle("-fx-background-color:transparent; -fx-background-insets:0;"\n'
                  '                + "-fx-border-radius:20; -fx-border-color:transparent;");')
assert old_page_style in src, "page style not found"
src = src.replace(old_page_style, new_page_style)

# 4) root() 整段：wrap 铺群像壁纸 + 黑罩
old_root = '''    public Node root() {
        // 垫层：与面板同色铺满整个下载中心区域，让圆角弧线外的角落显示同色而非窗口底色
        StackPane wrap = new StackPane();
        wrap.setStyle("-fx-background-color:" + "#733e6f" + ";");
        VBox box = new VBox(0);
        box.getChildren().addAll(topDivider, page);
        wrap.getChildren().add(box);
        return wrap;
    }'''
new_root = '''    public Node root() {
        // 底层：与主页/社区同源的群像壁纸（cover），上覆 76% 黑罩压暗
        StackPane wrap = new StackPane();
        URL bgUrl = getClass().getResource("/home_mobs.png");
        String bgCss = bgUrl == null
                ? "-fx-background-color:#0c0c0c;"
                : "-fx-background-color:#0c0c0c; -fx-background-image:url('" + bgUrl.toExternalForm()
                        + "'); -fx-background-size:cover; -fx-background-position:center;";
        wrap.setStyle(bgCss);
        wrap.setMinSize(0, 0);

        Rectangle shade = new Rectangle();
        shade.setFill(Color.color(0.047, 0.047, 0.047, 0.76));
        shade.widthProperty().bind(wrap.widthProperty());
        shade.heightProperty().bind(wrap.heightProperty());

        VBox box = new VBox(0);
        box.getChildren().addAll(topDivider, page);
        wrap.getChildren().addAll(shade, box);
        return wrap;
    }'''
assert old_root in src, "root block not found"
src = src.replace(old_root, new_root)

with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patch15")
