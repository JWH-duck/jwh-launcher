# -*- coding: utf-8 -*-
"""#270 续跑：Modrinth 分支置顶内置条目 + 详情路由 + OptiFine 专用流程。"""
import io, os, sys

MT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher\ui\ModrinthTab.java"

def load():
    with io.open(MT, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")

def save(s):
    with io.open(MT, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)

def rep(s, old, new, tag, expect=1):
    n = s.count(old)
    if n != expect:
        print(f"[FAIL] {tag}: anchor count={n} expect={expect}")
        sys.exit(1)
    print(f"[OK] {tag}")
    return s.replace(old, new)

s = load()

# 6. 仅 Modrinth 分支（searchAll）排序后置顶内置条目
old_pin = ("            List<ModrinthApi.Hit> merged = searchAll(q, mc, loader);\n"
           "            Platform.runLater(() -> {\n"
           "                items.clear();\n"
           "                items.addAll(merged);\n"
           "                applySort();\n"
           "                rebuildGrid();\n")
new_pin = ("            List<ModrinthApi.Hit> merged = searchAll(q, mc, loader);\n"
           "            Platform.runLater(() -> {\n"
           "                items.clear();\n"
           "                items.addAll(merged);\n"
           "                applySort();\n"
           "                if (type == Type.LOADER) pinBuiltin();\n"
           "                rebuildGrid();\n")
s = rep(s, old_pin, new_pin, "pin after sort")

# 7. openDetailPage 路由到 OptiFine 专用页
s = rep(s,
    "    private void openDetailPage(ModrinthApi.Hit hit) {\n",
    "    private void openDetailPage(ModrinthApi.Hit hit) {\n"
    "        if (OPTIFINE_ID.equals(hit.projectId())) { openOptifinePage(); return; }\n",
    "route optifine")

# 8. 新增 OptiFine 专用详情/安装流程，插在 shortDate 注释前
anchor_date = "    /** 版本列表里的发布日期：yyyy-MM-dd，缺失时显示占位。 */"
methods = '''    /** 内置 OptiFine 详情页：不走 Modrinth，经 BMCLAPI 镜像下载官方安装器并弹出。 */
    private void openOptifinePage() {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        page.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-background-radius:20; -fx-background-insets:0;"
                + "-fx-border-radius:20; -fx-border-color:" + Ui.BORDER + ";");

        ImageView icon = new ImageView(Ui.GRASS_BLOCK);
        icon.setFitWidth(56);
        icon.setFitHeight(56);
        icon.setPreserveRatio(true);
        icon.setSmooth(false);
        javafx.scene.shape.Rectangle iconClip = new javafx.scene.shape.Rectangle(56, 56);
        iconClip.setArcWidth(10);
        iconClip.setArcHeight(10);
        icon.setClip(iconClip);

        Label title = new Label("OptiFine");
        title.setStyle("-fx-font-size:19px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        Label sub = new Label("传统光影优化模组 · 安装后为独立版本");
        sub.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        VBox titleBox = new VBox(2);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.getChildren().addAll(title, sub);

        Button backBtn = new Button("返回");
        backBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        HBox headSpacer = new HBox();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox head = new HBox(12);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(icon, titleBox, headSpacer, backBtn);

        Label desc = new Label("OptiFine 官方下载页含浏览器校验、没有开放 API，本启动器经 BMCLAPI 镜像获取官方安装器；"
                + "下载完成后自动弹出安装器窗口，点击 Install 即安装为独立版本，可在主页选择启动。");
        desc.setWrapText(true);
        desc.setMaxHeight(72);
        desc.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");

        Label sec = new Label("目标实例（游戏版本）");
        sec.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        ComboBox<String> targetBox = new ComboBox<>();
        targetBox.setPrefWidth(460);
        targetBox.setPromptText("实例管理中的游戏版本");
        targetBox.setCellFactory(c -> new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });
        targetBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });
        for (String id : versionBox.getItems()) targetBox.getItems().add(id);
        String cur = versionBox.getSelectionModel().getSelectedItem();
        if (cur != null && targetBox.getItems().contains(cur)) targetBox.getSelectionModel().select(cur);
        else if (!targetBox.getItems().isEmpty()) targetBox.getSelectionModel().selectFirst();

        Label hint = new Label("仅按游戏版本查询 OptiFine；OptiFine 不依赖 Fabric/NeoForge 加载器。");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        Button downloadBtn = new Button("下载并运行安装器");
        downloadBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;"
                + " -fx-font-weight:bold; -fx-padding:8 26;");

        HBox targetRow = new HBox(10);
        targetRow.setAlignment(Pos.CENTER_LEFT);
        Label tl = new Label("目标实例：");
        tl.setMinWidth(64);
        targetRow.getChildren().addAll(tl, targetBox);
        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_RIGHT);
        actionRow.getChildren().add(downloadBtn);
        javafx.scene.layout.Region footSpacer = new javafx.scene.layout.Region();
        VBox.setVgrow(footSpacer, Priority.ALWAYS);

        page.getChildren().addAll(head, desc, new Separator(), sec, targetRow, hint,
                new Separator(), footSpacer, actionRow);
        javafx.scene.layout.StackPane wrap = new javafx.scene.layout.StackPane(page);
        wrap.setStyle("-fx-background-color:" + Ui.BG_LIGHT + ";");
        app.showFullPage("下载 OptiFine", wrap);
        backBtn.setOnAction(e -> app.showDownload());

        downloadBtn.setOnAction(e -> installOptifine(targetBox, downloadBtn));
    }

    /** 按所选实例的 MC 版本，经 BMCLAPI 取最新 OptiFine，下载安装器 jar 后弹出官方安装器。 */
    private void installOptifine(ComboBox<String> targetBox, Button btn) {
        String id = targetBox.getSelectionModel().getSelectedItem();
        if (id == null) {
            Ui.error("未选择实例", "请先在上方选择目标实例。");
            return;
        }
        String mc = mcBases.getOrDefault(id, id);
        btn.setDisable(true);
        sink.message("正在查询 OptiFine 版本…");
        sink.progress(Double.NaN);
        com.jwh.launcher.util.Http.DownloadControl[] ctlRef = new com.jwh.launcher.util.Http.DownloadControl[1];
        Ui.async2(() -> {
            List<ContentService.OptifineVersion> list = new ContentService(app.config).listOptifine(mc);
            if (list.isEmpty()) throw new IllegalStateException("OptiFine 没有适配 " + mc + " 的版本");
            ContentService.OptifineVersion target = null;
            for (ContentService.OptifineVersion v : list) {
                if (v.latest()) { target = v; break; }
            }
            if (target == null) target = list.get(0);
            long sz = com.jwh.launcher.util.Http.contentLength(target.downloadUrl());
            ctlRef[0] = bytesControl(sz);
            attachCtrl.accept(ctlRef[0]);
            return new ContentService(app.config).downloadOptifine(target.downloadUrl(), target.filename(), ctlRef[0]);
        }, jar -> {
            endDownload(ctlRef[0], () -> {
                btn.setDisable(false);
                sink.progress(1);
                sink.message("下载完成，正在启动安装器…");
                runInstaller(jar);
            });
        }, err -> {
            endDownload(ctlRef[0], () -> {
                btn.setDisable(false);
                sink.progress(0);
                Ui.error("OptiFine 下载失败", err);
            });
        });
    }

    /** 运行 OptiFine 安装器（GUI），用户点击 Install 后生成独立版本。 */
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

'''
s = rep(s, anchor_date, methods + anchor_date, "optifine methods")

save(s)
print("DONE")
