package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.game.JavaFinder;
import com.jwh.launcher.util.MemoryProbe;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 设置页（三标签：个性化 / 启动环境 / 其他）。
 * 现有控件按稿面归类到三个标签；改动后防抖自动保存并生效，右上角仅保留「恢复默认」重置各项。
 */
public class SettingsView {

    private final LauncherApp app;

    // ---- 启动环境 ----
    private final TextField mcDir = new TextField();
    private final CheckBox isolation = new CheckBox("开启版本隔离（每个版本独立的 saves / mods / config）");
    private final Spinner<Integer> minMem = new Spinner<>(512, 8192, 1024, 256);
    private final Slider maxMemSlider = new Slider(512, 16384, 4096);
    private final TextField maxMemField = new TextField("4096");
    private final Label memInfoLabel = new Label();
    private final SwitchBox memAutoSwitch = new SwitchBox(true);
    private final Label memHintLabel = new Label();
    private final ComboBox<JavaFinder.JavaRuntime> javaBox = new ComboBox<>();
    private final Spinner<Integer> threads = new Spinner<>(1, 32, 8, 1);
    private final TextArea jvmArgsArea = new TextArea();
    /** JVM 默认参数的三行灰字提示；仅在参数框为空时叠加显示（TextArea 占位符不支持多行换行）。 */
    private final Label jvmHint = new Label("-XX:+UseG1GC\n-XX:+ParallelRefProcEnabled\n-XX:MaxGCPauseMillis=50");
    private final ComboBox<String> sourceBox = new ComboBox<>();

    // 以下字段保留（内置默认值），界面不再构建可见控件。
    private final TextField microsoftClientId = new TextField();
    private final TextField microsoftRedirectUri = new TextField();
    private final TextField roomServer = new TextField();
    private final TextField roomToken = new TextField();

    // ---- 个性化：外观 / 字体 / 音乐 ----
    private final ComboBox<String> bgModeBox = new ComboBox<>();
    private final Label bgPathLabel = new Label();
    private final CheckBox customColorCheck = new CheckBox();
    private final ColorPicker textColorPicker = new ColorPicker();
    private final TextField textHexField = new TextField();
    private final ColorPicker dimColorPicker = new ColorPicker();
    private String pickedBgPath = "";
    private final ComboBox<String> musicBox = new ComboBox<>();

    // ---- 其他 ----
    private final ComboBox<String> windowModeBox = new ComboBox<>();
    private final ComboBox<String> languageBox = new ComboBox<>();
    private final ComboBox<String> channelBox = new ComboBox<>();
    private final TextField logPathField = new TextField();

    /** 内存分配区块（供外部定位滚动到该设置项）。 */
    private VBox memoryRow;
    private VBox tabPersonal, tabEnv, tabOther;
    private final Label[] tabLabels = new Label[3];
    private int activeTab = 0;
    /** 仅包裹三标签内容区的滚动容器；标签行与操作钮固定在其上方不随滚动。 */
    private ScrollPane contentScroll;

    /** 供外部获取内容滚动容器，用于定位到指定设置区块。 */
    public ScrollPane contentScroll() {
        return contentScroll;
    }

    /** root() 回填配置期间为 true：此时控件变化不触发自动保存，避免回填误写。 */
    private boolean loading = true;
    /** 自动保存防抖器：连续调整（拖滑块、打字、拖色）合并为一次保存。 */
    private final PauseTransition autoSaveTimer = new PauseTransition(Duration.millis(450));
    /** 待跟随的副作用标记：外观变化需重建界面、目录变化需刷新列表、下载源变化需测速。 */
    private boolean dirtyVisual, dirtyDir, dirtySource;

    public SettingsView(LauncherApp app) {
        this.app = app;
        // 内存数值框跟随滑块
        maxMemSlider.valueProperty().addListener((obs, o, n) -> {
            maxMemField.setText(String.valueOf(n.intValue()));
            updateMemInfo();
        });
        memAutoSwitch.setOnAction(this::onMemAutoChanged);
        hookAutoSave();
    }

    /**
     * 为设置页全部控件挂变化监听：任意一项被玩家调整后自动保存并生效。
     * 只在这里注册一次——root() 每次进设置页都会被重建调用，若放那里会重复累积。
     */
    private void hookAutoSave() {
        autoSaveTimer.setOnFinished(e -> applyAutoSave());
        mcDir.textProperty().addListener((o, a, b) -> { if (!loading) { dirtyDir = true; scheduleAutoSave(); } });
        isolation.selectedProperty().addListener((o, a, b) -> scheduleAutoSave());
        minMem.valueProperty().addListener((o, a, b) -> scheduleAutoSave());
        maxMemSlider.valueProperty().addListener((o, a, b) -> scheduleAutoSave());
        javaBox.valueProperty().addListener((o, a, b) -> scheduleAutoSave());
        threads.valueProperty().addListener((o, a, b) -> scheduleAutoSave());
        sourceBox.valueProperty().addListener((o, a, b) -> { if (!loading) { dirtySource = true; scheduleAutoSave(); } });
        jvmArgsArea.textProperty().addListener((o, a, b) -> {
            jvmHint.setVisible(b.trim().isEmpty());
            scheduleAutoSave();
        });
        microsoftClientId.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        microsoftRedirectUri.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        roomServer.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        roomToken.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        bgModeBox.valueProperty().addListener((o, a, b) -> { if (!loading) { dirtyVisual = true; scheduleAutoSave(); } });
        textColorPicker.valueProperty().addListener((o, a, b) -> { if (!loading) { dirtyVisual = true; scheduleAutoSave(); } });
        dimColorPicker.valueProperty().addListener((o, a, b) -> { if (!loading) { dirtyVisual = true; scheduleAutoSave(); } });
        musicBox.valueProperty().addListener((o, a, b) -> scheduleAutoSave());
        windowModeBox.valueProperty().addListener((o, a, b) -> scheduleAutoSave());
        languageBox.valueProperty().addListener((o, a, b) -> scheduleAutoSave());
        channelBox.valueProperty().addListener((o, a, b) -> scheduleAutoSave());
        customColorCheck.selectedProperty().addListener((o, a, b) -> {
            textColorPicker.setDisable(!b);
            textHexField.setDisable(!b);
            dimColorPicker.setDisable(!b);
            if (!loading) { dirtyVisual = true; scheduleAutoSave(); }
        });
    }

    /** 防抖排队：连续调整时不断重置计时，停手 450ms 后执行一次保存。 */
    private void scheduleAutoSave() {
        if (loading) return;
        autoSaveTimer.stop();
        autoSaveTimer.playFromStart();
    }

    /** 内存分配区块节点（用于「Java/内存」按钮跳转后自动滚动定位）。 */
    public VBox memoryRow() {
        return memoryRow;
    }

    /** 定位到内存区块前，先切到「启动环境」标签。 */
    public void openEnvTab() {
        switchTab(1);
    }

    // ------------------------------------------------------------------ 标签切换

    private void switchTab(int idx) {
        activeTab = idx;
        applyTab(tabPersonal, idx == 0);
        applyTab(tabEnv, idx == 1);
        applyTab(tabOther, idx == 2);
        for (int i = 0; i < tabLabels.length; i++) {
            if (tabLabels[i] != null) tabLabels[i].setStyle(tabStyle(i == idx));
        }
    }

    private static void applyTab(VBox page, boolean visible) {
        if (page == null) return;
        page.setVisible(visible);
        page.setManaged(visible);
    }

    private static String tabStyle(boolean selected) {
        if (selected) {
            return "-fx-text-fill:#ffffff; -fx-font-weight:bold; -fx-font-size:15px;"
                    + "-fx-background-color:rgba(217,130,194,0.12);"
                    + "-fx-border-color:#d982c2; -fx-border-width:1.5; -fx-border-radius:10;"
                    + "-fx-background-radius:10; -fx-padding:7 18; -fx-cursor:hand;";
        }
        return "-fx-text-fill:rgba(255,255,255,0.55); -fx-font-size:15px;"
                + "-fx-background-color:transparent; -fx-border-color:transparent;"
                + "-fx-padding:7 18; -fx-cursor:hand;";
    }

    // ------------------------------------------------------------------ 内存

    private void onMemAutoChanged() {
        boolean auto = memAutoSwitch.isOn();
        app.config.memoryMode = auto ? "auto" : "custom";
        applyMemModeUI();
        if (auto) app.applyAutoMemory();
        scheduleAutoSave();
    }

    /** 根据自动/自定义启/禁内存控件并更新提示。 */
    private void applyMemModeUI() {
        boolean auto = memAutoSwitch.isOn();
        maxMemSlider.setDisable(auto);
        maxMemField.setDisable(auto);
        minMem.setDisable(auto);
        if (auto) {
            memHintLabel.setText("每次打开启动器自动按本机可用内存推算安全值（物理内存一半，上限 16GB，下限 2GB）。");
        } else {
            memHintLabel.setText("手动设置下方数值，启动器不再自动检测（下载新版本也不会覆盖）。");
        }
        updateMemInfo();
    }

    /** 刷新内存提示行：物理总内存 / 已分配 / 推荐区间。 */
    private void updateMemInfo() {
        long totalMB = MemoryProbe.totalPhysicalMB();
        String total = totalMB > 0 ? String.format("%.1f GB", totalMB / 1024.0) : "未知";
        int alloc = (int) Math.round(maxMemSlider.getValue());
        memInfoLabel.setText("系统总内存 " + total + " · 已分配 " + String.format("%.1f", alloc / 1024.0)
                + " GB · 推荐 4–8 GB");
    }

    /** 外部（自动内存检测完成、保存设置后）刷新内存控件到当前配置。 */
    public void refreshMemoryControls() {
        boolean wasLoading = loading;
        loading = true;
        try {
            memAutoSwitch.setOn("auto".equals(app.config.memoryMode));
            minMem.getValueFactory().setValue(app.config.minMemory);
            maxMemSlider.setValue(app.config.maxMemory);
            applyMemModeUI();
        } finally {
            loading = wasLoading;
        }
    }

    // ------------------------------------------------------------------ 根布局

    public VBox root() {
        VBox box = new VBox(0);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:" + Ui.BG + ";");

        loading = true;

        // ---- 顶部：标签行 + 右侧操作按钮 ----
        HBox topRow = new HBox(16);
        topRow.setAlignment(Pos.CENTER_LEFT);

        String[] titles = {"个性化", "启动环境", "其他"};
        for (int i = 0; i < titles.length; i++) {
            final int idx = i;
            Label tab = new Label(titles[i]);
            tabLabels[i] = tab;
            tab.setOnMouseClicked(e -> switchTab(idx));
            topRow.getChildren().add(tab);
        }

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button resetBtn = new Button("恢复默认");
        resetBtn.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-border-color:" + Ui.BORDER + "; -fx-background-radius:10;"
                + "-fx-border-radius:10; -fx-padding:8 18; -fx-cursor:hand;");
        resetBtn.setOnAction(e -> resetDefaults());
        HBox actions = new HBox(10, resetBtn);
        actions.setAlignment(Pos.CENTER_RIGHT);
        topRow.getChildren().addAll(spacer, actions);

        Separator line = new Separator();
        line.setStyle("-fx-border-color:" + Ui.BORDER + ";");

        tabPersonal = buildPersonal();
        tabEnv = buildEnv();
        tabOther = buildOther();

        VBox content = new VBox(0, tabPersonal, tabEnv, tabOther);
        content.setPadding(new Insets(18, 0, 0, 0));

        contentScroll = new ScrollPane(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentScroll.setStyle("-fx-background-color:transparent; -fx-background:transparent;"
                + "-fx-border-color:null; -fx-padding:0;");
        VBox.setVgrow(contentScroll, Priority.ALWAYS);

        box.getChildren().addAll(topRow, line, contentScroll);

        fillControls();
        loading = false;
        switchTab(activeTab);
        return box;
    }

    // ------------------------------------------------------------------ 标签① 个性化

    private VBox buildPersonal() {
        VBox v = new VBox(20);
        v.setPadding(new Insets(4, 0, 0, 0));

        // 启动器风格（背景模式）
        v.getChildren().add(section("启动器风格"));
        bgModeBox.getItems().clear();
        bgModeBox.getItems().addAll("黑曜石（默认）", "导入背景图", "透明背景");
        styleCombo(bgModeBox, 210);
        Button pickBg = ghostBtn("导入背景图");
        pickBg.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择启动器背景图片");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
            File f = fc.showOpenDialog(null);
            if (f == null) return;
            try {
                String n = f.getName().toLowerCase();
                String ext = n.endsWith(".png") ? ".png" : (n.endsWith(".jpg") || n.endsWith(".jpeg")) ? ".jpg"
                        : n.endsWith(".gif") ? ".gif" : n.endsWith(".bmp") ? ".bmp" : ".png";
                Path dst = ConfigManager.configPath().getParent().resolve("background" + ext);
                Files.copy(f.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
                pickedBgPath = dst.toString();
                bgPathLabel.setText(dst.getFileName() + "（已复制到配置目录）");
                bgPathLabel.setManaged(true);
                bgPathLabel.setVisible(true);
                bgModeBox.getSelectionModel().select(1);
                dirtyVisual = true;
                scheduleAutoSave();
            } catch (Exception ex) {
                Ui.error("选择背景失败", ex);
            }
        });
        Button resetBg = ghostBtn("恢复默认");
        resetBg.setOnAction(e -> {
            pickedBgPath = "";
            bgPathLabel.setText("");
            bgPathLabel.setManaged(false);
            bgPathLabel.setVisible(false);
            bgModeBox.getSelectionModel().select(0);
            dirtyVisual = true;
            scheduleAutoSave();
        });
        bgPathLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        bgPathLabel.setWrapText(true);
        bgPathLabel.setManaged(false);
        bgPathLabel.setVisible(false);
        HBox rowBg = new HBox(10, bgModeBox, pickBg, resetBg);
        rowBg.setAlignment(Pos.CENTER_LEFT);
        Label hintBg = new Label("支持 JPG/PNG 格式图片上传，选图后自动复制到配置目录（原图移动不受影响），修改后立即生效。");
        hintBg.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        hintBg.setWrapText(true);
        v.getChildren().addAll(rowBg, bgPathLabel, hintBg);

        // 字体设置
        v.getChildren().add(section("字体设置"));
        customColorCheck.setText("");
        customColorCheck.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        textColorPicker.setValue(parseColor(Ui.resolveTextColor(app.config.textColor, Ui.TEXT), Ui.TEXT));
        dimColorPicker.setValue(parseColor(Ui.resolveTextColor(app.config.dimColor, Ui.TEXT_DIM), Ui.TEXT_DIM));
        textColorPicker.getStyleClass().add("jwh-color-chip");
        textColorPicker.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-background-radius:10; -fx-border-color:" + Ui.BORDER + "; -fx-border-radius:10;");
        // 次要文字色后端保留，稿面仅展示主文字色长条，此处隐藏控件。
        dimColorPicker.setManaged(false);
        dimColorPicker.setVisible(false);
        textHexField.setText(hex(textColorPicker.getValue()));
        textHexField.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-background-radius:10; -fx-border-color:" + Ui.BORDER + "; -fx-border-radius:10; -fx-padding:7 10;");
        textColorPicker.valueProperty().addListener((o, a, b) -> textHexField.setText(hex(b)));
        textHexField.textProperty().addListener((o, a, b) -> {
            try { textColorPicker.setValue(Color.web(b.trim())); } catch (Exception ignored) {}
        });
        Button resetColor = ghostBtn("恢复默认");
        resetColor.setOnAction(e -> {
            customColorCheck.setSelected(false);
            textColorPicker.setValue(parseColor("#e8eaed", Ui.TEXT));
            dimColorPicker.setValue(parseColor("#b9bfc7", Ui.TEXT_DIM));
        });
        HBox rowColor = new HBox(10, customColorCheck, textColorPicker, textHexField, resetColor);
        rowColor.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textHexField, Priority.ALWAYS);
        v.getChildren().add(rowColor);

        // 背景音乐
        v.getChildren().add(section("背景音乐设置"));
        musicBox.getItems().clear();
        musicBox.getItems().add("随机播放（默认）");
        var mb = app.musicBar();
        if (mb != null) {
            for (String name : mb.trackDisplayNames()) musicBox.getItems().add(name);
        }
        styleCombo(musicBox, 230);
        Button openMusic = ghostBtn("打开文件夹");
        openMusic.setOnAction(e -> openMusicFolder());
        Button resetMusic = ghostBtn("恢复默认");
        resetMusic.setOnAction(e -> musicBox.getSelectionModel().select(0));
        HBox rowMusic = new HBox(10, musicBox, openMusic, resetMusic);
        rowMusic.setAlignment(Pos.CENTER_LEFT);
        v.getChildren().add(rowMusic);

        return v;
    }

    // ------------------------------------------------------------------ 标签② 启动环境

    private VBox buildEnv() {
        VBox v = new VBox(20);
        v.setPadding(new Insets(4, 0, 0, 0));

        // 游戏目录
        v.getChildren().add(section("游戏目录（.minecraft）"));
        mcDir.setPrefWidth(460);
        HBox.setHgrow(mcDir, Priority.ALWAYS);
        Button browse = ghostBtn("浏览…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择 .minecraft 目录");
            File f = dc.showDialog(null);
            if (f != null) mcDir.setText(f.getAbsolutePath());
        });
        Button open = ghostBtn("打开目录");
        open.setOnAction(e -> openDir(mcDir.getText()));
        HBox rowDir = new HBox(10, mcDir, browse, open);
        rowDir.setAlignment(Pos.CENTER_LEFT);
        v.getChildren().add(rowDir);

        // 内存分配
        v.getChildren().add(section("内存分配"));
        maxMemSlider.setBlockIncrement(256);
        maxMemSlider.setMajorTickUnit(2048);
        maxMemSlider.setShowTickMarks(false);
        maxMemSlider.setPrefWidth(320);
        maxMemSlider.getStyleClass().add("jwh-mem-slider");
        // 轨道 / 填充 / 圆点均用 Rectangle 形状，由 Pane 手动布局，避免 Region 在布局容器里被错误拉伸收缩
        javafx.scene.shape.Rectangle memTrack = new javafx.scene.shape.Rectangle();
        memTrack.setHeight(4);
        memTrack.setFill(Color.web("#5a5261"));
        memTrack.setArcWidth(2);
        memTrack.setArcHeight(2);
        javafx.scene.shape.Rectangle memFill = new javafx.scene.shape.Rectangle();
        memFill.setHeight(4);
        memFill.setFill(Color.web("#e293cd"));
        memFill.setArcWidth(2);
        memFill.setArcHeight(2);
        javafx.scene.shape.Rectangle memThumb = new javafx.scene.shape.Rectangle();
        memThumb.setWidth(16);
        memThumb.setHeight(16);
        memThumb.setFill(Color.WHITE);
        memThumb.setArcWidth(16);
        memThumb.setArcHeight(16);
        javafx.scene.layout.Pane sliderStack = new javafx.scene.layout.Pane();
        sliderStack.setPrefWidth(320);
        sliderStack.setPrefHeight(22);
        sliderStack.getChildren().addAll(memTrack, memFill, memThumb);
        Runnable layoutFill = () -> {
            double span = maxMemSlider.getMax() - maxMemSlider.getMin();
            double frac = span > 0 ? (maxMemSlider.getValue() - maxMemSlider.getMin()) / span : 0;
            double trackW = sliderStack.getWidth();
            memTrack.setWidth(trackW);
            memFill.setWidth(Math.max(6, trackW * frac - 8));
            memThumb.setTranslateX(trackW * frac - 16.0 / 2.0);
            double cy = (sliderStack.getHeight() - 4) / 2.0;
            memTrack.setLayoutY(cy);
            memFill.setLayoutY(cy);
            memThumb.setLayoutY((sliderStack.getHeight() - 16) / 2.0);
        };
        maxMemSlider.valueProperty().addListener((o, a, b) -> layoutFill.run());
        sliderStack.widthProperty().addListener((o, a, b) -> layoutFill.run());
        maxMemField.setPrefWidth(90);
        maxMemField.setEditable(false);
        maxMemField.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-background-radius:8; -fx-border-color:" + Ui.BORDER + "; -fx-border-radius:8; -fx-padding:7 10;");
        Label mbUnit = new Label("MB");
        mbUnit.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + ";");
        HBox rowMemTop = new HBox(12, sliderStack, maxMemField, mbUnit);
        rowMemTop.setAlignment(Pos.CENTER_LEFT);

        Label tickMin = new Label("512 MB");
        tickMin.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
        Label tickMax = new Label("16384 MB");
        tickMax.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
        HBox tickSpacer = new HBox();
        HBox.setHgrow(tickSpacer, Priority.ALWAYS);
        HBox ticks = new HBox(0, tickMin, tickSpacer, tickMax);
        ticks.setPrefWidth(320);

        memInfoLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        Label autoLabel = new Label("自动分配内存");
        autoLabel.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:13px;");
        HBox rowMemBottom = new HBox(12, memInfoLabel);
        HBox.setHgrow(memInfoLabel, Priority.ALWAYS);
        rowMemBottom.getChildren().addAll(autoLabel, memAutoSwitch);
        rowMemBottom.setAlignment(Pos.CENTER_LEFT);

        memHintLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        memHintLabel.setWrapText(true);

        minMem.setPrefWidth(120);
        HBox rowMin = new HBox(10, new Label("最小内存："), minMem, memHintLabel);
        rowMin.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(memHintLabel, Priority.ALWAYS);

        memoryRow = new VBox(10, rowMemTop, ticks, rowMemBottom, rowMin);
        v.getChildren().add(memoryRow);

        // 版本隔离
        v.getChildren().add(section("版本隔离"));
        isolation.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        v.getChildren().add(isolation);

        // 默认游戏窗口大小
        v.getChildren().add(section("默认游戏窗口大小"));
        windowModeBox.getItems().clear();
        windowModeBox.getItems().addAll("窗口化（默认）", "全屏");
        styleCombo(windowModeBox, 230);
        v.getChildren().add(windowModeBox);

        // Java 运行环境
        v.getChildren().add(section("Java 运行环境"));
        javaBox.setPrefWidth(320);
        javaBox.setCellFactory(c -> new ListCell<>() {
            @Override
            protected void updateItem(JavaFinder.JavaRuntime j, boolean empty) {
                super.updateItem(j, empty);
                setText(empty || j == null ? "" : j.display());
                setStyle("-fx-text-fill:" + Ui.TEXT + ";");
            }
        });
        javaBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(JavaFinder.JavaRuntime j, boolean empty) {
                super.updateItem(j, empty);
                setText(empty || j == null ? "" : j.display());
                setStyle("-fx-text-fill:" + Ui.TEXT + ";");
            }
        });
        javaBox.getItems().clear();
        javaBox.getItems().add(JavaFinder.JavaRuntime.AUTO);
        if (app.javaRuntimes != null) javaBox.getItems().addAll(app.javaRuntimes);
        Button rescanJava = ghostBtn("自动搜索…");
        rescanJava.setOnAction(e -> rescanJava());
        Button pickJava = ghostBtn("手动导入");
        pickJava.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择 java.exe（或 java）");
            if (com.jwh.launcher.util.OSUtil.isWindows()) {
                fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java", "java.exe"));
            }
            File f = fc.showOpenDialog(null);
            if (f != null && f.exists()) {
                JavaFinder.JavaRuntime rt = new JavaFinder.JavaRuntime(f.toPath(), -1, "自定义");
                javaBox.getItems().add(0, rt);
                javaBox.getSelectionModel().select(0);
            }
        });
        HBox rowJava = new HBox(10, javaBox, rescanJava, pickJava);
        rowJava.setAlignment(Pos.CENTER_LEFT);
        v.getChildren().add(rowJava);

        // JVM 参数
        v.getChildren().add(section("JVM 参数"));
        jvmArgsArea.setPrefRowCount(3);
        jvmArgsArea.setWrapText(false);
        jvmArgsArea.setStyle("-fx-control-inner-background:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT
                + "; -fx-background-radius:10; -fx-border-color:" + Ui.BORDER + "; -fx-border-radius:10;");
        jvmHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;"
                + "-fx-padding:6 8; -fx-wrap-text:true;");
        jvmHint.setMouseTransparent(true);
        StackPane jvmPane = new StackPane(jvmArgsArea, jvmHint);
        StackPane.setAlignment(jvmHint, Pos.TOP_LEFT);
        v.getChildren().add(jvmPane);

        // 下载（保留并入）
        v.getChildren().add(section("下载"));
        threads.setPrefWidth(100);
        sourceBox.getItems().clear();
        sourceBox.getItems().addAll("自动选择（推荐，启动时测速取最快）", "BMCLAPI 国内镜像", "官方源（Mojang）");
        styleCombo(sourceBox, 230);
        HBox rowSource = new HBox(10, sourceBox, new Label("并发线程数："), threads);
        rowSource.setAlignment(Pos.CENTER_LEFT);
        v.getChildren().add(rowSource);

        return v;
    }

    // ------------------------------------------------------------------ 标签③ 其他

    private VBox buildOther() {
        VBox v = new VBox(20);
        v.setPadding(new Insets(4, 0, 0, 0));

        // 语言
        v.getChildren().add(section("语言"));
        languageBox.getItems().clear();
        languageBox.getItems().add("简体中文（默认）");
        styleCombo(languageBox, 230);
        v.getChildren().add(languageBox);

        // 更新通道
        v.getChildren().add(section("更新通道"));
        channelBox.getItems().clear();
        channelBox.getItems().addAll("正式版（默认）", "测试版");
        styleCombo(channelBox, 230);
        Label hintChannel = new Label("测试版更新更频繁，可能存在不稳定情况。");
        hintChannel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        v.getChildren().addAll(channelBox, hintChannel);

        // 日志与缓存
        v.getChildren().add(section("日志与缓存"));
        Path logDir = ConfigManager.configPath().getParent();
        logPathField.setText(logDir.toString());
        logPathField.setEditable(false);
        logPathField.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-background-radius:8; -fx-border-color:" + Ui.BORDER + "; -fx-border-radius:8; -fx-padding:7 10;");
        Button openLog = ghostBtn("打开日志");
        openLog.setOnAction(e -> openDir(logDir.toString()));
        Button clearCache = ghostBtn("清理缓存");
        clearCache.setOnAction(e -> clearCache());
        HBox rowLog = new HBox(10, logPathField, openLog, clearCache);
        rowLog.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(logPathField, Priority.ALWAYS);
        v.getChildren().add(rowLog);

        // 关于
        v.getChildren().add(section("关于"));
        Label about = new Label("JWH-Launcher v" + LauncherApp.APP_VERSION + " · Minecraft Java 版启动器");
        about.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");
        v.getChildren().add(about);

        return v;
    }

    // ------------------------------------------------------------------ 回填

    private void fillControls() {
        mcDir.setText(app.config.minecraftDir);
        isolation.setSelected(app.config.versionIsolation);
        refreshMemoryControls();

        String cfgJava = app.config.javaPath == null ? "" : app.config.javaPath.trim();
        boolean javaPicked = false;
        if (!cfgJava.isBlank()) {
            for (int i = 0; i < javaBox.getItems().size(); i++) {
                JavaFinder.JavaRuntime j = javaBox.getItems().get(i);
                if (j.javaExe() != null && j.javaExe().toString().equalsIgnoreCase(cfgJava)) {
                    javaBox.getSelectionModel().select(i);
                    javaPicked = true;
                    break;
                }
            }
        }
        if (!javaPicked) javaBox.getSelectionModel().selectFirst();
        threads.getValueFactory().setValue(app.config.downloadThreads);
        sourceBox.getSelectionModel().select("auto".equals(app.config.contentSource) ? 0 : app.config.useBmclapi() ? 1 : 2);
        jvmArgsArea.setText(app.config.jvmArgs);
        jvmHint.setVisible(app.config.jvmArgs == null || app.config.jvmArgs.trim().isEmpty());
        microsoftClientId.setText(app.config.microsoftClientId);
        microsoftRedirectUri.setText(app.config.microsoftRedirectUri);
        roomServer.setText(app.config.roomServer == null ? "" : app.config.roomServer);
        roomToken.setText(app.config.roomToken == null ? "" : app.config.roomToken);

        pickedBgPath = app.config.bgImagePath == null ? "" : app.config.bgImagePath;
        String mode = app.config.bgMode == null ? "default" : app.config.bgMode;
        bgModeBox.getSelectionModel().select("image".equals(mode) ? 1 : "transparent".equals(mode) ? 2 : 0);
        boolean hasBg = !pickedBgPath.isBlank();
        bgPathLabel.setText(hasBg ? Path.of(pickedBgPath).getFileName() + "（已复制到配置目录）" : "");
        bgPathLabel.setManaged(hasBg);
        bgPathLabel.setVisible(hasBg);

        // 字体：是否启用自定义主文字色
        boolean custom = app.config.textColor != null && !app.config.textColor.isBlank();
        customColorCheck.setSelected(custom);
        textColorPicker.setDisable(!custom);
        dimColorPicker.setDisable(!custom);
        textHexField.setDisable(!custom);
        textColorPicker.setValue(parseColor(Ui.resolveTextColor(app.config.textColor, Ui.TEXT), Ui.TEXT));
        dimColorPicker.setValue(parseColor(Ui.resolveTextColor(app.config.dimColor, Ui.TEXT_DIM), Ui.TEXT_DIM));

        // 音乐曲目
        String savedTrack = app.config.musicTrack == null ? "" : app.config.musicTrack.trim();
        int musicIdx = 0;
        if (!savedTrack.isBlank()) {
            for (int i = 1; i < musicBox.getItems().size(); i++) {
                if (musicBox.getItems().get(i).equalsIgnoreCase(savedTrack)) { musicIdx = i; break; }
            }
        }
        musicBox.getSelectionModel().select(musicIdx);

        windowModeBox.getSelectionModel().select("fullscreen".equals(app.config.windowMode) ? 1 : 0);
        languageBox.getSelectionModel().select("zh_CN".equals(app.config.language) ? 0 : 0);
        channelBox.getSelectionModel().select("beta".equals(app.config.updateChannel) ? 1 : 0);
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 自动保存：写配置并回读校验，然后只跟随本次改动真正需要的副作用。
     * 防抖自动保存：停手后由防抖器触发，恢复默认落盘也走这里。
     */
    private void applyAutoSave() {
        if (loading) return;
        String dir = mcDir.getText() == null ? "" : mcDir.getText().trim();
        if (!dir.isEmpty()) app.config.minecraftDir = dir;
        app.config.versionIsolation = isolation.isSelected();
        app.config.minMemory = minMem.getValue();
        app.config.maxMemory = (int) Math.round(maxMemSlider.getValue());
        app.config.memoryMode = memAutoSwitch.isOn() ? "auto" : "custom";

        JavaFinder.JavaRuntime jsel = javaBox.getSelectionModel().getSelectedItem();
        app.config.javaPath = (jsel == null || jsel.isAuto() || jsel.javaExe() == null)
                ? "" : jsel.javaExe().toString();
        app.config.downloadThreads = threads.getValue();
        int srcIdx = sourceBox.getSelectionModel().getSelectedIndex();
        app.config.contentSource = srcIdx == 1 ? "bmclapi" : srcIdx == 2 ? "official" : "auto";
        app.config.jvmArgs = jvmArgsArea.getText().trim();
        app.config.microsoftClientId = microsoftClientId.getText().trim();
        app.config.microsoftRedirectUri = microsoftRedirectUri.getText().trim();
        String rs = roomServer.getText() == null ? "" : roomServer.getText().trim();
        String rt = roomToken.getText() == null ? "" : roomToken.getText().trim();
        app.config.roomServer = rs.isEmpty() ? app.config.effectiveRoomServer() : rs;
        app.config.roomToken = rt.isEmpty() ? app.config.effectiveRoomToken() : rt;
        com.jwh.launcher.net.RoomCodeService.configure(
                app.config.effectiveRoomServer(), app.config.effectiveRoomToken());

        // 外观
        int bgIdx = Math.max(0, bgModeBox.getSelectionModel().getSelectedIndex());
        String newBgMode = bgIdx == 1 ? "image" : bgIdx == 2 ? "transparent" : "default";
        String newBgPath = pickedBgPath == null ? "" : pickedBgPath;
        boolean customColor = customColorCheck.isSelected();
        String newText = customColor ? hex(textColorPicker.getValue()) : "";
        String newDim = customColor ? hex(dimColorPicker.getValue()) : "";
        boolean visualChanged = dirtyVisual || !newBgMode.equals(app.config.bgMode)
                || !newBgPath.equals(app.config.bgImagePath == null ? "" : app.config.bgImagePath)
                || !newText.equalsIgnoreCase(app.config.textColor == null ? "" : app.config.textColor)
                || !newDim.equalsIgnoreCase(app.config.dimColor == null ? "" : app.config.dimColor);
        boolean transparentChanged = ("transparent".equals(newBgMode)) != ("transparent".equals(app.config.bgMode));
        app.config.bgMode = newBgMode;
        app.config.bgImagePath = newBgPath;
        app.config.textColor = newText;
        app.config.dimColor = newDim;

        // 音乐选曲
        int musicIdx = musicBox.getSelectionModel().getSelectedIndex();
        app.config.musicTrack = musicIdx <= 0 ? "" : musicBox.getItems().get(musicIdx);

        app.config.windowMode = windowModeBox.getSelectionModel().getSelectedIndex() == 1 ? "fullscreen" : "windowed";
        app.config.language = "zh_CN";
        app.config.updateChannel = channelBox.getSelectionModel().getSelectedIndex() == 1 ? "beta" : "stable";

        app.selectedJavaPath = app.config.javaPath;
        try {
            ConfigManager.save(app.config);
            var reloaded = ConfigManager.load();
            if (!app.config.minecraftDir.equals(reloaded.minecraftDir)) {
                throw new java.io.IOException("写入校验失败，配置未生效");
            }
        } catch (Exception ex) {
            Ui.error("保存失败", "配置写入失败：" + ex.getMessage()
                    + "\n请检查 %APPDATA%\\JWH-Launcher 目录是否可写。");
            return;
        }

        boolean needRefreshList = dirtyDir;
        boolean needSelectSource = dirtySource;
        dirtyVisual = false;
        dirtyDir = false;
        dirtySource = false;

        com.jwh.launcher.auth.MicrosoftAuthenticator.init(app.config);
        if ("auto".equals(app.config.memoryMode)) app.applyAutoMemory();
        if (visualChanged) {
            app.applyThemeAndRebuild();
            app.showSettings();
        } else if (needRefreshList) {
            app.refreshAll();
        }
        if (needSelectSource && "auto".equals(app.config.contentSource)) app.autoSelectSource();
        if (transparentChanged) {
            Ui.info("透明模式", "透明模式需要在重启启动器后生效（窗口将变为无边框透明）。\n"
                    + "请关闭启动器后重新打开。");
        }
    }

    /** 恢复默认：重置各项到默认值（保留账号与当前游戏目录），回填并落盘。 */
    private void resetDefaults() {
        boolean go = Ui.confirm("恢复默认", "将把外观、内存、Java、下载、JVM 参数、音乐、窗口与更新通道等设置恢复为默认值。\n"
                + "账号列表与当前游戏目录保留。是否继续？");
        if (!go) return;
        var def = new com.jwh.launcher.config.LauncherConfig();
        var cfg = app.config;
        loading = true;
        try {
            cfg.minMemory = def.minMemory;
            cfg.maxMemory = def.maxMemory;
            cfg.memoryMode = def.memoryMode;
            cfg.versionIsolation = def.versionIsolation;
            cfg.downloadThreads = def.downloadThreads;
            cfg.contentSource = def.contentSource;
            cfg.javaPath = "";
            cfg.jvmArgs = "";
            cfg.bgMode = def.bgMode;
            cfg.bgImagePath = "";
            cfg.textColor = "";
            cfg.dimColor = "";
            cfg.musicTrack = "";
            cfg.windowMode = def.windowMode;
            cfg.language = def.language;
            cfg.updateChannel = def.updateChannel;
            pickedBgPath = "";
            ConfigManager.save(cfg);
            app.selectedJavaPath = "";
            fillControls();
        } catch (Exception ex) {
            Ui.error("恢复默认失败", ex);
        } finally {
            loading = false;
        }
        dirtyVisual = true;
        scheduleAutoSave();
    }

    // ------------------------------------------------------------------ 动作辅助

    /** 后台重新扫描已安装 Java 并刷新下拉。 */
    private void rescanJava() {
        Ui.async2(JavaFinder::find, found -> {
            app.javaRuntimes = found;
            loading = true;
            try {
                javaBox.getItems().clear();
                javaBox.getItems().add(JavaFinder.JavaRuntime.AUTO);
                javaBox.getItems().addAll(found);
                String cfgJava = app.config.javaPath == null ? "" : app.config.javaPath.trim();
                boolean picked = false;
                if (!cfgJava.isBlank()) {
                    for (int i = 0; i < javaBox.getItems().size(); i++) {
                        JavaFinder.JavaRuntime j = javaBox.getItems().get(i);
                        if (j.javaExe() != null && j.javaExe().toString().equalsIgnoreCase(cfgJava)) {
                            javaBox.getSelectionModel().select(i);
                            picked = true;
                            break;
                        }
                    }
                }
                if (!picked) javaBox.getSelectionModel().selectFirst();
            } finally {
                loading = false;
            }
        }, e -> Ui.error("搜索 Java 失败", e));
    }

    private void openDir(String path) {
        try {
            Path p = Path.of(path == null ? "" : path.trim());
            if (!Files.exists(p)) Files.createDirectories(p);
            java.awt.Desktop.getDesktop().open(p.toFile());
        } catch (Exception ex) {
            Ui.error("无法打开", ex);
        }
    }

    /** 打开内置音乐落地的临时目录（外置资源本身在工程目录，此处打开本地缓存副本）。 */
    private void openMusicFolder() {
        try {
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "jwh-launcher-music");
            Files.createDirectories(dir);
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            Ui.error("无法打开音乐目录", ex);
        }
    }

    /** 清理缓存：音乐落地临时目录 + 更新临时残留（不删配置与已落地资源层）。 */
    private void clearCache() {
        long freed = 0;
        try {
            Path musicTmp = Paths.get(System.getProperty("java.io.tmpdir"), "jwh-launcher-music");
            freed += deleteDirContent(musicTmp);
            Path updateTmp = LauncherApp.appDir().resolve(".jwh-update");
            freed += deleteDirContent(updateTmp);
            Ui.info("清理缓存", "已清理缓存约 " + String.format("%.1f", freed / 1048576.0) + " MB。");
        } catch (Exception ex) {
            Ui.error("清理缓存失败", ex);
        }
    }

    private static long deleteDirContent(Path dir) throws java.io.IOException {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        long size = 0;
        try (var walk = Files.walk(dir)) {
            var files = walk.filter(Files::isRegularFile).toList();
            for (Path f : files) {
                try { size += Files.size(f); Files.deleteIfExists(f); } catch (Exception ignored) {}
            }
        }
        return size;
    }

    // ------------------------------------------------------------------ 样式辅助

    private Label section(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        return l;
    }

    private Button ghostBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-background-radius:10; -fx-border-color:" + Ui.BORDER + "; -fx-border-radius:10;"
                + "-fx-padding:8 14; -fx-cursor:hand;");
        return b;
    }

    private void styleCombo(ComboBox<?> box, double width) {
        box.setPrefWidth(width);
        box.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-background-radius:10; -fx-border-color:" + Ui.BORDER + "; -fx-border-radius:10;");
    }

    private static Color parseColor(String hex, String fallback) {
        try {
            return Color.web(hex == null || hex.isBlank() ? fallback : hex);
        } catch (Exception e) {
            return Color.web(fallback);
        }
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x",
                Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }
}
