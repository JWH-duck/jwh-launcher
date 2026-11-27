package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.download.DownloadManager;
import com.jwh.launcher.game.GameInstaller;
import com.jwh.launcher.game.VersionManifest;
import com.jwh.launcher.loader.FabricQuiltInstaller;
import com.jwh.launcher.loader.ForgeInstaller;
import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载中心：游戏版本 / 加载器 / Mod / 光影 / 材质包。
 */
public class DownloadView {

    private final LauncherApp app;
    /** 当前进行中的下载控制；弹窗「取消」按钮操作它。 */
    private volatile com.jwh.launcher.util.Http.DownloadControl activeCtrl;
    /** 下载进度弹窗（下载期间惰性创建，结束后关闭并置空）。 */
    private DownloadProgressDialog dialog;
    /** 下载进度转发到弹窗；搜索状态走全局状态栏，不进弹窗。 */
    private final ProgressSink sink = new ProgressSink() {
        @Override
        public void message(String text) {
            Platform.runLater(() -> {
                if (dialog != null) dialog.setMessage(text);
            });
        }

        @Override
        public void progress(double frac) {
            Platform.runLater(() -> {
                if (dialog != null) dialog.setProgress(frac);
            });
        }
    };
    private final VBox page = new VBox(8);
    private VersionManifest manifest;
    private ModrinthTab modTab;
    private ModrinthTab shaderPackTab;
    private ModrinthTab rpTab;
    private ModrinthTab loaderTab;
    private ModpackTab modpackTab;
    /** 版本下载页的加载器融合选择（无（仅原版）/ Fabric / Quilt / Forge / NeoForge）。 */
    private ComboBox<String> loaderPlatform;
    private ComboBox<String> loaderVersionBox;
    /** 版本列表关键词搜索框（按版本 id 过滤）。 */
    private TextField searchBox;
    /** 标题栏与内容标签行之间的近黑分隔色块（对标设计稿）。 */
    private final Region topDivider = new Region();

    public DownloadView(LauncherApp app) {
        this.app = app;

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("jwh-download-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle("-fx-background-color:transparent;");

        Tab versionTab = buildVersionTab();
        modTab = new ModrinthTab(app, ModrinthTab.Type.MOD, sink, this::attachControl, this::detachControl);
        shaderPackTab = new ModrinthTab(app, ModrinthTab.Type.SHADER, sink, this::attachControl, this::detachControl);
        rpTab = new ModrinthTab(app, ModrinthTab.Type.RESOURCEPACK, sink, this::attachControl, this::detachControl);
        loaderTab = new ModrinthTab(app, ModrinthTab.Type.LOADER, sink, this::attachControl, this::detachControl);
        modpackTab = new ModpackTab(app, sink, this::attachControl, this::detachControl);
        Tab modTabT = modTab.asTab();
        Tab shaderPackTabT = shaderPackTab.asTab();
        Tab loaderTabT = loaderTab.asTab();
        Tab rpTabT = rpTab.asTab();
        Tab modpackTabT = modpackTab.asTab();

        tabs.getTabs().addAll(versionTab, modTabT, loaderTabT, shaderPackTabT, rpTabT, modpackTabT);
        tabs.getSelectionModel().select(modpackTabT); // TEMP-SHADER-SELECT

        // Tab 切换动效：被选中的 Tab 内容从右侧滑入 + 淡入，与整页切换手感一致
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) return;
            javafx.scene.Node c = newT.getContent();
            if (c == null) return;
            javafx.application.Platform.runLater(() -> Ui.pageIn(c));
        });

        VBox.setVgrow(tabs, Priority.ALWAYS);
        // 内窗口圆角：面板与下方垫层同为 BG_LIGHT，弧线外露出的是垫层(同色)而非窗口底色，
        // 因此圆角四角无白三角/白边；圆角轮廓由深色边框线勾勒。
        page.setStyle("-fx-background-color:#261c30; -fx-background-radius:20; -fx-background-insets:0;"
                + "-fx-border-radius:20; -fx-border-color:transparent;");
        page.setPadding(new Insets(0, 0, 12, 0));
        topDivider.setStyle("-fx-background-color:#261c30; -fx-background-insets:0;");
        topDivider.setPrefHeight(8);
        page.getChildren().add(tabs);
    }

    public Node root() {
        // 垫层：与面板同色铺满整个下载中心区域，让圆角弧线外的角落显示同色而非窗口底色
        StackPane wrap = new StackPane();
        wrap.setStyle("-fx-background-color:#261c30;");
        VBox box = new VBox(0);
        box.getChildren().addAll(topDivider, page);
        wrap.getChildren().add(box);
        return wrap;
    }

    /** 进入页面时刷新（拉取官方版本清单 + 刷新各 Tab 的本地版本下拉）。 */
    public void refresh() {
        if (modTab != null) modTab.refreshVersions();
        if (shaderPackTab != null) shaderPackTab.refreshVersions();
        if (rpTab != null) rpTab.refreshVersions();
        if (loaderTab != null) loaderTab.refreshVersions();
        if (manifest == null) {
            Ui.async(() -> {
                manifest = VersionManifest.fetch(app.config.minecraftPath().resolve("launcher_cache_manifest.json"),
                        app.config.manifestUrl());
                return null;
            }, ok -> app.setStatus("官方版本清单已刷新（最新正式版：" + (manifest == null ? "?" : manifest.latestRelease) + "）"), err -> {
                app.setStatus("版本清单拉取失败：" + err.getMessage());
            });
        }
    }

    private void setProgress(String text, double frac) {
        sink.message(text);
        sink.progress(frac);
    }

    /** 注册当前下载控制：打开下载进度弹窗。下载结束必须调用 detachControl()。 */
    public void attachControl(com.jwh.launcher.util.Http.DownloadControl ctl) {
        activeCtrl = ctl;
        Platform.runLater(() -> {
            if (dialog == null) {
                dialog = new DownloadProgressDialog(app, "下载进度", () -> {
                    var c = activeCtrl;
                    if (c != null) c.cancel();
                });
            }
            dialog.show();
            dialog.setMessage("准备下载…");
            dialog.setProgress(Double.NaN);
        });
    }

    /** 下载结束（成功/失败/取消）后清空控制并关闭进度弹窗。 */
    public void detachControl() {
        activeCtrl = null;
        Platform.runLater(() -> {
            if (dialog != null) {
                dialog.close();
                dialog = null;
            }
        });
    }

    /** 当前下载是否已被用户取消（用于 ok/err 回调区分文案）。 */
    public boolean isDownloadCancelled() {
        var c = activeCtrl;
        return c != null && c.isCancelled();
    }

    // ================= 游戏版本 =================

    private Tab buildVersionTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12, 0, 12, 0));

        // ===== 类型筛选胶囊（正式版 / 快照 / 愚人节 / 远古版 / 全部） =====
        ComboBox<String> filter = new ComboBox<>();
        filter.getItems().addAll("全部", "正式版", "快照", "愚人节", "远古版");
        // 默认选中「正式版」（通用分类：正式版 / 快照 / 愚人节 / 远古版）
        filter.getSelectionModel().select(1);
        filter.getStyleClass().add("jwh-capsule");
        filter.getStyleClass().add("jwh-capsule-plain");

        // ===== 加载器融合选择：平台胶囊 + 版本胶囊 =====
        loaderPlatform = new ComboBox<>();
        loaderPlatform.getItems().addAll("无（仅原版）", "Fabric", "Quilt", "Forge", "NeoForge");
        loaderPlatform.setPromptText("选择加载器");
        loaderPlatform.getStyleClass().add("jwh-capsule");
        loaderPlatform.getStyleClass().add("jwh-capsule-plain");

        loaderVersionBox = new ComboBox<>();
        loaderVersionBox.getItems().addAll("默认最新稳定版");
        loaderVersionBox.getSelectionModel().selectFirst();
        loaderVersionBox.getStyleClass().add("jwh-capsule");
        loaderVersionBox.getStyleClass().add("jwh-capsule-plain");
        loaderVersionBox.setPromptText("加载器版本（默认最新稳定版）");
        loaderVersionBox.setDisable(true); // 未选择游戏版本前不可用

        // ===== 关键词搜索框（按版本 id 过滤），前置放大镜 =====
        searchBox = new TextField();
        searchBox.setPromptText("点击搜索...");
        searchBox.getStyleClass().add("jwh-search");
        javafx.scene.shape.Circle lens = new javafx.scene.shape.Circle(9, 9, 5.5);
        lens.setFill(null);
        lens.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.75));
        lens.setStrokeWidth(1.4);
        javafx.scene.shape.Line searchHandle = new javafx.scene.shape.Line(12.6, 12.6, 16.0, 16.0);
        searchHandle.setStroke(javafx.scene.paint.Color.rgb(255, 255, 255, 0.75));
        searchHandle.setStrokeWidth(1.4);
        javafx.scene.layout.Pane searchGlyph = new javafx.scene.layout.Pane(lens, searchHandle);
        searchGlyph.setPrefSize(18, 18);
        searchGlyph.setMinSize(18, 18);
        searchGlyph.setMaxSize(18, 18);
        HBox searchWrap = new HBox(8, searchGlyph, searchBox);
        searchWrap.setAlignment(Pos.CENTER_LEFT);
        searchWrap.getStyleClass().add("jwh-search-wrap");
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        HBox.setHgrow(searchWrap, Priority.ALWAYS);

        ListView<VersionManifest.Version> list = new ListView<>();
        list.getStyleClass().add("jwh-dl-list");
        list.setStyle("-fx-background-color:transparent; -fx-control-inner-background:transparent; -fx-border-color:transparent; -fx-border-width:0; -fx-background-radius:0; -fx-border-radius:0; -fx-padding:4 0;");
        list.setCellFactory(c -> new ListCell<>() {
            private final javafx.scene.image.ImageView icon = new javafx.scene.image.ImageView();
            private final Label idLabel = new Label();
            private final Label typeLabel = new Label();
            private final Label metaLabel = new Label();
            private final HBox row = new HBox(10);
            private final VBox info = new VBox(2);

            {
                icon.setFitWidth(32);
                icon.setFitHeight(32);
                icon.setPreserveRatio(true);
                icon.setImage(Ui.GRASS_BLOCK);
                idLabel.setStyle("-fx-text-fill:#ffffff; -fx-font-size:15px; -fx-font-weight:bold;");
                typeLabel.setStyle("-fx-text-fill:#d982c2; -fx-font-size:15px; -fx-font-weight:bold;");
                metaLabel.setStyle("-fx-text-fill:rgba(255,255,255,0.5); -fx-font-size:12px;");
                HBox titleLine = new HBox(6, idLabel, typeLabel);
                titleLine.setAlignment(Pos.CENTER_LEFT);
                HBox spacer = new HBox();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                info.getChildren().addAll(titleLine, metaLabel);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getChildren().addAll(icon, info, spacer);
            }

            private void applyRowStyle(boolean selected) {
                // 深色行：未选中透明 + 底部分隔细线；选中浅白半透明条
                setStyle(selected
                        ? "-fx-background-color:rgba(255,255,255,0.06); -fx-background-radius:8; -fx-padding:12 14;"
                        : "-fx-background-color:transparent; -fx-border-color:rgba(255,255,255,0.08); -fx-border-width:0 0 1 0; -fx-padding:12 14;");
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
                    idLabel.setText(v.id() + (installed ? "  ✓已安装" : ""));
                    typeLabel.setText("[" + v.typeLabel() + "]");
                    metaLabel.setText(fmtTime(v.releaseTime()));
                    // 类型图标：愚人节→粘液块、远古版→工作台、其余→草方块（通用效果）
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
        emptyLabel.setMouseTransparent(true);
        list.getItems().addListener((javafx.collections.ListChangeListener<VersionManifest.Version>) ch ->
                emptyLabel.setVisible(list.getItems().isEmpty()));

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
        filterBar.setStyle("-fx-background-color:#261c30; -fx-background-radius:0; -fx-padding:10 12;");
        HBox barSpacer = new HBox();
        HBox.setHgrow(barSpacer, Priority.ALWAYS);
        filterBar.getChildren().addAll(filter, loaderPlatform, loaderVersionBox, barSpacer, searchWrap);

        // ===== 列表区：空态居中 + 右下绿色圆形下载钮 =====
        StackPane listWrap = new StackPane(list, emptyLabel, installBtn);
        StackPane.setAlignment(installBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(installBtn, new Insets(0, 16, 16, 0));
        VBox.setVgrow(listWrap, Priority.ALWAYS);
        VBox.setMargin(listWrap, new Insets(0, 12, 0, 12));

        box.getChildren().addAll(filterBar, listWrap);
        Ui.async(() -> {
            manifest = VersionManifest.fetch(app.config.minecraftPath().resolve("launcher_cache_manifest.json"),
                    app.config.manifestUrl());
            return null;
        }, ok -> {
            applyFilter(filter, list);
            app.setStatus("清单已刷新");
        }, err -> Ui.error("拉取失败", err));
        return new Tab("游戏版本", box);
    }

    private void applyFilter(ComboBox<String> filter, ListView<VersionManifest.Version> list) {
        if (manifest == null) return;
        String f = filter.getSelectionModel().getSelectedItem();
        String q = searchBox == null ? "" : searchBox.getText().trim().toLowerCase();
        List<VersionManifest.Version> out = new ArrayList<>();
        for (VersionManifest.Version v : manifest.versions) {
            if (!q.isEmpty() && !v.id().toLowerCase().contains(q)) continue;
            if ("全部".equals(f)) out.add(v);
            else if ("正式版".equals(f) && v.isRelease()) out.add(v);
            else if ("快照".equals(f) && v.isSnapshot()) out.add(v);
            else if ("愚人节".equals(f) && v.isAprilFools()) out.add(v);
            else if ("远古版".equals(f) && v.isOld()) out.add(v);
        }
        // 不截断：官方清单按时间倒序，最近几月几乎全是每周快照，若只取前 60 条，
        // 「全部」看起来就只有快照版，正式版全被挤掉。ListView 虚拟化支持完整列表。
        list.getItems().setAll(out);
    }

    /** 下载指定版本（底部按钮与每行「↓ 安装」共用）；若选择了加载器则安装完游戏后自动安装加载器。
     *  已安装的版本若选择了加载器，则跳过游戏下载直接安装加载器。 */
    private void installVersion(VersionManifest.Version v, Button btn,
                                ComboBox<String> filter, ListView<VersionManifest.Version> list) {
        boolean installed = app.localVersions.exists(v.id());
        String plat = loaderPlatform == null ? null : loaderPlatform.getSelectionModel().getSelectedItem();
        String lv = loaderVersionBox == null ? null : loaderVersionBox.getSelectionModel().getSelectedItem();
        final boolean withLoader = plat != null && !"无（仅原版）".equals(plat);
        if (installed && !withLoader) {
            Ui.info("已安装", "该版本已安装，可直接在主页启动；如需安装加载器，请在上方选择加载器后再点「下载」。");
            return;
        }
        if (lv != null && lv.startsWith("正在获取")) {
            Ui.error("请稍候", "加载器版本列表正在获取中，请稍候再点「下载」。");
            return;
        }
        if (lv == null || lv.isBlank() || "默认最新稳定版".equals(lv) || lv.startsWith("正在")) lv = null;
        // 下拉框显示的是「0.31.0-beta.1（测试版）」这类带标签文本，直接当版本号用会让
        // 版本目录名/库 URL 都带上中文括号导致下载 404、启动缺 jar 秒退。
        // 这里剥离「（…）」/「(…)」标签，只取纯版本号。
        if (lv != null) {
            String t = lv.trim();
            int brace = t.indexOf('（');
            if (brace < 0) brace = t.indexOf('(');
            if (brace > 0) t = t.substring(0, brace).trim();
            lv = t;
        }
        final String loaderVersion = lv;
        btn.setDisable(true);
        GameInstaller inst = new GameInstaller(app.config);
        attachControl(inst);
        overallLast = -1.0; // 新一次下载：整体进度从 0 重新开始
        overallStepLabel = "准备下载";
        Ui.asyncProgress(progress -> {
            progress.accept("准备下载…");
            try {
                if (!installed) {
                    // 阶段1 游戏文件下载：占整体 0~90%（字节整体进度优先，总字节未知时按文件数）
                    setOverallStage(0.0, 0.9);
                    inst.install(v.id(), v.url(), new GameInstaller.InstallListener() {
                        @Override
                        public void onProgress(String step, int done, int total) {
                            overallStepLabel = step;
                            progress.accept(step);
                            updateRealProgress(done, total);
                        }

                        @Override
                        public void onBytes(String step, long bytesDone, long bytesTotal) {
                            updateOverallBytes(bytesDone, bytesTotal);
                        }
                    });
                } else {
                    progress.accept("游戏版本已存在，直接安装 " + plat + " 加载器…");
                }
                if (withLoader && !inst.isCancelled()) {
                    progress.accept("正在安装 " + plat + " 加载器…");
                    // 阶段2 加载器安装：补装加载器占整体 0~100%；融合下载占 90~100%（不回退）
                    setOverallStage(installed ? 0.0 : 0.9, installed ? 1.0 : 0.1);
                    installLoaderInternal(plat, v.id(), loaderVersion, progress);
                }
            } catch (Exception ex) {
                if (inst.isCancelled()) {
                    throw new RuntimeException("已取消");
                }
                throw new RuntimeException(ex);
            }
        }, s -> sink.message(s), ok -> {
            detachControl();
            btn.setDisable(false);
            setProgress(withLoader ? "下载与加载器安装完成" : "下载完成", 1);
            applyFilter(filter, list);
            // 自动分配模式：下载完新版本后按本机可用内存重新推算安全分配值（自定义模式不检测）
            app.applyAutoMemory();
            app.refreshAll();
        }, err -> {
            detachControl();
            btn.setDisable(false);
            if ("已取消".equals(err.getMessage())) {
                setProgress("已取消", 0);
            } else {
                setProgress("安装失败", 0);
                Ui.error("安装失败", err);
            }
        });
    }

    // ===== 整体进度：从点击下载起 0→100%，跨文件、跨阶段连续且只进不退 =====
    /** 当前阶段在整体中的位置：base=阶段起点，span=阶段权重（游戏下载 0~0.9，加载器安装 0.9~1.0）。 */
    private final double[] overallStage = new double[]{0.0, 1.0};
    private double overallLast = -1.0;
    /** 当前步骤描述（进度文本 = 步骤 + 整体百分比，不再显示单文件计数）。 */
    private String overallStepLabel = "准备中";

    /** 设置当前阶段在整体进度中的位置（每次切换阶段前调用）。 */
    private void setOverallStage(double base, double span) {
        overallStage[0] = base;
        overallStage[1] = span;
    }

    /** 文件数进度 → 整体进度（满条=完成；只进不退，阶段切换不回退）。 */
    private void updateRealProgress(int done, int total) {
        if (total <= 0) {
            sink.progress(Double.NaN);
            return;
        }
        double frac = overallStage[0] + overallStage[1] * Math.min(1.0, (double) done / total);
        applyOverall(frac);
    }

    /** 真实字节进度 → 整体进度（总字节未知时不更新，继续用文件数进度）。 */
    private void updateOverallBytes(long bytesDone, long bytesTotal) {
        if (bytesTotal <= 0) return;
        double frac = overallStage[0] + overallStage[1] * Math.min(1.0, (double) bytesDone / bytesTotal);
        applyOverall(frac);
    }

    private void applyOverall(double frac) {
        if (frac < overallLast) return; // 只进不退：跨批次/跨阶段不回退
        overallLast = frac;
        int pct = (int) Math.round(frac * 100);
        sink.progress(frac);
        sink.message(overallStepLabel + " 整体进度 " + pct + "%");
    }

    /** 安装加载器（供版本+加载器融合下载调用；复用加载器 Tab 的安装逻辑）。 */
    private void installLoaderInternal(String plat, String mc, String loaderVersion,
                                       java.util.function.Consumer<String> progress) throws Exception {
        if ("Fabric".equals(plat) || "Quilt".equals(plat)) {
            FabricQuiltInstaller.Platform p = "Fabric".equals(plat)
                    ? FabricQuiltInstaller.Platform.FABRIC : FabricQuiltInstaller.Platform.QUILT;
            new FabricQuiltInstaller(app.config).install(p, mc, loaderVersion, (step, done, total) -> {
                overallStepLabel = step;
                progress.accept(step);
                updateRealProgress(done, total);
            });
        } else {
            ForgeInstaller.Platform p = "Forge".equals(plat)
                    ? ForgeInstaller.Platform.FORGE : ForgeInstaller.Platform.NEOFORGE;
            if (p == ForgeInstaller.Platform.NEOFORGE && !ForgeInstaller.neoforgeSupported(mc)) {
                throw new IllegalStateException(mc + " 没有 NeoForge 适配（NeoForge 自 1.20.2 起），请改用 Forge / Fabric / Quilt");
            }
            new ForgeInstaller(app.config).install(p, mc, loaderVersion, javaRuntime(), line -> progress.accept(line),
                    (step, done, total) -> {
                        overallStepLabel = step;
                        progress.accept(step);
                        updateRealProgress(done, total);
                    });
        }
    }

    /** 官方清单的 ISO 时间 → 本地时间显示（如 2026年09月15日 19:23:02）。 */
    private static String fmtTime(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        } catch (Exception e) {
            return iso == null ? "" : iso;
        }
    }


    /** 通用加载器版本查询：按 MC 版本 + 平台填充加载器版本下拉框。 */
    private void fillLoaderVersions(String mc, String plat, ComboBox<String> loaderBox, boolean force) {
        if (mc == null || mc.isBlank() || plat == null || "无（仅原版）".equals(plat)) return;
        loaderBox.getItems().setAll("正在获取版本列表…");
        loaderBox.getSelectionModel().selectFirst();
        Thread t = new Thread(() -> {
            try {
                List<String[]> list = new ArrayList<>();
                if ("Fabric".equals(plat) || "Quilt".equals(plat)) {
                    FabricQuiltInstaller.Platform p = "Fabric".equals(plat)
                            ? FabricQuiltInstaller.Platform.FABRIC : FabricQuiltInstaller.Platform.QUILT;
                    list = new FabricQuiltInstaller(app.config).listLoadersAdapted(p, mc, force);
                } else {
                    ForgeInstaller.Platform p = "Forge".equals(plat)
                            ? ForgeInstaller.Platform.FORGE : ForgeInstaller.Platform.NEOFORGE;
                    if (p == ForgeInstaller.Platform.NEOFORGE && !ForgeInstaller.neoforgeSupported(mc)) {
                        Platform.runLater(() -> {
                            loaderBox.getItems().setAll("该版本无 NeoForge（自 1.20.2 起）");
                            loaderBox.getSelectionModel().selectFirst();
                        });
                        app.setStatus(mc + " 没有 NeoForge 适配，请改用 Forge / Fabric / Quilt");
                        return;
                    }
                    ForgeInstaller inst = new ForgeInstaller(app.config);
                    List<String> vs = inst.listVersions(p, mc, force);
                    if (vs.isEmpty()) {
                        // 兜底：最新/推荐
                        String[] v = inst.latestVersions(p, mc);
                        if (v[0] != null) vs.add(v[0]);
                        if (v[1] != null && !vs.contains(v[1])) vs.add(v[1]);
                    }
                    for (String v : vs) list.add(new String[]{v, ""});
                }
                List<String[]> finalList = list;
                Platform.runLater(() -> {
                    loaderBox.getItems().setAll("默认最新稳定版");
                    for (String[] s : finalList) {
                        loaderBox.getItems().add(s[1] == null || s[1].isEmpty() ? s[0] : s[0] + "（" + s[1] + "）");
                    }
                    loaderBox.getSelectionModel().selectFirst();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    loaderBox.getItems().setAll("默认最新稳定版");
                    loaderBox.getSelectionModel().selectFirst();
                    app.setStatus("加载器版本列表获取失败：" + e.getMessage() + "（可稍后重试）");
                });
            }
        }, "loader-version-query");
        t.setDaemon(true);
        t.start();
    }

    private Path javaRuntime() {
        try {
            Path p = Path.of(app.selectedJavaPath);
            if (Files.exists(p)) return p;
        } catch (Exception ignored) {
        }
        if (app.javaRuntimes != null && !app.javaRuntimes.isEmpty()) {
            return app.javaRuntimes.get(0).javaExe();
        }
        return Path.of("java");
    }
}
