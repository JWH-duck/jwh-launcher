package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.download.ContentService;
import com.jwh.launcher.download.ModrinthApi;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 光影加载器 Tab：
 * 方案 A：Iris（现代光影渲染器，装入目标版本 mods 目录，配合光影包使用）
 * 方案 B：OptiFine（官方无开放下载 API，社区统一经 BMCLAPI 镜像获取，下载后需运行安装器）
 */
public class ShaderTab {

    private final LauncherApp app;
    private final ProgressSink sink;
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<String> irisLoaderBox = new ComboBox<>();
    private final ComboBox<String> optifineBox = new ComboBox<>();
    private final TextArea note = new TextArea();

    /** 版本下拉的 id → 显示名（HMCL 式：加载器实例显示 "1.21.1 · NeoForge 21.1.265"）。 */
    private final java.util.Map<String, String> displayNames = new java.util.HashMap<>();
    /** 版本下拉的 id → 纯游戏版本（neoforge-21.1.265 → 1.21.1，供 API 使用）。 */
    private final java.util.Map<String, String> mcBases = new java.util.HashMap<>();

    private final java.util.function.Consumer<com.jwh.launcher.util.Http.DownloadControl> attachCtrl;
    private final Runnable detachCtrl;

    public ShaderTab(LauncherApp app, ProgressSink sink,
                     java.util.function.Consumer<com.jwh.launcher.util.Http.DownloadControl> attachCtrl,
                     Runnable detachCtrl) {
        this.app = app;
        this.sink = sink;
        this.attachCtrl = attachCtrl;
        this.detachCtrl = detachCtrl;
    }

    /** 单文件真实字节进度 + 取消控制。 */
    private com.jwh.launcher.util.Http.DownloadControl bytesControl(long totalSize) {
        com.jwh.launcher.util.Http.CancelFlag flag = new com.jwh.launcher.util.Http.CancelFlag();
        return new com.jwh.launcher.util.Http.DownloadControl() {
            @Override
            public void onBytes(long bytes) {
                double frac = totalSize > 0
                        ? Math.min(1.0, (double) bytes / totalSize)
                        : Double.NaN;
                javafx.application.Platform.runLater(() -> sink.progress(frac));
            }

            @Override
            public boolean isCancelled() {
                return flag.isCancelled();
            }
        };
    }

    /** 结束下载：隐藏取消按钮；被取消时不弹错误窗。 */
    private boolean endDownload(com.jwh.launcher.util.Http.DownloadControl ctl, Runnable onDone) {
        detachCtrl.run();
        if (ctl != null && ctl.isCancelled()) {
            sink.progress(0);
            sink.message("已取消");
            return true;
        }
        onDone.run();
        return false;
    }

    public Tab asTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));

        // 目标版本
        versionBox.setPromptText("安装到的版本（必选）");
        versionBox.setPrefWidth(220);
        versionBox.setCellFactory(c -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });
        versionBox.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });
        Button refreshBtn = new Button("刷新版本列表");
        refreshBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        refreshBtn.setOnAction(e -> refreshVersions());
        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getChildren().addAll(new Label("目标版本："), versionBox, refreshBtn);

        // ---- 方案 A：Iris ----
        Label t1 = new Label("方案 A：Iris（推荐，Fabric / NeoForge 现代光影渲染器）");
        t1.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");

        irisLoaderBox.getItems().addAll("fabric", "neoforge");
        irisLoaderBox.getSelectionModel().selectFirst();
        Button irisBtn = new Button("一键安装 Iris 到所选版本");
        irisBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white; -fx-font-weight:bold;");
        irisBtn.setOnAction(e -> installIris(irisBtn));
        HBox rowIris = new HBox(8);
        rowIris.setAlignment(Pos.CENTER_LEFT);
        rowIris.getChildren().addAll(new Label("加载器："), irisLoaderBox, irisBtn);

        // ---- 方案 B：OptiFine ----
        Label t2 = new Label("方案 B：OptiFine（传统光影，安装后为独立版本）");
        t2.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");

        Button ofRefresh = new Button("查询版本");
        ofRefresh.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        Button ofDownload = new Button("下载并运行安装器");
        ofDownload.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-text-fill:" + Ui.TEXT + ";");
        ofRefresh.setOnAction(e -> refreshOptifine());
        ofDownload.setOnAction(e -> downloadOptifine());
        HBox rowOf = new HBox(8);
        rowOf.setAlignment(Pos.CENTER_LEFT);
        rowOf.getChildren().addAll(ofRefresh, optifineBox, ofDownload);
        HBox.setHgrow(optifineBox, Priority.ALWAYS);

        note.setEditable(false);
        note.setWrapText(true);
        note.setPrefHeight(120);
        note.setStyle("-fx-control-inner-background:" + Ui.BG_LIGHT + "; -fx-text-fill:#1f2328; -fx-font-family:Consolas,monospace; -fx-font-size:13px;");
        note.setText("使用说明\n"
                + "· 方案 A（Iris）：你的版本需为 Fabric 或 NeoForge 加载器版本（先到\"加载器\"页安装），装好后\n"
                + "  再到\"光影包\"页搜索下载光影包（放入 shaderpacks 目录），游戏内光影设置选择即可。\n"
                + "· 方案 B（OptiFine）：OptiFine 官方下载页含浏览器校验、没有开放 API，本启动器按社区通用\n"
                + "  做法经 BMCLAPI 镜像获取安装包，下载后自动弹出其官方安装器（GUI），点击 Install 完成\n"
                + "  安装后即为独立版本，可正常启动。");

        VBox.setVgrow(note, Priority.ALWAYS);
        box.getChildren().addAll(top, t1, rowIris, t2, rowOf, note);
        return new Tab("光影加载器", box);
    }

    /**
     * 刷新：填充本地可见实例下拉（HMCL 式）。
     * 下拉显示合并名（如 "1.21.1 · NeoForge 21.1.265"），值为实例 id；
     * API 用纯游戏版本（mcBase），安装落点用实例 id 目录。
     */
    public void refreshVersions() {
        // 记下当前选中实例；重建下拉后若它仍在列表内则保留，避免刷新跳回首项。
        String oldId = versionBox.getSelectionModel().getSelectedItem();
        displayNames.clear();
        mcBases.clear();
        List<String> items = new ArrayList<>();
        for (var v : app.localVersions.scanVisible()) {
            displayNames.put(v.id(), v.displayName());
            mcBases.put(v.id(), v.mcBase() == null || v.mcBase().isBlank() ? v.id() : v.mcBase());
            items.add(v.id());
        }
        versionBox.getItems().setAll(items);
        if (oldId != null && items.contains(oldId)) {
            versionBox.getSelectionModel().select(oldId);
        } else if (!items.isEmpty()) {
            versionBox.getSelectionModel().selectFirst();
        }
    }

    /** 当前选中实例的纯游戏版本（供 Iris / OptiFine API 使用）。 */
    private String selectedMc() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (id == null) return null;
        return mcBases.getOrDefault(id, id);
    }

    /** 当前选中实例的 id（安装落点目录）。 */
    private String selectedId() {
        return versionBox.getSelectionModel().getSelectedItem();
    }

    private void installIris(Button btn) {
        String mc = selectedMc();
        String target = selectedId();
        if (mc == null || mc.isBlank() || target == null || target.isBlank()) {
            Ui.error("未选择版本", "请先选择目标版本。");
            return;
        }
        String loader = irisLoaderBox.getSelectionModel().getSelectedItem();
        btn.setDisable(true);
        sink.message("正在获取 Iris…");
        sink.progress(Double.NaN);
        com.jwh.launcher.util.Http.DownloadControl[] ctlRef = new com.jwh.launcher.util.Http.DownloadControl[1];
        Ui.async(() -> {
            List<ModrinthApi.Version> versions = new ModrinthApi().versions("iris", mc, loader);
            if (versions.isEmpty()) {
                throw new IllegalStateException("Iris 没有适配 " + mc + " (" + loader + ") 的版本");
            }
            ModrinthApi.File f = versions.get(0).primaryFile();
            ctlRef[0] = bytesControl(f.size());
            attachCtrl.accept(ctlRef[0]);
            new ContentService(app.config).downloadTo(ModrinthApi.Type.MOD, target, f.url(), f.filename(), ctlRef[0]);
            return null;
        }, ok -> {
            endDownload(ctlRef[0], () -> {
                btn.setDisable(false);
                sink.message("Iris 安装完成");
                sink.progress(1);
                Ui.info("安装完成", "Iris " + loader + " 版已放入 " + target + " 的 mods 目录。\n再到\"光影包\"页下载光影包即可。");
            });
        }, err -> {
            endDownload(ctlRef[0], () -> {
                btn.setDisable(false);
                sink.progress(0);
                Ui.error("Iris 安装失败", err);
            });
        });
    }

    private void refreshOptifine() {
        String mc = selectedMc();
        if (mc == null || mc.isBlank()) {
            Ui.error("未选择版本", "请先选择目标版本。");
            return;
        }
        app.setStatus("正在查询 OptiFine 版本…");
        Ui.async(() -> {
            List<ContentService.OptifineVersion> list = new ContentService(app.config).listOptifine(mc);
            Platform.runLater(() -> {
                optifineBox.getItems().clear();
                for (ContentService.OptifineVersion v : list) {
                    optifineBox.getItems().add(v.filename() + (v.latest() ? "  (最新)" : ""));
                }
                if (optifineBox.getItems().size() > 0) optifineBox.getSelectionModel().selectFirst();
                app.setStatus("找到 " + list.size() + " 个 OptiFine 版本");
            });
            return null;
        }, ok -> {
        }, err -> {
            app.setStatus("查询失败");
            Ui.error("查询失败", err);
        });
    }

    private void downloadOptifine() {
        String mc = selectedMc();
        String sel = optifineBox.getSelectionModel().getSelectedItem();
        if (mc == null || sel == null) {
            Ui.error("未选择", "请先选择目标版本并查询 OptiFine 版本。");
            return;
        }
        sink.message("正在下载 " + sel + " …");
        sink.progress(Double.NaN);
        com.jwh.launcher.util.Http.DownloadControl[] ctlRef = new com.jwh.launcher.util.Http.DownloadControl[1];
        Ui.async2(() -> {
            List<ContentService.OptifineVersion> list = new ContentService(app.config).listOptifine(mc);
            ContentService.OptifineVersion target = null;
            for (ContentService.OptifineVersion v : list) {
                if (sel.startsWith(v.filename())) {
                    target = v;
                    break;
                }
            }
            if (target == null) throw new IllegalStateException("找不到对应下载地址，请重新查询");
            long sz = com.jwh.launcher.util.Http.contentLength(target.downloadUrl());
            ctlRef[0] = bytesControl(sz);
            attachCtrl.accept(ctlRef[0]);
            return new ContentService(app.config).downloadOptifine(target.downloadUrl(), target.filename(), ctlRef[0]);
        }, jar -> {
            endDownload(ctlRef[0], () -> {
                sink.progress(1);
                sink.message("下载完成，正在启动安装器…");
                // 运行 OptiFine 安装器（GUI），用户点击 Install 后生成独立版本
                runInstaller(jar);
            });
        }, err -> {
            endDownload(ctlRef[0], () -> {
                sink.progress(0);
                Ui.error("下载失败", err);
            });
        });
    }

    private void runInstaller(Path jar) {
        String java = app.selectedJavaPath;
        if (java == null || java.isBlank() || !Files.exists(Path.of(java))) {
            if (app.javaRuntimes != null && !app.javaRuntimes.isEmpty()) {
                java = app.javaRuntimes.get(0).javaExe().toString();
            } else {
                java = "java";
            }
        }
        try {
            new ProcessBuilder(java, "-jar", jar.toString()).start();
            sink.message("安装器已启动，请在弹出的窗口中选择 Install");
        } catch (Exception e) {
            Ui.error("启动安装器失败", e);
        }
    }
}
