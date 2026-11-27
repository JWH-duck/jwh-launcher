package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.download.IconCache;
import com.jwh.launcher.download.ModpackInstaller;
import com.jwh.launcher.download.ModrinthApi;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 下载中心「整合包」Tab：从 Modrinth 搜索 .mrpack 整合包，
 * 点击后弹窗选择版本，一键安装为独立实例（自动装加载器 + 下载全部 Mod / 配置）。
 * 列表卡片与 Mod / 光影包 / 材质包同款深色双列网格。
 */
public class ModpackTab {

    private final LauncherApp app;
    private final ProgressSink sink;
    private final ModrinthApi api = new ModrinthApi();
    /** 当前搜索结果（双列网格数据源）。 */
    private final List<ModrinthApi.Hit> items = new ArrayList<>();
    /** 双列卡片网格容器（ScrollPane 内竖向滚动）：两列各占 50%，以中线为界左右严格等宽。 */
    private final GridPane grid = new GridPane();
    private final TextField searchBox = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<String> sourceBox = new ComboBox<>();
    private final ComboBox<String> sortBox = new ComboBox<>();
    /** 版本下拉的 id → 显示名（通用：加载器实例显示 "1.21.1 · NeoForge 21.1.265"）。 */
    private final java.util.Map<String, String> displayNames = new java.util.HashMap<>();
    /** 版本下拉的 id → 纯游戏版本（neoforge-21.1.265 → 1.21.1，供搜索 API 使用）。 */
    private final java.util.Map<String, String> mcBases = new java.util.HashMap<>();

    private final java.util.function.Consumer<com.jwh.launcher.util.Http.DownloadControl> attachCtrl;
    private final Runnable detachCtrl;

    public ModpackTab(LauncherApp app, ProgressSink sink,
                      java.util.function.Consumer<com.jwh.launcher.util.Http.DownloadControl> attachCtrl,
                      Runnable detachCtrl) {
        this.app = app;
        this.sink = sink;
        this.attachCtrl = attachCtrl;
        this.detachCtrl = detachCtrl;
    }

    public Tab asTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));

        // 目标版本（本地已装实例）
        versionBox.setPromptText("目标安装版本（必选）");
        versionBox.setPrefWidth(280);
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

        // 数据源（整合包暂仅 Modrinth）
        sourceBox.getItems().addAll("Modrinth");
        sourceBox.getSelectionModel().selectFirst();
        sourceBox.setPrefWidth(150);

        searchBox.setPromptText("点击搜索…");
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        // 排序下拉
        sortBox.getItems().addAll("下载量·高到低", "下载量·低到高", "名称 A–Z");
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

        Tab tab = new Tab("整合包", box);
        // 首次进入自动加载热门整合包
        tab.selectedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now) && items.isEmpty()) {
                refreshVersions();
                search();
            }
        });
        return tab;
    }

    /** 填充本地可见实例下拉；后台扫描，失败保持当前列表。 */
    private void refreshVersions() {
        String oldId = versionBox.getSelectionModel().getSelectedItem();
        Ui.async2(() -> {
            java.util.Map<String, String> names = new java.util.HashMap<>();
            java.util.Map<String, String> bases = new java.util.HashMap<>();
            List<String> ids = new ArrayList<>();
            for (var v : app.localVersions.scanVisible()) {
                names.put(v.id(), v.displayName());
                bases.put(v.id(), v.mcBase() == null || v.mcBase().isBlank() ? v.id() : v.mcBase());
                ids.add(v.id());
            }
            return new VersionOptions(names, bases, ids);
        }, r -> {
            displayNames.clear();
            displayNames.putAll(r.names());
            mcBases.clear();
            mcBases.putAll(r.bases());
            versionBox.getItems().setAll(r.ids());
            if (oldId != null && r.ids().contains(oldId)) {
                versionBox.getSelectionModel().select(oldId);
            } else if (!r.ids().isEmpty()) {
                versionBox.getSelectionModel().selectFirst();
            }
        }, e -> { /* 扫描失败保持当前列表 */ });
    }

    private record VersionOptions(java.util.Map<String, String> names,
                                  java.util.Map<String, String> bases,
                                  List<String> ids) {
    }

    /** 当前选中实例的纯游戏版本（供 Modrinth API 过滤）。 */
    private String selectedMc() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (id == null) return null;
        return mcBases.getOrDefault(id, id);
    }

    /** 重建双列网格：两列各占 50% 宽度，以中线为界左右严格等宽；落单的最后一张占左列，右列留空。 */
    private void rebuildGrid() {
        grid.getChildren().clear();
        for (int i = 0; i < items.size(); i++) {
            StackPane card = cardNode(items.get(i));
            GridPane.setHgrow(card, Priority.ALWAYS);
            GridPane.setFillWidth(card, true);
            grid.add(card, i % 2, i / 2);
        }
    }

    /** 一张整合包卡片：左图标 + 标题/作者/描述，底部版本胶囊 + 来源胶囊 + 右侧下载量。 */
    private StackPane cardNode(ModrinthApi.Hit h) {
        StackPane card = new StackPane();
        card.setPadding(new Insets(12));
        String baseStyle = "-fx-background-color:#2f2538; -fx-background-radius:12; -fx-border-radius:12;"
                + " -fx-border-color:rgba(255,255,255,0.08); -fx-border-width:1;";
        card.setStyle(baseStyle);
        card.setMinWidth(0);
        card.setPrefWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMinHeight(150);
        card.setPrefHeight(150);
        card.setMaxHeight(150);
        card.setAlignment(Pos.TOP_LEFT);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> openDetailPage(h));
        card.setOnMouseEntered(e -> card.setStyle(baseStyle.replace("rgba(255,255,255,0.08)", "#d982c2")));
        card.setOnMouseExited(e -> card.setStyle(baseStyle));

        ImageView iconView = new ImageView();
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        iconView.setPreserveRatio(true);
        iconView.setSmooth(true);
        Label titleLabel = new Label(h.title());
        titleLabel.setStyle("-fx-text-fill:#ffffff; -fx-font-size:15px; -fx-font-weight:bold;");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        titleLabel.setMinHeight(22);
        Label descLabel = new Label(h.description());
        descLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");
        descLabel.setWrapText(true);
        descLabel.setMaxWidth(Double.MAX_VALUE);
        descLabel.setPrefHeight(34);
        descLabel.setMaxHeight(34);

        List<javafx.scene.Node> infoChildren = new ArrayList<>();
        infoChildren.add(titleLabel);
        if (h.author() != null && !h.author().isBlank()) {
            Label authorLabel = new Label("by " + h.author());
            authorLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");
            authorLabel.setMaxWidth(Double.MAX_VALUE);
            infoChildren.add(authorLabel);
        }
        infoChildren.add(descLabel);
        VBox info = new VBox(6);
        info.getChildren().addAll(infoChildren);
        info.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(info, Priority.ALWAYS);

        List<javafx.scene.Node> bottomChildren = new ArrayList<>();
        String ver = versionTag(h);
        if (ver != null) {
            Label versionTag = new Label(ver);
            versionTag.setStyle("-fx-background-color:#2c2336; -fx-text-fill:#b9bfc7; -fx-font-size:11px;"
                    + " -fx-background-radius:6; -fx-padding:3 8;");
            bottomChildren.add(versionTag);
        }
        Label sourceTag = new Label("Modrinth");
        sourceTag.setStyle("-fx-background-color:#2c2336; -fx-text-fill:#b9bfc7; -fx-font-size:11px;"
                + " -fx-background-radius:6; -fx-padding:3 8;");
        bottomChildren.add(sourceTag);
        Region spacer = new Region();
        Label dlLabel = new Label(formatDownloads(h.downloads()) + " 下载");
        dlLabel.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");
        bottomChildren.add(spacer);
        bottomChildren.add(dlLabel);
        HBox bottom = new HBox(10);
        bottom.getChildren().addAll(bottomChildren);
        bottom.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);

        VBox content = new VBox(10, new HBox(12, iconView, info), bottom);
        content.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().add(content);

        iconView.setImage(Ui.GRASS_BLOCK);
        String icon = h.iconUrl();
        if (icon != null && !icon.isBlank()) {
            String currentIconUrl = icon;
            Image cached = IconCache.cached(icon);
            if (cached != null) {
                iconView.setImage(cached);
            } else {
                IconCache.ensure(icon, img -> {
                    if (icon.equals(currentIconUrl)) iconView.setImage(img);
                });
            }
        }
        return card;
    }

    /** 卡片版本胶囊：优先匹配当前所选 MC 版本，否则取列表末项（最新）。无版本信息返回 null。 */
    private String versionTag(ModrinthApi.Hit h) {
        if (h.versions() == null || h.versions().isEmpty()) return null;
        String mc = selectedMc();
        if (mc != null && !mc.isBlank()) {
            for (String v : h.versions()) {
                if (mc.equals(v)) return v;
            }
        }
        return h.versions().get(h.versions().size() - 1);
    }

    /** 按当前排序下拉重排本地结果（不重新请求接口）。 */
    private void applySort() {
        String s = sortBox.getValue();
        Comparator<ModrinthApi.Hit> cmp;
        if ("下载量·低到高".equals(s)) {
            cmp = Comparator.comparingLong(ModrinthApi.Hit::downloads);
        } else if ("名称 A–Z".equals(s)) {
            cmp = Comparator.comparing(h -> h.title() == null ? "" : h.title().toLowerCase(Locale.ROOT));
        } else {
            cmp = Comparator.comparingLong(ModrinthApi.Hit::downloads).reversed();
        }
        items.sort(cmp);
    }

    /** 下载量格式化：1.2M / 3.4K，不足千显示原值。 */
    private static String formatDownloads(long d) {
        if (d >= 1_000_000) return String.format(Locale.US, "%.1fM", d / 1_000_000.0);
        if (d >= 1_000) return String.format(Locale.US, "%.1fK", d / 1_000.0);
        return String.valueOf(d);
    }

    private void search() {
        String q = searchBox.getText().trim();
        String mc = selectedMc();
        items.clear();
        rebuildGrid();
        app.setStatus("正在搜索整合包…");
        Ui.async(() -> {
            List<ModrinthApi.Hit> hits = api.search(q, ModrinthApi.Type.MODPACK, mc, null, 60);
            Platform.runLater(() -> {
                items.clear();
                items.addAll(hits);
                applySort();
                rebuildGrid();
                app.setStatus("找到 " + hits.size() + " 个整合包");
                if (hits.isEmpty()) {
                    Ui.info("没有找到结果", "「" + (q.isBlank() ? "（空关键词）" : q)
                            + "」没有匹配的整合包。\n试试更短的关键词（如 vault、skyblock）。");
                }
            });
            return null;
        }, ok -> {
        }, err -> {
            app.setStatus("搜索失败");
            Ui.error("搜索失败", err);
        });
    }

    /**
     * 点击列表项 → 跳转全页面详情页（取代原来的小弹窗）。
     * 页面右上角只有「返回」，不带上一页的任何选项；
     * 上方默认选中整合包版本（同时显示该版本对应的游戏版本与加载器）。
     */
    private void openDetailPage(ModrinthApi.Hit hit) {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-background-radius:20; -fx-background-insets:0;"
                + "-fx-border-radius:20; -fx-border-color:" + Ui.BORDER + ";");

        // 顶部：整合包图标 + 名称 + 下载量，右上角「返回」
        javafx.scene.image.ImageView icon = new javafx.scene.image.ImageView(Ui.GRASS_BLOCK);
        icon.setFitWidth(56);
        icon.setFitHeight(56);
        icon.setPreserveRatio(true);
        icon.setSmooth(false);
        javafx.scene.shape.Rectangle iconClip = new javafx.scene.shape.Rectangle(56, 56);
        iconClip.setArcWidth(10);
        iconClip.setArcHeight(10);
        icon.setClip(iconClip);
        String iconUrl = hit.iconUrl();
        if (iconUrl != null && !iconUrl.isBlank()) {
            Image cached = IconCache.cached(iconUrl);
            if (cached != null) {
                icon.setImage(cached);
            } else {
                IconCache.ensure(iconUrl, icon::setImage);
            }
        }

        Label title = new Label(hit.title());
        title.setStyle("-fx-font-size:19px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        Label dlLabel = new Label("下载量 " + hit.downloads());
        dlLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        VBox titleBox = new VBox(2);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.getChildren().addAll(title, dlLabel);
        Button backBtn = new Button("返回");
        backBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        HBox headSpacer = new HBox();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox head = new HBox(12);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(icon, titleBox, headSpacer, backBtn);

        Label desc = new Label(hit.description());
        desc.setWrapText(true);
        desc.setMaxHeight(90);
        desc.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");

        Label sec = new Label("整合包版本（含对应游戏版本与加载器）");
        sec.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");

        // 整合包版本直接铺成列表（与 Mod 下载列表同款观感）
        ListView<ModrinthApi.Version> verList = new ListView<>();
        verList.setPrefHeight(232);
        verList.setMaxWidth(Double.MAX_VALUE);
        verList.setDisable(true);
        verList.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-background-radius:12;"
                + "-fx-border-radius:12; -fx-border-color:" + Ui.BORDER + ";");
        verList.setCellFactory(lv -> new ListCell<ModrinthApi.Version>() {
            @Override
            protected void updateItem(ModrinthApi.Version ver, boolean empty) {
                super.updateItem(ver, empty);
                if (empty || ver == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label numLbl = new Label(ver.versionNumber() == null ? ver.id() : ver.versionNumber());
                numLbl.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
                java.util.List<String> mcVers = ver.gameVersions();
                java.util.List<String> lds = ver.loaders();
                String mcShow = mcVers.isEmpty() ? "—"
                        : (mcVers.get(0) + (mcVers.size() > 1 ? " 等 " + mcVers.size() + " 个版本" : ""));
                String ldShow = lds.isEmpty() ? "原版" : lds.get(0);
                Label metaLbl = new Label("MC " + mcShow + "  ·  " + ldShow
                        + "  ·  " + shortDate(ver.datePublished()));
                metaLbl.setStyle("-fx-font-size:12px; -fx-text-fill:" + Ui.TEXT_DIM + ";");
                VBox line = new VBox(1, numLbl, metaLbl);
                setText(null);
                setGraphic(line);
            }
        });
        Label verEmpty = new Label("该整合包暂无可下载版本");
        verEmpty.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        verList.setPlaceholder(verEmpty);

        Label pickHint = new Label("安装后自动创建独立实例：装好该版本所需的加载器与全部 Mod，返回主页即可启动。");
        pickHint.setWrapText(true);
        pickHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        Button installBtn = new Button("安装");
        installBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;"
                + " -fx-font-weight:bold; -fx-padding:8 26;");
        installBtn.setDisable(true);

        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_RIGHT);
        actionRow.getChildren().add(installBtn);

        // 页面底部留白靠 Region 撑开，让安装按钮贴到页脚
        javafx.scene.layout.Region footSpacer = new javafx.scene.layout.Region();
        VBox.setVgrow(footSpacer, Priority.ALWAYS);

        page.getChildren().addAll(head, desc, new Separator(), sec, verList, pickHint,
                new Separator(), footSpacer, actionRow);

        javafx.scene.layout.StackPane wrap = new javafx.scene.layout.StackPane(page);
        wrap.setStyle("-fx-background-color:" + Ui.BG_LIGHT + ";");
        app.showFullPage("安装整合包 " + hit.title(), wrap);

        backBtn.setOnAction(e -> {
            app.showDownload();
        });

        app.setStatus("正在读取可用版本…");
        Ui.async2(() -> api.allVersions(hit.projectId()), vers -> {
            List<ModrinthApi.Version> sorted = new ArrayList<>(vers);
            // 按发布日期倒序：最新发布的排最前，进页面默认选中最新版本
            sorted.sort((a, b) -> b.datePublished() == a.datePublished()
                    ? b.versionNumber().compareTo(a.versionNumber())
                    : Long.compare(b.datePublished(), a.datePublished()));
            verList.getItems().setAll(sorted);
            if (!sorted.isEmpty()) {
                verList.getSelectionModel().selectFirst();
                verList.scrollTo(0);
            }
            verList.setDisable(sorted.isEmpty());
            installBtn.setDisable(sorted.isEmpty());
            if (sorted.isEmpty()) {
                pickHint.setText("该整合包暂无可下载版本。");
            }
        }, err -> {
            app.setStatus("读取版本失败");
            pickHint.setText("版本列表读取失败：" + err.getMessage());
            Ui.error("读取版本失败", err);
        });

        installBtn.setOnAction(e -> {
            ModrinthApi.Version ver = verList.getSelectionModel().getSelectedItem();
            if (ver == null) {
                Ui.error("未选择版本", "请先在上方选择整合包版本。");
                return;
            }
            sink.progress(0);
            sink.message("就绪");
            installPack(hit, ver);
        });
    }

    /** 版本列表里的发布日期：yyyy-MM-dd，缺失时显示占位。 */
    private static String shortDate(long epochMillis) {
        if (epochMillis <= 0) return "—";
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    private void installPack(ModrinthApi.Hit hit, ModrinthApi.Version ver) {
        sink.message("正在安装整合包…");
        sink.progress(Double.NaN);
        com.jwh.launcher.util.Http.DownloadControl ctl = new com.jwh.launcher.util.Http.CancelFlag();
        attachCtrl.accept(ctl);
        Ui.async2(() -> {
            ModpackInstaller inst = new ModpackInstaller(app.config);
            inst.setDownloadControl(ctl);
            return inst.install(hit.projectId(), ver, new ModpackInstaller.Listener() {
                @Override
                public void onProgress(String step, int done, int total) {
                    javafx.application.Platform.runLater(() -> {
                        sink.message(step + (total > 0 ? "  整体进度 " + Math.round(done * 100.0 / total) + "%" : ""));
                        sink.progress(total > 0
                                ? Math.min(1.0, (double) done / total)
                                : Double.NaN);
                    });
                }

                @Override
                public void onBytes(String step, long bytesDone, long bytesTotal) {
                    if (bytesTotal <= 0) return;
                    javafx.application.Platform.runLater(() -> {
                        sink.progress(Math.min(1.0, (double) bytesDone / bytesTotal));
                        sink.message(step + "  整体进度 " + Math.round(bytesDone * 100.0 / bytesTotal) + "%");
                    });
                }
            });
        }, id -> {
            detachCtrl.run();
            if (ctl.isCancelled()) {
                sink.progress(0);
                sink.message("已取消");
                return;
            }
            sink.message("整合包安装完成：" + id);
            sink.progress(1);
            app.refreshAll();
            Ui.info("安装完成", "整合包「" + hit.title() + "」已安装为独立版本「" + id + "」。\n"
                    + "包含所需加载器与全部 Mod，返回主页选择该版本即可启动。");
        }, err -> {
            detachCtrl.run();
            if (ctl.isCancelled()) {
                sink.progress(0);
                sink.message("已取消");
                return;
            }
            sink.progress(0);
            Ui.error("安装失败", err);
        });
    }
}
