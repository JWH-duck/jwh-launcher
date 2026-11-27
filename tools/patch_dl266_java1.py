# -*- coding: utf-8 -*-
import io
LA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"
with io.open(LA, "r", encoding="utf-8") as f:
    t = f.read().replace("\r\n", "\n")

def rep(old, new, tag):
    global t
    assert t.count(old) == 1, f"anchor not unique/found: {tag}"
    t = t.replace(old, new, 1)
    print("ok:", tag)

# 1) TabPane 背景透明（不再铺紫）
rep(
    '        tabs.setStyle("-fx-background-color:" + "#733e6f" + ";");',
    '        tabs.setStyle("-fx-background-color:transparent;");',
    "tabs bg transparent")

# 2) 页面板 + 顶部分隔色块 → 深色
rep(
    '''        page.setStyle("-fx-background-color:" + "#733e6f" + "; -fx-background-radius:20; -fx-background-insets:0;"
                + "-fx-border-radius:20; -fx-border-color:" + "transparent" + ";");
        page.setPadding(new Insets(0, 0, 12, 0));
        topDivider.setStyle("-fx-background-color:#0d0d0e; -fx-background-insets:0;");
        topDivider.setPrefHeight(18);''',
    '''        page.setStyle("-fx-background-color:#261c30; -fx-background-radius:20; -fx-background-insets:0;"
                + "-fx-border-radius:20; -fx-border-color:transparent;");
        page.setPadding(new Insets(0, 0, 12, 0));
        topDivider.setStyle("-fx-background-color:#261c30; -fx-background-insets:0;");
        topDivider.setPrefHeight(8);''',
    "page/divider dark")

# 3) 垫层背景 → 深色
rep(
    '        wrap.setStyle("-fx-background-color:" + "#733e6f" + ";");',
    '        wrap.setStyle("-fx-background-color:#261c30;");',
    "wrap bg dark")

# 4) 搜索框：前置放大镜矢量图标，包一层 HBox
rep(
    '''        // ===== 关键词搜索框（按版本 id 过滤） =====
        searchBox = new TextField();
        searchBox.setPromptText("点击搜索...");
        searchBox.getStyleClass().add("jwh-search");
        HBox.setHgrow(searchBox, Priority.ALWAYS);''',
    '''        // ===== 关键词搜索框（按版本 id 过滤），前置放大镜 =====
        searchBox = new TextField();
        searchBox.setPromptText("点击搜索...");
        searchBox.getStyleClass().add("jwh-search");
        javafx.scene.shape.Circle lens = new javafx.scene.shape.Circle(6.5, 6.5, 5.5);
        lens.setFill(null);
        lens.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.5));
        lens.setStrokeWidth(1.4);
        javafx.scene.shape.Line searchHandle = new javafx.scene.shape.Line(10.5, 10.5, 14.0, 14.0);
        searchHandle.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.5));
        searchHandle.setStrokeWidth(1.4);
        javafx.scene.layout.Pane searchGlyph = new javafx.scene.layout.Pane(lens, searchHandle);
        searchGlyph.setPrefSize(18, 18);
        searchGlyph.setMinSize(18, 18);
        HBox searchWrap = new HBox(8, searchGlyph, searchBox);
        searchWrap.setAlignment(Pos.CENTER_LEFT);
        searchWrap.getStyleClass().add("jwh-search-wrap");
        HBox.setHgrow(searchBox, Priority.ALWAYS);''',
    "search box magnifier")

# 5) 删除刷新按钮整块
rep(
    '''        Button refreshBtn = new Button("刷新");
        refreshBtn.getStyleClass().add("ghost-btn");
        refreshBtn.setMinWidth(72);
        refreshBtn.setPrefWidth(72);
        refreshBtn.setStyle("-fx-padding:6 14;");
        refreshBtn.setOnAction(e -> Ui.async(() -> {
            manifest = VersionManifest.fetch(app.config.minecraftPath().resolve("launcher_cache_manifest.json"),
                    app.config.manifestUrl());
            return null;
        }, ok -> {
            applyFilter(filter, list);
            app.setStatus("清单已刷新");
        }, err -> Ui.error("拉取失败", err)));

''',
    '',
    "remove refresh button")

# 6) 筛选条深色 + 子级去掉刷新钮、接搜索包装
rep(
    '''        HBox filterBar = new HBox(10);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setStyle("-fx-background-color:#1f1529; -fx-background-radius:0; -fx-padding:10 12;");
        HBox barSpacer = new HBox();
        HBox.setHgrow(barSpacer, Priority.ALWAYS);
        filterBar.getChildren().addAll(filter, loaderPlatform, loaderVersionBox, barSpacer,
                refreshBtn, searchBox);''',
    '''        HBox filterBar = new HBox(10);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setStyle("-fx-background-color:#261c30; -fx-background-radius:0; -fx-padding:10 12;");
        HBox barSpacer = new HBox();
        HBox.setHgrow(barSpacer, Priority.ALWAYS);
        filterBar.getChildren().addAll(filter, loaderPlatform, loaderVersionBox, barSpacer, searchWrap);''',
    "filter bar dark")

with io.open(LA, "w", encoding="utf-8", newline="\n") as f:
    f.write(t)
print("java part 1 done")
