package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.game.JavaFinder;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Separator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 设置页：游戏目录、版本隔离、内存、Java、下载线程、JVM 附加参数。
 */
public class SettingsView {

    private final LauncherApp app;
    private final TextField mcDir = new TextField();
    private final CheckBox isolation = new CheckBox("开启版本隔离（每个版本独立的 saves / mods / config，类似 PCL 的版本隔离）");
    private final Spinner<Integer> minMem = new Spinner<>(512, 8192, 1024, 256);
    private final Slider maxMemSlider = new Slider(1024, 16384, 4096);
    private final Label maxMemLabel = new Label();
    /** 内存分配方式：自动分配（每次打开按本机内存推算） / 自定义（手动设置，不检测）。 */
    private final ComboBox<String> memModeBox = new ComboBox<>();
    private final Label memHintLabel = new Label();
    private final ComboBox<JavaFinder.JavaRuntime> javaBox = new ComboBox<>();
    private final Spinner<Integer> threads = new Spinner<>(1, 32, 8, 1);
    private final TextField jvmArgs = new TextField();
    private final TextField microsoftClientId = new TextField();
    private final TextField microsoftRedirectUri = new TextField();
    private final TextField roomServer = new TextField();
    private final TextField roomToken = new TextField();
    private final ComboBox<String> sourceBox = new ComboBox<>();

    /** 内存分配区块（供外部定位滚动到该设置项）。 */
    private VBox memoryRow;

    /** root() 回填配置期间为 true：此时控件变化不触发自动保存，避免回填误写。 */
    private boolean loading = true;
    /** 自动保存防抖器：连续调整（拖滑块、打字、拖色）合并为一次保存。 */
    private final PauseTransition autoSaveTimer = new PauseTransition(Duration.millis(450));
    /** 待跟随的副作用标记：外观变化需重建界面、目录变化需刷新列表、下载源变化需测速。 */
    private boolean dirtyVisual, dirtyDir, dirtySource;

    // ---- 外观 ----
    private final ComboBox<String> bgModeBox = new ComboBox<>();
    private final Label bgPathLabel = new Label();
    private final ColorPicker textColorPicker = new ColorPicker();
    private final ColorPicker dimColorPicker = new ColorPicker();
    private String pickedBgPath = "";

    public SettingsView(LauncherApp app) {
        this.app = app;
        // 内存控件监听只在这里注册一次（root() 会被多次调用，若放那里会重复累积触发）
        maxMemSlider.valueProperty().addListener((obs, o, n) ->
                maxMemLabel.setText(String.format("最大内存：%d MB（当前系统可用约 %.1f GB）",
                        n.intValue(), Runtime.getRuntime().maxMemory() / 1073741824.0)));
        memModeBox.valueProperty().addListener((obs, o, n) -> {
            boolean auto = memModeBox.getSelectionModel().getSelectedIndex() == 0;
            if (auto) {
                // 切到自动分配：立即按本机可用内存检测并应用推荐值（无需先点保存）
                if (!"auto".equals(app.config.memoryMode)) {
                    app.config.memoryMode = "auto";
                    app.applyAutoMemory();
                } else {
                    applyMemModeUI();
                }
            } else {
                // 切到自定义：滑块/最小内存恢复可调，启动器不再自动检测
                app.config.memoryMode = "custom";
                applyMemModeUI();
            }
            scheduleAutoSave();
        });
        hookAutoSave();
    }

    /**
     * 为设置页全部控件挂变化监听：任何一项被玩家调整后自动保存并生效（无需「保存设置」按钮）。
     * 只在这里注册一次——root() 每次进设置页都会被重建调用，若放 root() 里会重复累积。
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
        jvmArgs.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        microsoftClientId.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        microsoftRedirectUri.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        roomServer.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        roomToken.textProperty().addListener((o, a, b) -> scheduleAutoSave());
        bgModeBox.valueProperty().addListener((o, a, b) -> { if (!loading) { dirtyVisual = true; scheduleAutoSave(); } });
        textColorPicker.valueProperty().addListener((o, a, b) -> { if (!loading) { dirtyVisual = true; scheduleAutoSave(); } });
        dimColorPicker.valueProperty().addListener((o, a, b) -> { if (!loading) { dirtyVisual = true; scheduleAutoSave(); } });
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

    /** 根据当前选择的分配方式启/禁内存控件并更新提示。 */
    private void applyMemModeUI() {
        boolean auto = memModeBox.getSelectionModel().getSelectedIndex() == 0;
        // 自动分配：滑块/最小内存只读（值由启动器按本机内存推算）；自定义：手动可调
        maxMemSlider.setDisable(auto);
        minMem.setDisable(auto);
        if (auto) {
            memHintLabel.setText("每次打开启动器自动按本机可用内存推算安全值（物理内存一半，上限 16GB，下限 2GB）。");
        } else {
            memHintLabel.setText("手动设置下方数值，启动器不再自动检测（下载新版本也不会覆盖）。");
        }
    }

    /** 外部（自动内存检测完成、保存设置后）刷新内存控件到当前配置。 */
    public void refreshMemoryControls() {
        // 外部刷新控件属于程序行为，临时屏蔽自动保存，防止「推算 → 保存 → 再推算」回环
        boolean wasLoading = loading;
        loading = true;
        try {
            memModeBox.getSelectionModel().select("auto".equals(app.config.memoryMode) ? 0 : 1);
            minMem.getValueFactory().setValue(app.config.minMemory);
            maxMemSlider.setValue(app.config.maxMemory);
            applyMemModeUI();
        } finally {
            loading = wasLoading;
        }
    }

    public VBox root() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:" + Ui.BG + ";");

        loading = true;   // 回填期间屏蔽自动保存，避免把程序回填当成玩家调整

        Label title = new Label("设置");
        title.setStyle("-fx-font-size:19px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");

        // 自动保存：不再设「保存设置」按钮，任意选项调整后即时写入配置并生效
        Label autoHint = new Label("所有修改自动保存并立即生效");
        autoHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        HBox titleRow = new HBox(12);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.getChildren().addAll(title, autoHint);

        // ---- 外观（自定义背景 / 文字颜色）----
        Label la = section("外观（自定义背景 / 文字颜色）");
        bgModeBox.getItems().clear();   // root() 会被多次调用（每次进设置页），先清空避免选项累积重复
        bgModeBox.getItems().addAll("默认背景（内置动态夜景）", "图片背景（自选图片）", "透明背景");
        bgModeBox.setPrefWidth(210);
        bgModeBox.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";");
        Button pickBg = smallBtn("选择背景图片…");
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
                Path dst = com.jwh.launcher.config.ConfigManager.configPath().getParent()
                        .resolve("background" + ext);
                Files.copy(f.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
                pickedBgPath = dst.toString();
                bgPathLabel.setText(dst.getFileName() + "（已复制到配置目录）");
                bgModeBox.getSelectionModel().select(1);
                dirtyVisual = true;
                scheduleAutoSave();
            } catch (Exception ex) {
                Ui.error("选择背景失败", ex);
            }
        });
        Button resetBg = smallBtn("恢复默认背景");
        resetBg.setOnAction(e -> {
            pickedBgPath = "";
            bgPathLabel.setText("未设置");
            bgModeBox.getSelectionModel().select(0);
            dirtyVisual = true;
            scheduleAutoSave();
        });
        bgPathLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        bgPathLabel.setWrapText(true);
        HBox rowA1 = new HBox(8);
        rowA1.setAlignment(Pos.CENTER_LEFT);
        rowA1.getChildren().addAll(new Label("背景模式："), bgModeBox, pickBg, resetBg);
        HBox rowA2 = new HBox(8);
        rowA2.setAlignment(Pos.CENTER_LEFT);
        rowA2.getChildren().addAll(bgPathLabel);

        textColorPicker.setValue(parseColor(Ui.resolveTextColor(app.config.textColor, Ui.TEXT), Ui.TEXT));
        dimColorPicker.setValue(parseColor(Ui.resolveTextColor(app.config.dimColor, Ui.TEXT_DIM), Ui.TEXT_DIM));
        Button resetColor = smallBtn("恢复默认文字颜色");
        resetColor.setOnAction(e -> {
            textColorPicker.setValue(parseColor("#e8eaed", Ui.TEXT));
            dimColorPicker.setValue(parseColor("#b9bfc7", Ui.TEXT_DIM));
        });
        HBox rowA3 = new HBox(8);
        rowA3.setAlignment(Pos.CENTER_LEFT);
        rowA3.getChildren().addAll(new Label("主文字颜色："), textColorPicker,
                new Label("   次要文字颜色："), dimColorPicker, resetColor);
        Label hintA = new Label("提示：支持 PNG / JPG 背景图，选图后自动复制到配置目录（原图移动不影响）。"
                + " 修改后自动保存并立即生效。");
        hintA.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        hintA.setWrapText(true);

        // ---- 游戏目录 ----
        Label l1 = section("游戏目录（.minecraft）");
        mcDir.setPrefWidth(420);
        HBox.setHgrow(mcDir, Priority.ALWAYS);
        Button browse = smallBtn("浏览…");
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择 .minecraft 目录");
            File f = dc.showDialog(null);
            if (f != null) mcDir.setText(f.getAbsolutePath());
        });
        Button open = smallBtn("打开目录");
        open.setOnAction(e -> {
            try {
                Path p = Path.of(mcDir.getText());
                if (!Files.exists(p)) Files.createDirectories(p);
                java.awt.Desktop.getDesktop().open(p.toFile());
            } catch (Exception ex) {
                Ui.error("无法打开", ex);
            }
        });
        HBox row1 = new HBox(8);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.getChildren().addAll(mcDir, browse, open);

        // ---- 版本隔离 ----
        Label l2 = section("版本隔离");
        isolation.setStyle("-fx-text-fill:" + Ui.TEXT + ";");

        // ---- 内存 ----
        Label l3 = section("内存分配（MB）");
        minMem.setPrefWidth(120);
        maxMemSlider.setBlockIncrement(256);
        maxMemSlider.setMajorTickUnit(2048);
        maxMemSlider.setShowTickMarks(false);
        maxMemSlider.setPrefWidth(300);
        maxMemLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");
        VBox memBox = new VBox(6);
        memBox.getChildren().addAll(maxMemSlider, maxMemLabel);
        HBox row3 = new HBox(12);
        row3.setAlignment(Pos.CENTER_LEFT);
        row3.getChildren().addAll(new Label("最小："), minMem, memBox);
        // 分配方式：自动分配（每次打开按本机可用内存推算安全值） / 自定义（手动设置，不检测）
        memModeBox.getItems().clear();   // 同上：root() 重复调用时防止选项累积
        memModeBox.getItems().addAll("自动分配（推荐）", "自定义");
        memModeBox.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";");
        memHintLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        memHintLabel.setWrapText(true);
        HBox memModeRow = new HBox(12);
        memModeRow.setAlignment(Pos.CENTER_LEFT);
        memModeRow.getChildren().addAll(new Label("分配方式："), memModeBox, memHintLabel);
        HBox.setHgrow(memHintLabel, Priority.ALWAYS);
        memoryRow = new VBox(8);
        memoryRow.getChildren().addAll(memModeRow, row3);

        // ---- Java ----
        Label l4 = section("Java 运行时");
        javaBox.setPrefWidth(460);
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
        javaBox.getItems().clear();   // 同上：重建时先清空，再填「自动选择 + 已检测到的运行时」
        javaBox.getItems().add(JavaFinder.JavaRuntime.AUTO);
        if (app.javaRuntimes != null) javaBox.getItems().addAll(app.javaRuntimes);
        javaBox.getSelectionModel().selectFirst(); // 自动选择（推荐）
        Button pickJava = smallBtn("选择其他 Java…");
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
        HBox row4 = new HBox(8);
        row4.setAlignment(Pos.CENTER_LEFT);
        row4.getChildren().addAll(javaBox, pickJava);

        // ---- 下载 ----
        Label l5 = section("下载");
        threads.setPrefWidth(100);
        sourceBox.getItems().clear();   // 同上：防止重复调用 root() 时选项累积
        sourceBox.getItems().addAll("自动选择（推荐，启动时测速取最快）", "BMCLAPI 国内镜像", "官方源（Mojang）");
        sourceBox.setPrefWidth(230);
        HBox row5 = new HBox(8);
        row5.setAlignment(Pos.CENTER_LEFT);
        row5.getChildren().addAll(new Label("下载源："), sourceBox, new Label("   并发线程数："), threads);

        // ---- JVM 附加参数 ----
        Label l6 = section("JVM 附加参数");
        jvmArgs.setPrefWidth(460);
        Label hintJvm = new Label("示例：-XX:+UseG1GC -Djava.net.preferIPv4Stack=true（以空格分隔）");
        hintJvm.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        // ---- 以下两块（Microsoft 客户端 ID / 联机房间号）按要求不在设置界面展示 ----
        // 对应字段（microsoftClientId / microsoftRedirectUri / roomServer / roomToken）仍保留在类字段区，
        // 回填与自动保存逻辑不变，功能继续走内置默认值；此处不再构建可见控件，
        // 避免客户端 ID、服务器网址、口令出现在界面与界面文案串里。

        // ---- 保存 ----
        Label note = new Label("提示：所有修改会自动保存并立即生效；修改 .minecraft 目录后，"
                + "下载中心会自动切换到新目录下的版本列表。");
        note.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        note.setWrapText(true);

        // rowRoom（服务器地址 / 口令一行）随联机房间号板块一并隐藏，不再构建。

        box.getChildren().addAll(titleRow, la, rowA1, rowA2, rowA3, hintA, new Separator(),
                l1, row1, new Separator(), l2, isolation, new Separator(),
                l3, memoryRow, new Separator(), l4, row4, new Separator(),
                l5, row5, new Separator(), l6, jvmArgs, hintJvm, new Separator(),
                // 以下两块按要求不在设置界面展示：Microsoft 客户端 ID、联机房间号（服务器地址/口令）。
                // 字段、回填与自动保存逻辑保留，功能继续走内置默认值，避免客户端 ID 与服务器网址外泄。
                note);

        // 填充当前配置
        mcDir.setText(app.config.minecraftDir);
        isolation.setSelected(app.config.versionIsolation);
        refreshMemoryControls();
        // Java 下拉：配置里指定过则选中对应项，否则「自动选择」
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
        jvmArgs.setText(app.config.jvmArgs);
        microsoftClientId.setText(app.config.microsoftClientId);
        microsoftRedirectUri.setText(app.config.microsoftRedirectUri);
        roomServer.setText(app.config.roomServer == null ? "" : app.config.roomServer);
        roomToken.setText(app.config.roomToken == null ? "" : app.config.roomToken);
        pickedBgPath = app.config.bgImagePath == null ? "" : app.config.bgImagePath;
        String mode = app.config.bgMode == null ? "default" : app.config.bgMode;
        bgModeBox.getSelectionModel().select("image".equals(mode) ? 1 : "transparent".equals(mode) ? 2 : 0);
        bgPathLabel.setText(pickedBgPath.isBlank() ? "未设置"
                : Path.of(pickedBgPath).getFileName() + "（已复制到配置目录）");
        loading = false;   // 回填完成，恢复正常自动保存
        return box;
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

    private Label section(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        return l;
    }

    private Button smallBtn(String text) {
        Button b = new Button(text);
        b.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        return b;
    }

    /**
     * 自动保存（玩家调整任意选项后由防抖器触发，无「保存设置」按钮）：
     * 写配置并回读校验，然后只跟随本次改动真正需要的副作用——
     * 外观变化重建界面并留在设置页；目录变化刷新下载中心版本列表；
     * 下载源切换为 auto 时再测速。不再弹「已保存」对话框打扰玩家。
     */
    private void applyAutoSave() {
        if (loading) return;   // 回填/重建过程中不落盘
        // 游戏目录：输入被清空时保留原值，避免把目录写坏
        String dir = mcDir.getText() == null ? "" : mcDir.getText().trim();
        if (!dir.isEmpty()) app.config.minecraftDir = dir;
        app.config.versionIsolation = isolation.isSelected();
        app.config.minMemory = minMem.getValue();
        app.config.maxMemory = (int) Math.round(maxMemSlider.getValue());
        app.config.memoryMode = memModeBox.getSelectionModel().getSelectedIndex() == 0 ? "auto" : "custom";
        // Java：选中「自动选择」则清空路径（自动匹配），否则写死所选运行时
        JavaFinder.JavaRuntime jsel = javaBox.getSelectionModel().getSelectedItem();
        app.config.javaPath = (jsel == null || jsel.isAuto() || jsel.javaExe() == null)
                ? "" : jsel.javaExe().toString();
        app.config.downloadThreads = threads.getValue();
        int srcIdx = sourceBox.getSelectionModel().getSelectedIndex();
        app.config.contentSource = srcIdx == 1 ? "bmclapi" : srcIdx == 2 ? "official" : "auto";
        app.config.jvmArgs = jvmArgs.getText().trim();
        app.config.microsoftClientId = microsoftClientId.getText().trim();
        app.config.microsoftRedirectUri = microsoftRedirectUri.getText().trim();
        // 联机房间号：改完立即同步给多人页，不用重启启动器
        // 地址清空 = 用内置服务（不写空值，避免配置文件里出现「空 = 关闭」的歧义）；填 off 才是关闭
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
        String newText = hex(textColorPicker.getValue());
        String newDim = hex(dimColorPicker.getValue());
        boolean visualChanged = dirtyVisual || !newBgMode.equals(app.config.bgMode)
                || !newBgPath.equals(app.config.bgImagePath == null ? "" : app.config.bgImagePath)
                || !newText.equalsIgnoreCase(app.config.textColor == null ? "" : app.config.textColor)
                || !newDim.equalsIgnoreCase(app.config.dimColor == null ? "" : app.config.dimColor);
        // 透明模式需要无边框窗口，只能启动时生效，切换后须重启
        boolean transparentChanged = ("transparent".equals(newBgMode)) != ("transparent".equals(app.config.bgMode));
        app.config.bgMode = newBgMode;
        app.config.bgImagePath = newBgPath;
        app.config.textColor = newText;
        app.config.dimColor = newDim;
        app.selectedJavaPath = app.config.javaPath;
        try {
            ConfigManager.save(app.config);
            // 回读校验：确认配置真的写进了磁盘
            var reloaded = ConfigManager.load();
            if (!app.config.minecraftDir.equals(reloaded.minecraftDir)) {
                throw new java.io.IOException("写入校验失败，配置未生效");
            }
        } catch (Exception ex) {
            Ui.error("自动保存失败", "配置写入失败：" + ex.getMessage()
                    + "\n请检查 %APPDATA%\\JWH-Launcher 目录是否可写。");
            return;
        }
        // ---- 跟随本次改动的副作用（自动保存不弹「已保存」提示）----
        boolean needRefreshList = dirtyDir;
        boolean needSelectSource = dirtySource;
        dirtyVisual = false;
        dirtyDir = false;
        dirtySource = false;
        // 正版登录 client_id 立即生效（如用户填了自定义 ID）
        com.jwh.launcher.auth.MicrosoftAuthenticator.init(app.config);
        // 自动分配模式：立即按本机内存重新推算（滑块同步显示；自定义模式不检测）
        if ("auto".equals(app.config.memoryMode)) app.applyAutoMemory();
        if (visualChanged) {
            // 自定义背景 / 文字颜色立即应用：刷新主题色并重建主界面，然后留在设置页
            app.applyThemeAndRebuild();
            app.showSettings();
        } else if (needRefreshList) {
            // 目录变化：下载中心按新目录刷新版本列表
            app.refreshAll();
        }
        if (needSelectSource && "auto".equals(app.config.contentSource)) app.autoSelectSource();
        if (transparentChanged) {
            Ui.info("透明模式", "透明模式需要在重启启动器后生效（窗口将变为无边框透明）。\n"
                    + "请关闭启动器后重新打开。");
        }
    }
}
