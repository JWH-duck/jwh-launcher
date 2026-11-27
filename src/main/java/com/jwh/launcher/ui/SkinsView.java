package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.ui.SkinCanvas;
import com.jwh.launcher.ui.Ui;
import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.json.JSONArray;
import org.json.JSONObject;

public class SkinsView {
    /** 强调粉（选中填充 / 主按钮）。 */
    private static final String PINK = "#D982C2";
    /** 外层窗口 / 未选描边按钮透出的紫褐底。 */
    private static final String OUTER_BG = "#733E6F";
    /** 卡片底色（深紫黑，对齐稿面大卡）。 */
    private static final String CARD_BG = "#1F1529";
    /** 内嵌缩略图卡底。 */
    private static final String CHIP_BG = "#281B31";
    private static final String TEXT_PRIMARY = "#e8eaed";
    private static final String TEXT_SUB = "#b9bfc7";
    private static final String BORDER = "rgba(255,255,255,0.14)";
    private static final String BORDER_SOFT = "rgba(255,255,255,0.08)";

    private final LauncherApp app;
    private final VBox root = new VBox(14.0);
    private final FlowPane grid = new FlowPane();
    private final Label countLabel = new Label();
    private final Label tipLabel = new Label();
    private final SkinCanvas preview;
    private final Label nameTag = new Label("");
    private final Button delBtn = new Button("删除皮肤");
    private Path selected;
    private ScrollPane scroll;
    private boolean dragging;
    private double dragStartX;
    private double dragStartY;
    private double dragStartYaw;
    private double dragStartPitch;
    private final List<VBox> boxes = new ArrayList<VBox>();

    /** 账号卡：头像 + 昵称 + 副行。 */
    private ImageView avatarView;
    private Label accountName;
    private Label accountSub;
    /** 左栏模型 / 披风按钮。 */
    private Button wideBtn;
    private Button slimBtn;
    private Button capeBtn;
    /** 右栏标签胶囊：已拥有皮肤 / 官方皮肤。 */
    private Button tabOwned;
    private Button tabOfficial;
    /** 右栏底部整宽保存按钮。 */
    private Button saveBtn;
    /** 当前是否处于「官方皮肤」标签（内置 Mojang 预设库）；false = 已拥有（本地 + 账号同步）。 */
    private boolean officialOnly;

    /** 一张内置官方预设皮肤：随 jar 打包，不依赖本地皮肤库。 */
    private static final class BuiltinSkin {
        final String name;
        final String resource;
        final boolean slim;
        BuiltinSkin(String name, String resource, boolean slim) {
            this.name = name;
            this.resource = resource;
            this.slim = slim;
        }
    }

    /** Mojang 官方默认角色预设（取自客户端 jar 的 entity/player 纹理）。 */
    private static final List<BuiltinSkin> BUILTIN = List.of(
            new BuiltinSkin("Steve",   "/skins/builtin/steve.png",  false),
            new BuiltinSkin("Alex",    "/skins/builtin/alex.png",   true),
            new BuiltinSkin("Ari",      "/skins/builtin/ari.png",    true),
            new BuiltinSkin("Efe",      "/skins/builtin/efe.png",    true),
            new BuiltinSkin("Kai",      "/skins/builtin/kai.png",    false),
            new BuiltinSkin("Makena",   "/skins/builtin/makena.png", true),
            new BuiltinSkin("Noor",     "/skins/builtin/noor.png",   true),
            new BuiltinSkin("Sunny",    "/skins/builtin/sunny.png",  true),
            new BuiltinSkin("Zuri",     "/skins/builtin/zuri.png",   false)
    );

    /** 皮肤站同步防重入：refresh() 末尾会触发同步，同步完成又调 refresh()，不拦住会无限循环。 */
    private boolean lsSyncing;
    /** 上次同步过的账号指纹（uuid|皮肤文件|披风文件），相同就不再发请求。 */
    private String lsSyncedKey = "";

    public SkinsView(LauncherApp app) {
        this.app = app;
        this.preview = new SkinCanvas(302.0, 300.0);
        this.preview.setModelScale(0.80);
        // 预览可左右拖动旋转：披风在背后，正视角度只能看到一条边
        final double[] drag = new double[2];
        this.preview.setOnMousePressed(e -> {
            drag[0] = e.getSceneX();
            drag[1] = this.preview.getYaw();
        });
        this.preview.setOnMouseDragged(e -> this.preview.setYaw(drag[1] + (e.getSceneX() - drag[0]) * 0.7));
        this.root.setPadding(new Insets(16.0));
        this.root.setStyle("-fx-background-color:" + OUTER_BG + ";");
        this.root.getChildren().add((Node)this.buildBody());
        this.refresh();
    }

    public VBox root() {
        return this.root;
    }

    private Node buildBody() {
        VBox leftCol = new VBox(14.0, new Node[]{this.buildAccountCard(), this.buildPreviewCard(), this.buildModelRow(), this.buildCapeRow()});
        leftCol.setPrefWidth(330.0);
        leftCol.setMinWidth(300.0);
        Node gridCard = this.buildGridCard();
        VBox.setVgrow(gridCard, (Priority)Priority.ALWAYS);
        HBox row = new HBox(16.0, new Node[]{leftCol, gridCard});
        HBox.setHgrow((Node)row.getChildren().get(1), (Priority)Priority.ALWAYS);
        VBox.setVgrow((Node)row, (Priority)Priority.ALWAYS);
        return row;
    }

    /** 左栏顶部账号卡：头像 + 昵称 + 副行。 */
    private Node buildAccountCard() {
        Image av = null;
        try (InputStream in = getClass().getResourceAsStream("/avatar_steve.png")) {
            if (in != null) av = new Image(in);
        } catch (Exception ignored) {
        }
        if (av == null) av = Ui.GRASS_BLOCK;
        this.avatarView = new ImageView(av);
        this.avatarView.setFitWidth(48.0);
        this.avatarView.setFitHeight(48.0);
        this.avatarView.setPreserveRatio(true);
        this.accountName = new Label("未登录");
        this.accountName.setStyle("-fx-text-fill:" + TEXT_PRIMARY + "; -fx-font-size:16px; -fx-font-weight:bold;");
        this.accountSub = new Label("点击登录账户");
        this.accountSub.setStyle("-fx-text-fill:" + TEXT_SUB + "; -fx-font-size:12px;");
        VBox textCol = new VBox(4.0, new Node[]{this.accountName, this.accountSub});
        textCol.setAlignment(Pos.CENTER_LEFT);
        HBox card = new HBox(12.0, new Node[]{this.avatarView, textCol});
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(14.0));
        card.setStyle("-fx-background-color:transparent; -fx-cursor:hand;");
        card.setOnMouseClicked(e -> openUrl(com.jwh.launcher.auth.LittleSkinAuth.HOME));
        return card;
    }

    private Node buildPreviewCard() {
        this.preview.setOnMousePressed(e -> {
            this.dragging = true;
            this.dragStartX = e.getSceneX();
            this.dragStartY = e.getSceneY();
            this.dragStartYaw = this.preview.getYaw();
            this.dragStartPitch = this.preview.getPitch();
            this.preview.setCursor(Cursor.CLOSED_HAND);
        });
        this.preview.setOnMouseDragged(e -> {
            if (!this.dragging) {
                return;
            }
            // 左右拖 → 绕 Y 旋转；上下拖 → 绕 X 俯仰。鼠标上推=俯视看头顶，下推=仰视看脚底
            double dx = e.getSceneX() - this.dragStartX;
            double dy = e.getSceneY() - this.dragStartY;
            this.preview.setYaw(this.dragStartYaw + dx * 0.45);
            this.preview.setPitch(this.dragStartPitch + dy * 0.35);
        });
        this.preview.setOnMouseReleased(e -> {
            this.dragging = false;
            this.preview.setCursor(Cursor.OPEN_HAND);
            if (Math.abs(e.getSceneX() - this.dragStartX) < 4.0) {
                this.preview.clickPulse();
            }
        });
        this.preview.setCursor(Cursor.OPEN_HAND);
        this.root.sceneProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                this.preview.startAnimation();
            } else {
                this.preview.stopAnimation();
            }
        });
        this.nameTag.setStyle("-fx-background-color:rgba(20,24,30,0.78); -fx-text-fill:white;-fx-font-size:12px; -fx-padding:3 10; -fx-background-radius:10;");
        this.nameTag.setVisible(false);
        StackPane stage = new StackPane(new Node[]{this.preview});
        // 透明舞台：人物直接浮在卡片上
        stage.setStyle("-fx-background-color:transparent;");
        stage.setMinHeight(120.0);
        stage.widthProperty().addListener((o, ov, nv) -> {
            double w = nv.doubleValue();
            if (w > 1.0 && Math.abs(this.preview.getWidth() - w) > 0.5) {
                this.preview.setWidth(w);
                this.preview.render();
            }
        });
        stage.heightProperty().addListener((o, ov, nv) -> {
            double h = nv.doubleValue();
            if (h > 1.0 && Math.abs(this.preview.getHeight() - h) > 0.5) {
                this.preview.setHeight(h);
                this.preview.render();
            }
        });
        Label dragTip = new Label("拖拽以旋转");
        dragTip.setStyle("-fx-text-fill:" + TEXT_SUB + "; -fx-font-size:11px;");
        StackPane.setAlignment((Node)dragTip, (Pos)Pos.BOTTOM_CENTER);
        StackPane.setMargin((Node)dragTip, (Insets)new Insets(0.0, 0.0, 8.0, 0.0));
        stage.getChildren().add((Node)dragTip);
        VBox.setVgrow((Node)stage, (Priority)Priority.ALWAYS);
        this.tipLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
        this.tipLabel.setWrapText(true);
        this.tipLabel.setText("");
        this.tipLabel.setVisible(false);
        this.tipLabel.setManaged(false);
        VBox card = new VBox(10.0, new Node[]{stage});
        card.setPadding(new Insets(14.0));
        card.setStyle("-fx-background-color:" + CARD_BG + "; -fx-background-radius:12;");
        VBox.setVgrow((Node)card, (Priority)Priority.ALWAYS);
        return card;
    }

    /** 左栏第一行：宽手臂 / 细手臂。 */
    private Node buildModelRow() {
        this.wideBtn = new Button("宽手臂");
        this.slimBtn = new Button("细手臂");
        for (Button b : new Button[]{this.wideBtn, this.slimBtn}) {
            b.setStyle(this.modelBtnStyle(false));
            b.setCursor(Cursor.HAND);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setPrefHeight(40.0);
        }
        this.wideBtn.setOnAction(e -> this.setSlim(false));
        this.slimBtn.setOnAction(e -> this.setSlim(true));
        HBox row = new HBox(10.0, new Node[]{this.wideBtn, this.slimBtn});
        HBox.setHgrow((Node)this.wideBtn, (Priority)Priority.ALWAYS);
        HBox.setHgrow((Node)this.slimBtn, (Priority)Priority.ALWAYS);
        return row;
    }

    /** 左栏第二行：披风（约 2/3）+ 删除皮肤（约 1/3）。 */
    private Node buildCapeRow() {
        this.capeBtn = new Button("披风（无）");
        this.capeBtn.setCursor(Cursor.HAND);
        this.capeBtn.setPrefHeight(40.0);
        this.capeBtn.setStyle(this.ghostBtnStyle());
        this.capeBtn.setOnAction(e -> this.importCape());
        // 右键移除披风（左键更换）
        this.capeBtn.setOnMouseClicked(e -> {
            if (e.isSecondaryButtonDown()) {
                this.removeCape();
            }
        });
        this.delBtn.setCursor(Cursor.HAND);
        this.delBtn.setPrefHeight(40.0);
        this.delBtn.setPrefWidth(110.0);
        this.delBtn.setMaxWidth(110.0);
        this.delBtn.setStyle("-fx-background-color:#B8467C; -fx-text-fill:white; -fx-background-radius:10; -fx-padding:7 12; -fx-font-size:13px;");
        this.delBtn.setOnAction(e -> this.deleteSelected());
        HBox row = new HBox(10.0, new Node[]{this.capeBtn, this.delBtn});
        HBox.setHgrow((Node)this.capeBtn, (Priority)Priority.ALWAYS);
        return row;
    }

    private String modelBtnStyle(boolean on) {
        return on
                ? "-fx-background-color:" + PINK + "; -fx-text-fill:white; -fx-background-radius:10; -fx-padding:7 12; -fx-font-size:13px; -fx-font-weight:bold;"
                : "-fx-background-color:transparent; -fx-text-fill:" + TEXT_PRIMARY + "; -fx-background-radius:10; -fx-padding:7 12; -fx-font-size:13px; -fx-border-color:rgba(255,255,255,0.22); -fx-border-radius:10;";
    }

    private String ghostBtnStyle() {
        return "-fx-background-color:transparent; -fx-text-fill:" + TEXT_PRIMARY + "; -fx-background-radius:10; -fx-padding:7 12; -fx-font-size:13px; -fx-border-color:rgba(255,255,255,0.22); -fx-border-radius:10;";
    }

    private String tabStyle(boolean on) {
        return on
                ? "-fx-background-color:" + PINK + "; -fx-text-fill:white; -fx-background-radius:8; -fx-padding:6 14; -fx-font-size:12px; -fx-font-weight:bold;"
                : "-fx-background-color:" + CARD_BG + "; -fx-text-fill:" + TEXT_SUB + "; -fx-background-radius:8; -fx-padding:6 14; -fx-font-size:12px; -fx-border-color:" + BORDER + "; -fx-border-radius:8;";
    }

    /** 切换宽/窄模型：立即重绘预览，并把选择写回账号（下次启动沿用）。 */
    private void setSlim(boolean slim) {
        this.preview.setSlimOverride(slim);
        this.preview.render();
        this.syncModelButtons(slim);
        LauncherConfig.Account acc = this.currentAccount();
        if (acc != null) {
            acc.skinSlim = slim;
            com.jwh.launcher.config.ConfigManager.save(this.app.config);
        }
        this.app.showToast(slim ? "已切换为细手臂（slim）模型。" : "已切换为宽手臂（classic）模型。");
    }

    private void syncModelButtons(boolean slim) {
        if (this.wideBtn == null || this.slimBtn == null) return;
        this.wideBtn.setStyle(this.modelBtnStyle(!slim));
        this.slimBtn.setStyle(this.modelBtnStyle(slim));
    }

    /** 当前账号的披风文件路径；没有就返回 null。 */
    private Path capePath(LauncherConfig.Account acc) {
        if (acc == null || acc.capeFile == null || acc.capeFile.isBlank()) {
            return null;
        }
        Path p = Path.of(this.app.config.minecraftDir).resolve("skins").resolve(acc.capeFile);
        if (Files.isRegularFile(p)) {
            return p;
        }
        // 皮肤站同步的披风落在 skins/library/ 下，同样按文件名找
        Path p2 = this.libraryDir().resolve(acc.capeFile);
        return Files.isRegularFile(p2) ? p2 : null;
    }

    /** 本地导入一张披风 PNG 并绑到当前账号（无账号时 ensureAccount 兜底补离线身份）。 */
    private void importCape() {
        LauncherConfig.Account acc = this.ensureAccount();
        if (acc.uuid == null || acc.uuid.isBlank()) {
            Ui.error("选择披风失败", "当前账号缺少 UUID，无法绑定本地披风。");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("选择披风 PNG（64×32 或 64×64）");
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter[]{
                new FileChooser.ExtensionFilter("披风图片", new String[]{"*.png"}),
                new FileChooser.ExtensionFilter("所有文件", new String[]{"*.*"})});
        File f = fc.showOpenDialog(null);
        if (f == null) {
            return;
        }
        try {
            try (InputStream in = Files.newInputStream(f.toPath())) {
                Image img = new Image(in);
                if (img == null || img.isError()) {
                    this.setTip("这张图片无法读取，请换一张 PNG 披风。");
                    return;
                }
                int iw = (int) Math.round(img.getWidth());
                int ih = (int) Math.round(img.getHeight());
                if (iw != 64 || (ih != 32 && ih != 64)) {
                    this.setTip("这张披风图不是标准尺寸（需要 64×32 或 64×64）。");
                    return;
                }
            }
            Path libDir = this.libraryDir();
            Files.createDirectories(libDir, new FileAttribute[0]);
            String nick = (acc.displayName == null || acc.displayName.isBlank()) ? "Steve" : acc.displayName;
            String base = nick + "-cape";
            Path dst = libDir.resolve(base + ".png");
            int i = 2;
            while (Files.exists(dst, new LinkOption[0])) {
                dst = libDir.resolve(base + " (" + i + ").png");
                ++i;
            }
            Files.copy(f.toPath(), dst, new CopyOption[0]);
            dst.toFile().setWritable(true);
            acc.capeFile = dst.getFileName().toString();
            com.jwh.launcher.config.ConfigManager.save(this.app.config);
            this.refresh();
            this.app.refreshAll();
            this.app.showToast("已绑定披风：" + dst.getFileName());
        } catch (Exception ex) {
            Ui.error("导入披风失败", ex);
        }
    }

    /** 解绑当前账号的本地披风（库里的披风文件保留，仅取消绑定）。 */
    private void removeCape() {
        LauncherConfig.Account acc = this.currentAccount();
        if (acc != null) {
            acc.capeFile = "";
            com.jwh.launcher.config.ConfigManager.save(this.app.config);
        }
        this.preview.clearCape();
        this.refresh();
        this.app.showToast("已移除当前账号的披风。");
    }

    private static void openUrl(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception ignored) {
        }
    }

    private Node buildGridCard() {
        this.grid.setHgap(12.0);
        this.grid.setVgap(12.0);
        this.grid.setPadding(new Insets(4.0, 2.0, 4.0, 2.0));
        this.grid.setPrefWrapLength(520.0);
        this.scroll = new ScrollPane((Node)this.grid);
        this.scroll.setFitToWidth(true);
        this.scroll.getStyleClass().add("jwh-skins-scroll");
        this.scroll.setStyle("-fx-background-color:transparent; -fx-background-insets:0; -fx-padding:0; -fx-border-color:transparent;");
        VBox.setVgrow((Node)this.scroll, (Priority)Priority.ALWAYS);

        // 顶部标签行：已拥有皮肤 / 官方皮肤
        this.tabOwned = new Button("已拥有皮肤");
        this.tabOfficial = new Button("官方皮肤");
        this.tabOwned.setCursor(Cursor.HAND);
        this.tabOfficial.setCursor(Cursor.HAND);
        this.tabOwned.setOnAction(e -> this.switchTab(false));
        this.tabOfficial.setOnAction(e -> this.switchTab(true));

        Region spacer = new Region();
        HBox.setHgrow((Node)spacer, (Priority)Priority.ALWAYS);
        Hyperlink lsHome = new Hyperlink("LittleSkin官网");
        lsHome.setStyle("-fx-text-fill:" + TEXT_SUB + "; -fx-font-size:12px; -fx-cursor:hand;");
        lsHome.setOnAction(e -> openUrl(com.jwh.launcher.auth.LittleSkinAuth.HOME));
        Button addBtn = new Button("+ 添加皮肤");
        addBtn.setCursor(Cursor.HAND);
        addBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:" + TEXT_PRIMARY + "; -fx-background-radius:8; -fx-padding:6 14; -fx-font-size:12px; -fx-border-color:rgba(255,255,255,0.22); -fx-border-radius:8;");
        addBtn.setOnAction(e -> this.importSkin());
        HBox topRow = new HBox(10.0, new Node[]{this.tabOwned, this.tabOfficial, spacer, lsHome, addBtn});
        topRow.setAlignment(Pos.CENTER_LEFT);

        this.saveBtn = new Button("保存设置");
        this.saveBtn.setMaxWidth(Double.MAX_VALUE);
        this.saveBtn.setPrefHeight(44.0);
        this.saveBtn.setCursor(Cursor.HAND);
        this.saveBtn.setStyle("-fx-background-color:" + PINK + "; -fx-text-fill:white; -fx-background-radius:10; -fx-padding:10 14; -fx-font-size:15px; -fx-font-weight:bold;");
        this.saveBtn.setOnAction(e -> this.applySelected());

        VBox card = new VBox(12.0, new Node[]{topRow, this.scroll, this.saveBtn});
        card.setPadding(new Insets(14.0));
        card.setStyle("-fx-background-color:" + CARD_BG + "; -fx-background-radius:14;");
        return card;
    }

    private void switchTab(boolean official) {
        this.officialOnly = official;
        this.refresh();
    }

    /** 首格虚线「拖放添加皮肤」占位卡。 */
    private Node buildDropCard() {
        Label plus = new Label("+");
        plus.setStyle("-fx-text-fill:" + TEXT_SUB + "; -fx-font-size:26px; -fx-font-weight:bold;");
        Label text = new Label("拖放添加皮肤");
        text.setStyle("-fx-text-fill:" + TEXT_SUB + "; -fx-font-size:12px;");
        VBox box = new VBox(6.0, new Node[]{plus, text});
        box.setAlignment(Pos.CENTER);
        box.setPrefWidth(134.0);
        box.setPrefHeight(228.0);
        box.setMaxHeight(228.0);
        box.setStyle("-fx-background-color:transparent; -fx-background-radius:10; -fx-border-color:" + BORDER + "; -fx-border-radius:10; -fx-border-style:dashed; -fx-border-width:1;");
        box.setCursor(Cursor.HAND);
        box.setOnMouseClicked(e -> this.importSkin());
        return box;
    }

    private Node buildCard(Path p, boolean isCurrent) {
        Image img = null;
        try (InputStream in2 = Files.newInputStream(p, new OpenOption[0])) {
            img = new Image(in2);
        } catch (Exception ignored) {
        }
        boolean premium = p.getFileName().toString().startsWith("正版-");
        return this.buildCardCore(img, p.getFileName().toString(), premium ? "正版 · 随账号生效" : null, p, isCurrent);
    }

    /** 内置官方预设卡：贴图来自 jar 资源，点击时才落地到本地库，再复用文件链路预览/选用。 */
    private Node buildBuiltinCard(BuiltinSkin b, boolean isCurrent) {
        Image img = null;
        try (InputStream in = getClass().getResourceAsStream(b.resource)) {
            if (in != null) img = new Image(in);
        } catch (Exception ignored) {
        }
        VBox box = (VBox) this.buildCardCore(img, b.name, null, null, isCurrent);
        box.setOnMouseClicked(e -> {
            Path bp = this.materializeBuiltin(b);
            if (bp != null) {
                this.previewSkin(bp);
                // 内置角色按官方臂型预览，不沿用账号上次的宽/细设置
                this.preview.setSlimOverride(b.slim);
                this.preview.render();
                this.syncModelButtons(b.slim);
                if (e.getClickCount() >= 2) {
                    this.applySkin(bp);
                }
            }
        });
        return box;
    }

    /** 把内置资源复制一份到本地皮肤库（已存在则复用），返回可走文件链路的路径。 */
    private Path materializeBuiltin(BuiltinSkin b) {
        try {
            Path lib = this.libraryDir();
            Files.createDirectories(lib, new FileAttribute[0]);
            Path dst = lib.resolve("官方预设-" + b.name + ".png");
            if (!Files.exists(dst, new LinkOption[0])) {
                try (InputStream in = getClass().getResourceAsStream(b.resource)) {
                    if (in != null) {
                        Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                        dst.toFile().setWritable(true);
                    }
                }
            }
            return dst;
        } catch (Exception e) {
            return null;
        }
    }

    private Node buildCardCore(Image img, String displayName, String tagText, Path userData, boolean isCurrent) {
        SkinCanvas thumb = new SkinCanvas(118.0, 152.0);
        if (img != null) thumb.setSkin(img);
        thumb.render();
        Label badge = new Label("使用中");
        badge.setStyle("-fx-background-color:" + PINK + "; -fx-text-fill:white;-fx-font-size:10px; -fx-padding:1 6; -fx-background-radius:8;");
        badge.setVisible(isCurrent);
        badge.setManaged(isCurrent);
        StackPane pic = new StackPane(new Node[]{thumb, badge});
        pic.setStyle("-fx-background-color:transparent;");
        pic.setMinHeight(152.0);
        pic.setPrefHeight(152.0);
        pic.setMaxHeight(152.0);
        pic.setAlignment(Pos.CENTER);
        StackPane.setAlignment((Node)badge, (Pos)Pos.TOP_RIGHT);
        StackPane.setMargin((Node)badge, (Insets)new Insets(6.0));
        Label name = new Label(displayName);
        name.setStyle("-fx-text-fill:" + TEXT_SUB + "; -fx-font-size:11px;");
        name.setMaxWidth(118.0);
        name.setPrefHeight(30.0);
        name.setWrapText(true);
        Label tag = null;
        if (tagText != null) {
            tag = new Label(tagText);
            tag.setStyle("-fx-text-fill:" + TEXT_SUB + "; -fx-font-size:10px;");
            tag.setPrefHeight(14.0);
        }
        VBox box = new VBox(6.0, new Node[]{pic, name});
        if (tag != null) box.getChildren().add((Node)tag);
        box.setPadding(new Insets(8.0));
        box.setPrefWidth(134.0);
        // 两类卡片锁定等高：画布 152 + 两行文件名 30 + 标签行 14 + 间距/内边距，避免官方卡被文本撑高
        box.setPrefHeight(228.0);
        box.setMaxHeight(228.0);
        box.setStyle(this.borderStyle(isCurrent));
        box.setOnMouseEntered(e -> {
            if (!isCurrent) {
                box.setStyle(this.borderStyle(false, PINK + "55"));
            }
        });
        box.setOnMouseExited(e -> box.setStyle(this.borderStyle(isCurrent)));
        box.setOnMouseClicked(e -> {
            if (userData != null) {
                this.previewSkin(userData);
                if (e.getClickCount() >= 2) {
                    this.applySkin(userData);
                }
            }
        });
        box.setUserData((Object)userData);
        this.boxes.add(box);
        return box;
    }

    private String borderStyle(boolean current) {
        return this.borderStyle(current, "transparent");
    }

    private String borderStyle(boolean current, String hoverColor) {
        String color = current ? PINK : hoverColor;
        return "-fx-background-color:" + CHIP_BG + "; -fx-background-radius:10;-fx-border-color:" + color + "; -fx-border-radius:10;-fx-border-width:" + (current ? 2 : 1) + ";";
    }

    public void refresh() {
        this.boxes.clear();
        this.grid.getChildren().clear();
        LauncherConfig.Account acc = this.currentAccount();
        boolean offline = acc != null && acc.type == LauncherConfig.Account.Type.OFFLINE;
        List<Path> skins;
        List<BuiltinSkin> builtins;
        if (this.officialOnly) {
            // 官方皮肤：内置 Mojang 预设角色库（随 jar 打包）
            skins = List.of();
            builtins = BUILTIN;
        } else {
            // 已拥有：本地导入 + 正版账号同步 + 皮肤站同步，全部展示在这一标签
            skins = this.listLibrary();
            builtins = List.of();
        }
        int total = this.officialOnly ? builtins.size() : skins.size();
        this.countLabel.setText("共 " + total + " 张皮肤");

        // 账号卡回显
        if (this.accountName != null) {
            if (acc == null) {
                this.accountName.setText("未登录");
                this.accountSub.setText("点击登录账户");
            } else {
                this.accountName.setText(acc.displayName == null || acc.displayName.isBlank() ? "未命名" : acc.displayName);
                this.accountSub.setText(this.typeLabel(acc));
            }
        }
        // 标签胶囊样式
        if (this.tabOwned != null) {
            this.tabOwned.setStyle(this.tabStyle(!this.officialOnly));
            this.tabOfficial.setStyle(this.tabStyle(this.officialOnly));
        }

        String curHash = this.currentSkinHash(acc);
        if (this.officialOnly) {
            for (BuiltinSkin b : builtins) {
                this.grid.getChildren().add(this.buildBuiltinCard(b, false));
            }
        } else if (skins.isEmpty()) {
            this.grid.getChildren().add(this.buildDropCard());
            Label empty = new Label("皮肤库还是空的，点「+ 添加皮肤」导入一张 PNG（支持 64×64 或 64×32）。");
            empty.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
            empty.setWrapText(true);
            this.grid.getChildren().add(empty);
        } else {
            this.grid.getChildren().add(this.buildDropCard());
            for (Path p : skins) {
                this.grid.getChildren().add(this.buildCard(p, curHash != null && curHash.equals(SkinsView.sha1Hex(p))));
            }
        }
        Path keep = this.selected != null && Files.isRegularFile(this.selected, new LinkOption[0]) ? this.selected : null;
        if (keep == null) {
            if (!skins.isEmpty()) {
                keep = skins.get(0);
            } else if (!builtins.isEmpty()) {
                keep = this.materializeBuiltin(builtins.get(0));
            }
        }
        this.selected = keep;
        this.previewSkin(this.selected);
        this.updateActionState();
        // 本地换肤只对离线账号生效（走本地 SkinServer 下发）；正版/皮肤站皮肤由官方/站点下发，本地选图仅用于预览
        String tip;
        if (offline) {
            tip = "";
        } else if (acc != null && acc.type == LauncherConfig.Account.Type.MICROSOFT) {
            tip = "正版皮肤来自 Mojang，游戏内显示以官方账号皮肤为准；此处本地选图仅作预览。";
        } else if (acc != null && acc.type == LauncherConfig.Account.Type.LITTLESKIN) {
            tip = "游戏内显示以皮肤站账号皮肤为准；此处本地选图仅作预览。";
        } else {
            tip = "未登录时将使用离线身份保存本地皮肤，选图即生效。";
        }
        this.setTip(tip);
        // 模型按钮 / 披风按当前账号状态回显（切账号后不会停在上一个账号的选择）
        boolean slimNow = acc != null && acc.skinSlim != null ? acc.skinSlim : this.preview.isSlim();
        this.syncModelButtons(slimNow);
        if (this.capeBtn != null) {
            boolean hasCape = this.capePath(acc) != null;
            this.capeBtn.setText(hasCape ? "披风 · 点击更换（右键移除）" : "披风（无）");
            if (!hasCape) {
                this.preview.clearCape();
            }
        }
        // 正版账号的皮肤来自 Mojang，后台拉取一次，完成后自动刷新列表
        this.syncPremiumSkin();
    }

    private String typeLabel(LauncherConfig.Account acc) {
        if (acc == null) return "点击登录账户";
        switch (acc.type) {
            case MICROSOFT: return "正版账号";
            case LITTLESKIN: return "LittleSkin 账号";
            default: return "离线账号";
        }
    }

    /** 提示条按内容自动显隐，空文案时不占高度。 */
    private void setTip(String text) {
        boolean show = text != null && !text.isEmpty();
        this.tipLabel.setText(show ? text : "");
        this.tipLabel.setVisible(show);
        this.tipLabel.setManaged(show);
    }

    private void updateActionState() {
        boolean has = this.selected != null && Files.isRegularFile(this.selected, new LinkOption[0]);
        boolean isCurrent = has && this.isSelectedCurrent(this.selected);
        boolean premium = has && this.selected.getFileName().toString().startsWith("正版-");
        if (this.saveBtn != null) {
            this.saveBtn.setDisable(!has || isCurrent);
            this.saveBtn.setText(isCurrent ? "当前使用中" : "保存设置");
        }
        this.delBtn.setDisable(!has || premium);
    }

    private boolean isSelectedCurrent(Path p) {
        String hash = this.currentSkinHash(this.currentAccount());
        return hash != null && hash.equals(SkinsView.sha1Hex(p));
    }

    private void previewSkin(Path p) {
        this.selected = p;
        if (p == null) {
            this.nameTag.setVisible(false);
            this.updateActionState();
            return;
        }
        try (InputStream in = Files.newInputStream(p, new OpenOption[0]);){
            boolean ok = this.preview.setSkin(new Image(in));
            LauncherConfig.Account cur = this.currentAccount();
            // 账号手动选过宽/窄就照它来，没选过才按贴图自动判定
            this.preview.setSlimOverride(cur == null ? null : cur.skinSlim);
            // 顺手把披风带上，切皮肤时预览不会丢掉披风
            Path cape = this.capePath(cur);
            if (cape != null && !this.preview.hasCape()) {
                this.preview.setCape(cape);
            }
            // 每次进入皮肤库都回到默认视角：切到别的页再切回来，不会停在上次拖到的怪角度
            this.preview.setYaw(20.0);
            this.preview.setPitch(7.0);
            this.preview.render();
            LauncherConfig.Account acc = this.currentAccount();
            this.nameTag.setText(acc == null ? "未选择账号" : acc.displayName);
            this.nameTag.setVisible(true);
            if (!ok) {
                this.setTip("这张 PNG 不是标准皮肤尺寸（需要 64×64 或 64×32）。");
            }
        }
        catch (Exception e) {
            Ui.error((String)"预览皮肤失败", (Exception)e);
        }
        for (VBox box : this.boxes) {
            boolean cur = p.equals(box.getUserData());
            box.setStyle(this.borderStyle(cur, PINK + "55"));
        }
        this.updateActionState();
    }

    private void importSkin() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择皮肤 PNG（支持一次导入一张，64×64 或 64×32）");
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter[]{new FileChooser.ExtensionFilter("皮肤图片", new String[]{"*.png"}), new FileChooser.ExtensionFilter("所有文件", new String[]{"*.*"})});
        File f = fc.showOpenDialog(null);
        if (f == null) {
            return;
        }
        try {
            Path libDir = this.libraryDir();
            Files.createDirectories(libDir, new FileAttribute[0]);
            String base = f.getName().replaceFirst("(?i)\\.png$", "");
            Path dst = libDir.resolve(f.getName());
            int i = 2;
            while (Files.exists(dst, new LinkOption[0])) {
                dst = libDir.resolve(base + " (" + i + ").png");
                ++i;
            }
            Files.copy(f.toPath(), dst, new CopyOption[0]);
            dst.toFile().setWritable(true); // 库里的皮肤必须是可写可删的，避免源文件只读属性带过来
            this.selected = dst;
            this.refresh();
            this.app.showToast("已导入皮肤：" + String.valueOf(dst.getFileName()));
        }
        catch (Exception ex) {
            Ui.error((String)"导入皮肤失败", (Exception)ex);
        }
    }

    private void applySelected() {
        if (this.selected != null) {
            this.applySkin(this.selected);
        }
    }

    private void applySkin(Path from) {
        // 未登录 / 未选中账号时也允许换肤：ensureAccount 会复用已有账号或自动补一个离线身份
        LauncherConfig.Account acc = this.ensureAccount();
        if (acc.uuid == null || acc.uuid.isBlank()) {
            Ui.error("切换皮肤失败", "当前账号缺少 UUID，无法写入本地皮肤。");
            return;
        }
        try {
            Path skinsDir = Path.of(this.app.config.minecraftDir, new String[0]).resolve("skins");
            Files.createDirectories(skinsDir, new FileAttribute[0]);
            String fn = acc.uuid.replace("-", "") + ".png";
            Files.copy(from, skinsDir.resolve(fn), StandardCopyOption.REPLACE_EXISTING);
            skinsDir.resolve(fn).toFile().setWritable(true); // 生效皮肤也要保持可写，下次换肤才能覆盖
            acc.skinFile = fn;
            com.jwh.launcher.config.ConfigManager.save(this.app.config);
            this.previewSkin(from);
            this.refresh();
            this.app.refreshAll();
            this.app.showToast("已切换到皮肤：" + String.valueOf(from.getFileName()) + "，下次启动游戏生效。");
        }
        catch (Exception ex) {
            Ui.error((String)"切换皮肤失败", (Exception)ex);
        }
    }

    private void deleteSelected() {
        if (this.selected != null) {
            this.deleteSkin(this.selected);
        }
    }

    private void deleteSkin(Path p) {
        if (!Ui.confirm((String)"删除皮肤", (String)("确定从皮肤库删除「" + String.valueOf(p.getFileName()) + "」？当前生效皮肤不会受影响。"))) {
            return;
        }
        try {
            // Windows 上只读文件不允许删除，删除前先清掉只读位
            if (Files.exists(p, new LinkOption[0]) && !Files.isWritable(p)) {
                p.toFile().setWritable(true);
            }
            Files.deleteIfExists(p);
            if (p.equals(this.selected)) {
                this.selected = null;
            }
            this.refresh();
            this.app.showToast("已从皮肤库删除：" + String.valueOf(p.getFileName()));
        }
        catch (Exception ex) {
            Ui.error((String)"删除皮肤失败", (Exception)ex);
        }
    }

    private LauncherConfig.Account currentAccount() {
        int idx = this.app.config.selectedAccount;
        List accounts = this.app.config.accounts;
        if (idx < 0 || idx >= accounts.size()) {
            return null;
        }
        return (LauncherConfig.Account)accounts.get(idx);
    }

    /**
     * 取一个可写本地皮肤的账号：优先当前选中账号；没选中时复用列表里第一个；
     * 一个账号都没有则自动补一个离线身份。保证未登录状态下也能进皮肤库选皮肤并生效，
     * 不再要求必须先登录某个站点账号。
     */
    private LauncherConfig.Account ensureAccount() {
        LauncherConfig.Account acc = this.currentAccount();
        if (acc != null) {
            return acc;
        }
        List<LauncherConfig.Account> accounts = this.app.config.accounts;
        if (!accounts.isEmpty()) {
            this.app.auth.selectAccount(0);
            return accounts.get(0);
        }
        return this.app.auth.addOfflineAccount("Steve");
    }

    private Path libraryDir() {
        return Path.of(this.app.config.minecraftDir, new String[0]).resolve("skins").resolve("library");
    }

    /**
     * 正版（Microsoft）账号皮肤同步：从 Mojang sessionserver 按 UUID 拉取皮肤
     * 纹理，下载到本地皮肤库（文件名带「正版-」前缀），完成后刷新列表。
     * 全程后台线程，失败静默，不影响页面其它功能。
     */
    private void syncPremiumSkin() {
        LauncherConfig.Account acc = this.currentAccount();
        if (acc == null) {
            return;
        }
        if (acc.type == LauncherConfig.Account.Type.LITTLESKIN) {
            this.syncLittleSkin(acc);
            return;
        }
        if (acc.type != LauncherConfig.Account.Type.MICROSOFT) {
            return;
        }
        String uuid = acc.uuid;
        if (uuid == null || uuid.isEmpty()) {
            return;
        }
        Path lib = this.libraryDir();
        Path target = lib.resolve("正版-" + acc.displayName + ".png");
        if (Files.isRegularFile(target, new LinkOption[0])) {
            return; // 已同步过
        }
        Thread t = new Thread(() -> {
            try {
                // 公开接口：按 UUID 查 Mojang profile，properties[0].value 是 base64 的纹理 JSON
                String profile = Http.getText("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
                JSONObject root = Json.obj(profile);
                JSONArray props = Json.optArr(root, "properties");
                String skinUrl = null;
                if (props != null) {
                    for (int i = 0; i < props.length(); i++) {
                        JSONObject p0 = props.optJSONObject(i);
                        if (p0 == null || !"textures".equals(p0.optString("name"))) {
                            continue;
                        }
                        String val = p0.optString("value");
                        if (val == null || val.isEmpty()) {
                            break;
                        }
                        byte[] dec = Base64.getDecoder().decode(val);
                        JSONObject tex = Json.obj(new String(dec, StandardCharsets.UTF_8));
                        JSONObject skin = Json.optObj(Json.optObj(tex, "textures"), "SKIN");
                        skinUrl = skin == null ? null : skin.optString("url");
                        break;
                    }
                }
                if (skinUrl == null || skinUrl.isEmpty()) {
                    return;
                }
                byte[] png = Http.getBytes(skinUrl);
                if (png == null || png.length < 100) {
                    return;
                }
                Files.createDirectories(lib, new FileAttribute[0]);
                Files.write(target, png, new OpenOption[0]);
                Platform.runLater(() -> {
                    if (Files.isRegularFile(target, new LinkOption[0])) {
                        this.refresh();
                    }
                });
            } catch (Exception ignored) {
                // 网络失败 / 无皮肤 / 解析失败都静默，下次进入页面会再尝试
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * LittleSkin 账号：从皮肤站拉取该角色的皮肤与披风，落到皮肤库并写回账号。
     * 下载成功后顺手把模型（slim/classic）也同步成站点那边选的，预览直接对上。
     */
    private void syncLittleSkin(LauncherConfig.Account acc) {
        if (acc == null || acc.uuid == null || acc.uuid.isBlank()) return;
        // 启动加载页已经拉过这一次运行里就不再重复联网
        if (!com.jwh.launcher.auth.LittleSkinSync.claim(acc)) return;
        this.lsSyncedKey = com.jwh.launcher.auth.LittleSkinSync.keyOf(acc);
        this.lsSyncing = true;
        Thread t = new Thread(() -> {
            boolean changed = false;
            try {
                changed = com.jwh.launcher.auth.LittleSkinSync.applyTo(this.app.config, acc);
            } catch (Exception ignored) {
                // 站点连不上 / 该角色没设材质：静默，下次进页面再拉
            }
            final boolean finalChanged = changed;
            Platform.runLater(() -> {
                if (finalChanged) {
                    com.jwh.launcher.config.ConfigManager.save(this.app.config);
                }
                this.lsSyncing = false;
                this.refresh();
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private List<Path> listLibrary() {
        Path dir = this.libraryDir();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> s = Files.list(dir)) {
                return s.filter(p -> {
                            // 披风图（xxx-cape.png）不是皮肤，别当成卡片列出来
                            String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".png") && !n.contains("-cape");
                        })
                        .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    private String currentSkinHash(LauncherConfig.Account acc) {
        if (acc == null || acc.uuid == null || acc.uuid.isBlank()) {
            return null;
        }
        Path cur = Path.of(this.app.config.minecraftDir, new String[0]).resolve("skins").resolve(acc.uuid.replace("-", "") + ".png");
        return Files.isRegularFile(cur, new LinkOption[0]) ? SkinsView.sha1Hex(cur) : null;
    }

    static String sha1Hex(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(p, new LinkOption[0]);){
                int n;
                byte[] buf = new byte[8192];
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (Exception e) {
            return "";
        }
    }
}
