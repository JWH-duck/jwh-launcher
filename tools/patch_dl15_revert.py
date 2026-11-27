# -*- coding: utf-8 -*-
# 回退补丁15：下载页恢复整页铺紫 #733e6f，撤掉群像壁纸层与黑罩。
import io

JAVA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(JAVA, "r", encoding="utf-8") as f:
    src = f.read().replace("\r\n", "\n")

# 1) 移除补丁15 新增的 import
for imp in ("import javafx.scene.paint.Color;\n",
            "import javafx.scene.shape.Rectangle;\n",
            "import java.net.URL;\n"):
    assert imp in src, imp
    src = src.replace(imp, "")

# 2) tabs 背景恢复整铺紫
src = src.replace('tabs.setStyle("-fx-background-color:transparent;");',
                  'tabs.setStyle("-fx-background-color:" + "#733e6f" + ";");')

# 3) page 背景恢复整铺紫 + 圆角20
old_page = ('page.setStyle("-fx-background-color:transparent; -fx-background-insets:0;"\n'
            '                + "-fx-border-radius:20; -fx-border-color:transparent;");')
new_page = ('page.setStyle("-fx-background-color:" + "#733e6f" + "; -fx-background-radius:20; -fx-background-insets:0;"\n'
            '                + "-fx-border-radius:20; -fx-border-color:" + "transparent" + ";");')
assert old_page in src, "page style not found"
src = src.replace(old_page, new_page)

# 4) root() 还原为紫垫层
old_root = '''    public Node root() {
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
new_root = '''    public Node root() {
        // 垫层：与面板同色铺满整个下载中心区域，让圆角弧线外的角落显示同色而非窗口底色
        StackPane wrap = new StackPane();
        wrap.setStyle("-fx-background-color:" + "#733e6f" + ";");
        VBox box = new VBox(0);
        box.getChildren().addAll(topDivider, page);
        wrap.getChildren().add(box);
        return wrap;
    }'''
assert old_root in src, "root block not found"
src = src.replace(old_root, new_root)

with io.open(JAVA, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK revert java")

# 5) CSS 移除补丁15 追加的内容区透明规则
CSS = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\resources\style.css"
with io.open(CSS, "r", encoding="utf-8") as f:
    css = f.read().replace("\r\n", "\n")
rule = "\n.jwh-download-tabs .tab-content-area {\n    -fx-background-color: transparent;\n}\n"
if rule in css:
    css = css.replace(rule, "\n")
with io.open(CSS, "w", encoding="utf-8", newline="\n") as f:
    f.write(css)
print("OK revert css")
