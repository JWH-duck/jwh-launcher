# -*- coding: utf-8 -*-
import io
JA = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"
with io.open(JA, "r", encoding="utf-8") as f:
    lines = f.read().replace("\r\n", "\n").split("\n")

# 定位 asTab 方法起止
start = next(i for i, l in enumerate(lines) if l.strip() == "public Tab asTab() {")
# 方法结束：从 start 起找缩进 4 的 "}"
end = next(i for i in range(start + 1, len(lines)) if lines[i] == "    }")
print("asTab lines", start + 1, end + 1)

new_method = '''    public Tab asTab() {
        boolean mod = type == Type.MOD;
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));

        // 目标版本（本地已装实例，HMCL 式：加载器实例显示合并名，被继承的原版隐藏）
        versionBox.setPromptText("安装到的版本（必选）");
        versionBox.setCellFactory(c -> new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });
        versionBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });

        // 数据源
        sourceBox.getItems().addAll("Modrinth（推荐）", "CurseForge");
        sourceBox.getSelectionModel().selectFirst();
        sourceBox.setPrefWidth(150);
        sourceBox.setOnAction(e -> {
            boolean cf = "CurseForge".equals(sourceBox.getValue());
            searchBox.setPromptText(cf ? "搜索模组/材质包（CurseForge）" : searchPrompt());
        });

        searchBox.setPromptText(searchPrompt());
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        // 排序下拉（仅 Mod 新样式使用）
        sortBox.getItems().addAll("下载量（高→低）", "下载量（低→高）", "名称 A–Z");
        sortBox.getSelectionModel().selectFirst();
        sortBox.setPrefWidth(150);

        // 双列卡片网格：ScrollPane 内竖向滚动，横向不滚
        grid.setStyle("-fx-background-color:transparent;");
        grid.setHgap(8);
        grid.setVgap(10);
        ColumnConstraints col = new ColumnConstraints();
        col.setPercentWidth(50);
        col.setHgrow(Priority.ALWAYS);
        col.setFillWidth(true);
        grid.getColumnConstraints().setAll(col, col);
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color:transparent; -fx-background:" + Ui.PANEL + ";");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        if (mod) {
            sourceBox.getStyleClass().addAll("jwh-capsule", "jwh-capsule-plain");
            versionBox.getStyleClass().addAll("jwh-capsule", "jwh-capsule-plain");
            sortBox.getStyleClass().addAll("jwh-capsule", "jwh-capsule-plain");
            searchBox.getStyleClass().add("jwh-search");

            // 搜索框：放大镜内嵌深色胶囊
            javafx.scene.shape.Circle lens = new javafx.scene.shape.Circle(9, 9, 5.5);
            lens.setFill(null);
            lens.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.75));
            lens.setStrokeWidth(1.4);
            javafx.scene.shape.Line handle = new javafx.scene.shape.Line(12.6, 12.6, 16.0, 16.0);
            handle.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.75));
            handle.setStrokeWidth(1.4);
            javafx.scene.layout.Pane glyph = new javafx.scene.layout.Pane(lens, handle);
            glyph.setPrefSize(18, 18);
            glyph.setMinSize(18, 18);
            glyph.setMaxSize(18, 18);
            HBox searchWrap = new HBox(8, glyph, searchBox);
            searchWrap.setAlignment(Pos.CENTER_LEFT);
            searchWrap.getStyleClass().add("jwh-search-wrap");
            HBox.setHgrow(searchBox, Priority.ALWAYS);
            HBox.setHgrow(searchWrap, Priority.ALWAYS);

            HBox filterBar = new HBox(10);
            filterBar.setAlignment(Pos.CENTER_LEFT);
            filterBar.getChildren().addAll(sourceBox, versionBox, searchWrap, sortBox);
            searchBox.setOnAction(e -> search());
            sortBox.setOnAction(e -> { applySort(); rebuildGrid(); });
            box.getChildren().addAll(filterBar, scroll);
        } else {
            searchBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
            refreshBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");

            HBox top = new HBox(8);
            top.setAlignment(Pos.CENTER_LEFT);
            top.getChildren().addAll(new Label("数据源："), sourceBox,
                    new Label("目标版本："), versionBox);
            HBox.setHgrow(versionBox, Priority.ALWAYS);
            HBox searchRow = new HBox(8);
            searchRow.setAlignment(Pos.CENTER_LEFT);
            searchRow.getChildren().addAll(searchBox, searchBtn, refreshBtn);

            searchBtn.setOnAction(e -> search());
            searchBox.setOnAction(e -> search());
            refreshBtn.setOnAction(e -> {
                refreshVersions();
                IconCache.resetFailures();
                search();
            });
            box.getChildren().addAll(top, searchRow, scroll);
        }

        Tab tab = new Tab(type.label, box);
        // 进入下载界面第一时间自动加载热门列表，无需手动点搜索
        tab.selectedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now) && items.isEmpty()) {
                refreshVersions();
                search();
            }
        });
        return tab;
    }'''

lines[start:end + 1] = new_method.split("\n")
with io.open(JA, "w", encoding="utf-8", newline="\n") as f:
    f.write("\n".join(lines))
print("asTab replaced")
