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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * 下载中心「整合包」Tab：从 Modrinth 搜索 .mrpack 整合包，
 * 点击后弹窗选择版本，一键安装为独立实例（自动装加载器 + 下载全部 Mod / 配置）。
 */
public class ModpackTab {

    private final LauncherApp app;
    private final ProgressSink sink;
    private final ModrinthApi api = new ModrinthApi();
    /** 当前搜索结果（双列网格数据源）。 */
    private final List<ModrinthApi.Hit> items = new ArrayList<>();
    /** 双列卡片网格容器（ScrollPane 内竖向滚动）。 */
    private final VBox grid = new VBox(10);
    private final TextField searchBox = new TextField();
    private final Button searchBtn = new Button("搜索");
    private final Button refreshBtn = new Button("刷新");

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

        searchBox.setPromptText("搜索整合包，例如：vault / skyblock / rlcraft / create");
        searchBox.setPrefWidth(340);
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        searchBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
        refreshBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");

        // 双列卡片网格：ScrollPane 内竖向滚动，横向不滚
        grid.setStyle("-fx-background-color:transparent;");
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color:transparent; -fx-background:" + Ui.PANEL + ";");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getChildren().addAll(searchBox, searchBtn, refreshBtn);

        searchBtn.setOnAction(e -> search());
        searchBox.setOnAction(e -> search());
        refreshBtn.setOnAction(e -> {
            IconCache.resetFailures();
            search();
        });

        box.getChildren().addAll(top, scroll);
        Tab tab = new Tab("整合包", box);
        // 首次进入自动加载热门整合包
        tab.selectedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now) && items.isEmpty()) {
                search();
            }
        });
        return tab;
    }

    /** 重建双列网格：每行两张等宽卡片，落单的最后一张补弹性占位。 */
    private void rebuildGrid() {
        grid.getChildren().clear();
        for (int i = 0; i < items.size(); i += 2) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.TOP_LEFT);
            row.setFillHeight(true);
            row.setMinWidth(0);
            row.setMaxWidth(Double.MAX_VALUE);

            VBox a = cardNode(items.get(i));
            HBox.setHgrow(a, Priority.ALWAYS);
            row.getChildren().add(a);

            if (i + 1 < items.size()) {
                VBox b = cardNode(items.get(i + 1));
                HBox.setHgrow(b, Priority.ALWAYS);
                row.getChildren().add(b);
            } else {
                Region filler = new Region();
                filler.setMinWidth(0);
                HBox.setHgrow(filler, Priority.ALWAYS);
                row.getChildren().add(filler);
            }
            grid.getChildren().add(row);
        }
    }

    /** 一张整合包卡片：图标 + 标题/下载量分类/简介，整卡可点进详情页。 */
    private VBox cardNode(ModrinthApi.Hit h) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(10));
        card.setStyle("-fx-background-color:rgba(255,255,255,0.75); -fx-background-radius:10; -fx-border-radius:10;"
                + "-fx-border-color:#e1e4e8; -fx-border-width:1;");
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> openDetailPage(h));

        ImageView iconView = new ImageView();
        iconView.setFitWidth(36);
        iconView.setFitHeight(36);
        iconView.setPreserveRatio(true);
        iconView.setSmooth(true);
        Label titleLabel = new Label(h.title());
        titleLabel.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:14px; -fx-font-weight:bold;");
        Label metaLabel = new Label("下载量 " + h.downloads()
                + "    [" + String.join("/", h.categories()) + "]");
        metaLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        Label descLabel = new Label(h.description());
        descLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        descLabel.setWrapText(true);
        VBox info = new VBox(2, titleLabel, metaLabel, descLabel);
        HBox row = new HBox(10, iconView, info);
        row.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(row);

        iconView.setImage(Ui.GRASS_BLOCK);
        String icon = h.iconUrl();
        if (icon != null && !icon.isBlank()) {
            String currentIconUrl = icon;
            Image cached = IconCache.cached(icon);
            if (cached != null) {
                iconView.setImage(cached);
            } else {
                IconCache.ensure(icon, img -> {
                    if (icon.equals(currentIconUrl)) {
                        iconView.setImage(img);
                    }
                });
            }
        }
        return card;
    }

    private void search() {
        String q = searchBox.getText().trim();
        items.clear();
        rebuildGrid();
        app.setStatus("正在搜索整合包…");
        Ui.async(() -> {
            List<ModrinthApi.Hit> hits = api.search(q, ModrinthApi.Type.MODPACK, null, null, 60);
            Platform.runLater(() -> {
                items.clear();
                items.addAll(hits);
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
                + "-fx-border-radius:20; -fx-border-color:rgba(31,35,40,0.18);");

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
        sec.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT_DIM + ";");

        // 整合包版本直接铺成列表（与 Mod 下载列表同款观感）
        ListView<ModrinthApi.Version> verList = new ListView<>();
        verList.setPrefHeight(232);
        verList.setMaxWidth(Double.MAX_VALUE);
        verList.setDisable(true);
        verList.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-background-radius:12;"
                + "-fx-border-radius:12; -fx-border-color:rgba(31,35,40,0.14);");
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
