# -*- coding: utf-8 -*-
# 一次性补丁：把 DownloadView.buildVersionTab 重构为设计稿紫系布局（筛选条 + 搜索框 + 绿色圆钮 + 空态）。
import io, sys

P = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\DownloadView.java"

with io.open(P, "r", encoding="utf-8") as f:
    src = f.read()

NEW_METHOD = r'''    private Tab buildVersionTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));

        // ===== 类型筛选胶囊（正式版 / 快照 / 愚人节 / 远古版 / 全部） =====
        ComboBox<String> filter = new ComboBox<>();
        filter.getItems().addAll("全部", "正式版", "快照", "愚人节", "远古版");
        // 默认选中「正式版」（HMCL 同款分类：正式版 / 快照 / 愚人节 / 远古版）
        filter.getSelectionModel().select(1);
        filter.getStyleClass().add("jwh-capsule");

        // ===== 加载器融合选择：平台胶囊 + 版本胶囊 =====
        loaderPlatform = new ComboBox<>();
        loaderPlatform.getItems().addAll("无（仅原版）", "Fabric", "Quilt", "Forge", "NeoForge");
        loaderPlatform.getSelectionModel().selectFirst();
        loaderPlatform.getStyleClass().add("jwh-capsule");

        loaderVersionBox = new ComboBox<>();
        loaderVersionBox.getItems().addAll("默认最新稳定版");
        loaderVersionBox.getSelectionModel().selectFirst();
        loaderVersionBox.getStyleClass().add("jwh-capsule");
        loaderVersionBox.setPromptText("加载器版本（默认最新稳定版）");
        loaderVersionBox.setDisable(true); // 未选择游戏版本前不可用

        // ===== 关键词搜索框（按版本 id 过滤） =====
        searchBox = new TextField();
        searchBox.setPromptText("点击搜索...");
        searchBox.getStyleClass().add("jwh-search");
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        ListView<VersionManifest.Version> list = new ListView<>();
        list.setStyle("-fx-background-color:transparent; -fx-control-inner-background:transparent;");
        list.setCellFactory(c -> new ListCell<>() {
            private final javafx.scene.image.ImageView icon = new javafx.scene.image.ImageView();
            private final Label idLabel = new Label();
            private final Label metaLabel = new Label();
            private final HBox row = new HBox(10);
            private final VBox info = new VBox(2);

            {
                icon.setFitWidth(32);
                icon.setFitHeight(32);
                icon.setPreserveRatio(true);
                icon.setImage(Ui.GRASS_BLOCK);
                idLabel.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:15px; -fx-font-weight:bold;");
                metaLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
                HBox spacer = new HBox();
                // 只做弹性占位，避免 MAX_VALUE 宽度把版本名挤成省略号“…”
                HBox.setHgrow(spacer, Priority.ALWAYS);
                info.getChildren().addAll(idLabel, metaLabel);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getChildren().addAll(icon, info, spacer);
            }

            private void applyRowStyle(boolean selected) {
                // 选中：深紫半透明条（稿 st22 #141414 @25%），未选中透明
                setStyle(selected
                        ? "-fx-background-color:rgba(20,20,20,0.25); -fx-background-radius:8;"
                        : "-fx-background-color:transparent; -fx-background-radius:8;");
            }

            @Override
            public void updateSelected(boolean selected) {
                super.updateSelected(selected);
                if (!isEmpty()) applyRowStyle(selected);
            }

            @Override
            protected void updateItem(VersionManifest.Version v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    boolean installed = app.localVersions.exists(v.id());
                    idLabel.setText(v.id() + "   [" + v.typeLabel() + "]" + (installed ? "  ✓已安装" : ""));
                    metaLabel.setText(fmtTime(v.releaseTime()));
                    // 类型图标：愚人节→粘液块、远古版→工作台、其余→草方块（HMCL 同款效果）
                    icon.setImage(iconFor(v));
                    setGraphic(row);
                    setText(null);
                    applyRowStyle(isSelected());
                }
            }

            private javafx.scene.image.Image iconFor(VersionManifest.Version v) {
                if (v.isAprilFools()) return Ui.SLIME_BLOCK;
                if (v.isOld()) return Ui.CRAFTING_TABLE;
                return Ui.GRASS_BLOCK;
            }
        });

        // 空状态提示（稿：当前暂无游戏版本）
        Label emptyLabel = new Label("当前暂无游戏版本");
        emptyLabel.setStyle("-fx-text-fill:rgba(255,255,255,0.85); -fx-font-size:18px;");
        emptyLabel.setVisible(false);
        list.getItems().addListener((javafx.collections.ListChangeListener<VersionManifest.Version>) ch ->
                emptyLabel.setVisible(list.getItems().isEmpty()));

        Button refreshBtn = new Button("刷新");
        refreshBtn.getStyleClass().add("ghost-btn");
        refreshBtn.setOnAction(e -> Ui.async(() -> {
            manifest = VersionManifest.fetch(app.config.minecraftPath().resolve("launcher_cache_manifest.json"),
                    app.config.manifestUrl());
            return null;
        }, ok -> {
            applyFilter(filter, list);
            app.setStatus("清单已刷新");
        }, err -> Ui.error("拉取失败", err)));

        Button refreshLoaderBtn = new Button("刷新加载器");
        refreshLoaderBtn.getStyleClass().add("ghost-btn");
        refreshLoaderBtn.setOnAction(e -> {
            VersionManifest.Version v = list.getSelectionModel().getSelectedItem();
            String plat = loaderPlatform.getSelectionModel().getSelectedItem();
            if (v == null || plat == null || "无（仅原版）".equals(plat)) {
                Ui.error("未选择", "请先在上方列表选择游戏版本，并选择加载器平台。");
                return;
            }
            loaderVersionBox.setDisable(false);
            fillLoaderVersions(v.id(), plat, loaderVersionBox, true);
            app.setStatus("正在刷新 " + plat + " 加载器版本列表…");
        });

        Button installBtn = new Button("下载");
        installBtn.getStyleClass().add("jwh-fab");
        installBtn.setOnAction(e -> {
            VersionManifest.Version v = list.getSelectionModel().getSelectedItem();
            if (v == null) {
                Ui.error("未选择", "请先选择一个版本。");
                return;
            }
            installVersion(v, installBtn, filter, list);
        });

        filter.valueProperty().addListener((obs, o, n) -> applyFilter(filter, list));
        searchBox.textProperty().addListener((obs, o, n) -> applyFilter(filter, list));

        // 选中某个版本后，若已选加载器则自动查询该版本可用的加载器版本列表
        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            String plat = loaderPlatform.getSelectionModel().getSelectedItem();
            if (n != null && plat != null && !"无（仅原版）".equals(plat)) {
                loaderVersionBox.setDisable(false);
                fillLoaderVersions(n.id(), plat, loaderVersionBox, false);
            } else {
                loaderVersionBox.setDisable(true);
                loaderVersionBox.getItems().setAll("默认最新稳定版");
                loaderVersionBox.getSelectionModel().selectFirst();
            }
        });
        // 切换加载器平台时，若已选中版本则刷新加载器版本列表
        loaderPlatform.valueProperty().addListener((obs, o, n) -> {
            VersionManifest.Version v = list.getSelectionModel().getSelectedItem();
            if (v != null && n != null && !"无（仅原版）".equals(n)) {
                loaderVersionBox.setDisable(false);
                fillLoaderVersions(v.id(), n, loaderVersionBox, false);
            } else {
                loaderVersionBox.setDisable(true);
                loaderVersionBox.getItems().setAll("默认最新稳定版");
                loaderVersionBox.getSelectionModel().selectFirst();
            }
        });

        // ===== 深紫筛选条：类型 / 加载器平台 / 加载器版本 + 弹性占位 + 搜索框 =====
        HBox filterBar = new HBox(10);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setStyle("-fx-background-color:#1f1529; -fx-background-radius:12; -fx-padding:10 12;");
        HBox barSpacer = new HBox();
        HBox.setHgrow(barSpacer, Priority.ALWAYS);
        filterBar.getChildren().addAll(filter, loaderPlatform, loaderVersionBox, barSpacer,
                refreshBtn, refreshLoaderBtn, searchBox);

        // ===== 列表区：空态居中 + 右下绿色圆形下载钮 =====
        AnchorPane fabLayer = new AnchorPane();
        AnchorPane.setRightAnchor(installBtn, 16.0);
        AnchorPane.setBottomAnchor(installBtn, 16.0);
        fabLayer.getChildren().add(installBtn);
        StackPane listWrap = new StackPane(list, emptyLabel, fabLayer);
        VBox.setVgrow(listWrap, Priority.ALWAYS);

        box.getChildren().addAll(filterBar, listWrap);
        refreshBtn.fire();
        return new Tab("游戏版本", box);
    }

'''

start = src.index("    private Tab buildVersionTab() {")
end = src.index("    private void applyFilter(")
src = src[:start] + NEW_METHOD + src[end:]

# applyFilter 增加关键词过滤
OLD_FILTER = '''    private void applyFilter(ComboBox<String> filter, ListView<VersionManifest.Version> list) {
        if (manifest == null) return;
        String f = filter.getSelectionModel().getSelectedItem();
        List<VersionManifest.Version> out = new ArrayList<>();
        for (VersionManifest.Version v : manifest.versions) {
            if ("全部".equals(f)) out.add(v);'''
NEW_FILTER = '''    private void applyFilter(ComboBox<String> filter, ListView<VersionManifest.Version> list) {
        if (manifest == null) return;
        String f = filter.getSelectionModel().getSelectedItem();
        String q = searchBox == null ? "" : searchBox.getText().trim().toLowerCase();
        List<VersionManifest.Version> out = new ArrayList<>();
        for (VersionManifest.Version v : manifest.versions) {
            if (!q.isEmpty() && !v.id().toLowerCase().contains(q)) continue;
            if ("全部".equals(f)) out.add(v);'''
assert OLD_FILTER in src, "applyFilter block not found"
src = src.replace(OLD_FILTER, NEW_FILTER)

with io.open(P, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("OK patched")
