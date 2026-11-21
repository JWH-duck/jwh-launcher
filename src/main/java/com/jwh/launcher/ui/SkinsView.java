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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
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
    private final LauncherApp app;
    private final VBox root = new VBox(12.0);
    private final FlowPane grid = new FlowPane();
    private final Label countLabel = new Label();
    private final Label tipLabel = new Label();
    private final SkinCanvas preview;
    private final Label nameTag = new Label("");
    private final Button useBtn = new Button("使用这个皮肤");
    private final Button delBtn = new Button("删除");
    private Path selected;
    private ScrollPane scroll;
    private boolean dragging;
    private double dragStartX;
    private double dragStartY;
    private double dragStartYaw;
    private double dragStartPitch;
    private final List<VBox> boxes = new ArrayList<VBox>();
    /** 预览区最左侧的「走动」开关；用无默认样式的容器承载，保证背景彻底透明。 */
    private StackPane walkButton;
    /** 开关图标视图，主题变深/变浅时直接换图。 */
    private ImageView walkIconView;
    /** 模型切换按钮（宽/窄）与披风勾选框，refresh 时按账号状态同步。 */
    private Button wideBtn;
    private Button slimBtn;
    private javafx.scene.control.CheckBox capeBox;
    /** 皮肤站同步防重入：refresh() 末尾会触发同步，同步完成又调 refresh()，不拦住会无限循环。 */
    private boolean lsSyncing;
    /** 上次同步过的账号指纹（uuid|皮肤文件|披风文件），相同就不再发请求。 */
    private String lsSyncedKey = "";

    public SkinsView(LauncherApp app) {
        this.app = app;
        this.preview = new SkinCanvas(302.0, 300.0);
        // 预览可左右拖动旋转：披风在背后，正视角度只能看到一条边
        final double[] drag = new double[2];
        this.preview.setOnMousePressed(e -> {
            drag[0] = e.getSceneX();
            drag[1] = this.preview.getYaw();
        });
        this.preview.setOnMouseDragged(e -> this.preview.setYaw(drag[1] + (e.getSceneX() - drag[0]) * 0.7));
        this.root.setPadding(new Insets(16.0));
        this.root.setStyle("-fx-background-color:" + Ui.BG + ";");
        this.root.getChildren().addAll(new Node[]{this.buildHeader(), this.buildBody()});
        this.refresh();
    }

    public VBox root() {
        return this.root;
    }

    /** 走动按钮不画任何底色块（要求完全融入背景），hover 反馈交给图标自身缩放。 */
    private String styleWalkBtn(boolean on, boolean hover) {
        return "-fx-background-color:transparent; -fx-background-radius:8; -fx-padding:2;";
    }

    /** 深色背景（图片/透明模式）用白色线稿，浅色背景用深色线稿，保证图标始终看得见。 */
    private Image pickWalkIcon() {
        String mode = (this.app != null && this.app.config != null) ? this.app.config.bgMode : null;
        boolean dark = "image".equals(mode) || "transparent".equals(mode);
        Image ic = dark ? Ui.WALK_LIGHT : Ui.WALK_DARK;
        return ic != null ? ic : Ui.WALK_DARK;
    }

    /** 主题变化后按需刷新按钮图标（界面重建前也会走一次，双重保险）。 */
    private void syncWalkIcon() {
        Image ic = this.pickWalkIcon();
        if (ic != null && this.walkIconView != null) {
            this.walkIconView.setImage(ic);
        }
    }

    private Node buildHeader() {
        Label title = new Label("皮肤库");
        title.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:18px; -fx-font-weight:bold;");
        this.countLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        Region spacer = new Region();
        HBox.setHgrow((Node)spacer, (Priority)Priority.ALWAYS);
        Button add = new Button("+ 添加皮肤");
        add.setStyle("-fx-background-color:#3FA9F5; -fx-text-fill:white;-fx-background-radius:6; -fx-padding:6 14; -fx-cursor:hand; -fx-font-weight:bold;");
        add.setOnAction(e -> this.importSkin());
        // 皮肤站直达：点一下开 LittleSkin 官网，去那边挑皮肤/披风、改宽窄模型
        javafx.scene.control.Hyperlink lsHome = new javafx.scene.control.Hyperlink("LittleSkin 官网");
        lsHome.setStyle("-fx-text-fill:#0DB9C6; -fx-font-size:12px; -fx-cursor:hand;");
        lsHome.setOnAction(e -> openUrl(com.jwh.launcher.auth.LittleSkinAuth.HOME));
        javafx.scene.control.Hyperlink lsReg = new javafx.scene.control.Hyperlink("注册");
        lsReg.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px; -fx-cursor:hand;");
        lsReg.setOnAction(e -> openUrl(com.jwh.launcher.auth.LittleSkinAuth.REGISTER));
        HBox row = new HBox(10.0, new Node[]{title, this.countLabel, spacer, lsHome, lsReg, add});
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Node buildBody() {
        HBox row = new HBox(14.0, new Node[]{this.buildPreviewCard(), this.buildGridCard()});
        HBox.setHgrow((Node)((Node)row.getChildren().get(1)), (Priority)Priority.ALWAYS);
        VBox.setVgrow((Node)row, (Priority)Priority.ALWAYS);
        return row;
    }

    private Node buildPreviewCard() {
        Label head = new Label("预览");
        head.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:14px; -fx-font-weight:bold;");
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
        // 透明舞台：人物直接浮在启动器面板上，跟随主题背景色
        stage.setStyle("-fx-background-color:transparent;");
        // Canvas 不可伸缩，尺寸必须自己跟随舞台，预览才会随窗口自适应高度（否则整页撑高会挤掉顶栏）
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
        // 名字胶囊放在「预览」标题行右侧，避免遮住人物头部
        Region headSpacer = new Region();
        HBox.setHgrow((Node)headSpacer, (Priority)Priority.ALWAYS);
        // 「走动」开关：放在预览框最左边，只有线稿小人、背景全透明；提示文字挂在 tooltip 上
        ImageView walkIcon = new ImageView(this.pickWalkIcon());
        walkIcon.setFitWidth(28.0);
        walkIcon.setFitHeight(28.0);
        walkIcon.setPreserveRatio(true);
        this.walkIconView = walkIcon;
        // 用 StackPane 而不是 Button：Button 自带 Modena 的灰蓝底/描边，怎么清都会留色块
        StackPane walkBtn = new StackPane((Node)walkIcon);
        walkBtn.setPadding(new Insets(2.0));
        walkBtn.setCursor(Cursor.HAND);
        walkBtn.setPickOnBounds(true);
        // 悬停提示用界面内自绘气泡（Tooltip 是独立弹窗，无法随主窗口一起截图校验）
        Label walkTip = new Label("走动");
        walkTip.setStyle("-fx-background-color:rgba(30,35,44,0.92); -fx-text-fill:white;"
                + " -fx-font-size:11px; -fx-padding:2 6; -fx-background-radius:5;");
        walkTip.setTranslateX(-3.0);
        walkTip.setMouseTransparent(true);
        walkTip.setVisible(false);
        walkBtn.setStyle(this.styleWalkBtn(false, false));
        walkBtn.setOnMouseEntered(e -> {
            walkBtn.setStyle(this.styleWalkBtn(this.preview.isWalking(), true));
            walkIcon.setScaleX(1.10);
            walkIcon.setScaleY(1.10);
            walkTip.setVisible(true);
        });
        walkBtn.setOnMouseExited(e -> {
            walkBtn.setStyle(this.styleWalkBtn(this.preview.isWalking(), false));
            walkIcon.setScaleX(1.0);
            walkIcon.setScaleY(1.0);
            walkTip.setVisible(false);
        });
        walkBtn.setOnMouseClicked(e -> {
            boolean on = !this.preview.isWalking();
            this.preview.setWalking(on);
            this.preview.render();
            walkBtn.setStyle(this.styleWalkBtn(on, walkBtn.isHover()));
        });
        this.walkButton = walkBtn;
        // 用一层铺满舞台但不吃鼠标的 HBox 把按钮钉在左边缘（StackPane.setAlignment 在此布局下不生效）
        HBox walkHolder = new HBox(0.0, new Node[]{walkBtn, walkTip});
        walkHolder.setAlignment(Pos.CENTER_LEFT);
        walkHolder.setPickOnBounds(false);
        walkHolder.setFillHeight(false);   // 不拉伸按钮高度，否则透明背景会被拉成一整条竖条
        walkHolder.setPadding(new Insets(0.0, 0.0, 0.0, 10.0));
        walkBtn.setMaxHeight(Region.USE_PREF_SIZE);
        walkBtn.setMaxWidth(Region.USE_PREF_SIZE);
        walkBtn.setTranslateY(-34.0);
        stage.getChildren().add((Node)walkHolder);
        HBox headRow = new HBox(8.0, new Node[]{head, headSpacer, this.nameTag});
        headRow.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow((Node)stage, (Priority)Priority.ALWAYS);
        this.tipLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
        this.tipLabel.setWrapText(true);
        this.tipLabel.setText("");
        this.tipLabel.setVisible(false);
        this.tipLabel.setManaged(false);
        this.useBtn.setMaxWidth(Double.MAX_VALUE);
        this.useBtn.setStyle("-fx-background-color:#3FA9F5; -fx-text-fill:white;-fx-background-radius:6; -fx-padding:7 12; -fx-font-weight:bold; -fx-cursor:hand;");
        this.useBtn.setOnAction(e -> this.applySelected());
        this.delBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:" + Ui.TEXT_DIM + ";-fx-background-radius:6; -fx-padding:7 10; -fx-cursor:hand;");
        this.delBtn.setOnAction(e -> this.deleteSelected());
        HBox actions = new HBox(8.0, new Node[]{this.useBtn, this.delBtn});
        HBox.setHgrow((Node)this.useBtn, (Priority)Priority.ALWAYS);
        VBox card = new VBox(10.0, new Node[]{headRow, stage, this.buildModelBar(), this.tipLabel, actions});
        card.setPadding(new Insets(14.0));
        card.setPrefWidth(330.0);
        card.setMinWidth(300.0);
        card.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-background-radius:12;");
        return card;
    }

    /**
     * 预览下方的「模型 + 披风」控制条：
     * 宽/窄手臂可直接改 3D 模型（皮肤站那边也按这个显示），披风勾选后画出背后的披风。
     */
    private Node buildModelBar() {
        Label tag = new Label("模型");
        tag.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        this.wideBtn = new Button("宽手臂");
        this.slimBtn = new Button("细手臂");
        for (Button b : new Button[]{this.wideBtn, this.slimBtn}) {
            b.setStyle(modelBtnStyle(false));
            b.setCursor(Cursor.HAND);
        }
        this.wideBtn.setOnAction(e -> this.setSlim(false));
        this.slimBtn.setOnAction(e -> this.setSlim(true));

        this.capeBox = new javafx.scene.control.CheckBox("披风");
        this.capeBox.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        this.capeBox.setOnAction(e -> this.toggleCape(this.capeBox.isSelected()));

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox bar = new HBox(6.0, new Node[]{tag, this.wideBtn, this.slimBtn, sp, this.capeBox});
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private String modelBtnStyle(boolean on) {
        return on
                ? "-fx-background-color:#3FA9F5; -fx-text-fill:white; -fx-background-radius:5; -fx-padding:3 9; -fx-font-size:12px;"
                : "-fx-background-color:" + Ui.BG_LIGHT + "; -fx-text-fill:" + Ui.TEXT + "; -fx-background-radius:5; -fx-padding:3 9; -fx-font-size:12px;";
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

    /** 披风开关：勾上就把账号名下的披风文件画出来，取消则移除。 */
    private void toggleCape(boolean on) {
        LauncherConfig.Account acc = this.currentAccount();
        if (on) {
            Path cape = this.capePath(acc);
            if (cape == null) {
                this.capeBox.setSelected(false);
                this.setTip("当前账号还没有披风文件：用 LittleSkin 账号登录后会自动从皮肤站同步。");
                return;
            }
            if (!this.preview.setCape(cape)) {
                this.capeBox.setSelected(false);
                this.setTip("这张披风图不是标准尺寸（需要 64×32 或 64×64）。");
                return;
            }
        } else {
            this.preview.clearCape();
        }
        this.preview.render();
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
        this.scroll.setStyle("-fx-background-color:transparent; -fx-background-insets:0; -fx-padding:0; -fx-border-color:transparent;");
        VBox.setVgrow((Node)this.scroll, (Priority)Priority.ALWAYS);
        VBox card = new VBox(8.0, new Node[]{this.scroll});
        card.setPadding(new Insets(12.0));
        card.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-background-radius:12;");
        return card;
    }

    private Node buildCard(Path p, boolean isCurrent) {
        SkinCanvas thumb = new SkinCanvas(118.0, 152.0);
        try (InputStream in2 = Files.newInputStream(p, new OpenOption[0]);){
            thumb.setSkin(new Image(in2));
        }
        catch (Exception in2) {
            // empty catch block
        }
        thumb.render();
        Label badge = new Label("使用中");
        badge.setStyle("-fx-background-color:#3FA9F5; -fx-text-fill:white;-fx-font-size:10px; -fx-padding:1 6; -fx-background-radius:8;");
        badge.setVisible(isCurrent);
        badge.setManaged(isCurrent);
        StackPane pic = new StackPane(new Node[]{thumb, badge});
        // 缩略图同样透明，和卡片面板融在一起
        pic.setStyle("-fx-background-color:transparent;");
        StackPane.setAlignment((Node)badge, (Pos)Pos.TOP_RIGHT);
        StackPane.setMargin((Node)badge, (Insets)new Insets(6.0));
        Label name = new Label(p.getFileName().toString());
        name.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
        name.setMaxWidth(118.0);
        name.setWrapText(true);
        Button use = new Button("使用");
        use.setStyle("-fx-background-color:#3FA9F5; -fx-text-fill:white;-fx-font-size:11px; -fx-padding:2 8; -fx-background-radius:5; -fx-cursor:hand;");
        use.setOnAction(e -> {
            e.consume();
            this.applySkin(p);
        });
        Button remove = new Button("删除");
        remove.setStyle("-fx-background-color:rgba(0,0,0,0.06); -fx-text-fill:" + Ui.TEXT_DIM + ";-fx-font-size:11px; -fx-padding:2 8; -fx-background-radius:5; -fx-cursor:hand;");
        remove.setOnAction(e -> {
            e.consume();
            this.deleteSkin(p);
        });
        // 自动同步来的正版皮肤由 Mojang 账号管理，不提供本地「使用/删除」
        boolean premium = p.getFileName().toString().startsWith("正版-");
        HBox hoverBar;
        if (premium) {
            Button tag = new Button("正版皮肤 · 随账号生效");
            tag.setDisable(true);
            tag.setStyle("-fx-background-color:rgba(0,0,0,0.06); -fx-text-fill:" + Ui.TEXT_DIM + ";-fx-font-size:11px; -fx-padding:2 8; -fx-background-radius:5;");
            hoverBar = new HBox(4.0, new Node[]{tag});
        } else {
            hoverBar = new HBox(4.0, new Node[]{use, remove});
        }
        hoverBar.setAlignment(Pos.CENTER);
        hoverBar.setVisible(false);
        boolean[] isSelected = new boolean[]{isCurrent};
        VBox box = new VBox(6.0, new Node[]{pic, name, hoverBar});
        box.setPadding(new Insets(8.0));
        box.setPrefWidth(134.0);
        box.setStyle(this.borderStyle(isCurrent));
        box.setOnMouseEntered(e -> {
            hoverBar.setVisible(true);
            if (!isSelected[0]) {
                box.setStyle(this.borderStyle(false, "#3FA9F555"));
            }
        });
        box.setOnMouseExited(e -> {
            hoverBar.setVisible(false);
            box.setStyle(this.borderStyle(isSelected[0]));
        });
        box.setOnMouseClicked(e -> {
            this.previewSkin(p);
            if (e.getClickCount() >= 2) {
                this.applySkin(p);
            }
        });
        box.setUserData((Object)p);
        this.boxes.add(box);
        return box;
    }

    private String borderStyle(boolean current) {
        return this.borderStyle(current, "transparent");
    }

    private String borderStyle(boolean current, String hoverColor) {
        String color = current ? "#3FA9F5" : hoverColor;
        return "-fx-background-color:" + Ui.PANEL + "; -fx-background-radius:10;-fx-border-color:" + color + "; -fx-border-radius:10;-fx-border-width:" + (current ? 2 : 1) + ";";
    }

    public void refresh() {
        this.syncWalkIcon();
        Path keep;
        this.boxes.clear();
        this.grid.getChildren().clear();
        LauncherConfig.Account acc = this.currentAccount();
        boolean offline = acc != null && acc.type == LauncherConfig.Account.Type.OFFLINE;
        List<Path> skins = this.listLibrary();
        this.countLabel.setText("共 " + skins.size() + " 张皮肤");
        if (skins.isEmpty()) {
            Label empty = new Label("皮肤库还是空的，点右上角「+ 添加皮肤」导入一张 PNG（支持 64×64 或 64×32）。");
            empty.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
            empty.setWrapText(true);
            this.grid.getChildren().add(empty);
        } else {
            String curHash = this.currentSkinHash(acc);
            for (Path p : skins) {
                this.grid.getChildren().add(this.buildCard(p, curHash != null && curHash.equals(SkinsView.sha1Hex(p))));
            }
        }
        Path path = keep = this.selected != null && Files.isRegularFile(this.selected, new LinkOption[0]) ? this.selected : null;
        if (keep == null && !skins.isEmpty()) {
            keep = skins.get(0);
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
            tip = "先在「账号管理」里选中一个账号。";
        }
        this.setTip(tip);
        // 模型按钮 / 披风勾选按当前账号状态回显（切账号后不会停在上一个账号的选择）
        boolean slimNow = acc != null && acc.skinSlim != null ? acc.skinSlim : this.preview.isSlim();
        this.syncModelButtons(slimNow);
        if (this.capeBox != null) {
            boolean hasCape = this.capePath(acc) != null;
            this.capeBox.setText(hasCape ? "披风" : "披风（无）");
            this.capeBox.setSelected(hasCape);
            this.capeBox.setVisible(true);
            if (!hasCape) {
                this.preview.clearCape();
            }
        }
        // 正版账号的皮肤来自 Mojang，后台拉取一次，完成后自动刷新列表
        this.syncPremiumSkin();
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
        this.useBtn.setDisable(!has || isCurrent);
        this.useBtn.setText(isCurrent ? "当前使用中" : "使用这个皮肤");
        this.useBtn.setStyle((String)(isCurrent ? "-fx-background-color:" + Ui.BG_LIGHT + "; -fx-text-fill:" + Ui.TEXT_DIM + ";-fx-background-radius:6; -fx-padding:7 12;" : "-fx-background-color:#3FA9F5; -fx-text-fill:white;-fx-background-radius:6; -fx-padding:7 12; -fx-font-weight:bold; -fx-cursor:hand;"));
        this.delBtn.setDisable(!has);
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
            box.setStyle(this.borderStyle(cur, "#3FA9F555"));
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
        LauncherConfig.Account acc = this.currentAccount();
        if (acc == null) {
            Ui.error("未选择账号", "请先在「账号管理」里选中一个账号。");
            return;
        }
        if (acc.uuid == null || acc.uuid.isBlank()) {
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
            try (InputStream in = Files.newInputStream(p, new OpenOption[0]);){
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
