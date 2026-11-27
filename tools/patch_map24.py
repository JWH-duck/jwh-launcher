# -*- coding: utf-8 -*-
# 补丁24：把旧 HomeView 的 BBSMC 地图板块（搜索/排序/两列卡片网格/缩略图/作者/更新时间/简介/标签/统计/详情跳转）
# 迁入新 CommunityView 左列；网格在左列剩余高度内内部滚动，窗口固定 1000x650 不被顶大。
import io, re

P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\CommunityView.java"
with io.open(P, "r", encoding="utf-8") as f:
    s = f.read().replace("\r\n", "\n")

# 1) 增补 import
imp_anchor = "import javafx.util.Duration;\n"
imp_add = (
    "import com.jwh.launcher.download.BbsmcMaps;\n"
    "import com.jwh.launcher.download.IconCache;\n"
    "import javafx.animation.PauseTransition;\n"
    "import javafx.scene.control.OverrunStyle;\n"
    "import javafx.scene.layout.FlowPane;\n"
    "import javafx.scene.shape.Rectangle;\n"
)
assert s.count(imp_anchor) == 1, "import anchor"
s = s.replace(imp_anchor, imp_add + imp_anchor, 1)

# 2) 地图字段
fld_anchor = "    private ScheduledFuture<?> autoTask;\n"
fld_add = (
    "\n    // ===== BBSMC 地图板块（搜索 + 两列卡片网格，内部滚动） =====\n"
    "    private HBox searchBar;\n"
    "    private VBox mapGrid;\n"
    "    private ScrollPane mapScroll;\n"
    "    private TextField mapSearch;\n"
    "    private ComboBox<String> mapSort;\n"
    "    private Label mapStatus;\n"
    "    private Button mapMoreBtn;\n"
    "    private final List<BbsmcMaps.Item> mapItems = new ArrayList<>();\n"
    "    private boolean mapLoading;\n"
    "    private boolean mapLoadedOnce;\n"
    "    private boolean mapWantTop;\n"
    "    private int mapTotal;\n"
    "    private static final int MAP_PAGE = 20;\n"
)
assert s.count(fld_anchor) == 1, "field anchor"
s = s.replace(fld_anchor, fld_anchor + fld_add, 1)

# 3) 重写 buildLeftColumn 整方法
col_start = "    // ===== 左列：横幅 + 搜索行 + 地图卡片网格 ====="
col_end_marker = "        col.getChildren().add(grid);\n\n        return col;\n    }"
i0 = s.index(col_start)
i1 = s.index(col_end_marker) + len(col_end_marker)
new_col = '''    // ===== 左列：横幅 + 搜索行 + 地图卡片网格（BBSMC 真实数据，内部滚动） =====
    private VBox buildLeftColumn() {
        VBox col = new VBox(16);
        col.setMinSize(0, 0);
        col.setPrefWidth(0);
        col.setMaxWidth(Double.MAX_VALUE);
        col.setMaxHeight(Double.MAX_VALUE);

        // 顶部白框：官网精选更新卡单页轮播
        col.getChildren().add(buildHighlightBanner());

        // 搜索行：搜索框 + 排序下拉 + 放大镜
        searchBar = new HBox(8);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setMinWidth(0);
        searchBar.setMaxWidth(Double.MAX_VALUE);

        mapSearch = new TextField();
        mapSearch.setPromptText("搜索 地图...");
        mapSearch.setStyle("-fx-background-color:#ffffff; -fx-text-fill:#1f2328;"
                + " -fx-prompt-text-fill:#9aa0a8; -fx-background-radius:10; -fx-border-radius:10;"
                + " -fx-border-color:rgba(255,255,255,0.14); -fx-padding:8 12; -fx-font-size:14px;"
                + " -fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
        HBox.setHgrow(mapSearch, Priority.ALWAYS);
        mapSearch.setOnAction(e -> reloadMaps());
        PauseTransition debounce = new PauseTransition(Duration.millis(500));
        debounce.setOnFinished(e -> reloadMaps());
        mapSearch.textProperty().addListener((o, a, b) -> debounce.playFromStart());

        mapSort = new ComboBox<>();
        mapSort.getItems().addAll("热门", "最新");
        mapSort.getSelectionModel().select(0);
        mapSort.setPrefWidth(96);
        mapSort.setStyle("-fx-background-color:#ffffff; -fx-text-fill:#1f2328;"
                + " -fx-background-radius:10; -fx-border-radius:10; -fx-border-color:rgba(255,255,255,0.14);"
                + " -fx-font-size:13px;");
        mapSort.setOnAction(e -> reloadMaps());

        javafx.scene.shape.Circle lens = new javafx.scene.shape.Circle(7);
        lens.setFill(Color.TRANSPARENT);
        lens.setStroke(Color.web("#1f2328"));
        lens.setStrokeWidth(2);
        javafx.scene.shape.Line handle = new javafx.scene.shape.Line(5.5, 5.5, 9.5, 9.5);
        handle.setStroke(Color.web("#1f2328"));
        handle.setStrokeWidth(2);
        StackPane searchIcon = new StackPane(lens, handle);
        Button searchBtn = new Button();
        searchBtn.setGraphic(searchIcon);
        searchBtn.setPrefSize(38, 38);
        searchBtn.setStyle("-fx-background-color:#ffffff; -fx-background-radius:10; -fx-border-radius:10; -fx-cursor:hand;");
        searchBtn.setOnAction(e -> reloadMaps());

        searchBar.getChildren().addAll(mapSearch, mapSort, searchBtn);
        col.getChildren().add(searchBar);

        // 地图网格：两列卡片，在左列剩余高度内内部滚动，不参与把窗口顶大
        mapGrid = new VBox(8);
        mapGrid.setFillWidth(true);
        mapGrid.setMinWidth(0);
        mapGrid.setMaxWidth(Double.MAX_VALUE);
        mapScroll = new ScrollPane(mapGrid);
        mapScroll.setFitToWidth(true);
        mapScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        mapScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        mapScroll.setPannable(true);
        mapScroll.setMinHeight(0);
        mapScroll.setPrefHeight(0);
        mapScroll.setMaxHeight(Double.MAX_VALUE);
        mapScroll.setStyle("-fx-background-color:transparent; -fx-background:transparent;"
                + "-fx-padding:0; -fx-background-insets:0; -fx-border-width:0;");
        VBox.setVgrow(mapScroll, Priority.ALWAYS);
        mapGrid.heightProperty().addListener((o, ov, nv) -> {
            if (mapWantTop && nv.doubleValue() > 10) {
                mapWantTop = false;
                mapScroll.setVvalue(0);
                Platform.runLater(() -> mapScroll.setVvalue(0));
            }
        });
        col.getChildren().add(mapScroll);

        // 底部状态行：命中统计 + 加载更多
        mapStatus = new Label("");
        mapStatus.setStyle("-fx-text-fill:#6b7280; -fx-font-size:11px;");
        mapStatus.setTextOverrun(OverrunStyle.ELLIPSIS);
        mapMoreBtn = new Button("加载更多");
        mapMoreBtn.setStyle("-fx-background-color:#ffffff; -fx-text-fill:#1f2328; -fx-background-radius:8;"
                + " -fx-font-size:11px; -fx-padding:3 10; -fx-cursor:hand;");
        mapMoreBtn.setVisible(false);
        mapMoreBtn.setOnAction(e -> loadMaps(false));
        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        HBox foot = new HBox(8, mapStatus, footSpacer, mapMoreBtn);
        foot.setAlignment(Pos.CENTER_LEFT);
        col.getChildren().add(foot);

        // 第一次进场景再拉数据，避免页面没显示就发请求
        col.sceneProperty().addListener((o, was, now) -> {
            if (now != null && !mapLoadedOnce) {
                mapLoadedOnce = true;
                reloadMaps();
            }
        });
        return col;
    }'''
s = s[:i0] + new_col + s[i1:]

# 4) 替换旧占位 mapCard 方法为真实地图方法组
mc_start = "    /** 单张地图占位卡：上部自适应封面 + 底部标题/副标题，卡片等高撑满。 */"
mc_end_marker = "        card.getChildren().add(text);\n        return card;\n    }"
j0 = s.index(mc_start)
j1 = s.index(mc_end_marker) + len(mc_end_marker)
new_methods = '''    /** 把地图滚动位置拉回顶部：连发两轮 runLater，避开布局与焦点滚动时序。 */
    private void scrollMapsToTop() {
        if (mapScroll == null) return;
        mapWantTop = true;
        Platform.runLater(() -> mapScroll.setVvalue(0));
        Platform.runLater(() -> Platform.runLater(() -> mapScroll.setVvalue(0)));
    }

    /** 按两张一行重排卡片（数据变了或「加载更多」后调用）。 */
    private void rebuildMapRows() {
        if (mapGrid == null) return;
        mapGrid.getChildren().removeIf(n -> n != searchBar);
        if (mapItems.isEmpty()) {
            Label empty = new Label(mapLoading ? "正在加载…" : "暂无地图");
            empty.setStyle("-fx-text-fill:#6b7280; -fx-font-size:12px;");
            empty.setPadding(new Insets(16, 0, 0, 4));
            mapGrid.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < mapItems.size(); i += 2) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.TOP_LEFT);
            row.setFillHeight(true);
            row.setMinWidth(0);
            row.setMaxWidth(Double.MAX_VALUE);

            VBox a = buildMapCard(mapItems.get(i));
            HBox.setHgrow(a, Priority.ALWAYS);
            row.getChildren().add(a);

            VBox b = (i + 1 < mapItems.size()) ? buildMapCard(mapItems.get(i + 1)) : null;
            if (b != null) {
                HBox.setHgrow(b, Priority.ALWAYS);
                row.getChildren().add(b);
            } else {
                Region filler = new Region();
                filler.setMinWidth(0);
                HBox.setHgrow(filler, Priority.ALWAYS);
                row.getChildren().add(filler);
            }
            row.widthProperty().addListener((o, ov, nv) -> {
                double w = Math.max(70, (nv.doubleValue() - 8) / 2);
                a.setPrefWidth(w);
                if (b != null) b.setPrefWidth(w);
            });
            mapGrid.getChildren().add(row);
        }
    }

    /** 重新拉第一页（换关键词 / 换排序）。 */
    private void reloadMaps() {
        if (mapGrid == null) return;
        mapItems.clear();
        mapTotal = 0;
        loadMaps(true);
    }

    /** 拉一页地图；reset=true 替换内容，否则追加。 */
    private void loadMaps(boolean reset) {
        if (mapGrid == null || mapLoading) return;
        mapLoading = true;
        if (mapMoreBtn != null) mapMoreBtn.setDisable(true);
        mapStatus.setText("正在加载…");
        String q = mapSearch == null ? "" : mapSearch.getText();
        String idx = (mapSort != null && mapSort.getSelectionModel().getSelectedIndex() == 1) ? "newest" : "downloads";
        int offset = reset ? 0 : mapItems.size();
        Ui.async2(() -> BbsmcMaps.search(q, idx, offset, MAP_PAGE), res -> {
            mapLoading = false;
            if (res == null) return;
            if (reset) mapItems.clear();
            mapItems.addAll(res.items);
            mapTotal = res.total;
            rebuildMapRows();
            if (reset) scrollMapsToTop();
            String msg = mapTotal > 0
                    ? "地图 · 共 " + BbsmcMaps.num(mapTotal) + " 张 · 已显示 " + mapItems.size() + " 张（可滚动查看）"
                    : "没有找到地图";
            if (q != null && !q.isBlank()) msg = "「" + q.trim() + "」· " + msg;
            mapStatus.setText(msg);
            mapMoreBtn.setVisible(mapTotal > mapItems.size());
            mapMoreBtn.setDisable(res.items.isEmpty());
        }, err -> {
            mapLoading = false;
            mapStatus.setText("加载失败：" + (err.getMessage() == null ? "网络异常" : err.getMessage()));
            if (mapMoreBtn != null) mapMoreBtn.setDisable(false);
        });
    }

    /** 一张地图卡：大封面 + 左下角项目小图标 + 标题/作者/简介/标签 + 下载关注数，整卡点进官网详情页。 */
    private VBox buildMapCard(BbsmcMaps.Item it) {
        VBox card = new VBox(0);
        card.setStyle("-fx-background-color:#ffffff; -fx-background-radius:8; -fx-border-radius:8;"
                + " -fx-border-color:rgba(0,0,0,0.08); -fx-border-width:1;");
        card.setMinWidth(0);
        card.setPrefWidth(200);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> openMapPage(it));

        // ---- 封面（等比铺满后裁掉溢出部分，图片不拉伸变形） ----
        ImageView cover = new ImageView();
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        cover.setImage(Ui.GRASS_BLOCK);
        StackPane coverBox = new StackPane(cover);
        coverBox.setStyle("-fx-background-color:#eaeaea;");
        coverBox.setMinHeight(100);
        coverBox.setPrefHeight(100);
        coverBox.setMaxHeight(100);
        coverBox.setMinWidth(0);
        cover.fitWidthProperty().bind(coverBox.widthProperty());
        Rectangle clip = new Rectangle();
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clip.widthProperty().bind(coverBox.widthProperty());
        clip.heightProperty().bind(coverBox.heightProperty());
        coverBox.setClip(clip);

        // 左上角项目图标（骑在封面左下的小方块）
        ImageView mini = new ImageView(Ui.GRASS_BLOCK);
        mini.setFitWidth(28);
        mini.setFitHeight(28);
        mini.setPreserveRatio(false);
        mini.setSmooth(true);
        StackPane miniBox = new StackPane(mini);
        miniBox.setMinSize(30, 30);
        miniBox.setPrefSize(30, 30);
        miniBox.setMaxSize(30, 30);
        miniBox.setStyle("-fx-background-color:#ffffff; -fx-background-radius:6; -fx-border-color:rgba(0,0,0,0.1);"
                + " -fx-border-radius:6; -fx-border-width:1;");
        StackPane.setAlignment(miniBox, Pos.BOTTOM_LEFT);
        StackPane.setMargin(miniBox, new Insets(0, 0, -10, 9));
        coverBox.getChildren().add(miniBox);

        // ---- 文字区 ----
        Label titleL = new Label(it.title);
        titleL.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:#1f2328;");
        titleL.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleL.setMaxWidth(Double.MAX_VALUE);
        titleL.setMinWidth(0);

        StringBuilder au = new StringBuilder();
        if (it.author != null && !it.author.isBlank()) au.append("by ").append(it.author);
        if (it.modified > 0) au.append(au.length() > 0 ? " · " : "").append("更新于 ").append(BbsmcMaps.ago(it.modified));
        Label authorL = new Label(au.toString());
        authorL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#6b7280;");
        authorL.setTextOverrun(OverrunStyle.ELLIPSIS);
        authorL.setMaxWidth(Double.MAX_VALUE);
        authorL.setMinWidth(0);

        Label descL = new Label(it.description == null || it.description.isBlank()
                ? "（作者没有写简介）" : it.description);
        descL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#6b7280;");
        descL.setWrapText(true);
        descL.setMaxHeight(18);
        descL.setMinHeight(18);
        descL.setTextOverrun(OverrunStyle.ELLIPSIS);
        descL.setMaxWidth(Double.MAX_VALUE);
        descL.setMinWidth(0);

        FlowPane tagBox = new FlowPane(4, 4);
        tagBox.setMinWidth(0);
        tagBox.setMaxWidth(Double.MAX_VALUE);
        tagBox.setPrefWrapLength(180);
        for (int i = 0; i < it.tags.size() && i < 3; i++) tagBox.getChildren().add(mapTag(it.tags.get(i)));

        Label dlL = new Label("↓ " + BbsmcMaps.num(it.downloads));
        dlL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#6b7280;");
        Label followL = new Label("♥ " + it.follows);
        followL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#6b7280;");
        HBox numBox = new HBox(8, dlL, followL);
        numBox.setAlignment(Pos.CENTER_LEFT);

        Label goL = new Label("官网下载 ↗");
        goL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#3FA9F5; -fx-cursor:hand;");
        Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox bottom = new HBox(6, numBox, bottomSpacer, goL);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setMinWidth(0);

        VBox body = new VBox(3, titleL, authorL, descL, tagBox, bottom);
        body.setPadding(new Insets(8, 8, 5, 8));
        body.setMinWidth(0);
        body.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(body, Priority.ALWAYS);

        card.getChildren().addAll(coverBox, body);

        // ---- 两张图各自异步就位，失败保持草方块占位 ----
        if (it.thumb != null && !it.thumb.isBlank()) {
            Image c = IconCache.cached(it.thumb, 360);
            if (c != null) cover.setImage(c);
            else IconCache.ensure(it.thumb, 360, cover::setImage);
        }
        if (it.icon != null && !it.icon.isBlank()) {
            Image c = IconCache.cached(it.icon, 34);
            if (c != null) mini.setImage(c);
            else IconCache.ensure(it.icon, 34, mini::setImage);
        }
        return card;
    }

    /** 标签小胶囊。 */
    private static Label mapTag(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-background-color:#eef0f3; -fx-background-radius:4; -fx-padding:1 6;"
                + " -fx-font-size:11.5px; -fx-text-fill:#6b7280;");
        return l;
    }

    /** 点卡片 → 打开 BBSMC 官网详情页（下载统一在官网完成）。 */
    private void openMapPage(BbsmcMaps.Item it) {
        try {
            app.openUrl(it.pageUrl());
        } catch (Exception ex) {
            Ui.error("打不开官网页面", String.valueOf(ex.getMessage()));
        }
    }'''
s = s[:j0] + new_methods + s[j1:]

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
print("OK patch24")
