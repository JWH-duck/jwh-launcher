package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.game.GameInstaller;
import com.jwh.launcher.game.GameLauncher;
import com.jwh.launcher.game.LocalVersions;
import com.jwh.launcher.download.BbsmcMaps;
import com.jwh.launcher.download.IconCache;
import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.ModpackImporter;
import com.jwh.launcher.util.ModCheck;
import com.jwh.launcher.util.SkinServer;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.concurrent.Worker;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.shape.Rectangle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polygon;
import javafx.stage.Popup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 主页：本地版本列表 + 版本信息 + 启动/修复/删除。
 * Java 运行时与内存分配已移至「设置」页；游戏输出日志已移至「日志」页。
 */
public class HomeView {

    /** 「多人游戏」入口按钮：底色、圆角、投影与右侧绿色「启动游戏」完全一致，只是文案不同。 */
    private static final String MULTI_BTN_STYLE =
            "-fx-background-color:#7ED957; -fx-background-radius:8;"
                    + "-fx-font-size:16px; -fx-font-weight:bold; -fx-text-fill:white;"
                    + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.25), 5, 0, 0, 2);";
    private static final String MULTI_BTN_HOVER_STYLE =
            "-fx-background-color:#6FC74A; -fx-background-radius:8;"
                    + "-fx-font-size:16px; -fx-font-weight:bold; -fx-text-fill:white;"
                    + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.30), 6, 0, 0, 2);";

    private final LauncherApp app;
    private final ListView<LocalVersions.LocalVersion> versionList = new ListView<>();
    private final Button launchBtn = new Button("启动游戏");
    private final Label verTitle = new Label();
    private final Label verMeta = new Label();
    /** 「已安装版本」标题（无版本时切换为「缺少游戏版本」）。 */
    private final Label instTitle = new Label("已安装版本");
    /** 空列表占位提示（灰字小号，显示在白色版本面板内）。 */
    private final Label emptyHint = new Label("请前往下载中心安装游戏版本");
    /** 启动按钮下方显示「已选择启动的版本」；没有任何实例时显示「未安装游戏实例」。 */
    private static final String NO_INSTANCE_TEXT = "未安装游戏实例";
    private final Label launchVerLabel = new Label(NO_INSTANCE_TEXT);
    /** 启动按钮右侧实例切换箭头；整个控件向上弹出已安装版本列表。 */
    private StackPane launchArrow;
    /** 实例下拉弹层（懒创建，重复使用）。 */
    private Popup instancePopup;
    /** 下拉弹层根容器与该层里的自绘悬停气泡。 */
    private StackPane instanceMenuWrap;
    private Label instanceHint;

    /** 主页皮肤小人：与皮肤库同一个渲染器，皮肤同步「皮肤库正在使用」的那张。 */
    private SkinCanvas homeSkin;

    /** 加载器类型 → 头像（NeoForge=狐狸、Fabric=床、Quilt=拼布、Forge=火焰；原版=草方块）。 */
    private static final java.util.Map<String, Image> LOADER_ICONS = new java.util.HashMap<>();
    private static final Image VANILLA_ICON = loadIcon("/icons/loader_vanilla.png");

    static {
        LOADER_ICONS.put("NeoForge", loadIcon("/icons/loader_neoforge.png"));
        LOADER_ICONS.put("Fabric", loadIcon("/icons/loader_fabric.png"));
        LOADER_ICONS.put("Quilt", loadIcon("/icons/loader_quilt.png"));
        LOADER_ICONS.put("Forge", loadIcon("/icons/loader_forge.png"));
    }

    /** 从资源加载头像；失败时返回 null（调用方回退到原版图标）。加载时保持原始尺寸，缩放交给 ImageView（关闭平滑）。 */
    private static Image loadIcon(String path) {
        try (java.io.InputStream in = HomeView.class.getResourceAsStream(path)) {
            return in == null ? null : new Image(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** 版本对应头像：按加载器类型取，未知/原版用草方块。 */
    private static Image iconFor(LocalVersions.LocalVersion v) {
        if (v.loaderName() != null) {
            Image im = LOADER_ICONS.get(v.loaderName());
            if (im != null) return im;
        }
        return VANILLA_ICON;
    }

    public HomeView(LauncherApp app) {
        this.app = app;
    }

    /** 主页根节点（首次构建后缓存，避免每次切页都重建整套节点）。 */
    private VBox cachedRoot;

    /**
     * 迷你播放器：已移到窗口右上角（LauncherApp 顶栏，全页面共用一份）。
     * 这里只借 {@code app.musicBar()} 拿到同一个实例，用于进游戏暂停 / 出游戏续播。
     */
    private MusicBar musicBar() {
        return app.musicBar();
    }

    /** 「多人游戏」待生效的服务器地址：点按钮时填，launch() 里消费一次后清空。 */
    private String pendingQuickPlay;
    /** 上一次填过的服务器地址（只存在内存里，方便下次改个端口就启动）。 */
    private String lastServer = "";

    public VBox root() {
        if (cachedRoot != null) {
            refresh();
            return cachedRoot;
        }

        VBox body = new VBox();
        body.setPadding(new Insets(16));
        body.setStyle("-fx-background-color:" + Ui.BG + ";");
        // 必须显式要求撑满，否则父 StackPane 只按 pref 尺寸居中放置，启动键会浮到中间
        body.setMaxWidth(Double.MAX_VALUE);
        body.setMaxHeight(Double.MAX_VALUE);

        // ---- 左：版本列表 ----
        VBox left = new VBox(10);
        left.setPrefWidth(330);
        instTitle.setStyle("-fx-font-size:16px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        emptyHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");
        versionList.setPrefHeight(420);
        versionList.setPlaceholder(emptyHint);
        versionList.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";"
                + "-fx-background-radius:14; -fx-border-radius:14; -fx-padding:6;");
        versionList.setCellFactory(c -> new ListCell<>() {
            private final ImageView icon = new ImageView();
            private final Label name = new Label();
            private final HBox row = new HBox(6);

            {
                icon.setFitWidth(40);
                icon.setFitHeight(40);
                icon.setPreserveRatio(true);
                icon.setSmooth(false); // 像素风头像保持锐利
                name.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:14px;");
                row.setAlignment(Pos.CENTER_LEFT);
                row.getChildren().addAll(icon, name);
                // cell 本体透明，圆角卡片由 row 承担，选中/悬停时切换背景
                setStyle("-fx-background-color:transparent; -fx-padding:2 2;");
                selectedProperty().addListener((obs, o, n) -> applyRowStyle());
                hoverProperty().addListener((obs, o, n) -> applyRowStyle());
            }

            private void applyRowStyle() {
                String bg = "transparent";
                if (isSelected()) bg = "rgba(63,169,245,0.16)";
                else if (isHover()) bg = "rgba(0,0,0,0.05)";
                row.setStyle("-fx-background-color:" + bg + "; -fx-background-radius:10; -fx-padding:6 8;");
            }

            @Override
            protected void updateItem(LocalVersions.LocalVersion v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setGraphic(null);
                } else {
                    icon.setImage(iconFor(v));
                    name.setText(v.displayName());
                    setGraphic(row);
                    applyRowStyle();
                }
            }
        });
        versionList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null) {
                updateSelection(n);
            } else if (versionList.getItems().isEmpty()) {
                // 删光实例后的空状态：不能继续显示上一个版本的版本号
                showNoInstance();
            }
        });
        VBox.setVgrow(versionList, Priority.ALWAYS);
        javafx.scene.layout.FlowPane leftBtns = new javafx.scene.layout.FlowPane(8, 8);
        leftBtns.setMaxWidth(300);
        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle(auxBtnStyle());
        refreshBtn.setOnAction(e -> refresh());
        Button goDownload = new Button("去下载版本 / 加载器");
        goDownload.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;"
                + "-fx-background-radius:6; -fx-padding:7 12;");
        goDownload.setOnAction(e -> app.showDownload());
        leftBtns.getChildren().addAll(refreshBtn, goDownload);
        left.getChildren().addAll(instTitle, versionList, leftBtns);

        // ---- 右：版本信息卡 + 启动 ----
        VBox right = new VBox(12);
        right.setPrefWidth(430);
        HBox.setHgrow(right, Priority.ALWAYS);

        // 版本信息卡
        VBox infoCard = new VBox(4);
        infoCard.setPadding(new Insets(14));
        infoCard.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-background-radius:14; -fx-border-radius:14;");
        verTitle.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:21px; -fx-font-weight:bold;");
        verMeta.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");
        verMeta.setWrapText(true);
        infoCard.getChildren().addAll(verTitle, verMeta);

        // 启动按钮（主操作）：与右侧箭头共用一块蓝色底座
        // 背景/边框全部清空，避免 Modena 主题给按钮画一圈圆角描边、把底座切成上下两块
        launchBtn.setBackground(javafx.scene.layout.Background.EMPTY);
        launchBtn.setBorder(javafx.scene.layout.Border.EMPTY);
        // 去掉 "button" 样式类，避免 Modena 主题的默认底色/hover 底色把底座切成两块
        launchBtn.getStyleClass().clear();
        launchBtn.setStyle("-fx-background-color:transparent; -fx-background-insets:0;"
                + "-fx-text-fill:white; -fx-font-size:16px;"
                + "-fx-font-weight:bold; -fx-pref-height:38; -fx-pref-width:112;"
                + "-fx-alignment:center; -fx-padding:0;"
                + "-fx-cursor:hand; -fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
        launchBtn.setOnAction(e -> launch());

        // 辅助按钮：均分一行
        Button repairBtn = new Button("修复文件");
        repairBtn.setStyle(auxBtnStyle());
        repairBtn.setMaxWidth(Double.MAX_VALUE);
        repairBtn.setOnAction(e -> repairVersion());
        Button deleteBtn = new Button("删除版本");
        deleteBtn.setStyle(auxBtnStyle() + "-fx-text-fill:#d64545;");
        deleteBtn.setMaxWidth(Double.MAX_VALUE);
        deleteBtn.setOnAction(e -> deleteVersion());
        Button instBtn = new Button("实例管理");
        instBtn.setStyle(auxBtnStyle());
        instBtn.setMaxWidth(Double.MAX_VALUE);
        instBtn.setOnAction(e -> {
            LocalVersions.LocalVersion sel = versionList.getSelectionModel().getSelectedItem();
            if (sel == null) {
                Ui.error("未选择版本", "请先在左侧选择一个已安装的版本，再打开实例管理。");
                return;
            }
            this.openInstanceManager(sel);
        });
        Button settingBtn = new Button("Java / 内存");
        settingBtn.setStyle(auxBtnStyle());
        settingBtn.setMaxWidth(Double.MAX_VALUE);
        settingBtn.setOnAction(e -> app.showSettings(true));
        HBox auxRow = new HBox(8);
        auxRow.setAlignment(Pos.CENTER);
        for (Button b : new Button[]{repairBtn, deleteBtn, instBtn, settingBtn}) {
            HBox.setHgrow(b, Priority.ALWAYS);
            auxRow.getChildren().add(b);
        }

        // 底部弹性占位（日志已移至左侧导航「日志」页）
        VBox spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox launchGroup = this.buildLaunchGroup();
        HBox launchRow = new HBox(launchGroup);
        launchRow.setAlignment(Pos.BOTTOM_RIGHT);
        launchRow.setFillHeight(false); // 不让外层把启动按键组纵向拉伸

        // 极简主页：面板（版本列表卡、版本信息卡、辅助按钮行）全部不上场景。
        // 上：minecraft.wiki 两张 game drops 卡（实时网页裁切）
        // 下：左侧弹性 + 「你知道吗」竖条（宽 = 单卡 2/5，底边停在启动键上方） + 启动按键组
        HBox wikiRow = new HBox(this.buildWikiCard(body));
        wikiRow.setAlignment(Pos.TOP_LEFT);
        wikiRow.setFillHeight(false);
        // 锁住卡片自身的 2:1 高度，别让下面那一行把 wikiRow 压扁
        wikiRow.setMinHeight(Region.USE_PREF_SIZE);
        wikiRow.setMaxHeight(Region.USE_PREF_SIZE);

        Region dyk = this.buildDidYouKnow();
        // 同卡片：宽度锁成 USE_PREF_SIZE 会和 prefWidth 绑定打转，放大后缩不回来
        dyk.setMinWidth(0);
        dyk.setMaxWidth(Double.MAX_VALUE);
        // 不放开 maxHeight，VBox.setVgrow 就不生效，面板撑不到启动键上方
        dyk.setMaxHeight(Double.MAX_VALUE);
        // minHeight 归零：否则这一批事实行数一变，面板就跟着变长变短
        dyk.setMinHeight(0);
        // 宽 = 单卡宽的 52%（字号放大后太窄会把每条事实折成三行，这里放宽一些）
        dyk.prefWidthProperty().bind(body.widthProperty().subtract(32).multiply(0.26));
        VBox.setVgrow(dyk, Priority.ALWAYS);

        VBox dykCol = new VBox(dyk);
        dykCol.setFillWidth(true);
        dykCol.setMinWidth(Region.USE_PREF_SIZE);
        // VBox 的 maxHeight 默认按内容算，HBox 的 fillHeight 就拉不动它，
        // 结果整列停在「内容高度」，面板下方留一片空白。放开才能撑到底。
        dykCol.setMaxHeight(Double.MAX_VALUE);
        dykCol.setMinHeight(0);
        // 「你知道吗」下方这块空白放迷你音乐播放器：高度与启动按键组一致，
        // 上面留 8px 间隙，底边正好和「启动游戏」齐平（占位与原 reserveUnder 等价）。
        // 「你知道吗」下方不再挂音乐条：播放器已移到窗口右上角（LauncherApp 顶栏，
        // 全页面共用一份）。空出来的这块高度放「多人游戏」入口，高度仍绑启动按键组，
        // 「你知道吗」面板尺寸完全照旧。
        Button multiplayerBtn = new Button("多人游戏");
        // 和「启动游戏」按键组一模一样大：宽高都直接绑到 launchGroup，
        // 两块按钮左右并排时上下沿完全齐平。
        multiplayerBtn.prefWidthProperty().bind(launchGroup.widthProperty());
        multiplayerBtn.minWidthProperty().bind(launchGroup.widthProperty());
        multiplayerBtn.prefHeightProperty().bind(launchGroup.heightProperty());
        multiplayerBtn.minHeightProperty().bind(launchGroup.heightProperty());
        multiplayerBtn.setStyle(MULTI_BTN_STYLE);
        multiplayerBtn.setCursor(Cursor.HAND);
        multiplayerBtn.setFocusTraversable(false);
        multiplayerBtn.setOnMouseEntered(e -> multiplayerBtn.setStyle(MULTI_BTN_HOVER_STYLE));
        multiplayerBtn.setOnMouseExited(e -> multiplayerBtn.setStyle(MULTI_BTN_STYLE));
        multiplayerBtn.setOnAction(e -> multiplayer());

        HBox multiRow = new HBox(multiplayerBtn);
        multiRow.setAlignment(Pos.CENTER_RIGHT);
        multiRow.setMinHeight(0);
        multiRow.prefHeightProperty().bind(launchGroup.heightProperty());
        multiRow.maxHeightProperty().bind(launchGroup.heightProperty());
        VBox.setMargin(multiRow, new Insets(8, 0, 0, 0));
        // 和「启动游戏」拉开间距：整列宽度不变（「你知道吗」尺寸不受影响），
        // 只把按钮自身往左平移，右边自然空出这段缝。
        multiplayerBtn.setTranslateX(-40.0);
        dykCol.getChildren().add(multiRow);

        VBox mapPanel = this.buildMapPanel();
        HBox.setHgrow(mapPanel, Priority.ALWAYS);

        // 皮肤小人：站在「启动游戏」正上方、「你知道吗」右侧的空区里。
        // 复用皮肤库同一个 SkinCanvas，所以动画、视角、光影完全一致。
        // 画布宽度比站位列宽出 80px：挥手时手臂是往左上斜举的，
        // 画布只有 150 宽时手臂会在画布左边缘被裁掉，看上去就像被「你知道吗」压住。
        // 加宽之后左侧多出 60px 的绘制空间，人物本体位置不变（下面 translateX 已补偿）。
        this.homeSkin = new SkinCanvas(240, 232);
        // 正对屏幕：皮肤库预览默认是 yaw 20 的侧身，主页这里要正面
        this.homeSkin.setYaw(0.0);
        this.homeSkin.setPitch(3.0);
        this.homeSkin.setCursor(Cursor.HAND);
        this.homeSkin.setOnMouseClicked(e -> {
            this.homeSkin.clickPulse();
            e.consume();
        });
        // 顶部弹性占位把小人压到底部，脚正好踩在「启动游戏」上方
        Region skinTop = new Region();
        VBox.setVgrow(skinTop, Priority.ALWAYS);
        VBox skinCol = new VBox(skinTop, this.homeSkin);
        skinCol.setAlignment(Pos.BOTTOM_CENTER);
        skinCol.setMaxHeight(Double.MAX_VALUE);
        skinCol.setMinHeight(0);
        // 站位微调：整体往右挪一点，别和启动键左边缘完全对齐（视觉上偏左）。
        // 画布加宽后中心会自己往右漂，这里的数值已把那次漂移抵掉，人物落点和加宽前一致。
        this.homeSkin.setTranslateX(20.0);
        // 宽度跟启动按键组绑死，列宽就和按键一样，竖条不会被推着移位
        skinCol.minWidthProperty().bind(launchGroup.widthProperty());
        skinCol.prefWidthProperty().bind(launchGroup.widthProperty());
        skinCol.maxWidthProperty().bind(launchGroup.widthProperty());
        skinCol.sceneProperty().addListener((o, wasNull, nowScene) -> {
            if (nowScene != null) {
                // 鼠标在窗口里游走时，小人脑袋跟着转，看起来像是盯着光标
                nowScene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, this::aimHomeSkinAt);
                nowScene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_EXITED,
                        ev -> { if (this.homeSkin != null) this.homeSkin.clearHeadAim(); });
                this.homeSkin.startAnimation();
            } else {
                this.homeSkin.stopAnimation();
            }
        });
        // 小人 + 启动按键合成一列：竖条右侧就是这一列，位置和以前完全一致
        VBox rightCol = new VBox(skinCol, launchRow);
        rightCol.setMinWidth(Region.USE_PREF_SIZE);
        VBox.setVgrow(skinCol, Priority.ALWAYS);

        HBox bottomRow = new HBox(mapPanel, dykCol, rightCol);
        // MC百科入口已经进了滚动区（随内容滚），面板只跟维基卡留一点缝
        HBox.setMargin(mapPanel, new Insets(8, 0, 0, 0));
        HBox.setMargin(rightCol, new Insets(0, 8, 0, 0));
        bottomRow.setAlignment(Pos.TOP_LEFT);
        // maxHeight 不放开，VBox.setVgrow 只会撑到「内容高度」，面板就停在半空、下面留一片空白
        bottomRow.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(bottomRow, Priority.ALWAYS);

        body.getChildren().setAll(wikiRow, bottomRow);

        refresh();
        cachedRoot = body;
        return body;
    }

    /**
     * 官网首页 DYK 区块的事实池。官网点「More facts!」是在原地淡出换新的一批
     * （见 #mp-dyk-fact 的 loading/opacity 过渡），不会跳页；这里同样原地轮换。
     * <p>
     * 数据来源：首页当前那一批三条 + 各条目的「你知道吗（Trivia）」段落原文，
     * 共 11 批 33 条，逐条翻译。官网首页 DYK 的其余事实由前端 JS 另行请求，
     * 静态 HTML 里只有当前这一组，服务端接口亦不允许抓取，故不采用在线拉取，
     * 而是把事实固化进包里（离线可用、点多少次都不会卡在同样的三页）。
     */
    private static final String[][] DYK_GROUPS = {
            {
                    "……骆驼能从栅栏和墙上走过去？",
                    "……下界砖栅栏无法与木制栅栏连接，但可以与木制栅栏门连接？",
                    "……在树苗最初被加入到前古典时期，诺奇曾试图把它隐藏起来，因为他觉得它“很难看”？"
            },
            {
                    "……指南针总共用了 32 帧动画？",
                    "……主菜单上有一句永远不会显示的闪烁标语？",
                    "……苦力怕最初只有近战攻击，只有在被杀时才会爆炸？"
            },
            {
                    "……铁傀儡会把罂粟送给村庄里的村民和幼年村民，这个动作是致敬宫崎骏《天空之城》里的古代机器人？",
                    "……铁傀儡手里的花并不是捡来的，而是它们自己凭空“生成”在手上的？",
                    "……用命名牌给生物命名后，它就不会因为你走远而消失？"
            },
            {
                    "……苦力怕身上用的其实是橡树叶的纹理，它靠和树叶同款来伪装自己？",
                    "……诺奇说过苦力怕摸起来“像干树叶一样脆”，还说他一直以为它们是用树叶做的？",
                    "……关于苦力怕为什么会爆炸，Dinnerbone 的解释是“它们太紧张了，抖得厉害，就像搓两根木棍生火”？"
            },
            {
                    "……Dinnerbone 建议把“一群末影人”的量词定为 a haunting of Endermen？",
                    "……现实里刚挖出来的钻石原石是浑浊的，只有最纯净的那些才会被切磨成透明宝石？",
                    "……驯服的狼尾巴翘起或下垂的角度，直接对应它当前的血量百分比？"
            },
            {
                    "……你手里拿着肉或骨头走到离狼 8 格以内，它会歪着头“讨食” 2 到 4 秒？",
                    "……算上毛色、项圈颜色、狼铠染色和坐姿，狼在视觉上约有 1.64 万亿种不同样子？",
                    "……床的水平 8 格、垂直 5 格范围内有怪物时，游戏会拒绝你睡觉并提示“附近有怪物”？"
            },
            {
                    "……村民可以在下界和末地正常睡觉，床也不会爆炸；会炸的只有玩家自己躺上去？",
                    "……床在下界和末地会爆炸，是因为诺奇觉得这是实现“各维度独立重生点”最省事的办法？",
                    "……被床炸死时，死亡信息写的是“被【有意为之的游戏设计】杀死”？"
            },
            {
                    "……成年鸡每 5 到 10 分钟就会下一个蛋，是主世界唯一不靠繁殖也能补充数量的生物？",
                    "……把鸡蛋砸向地面，有 1/8 的概率孵出一只小鸡？",
                    "……那 1/8 命中之后还有 1/32 的概率额外再冒出三只，也就是单颗蛋出四只鸡的总概率是 1/256？"
            },
            {
                    "……当过“鸡骑士”坐骑的鸡，从此就不再下蛋了？",
                    "……鸡下落的速度比其他生物都慢，翅膀一路扇个不停，被推下悬崖摔死了尸体照样慢慢飘？",
                    "……恶魂的叫声其实来自 C418 养的宠物猫——猫睡梦中被吵醒时发出的声音？"
            },
            {
                    "……ghast 在英语里是个古词，意思是“可怕的”，把它当作怪物名的是洛夫克拉夫特？",
                    "……恶魂会中毒，却对高温完全免疫，路过熔岩瀑布时还会慢下来享受一下？",
                    "……僵尸的音效是 C418 得了流感时，对着麦克风从喉咙里咕噜出来的？"
            },
            {
                    "……僵尸会掉羽毛，是因为当年作者不知道它们该掉什么，而那时候游戏里还没有鸡？",
                    "……僵尸长得像 Steve 只是巧合，官方从未暗示过两者有什么渊源？",
                    "……床爆炸的威力是 5，比 TNT 的 4 更强，但弱于充能苦力怕和末地水晶的 6？"
            }
    };

    /**
     * 「你知道吗…」面板：宽度是单张 game drops 卡的 2/5，高度撑到启动按键组上方。
     * 「更多事实!」在面板内原地换一批事实，不跳转外部页面。
     */
    private Region buildDidYouKnow() {
        Label title = new Label("你知道吗…");
        title.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:#202122;");
        title.setWrapText(true);

        VBox facts = new VBox(3);
        final int[] sel = {0};
        Runnable render = () -> {
            facts.getChildren().clear();
            for (String s : DYK_GROUPS[sel[0]]) {
                facts.getChildren().add(this.dykFact(s));
            }
        };
        render.run();

        Button more = new Button("更多事实!");
        more.setMaxWidth(Double.MAX_VALUE);
        more.setMinHeight(Region.USE_PREF_SIZE);
        more.setStyle("-fx-background-color:rgba(233,233,233,0.75); -fx-background-radius:2; -fx-border-radius:2;"
                + "-fx-border-color:#b9b9b9; -fx-border-width:1;"
                + "-fx-font-size:12.5px; -fx-font-weight:bold; -fx-text-fill:#202122; -fx-padding:5 0 5 0;");
        // 事实区放进可滚动容器：面板高度就只由布局决定，不会因为这一批多一行、下一批少一行而跳动
        ScrollPane scroller = new ScrollPane(facts);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setPannable(true);
        // min 归零，否则该组内容最矮也顶着一个最小高度，整块面板照样会变长变短
        scroller.setMinHeight(0);
        scroller.setPrefHeight(0);
        scroller.setStyle("-fx-background-color:transparent; -fx-background:transparent;"
                + "-fx-padding:0; -fx-background-insets:0; -fx-border-width:0;");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        // 与官网一致：原地淡出 → 换内容 → 淡入，全程不离开当前窗口
        more.setOnAction(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(120), facts);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(ev -> {
                sel[0] = (sel[0] + 1) % DYK_GROUPS.length;
                render.run();
                scroller.setVvalue(0.0);
                FadeTransition fadeIn = new FadeTransition(Duration.millis(160), facts);
                fadeIn.setFromValue(0.0);
                fadeIn.setToValue(1.0);
                fadeIn.play();
            });
            fadeOut.play();
        });

        VBox box = new VBox(4, title, scroller, more);
        box.setMinHeight(0);
        box.setPadding(new Insets(6, 7, 6, 7));
        box.setStyle("-fx-background-color:rgba(251,251,251,0.75); -fx-background-radius:3; -fx-border-radius:3;"
                + "-fx-border-color:#c9c9c9; -fx-border-width:1;");
        box.setFillWidth(true);
        return box;
    }

    /** 窄栏里必须用 TextFlow：Label 的 prefWidth 会按单行算，容器一窄就被省略号截断。 */
    private TextFlow dykFact(String text) {
        Text t = new Text("• " + text);
        t.setFont(Font.font(12.5));
        t.setFill(Color.web("#202122"));
        TextFlow flow = new TextFlow(t);
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.setLineSpacing(1.5);
        return flow;
    }

    /**
     * 用版式可靠的站点做底，卡片文本在本地逐条换成中文（见 CROP_SCRIPT 末尾的 KEEP 词典）。
     * 不直接换中文站：那边卡片版式不同，词条会竖排、平台版本还会串进卡片。
     */
    private static final String WIKI_HOME = "https://minecraft.wiki/";

    /**
     * 把 minecraft.wiki 首页的「两张 game drops 卡」搬到主页。
     * <p>
     * 以前是用 WebView 直接加载官网再裁切，但 WebKit 自己的网络栈请求特征会被 Cloudflare
     * 拦掉（表现为整块空白，或者永远停在「正在加载 minecraft.wiki …」），而同一时刻 Java 的
     * HttpClient 却能稳定拿到 200。所以现在改成：Java 抓首页 HTML → 解析两张卡 →
     * 下载图片（磁盘缓存）→ 用原生控件拼出来。好处是不依赖 WebView，网络抖动时还能显示
     * 上一次缓存下来的图，不会再出现「一直失败」。
     */
    private static final class WikiCardData {
        String imageUrl = "";
        String title = "";
        String link = "";
        String tagText = "";
        boolean tagUpcoming;
        final List<String> linkIcons = new ArrayList<>();
        final List<String> linkTexts = new ArrayList<>();
    }

    /** 卡片文案汉化表：官网是英文，本地显示中文；没命中的保留英文。 */
    private static final Map<String, String> WIKI_CN = new HashMap<>();
    static {
        WIKI_CN.put("Wilderness Bound", "荒野之旅");
        WIKI_CN.put("Latest", "最新");
        WIKI_CN.put("Fourth Drop 2026", "2026年第四次更新");
        WIKI_CN.put("Upcoming", "即将推出");
        WIKI_CN.put("Dappled Forest", "斑驳森林");
        WIKI_CN.put("Abandoned Camp", "废弃营地");
        WIKI_CN.put("Straw Bed", "稻草床");
        WIKI_CN.put("Cushion", "坐垫");
        WIKI_CN.put("Ice Caves", "冰洞");
        WIKI_CN.put("Frostbite", "冻伤");
        WIKI_CN.put("Ice Ball", "冰球");
        WIKI_CN.put("Icicle", "冰柱");
        WIKI_CN.put("Ice Crystal", "冰晶");
    }

    private static String wikiCn(String en) {
        if (en == null) return "";
        String v = WIKI_CN.get(en.trim());
        return v != null ? v : en.trim();
    }

    /** 图片缓存目录。放 APPDATA 下，重启后还在，断网也能出图。 */
    private static Path wikiCacheDir() {
        String appdata = System.getenv("APPDATA");
        Path base = (appdata != null && !appdata.isEmpty())
                ? Path.of(appdata, "JWH-Launcher", "cache", "wiki")
                : Path.of(System.getProperty("java.io.tmpdir"), "jwh-wiki-cache");
        try { Files.createDirectories(base); } catch (Exception ignored) { }
        return base;
    }

    private static String wikiCacheName(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            String clean = url;
            int q = clean.indexOf('?');
            if (q >= 0) clean = clean.substring(0, q);
            int dot = clean.lastIndexOf('.');
            String ext = (dot > clean.lastIndexOf('/')) ? clean.substring(dot) : ".png";
            if (ext.length() > 5) ext = ".png";
            return sb + ext;
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode()) + ".png";
        }
    }

    /**
     * 上次成功抓到的两张卡（官网原文），写在 cache/wiki/cards.properties。
     * 启动时先按它渲染，再去拉官网：官网换了内容 → 这次抓到就覆盖它，
     * 于是下一次启动开屏直接就是新内容；官网挂了 → 至少还是上次的真实内容。
     */
    private static Path wikiCardsFile() {
        return wikiCacheDir().resolve("cards.properties");
    }

    private static void saveWikiCards(List<WikiCardData> cards) {
        if (cards == null || cards.size() < 2) return;
        try {
            Properties p = new Properties();
            for (int i = 0; i < 2; i++) {
                WikiCardData d = cards.get(i);
                String k = "card" + i + ".";
                p.setProperty(k + "title", d.title == null ? "" : d.title);
                p.setProperty(k + "image", d.imageUrl == null ? "" : d.imageUrl);
                p.setProperty(k + "link", d.link == null ? "" : d.link);
                p.setProperty(k + "tag", d.tagText == null ? "" : d.tagText);
                p.setProperty(k + "upcoming", d.tagUpcoming ? "1" : "0");
                p.setProperty(k + "icons", String.join("|", d.linkIcons));
                p.setProperty(k + "texts", String.join("|", d.linkTexts));
            }
            p.setProperty("savedAt", String.valueOf(System.currentTimeMillis()));
            try (var w = Files.newBufferedWriter(wikiCardsFile(), StandardCharsets.UTF_8)) {
                p.store(w, "minecraft.wiki 主页 game drops 卡片（上次成功抓取的结果）");
            }
        } catch (Exception e) {
            System.err.println("[wiki] 缓存写入失败: " + e);
        }
    }

    private static List<WikiCardData> loadWikiCards() {
        Path f = wikiCardsFile();
        if (!Files.isRegularFile(f)) return null;
        try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            List<WikiCardData> out = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String k = "card" + i + ".";
                String title = p.getProperty(k + "title", "").trim();
                String image = p.getProperty(k + "image", "").trim();
                if (title.isEmpty() || image.isEmpty()) return null;
                WikiCardData d = new WikiCardData();
                d.title = title;
                d.imageUrl = image;
                d.link = p.getProperty(k + "link", "").trim();
                d.tagText = p.getProperty(k + "tag", "").trim();
                d.tagUpcoming = "1".equals(p.getProperty(k + "upcoming", "0"));
                for (String s : splitList(p.getProperty(k + "icons", ""))) d.linkIcons.add(s);
                for (String s : splitList(p.getProperty(k + "texts", ""))) d.linkTexts.add(s);
                out.add(d);
            }
            System.out.println("[wiki] 使用上次缓存 (" + p.getProperty("savedAt", "?") + ")");
            return out.size() >= 2 ? out : null;
        } catch (Exception e) {
            System.err.println("[wiki] 缓存读取失败: " + e);
            return null;
        }
    }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split("\\|")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    private static byte[] httpGetBytes(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    /** 用 Java 的 HttpClient 把 wiki 首页 HTML 抓下来（WebView 直连会被 Cloudflare 拦）。 */
    private static String fetchWikiHtml() throws IOException, InterruptedException {
        byte[] bytes = httpGetBytes(WIKI_HOME);
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (body.length() < 5000) throw new IOException("返回内容异常（长度 " + body.length() + "）");
        return body;
    }

    private static String wikiAbs(String path) {
        if (path.startsWith("http")) return path;
        return "https://minecraft.wiki" + path;
    }

    private static String xmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#039;", "'").replace("&nbsp;", " ");
    }

    /** 从官网首页 HTML 里解析出两张卡：大图、标题、跳转链接、角标、底部词条。 */
    private static List<WikiCardData> parseWikiCards(String html) {
        List<WikiCardData> out = new ArrayList<>();
        if (html == null) return out;
        String[] chunks = html.split("mp-section mp-highlight");
        for (int i = 1; i < chunks.length && out.size() < 2; i++) {
            String s = chunks[i];
            // 最后一张卡之后紧跟着首页的「平台版本」等区块，不截断就会把它们里的
            // sprite-text 也当成词条解析进来（表现为出现 Chaos G… / Tiny Take… 之类）。
            int cut = s.indexOf("mp-platform-wrapper");
            if (cut > 0) s = s.substring(0, cut);
            WikiCardData d = new WikiCardData();

            Matcher mImg = Pattern.compile("src=\"(/images/thumb/[^\"]+)\"").matcher(s);
            if (mImg.find()) d.imageUrl = wikiAbs(mImg.group(1));

            Matcher mTitle = Pattern.compile(
                    "class=\"mp-highlight-title\"><a href=\"([^\"]+)\"[^>]*>([^<]+)</a>").matcher(s);
            if (mTitle.find()) {
                d.link = wikiAbs(mTitle.group(1));
                d.title = xmlUnescape(mTitle.group(2)).trim();
            }

            Matcher mTag = Pattern.compile("class=\"mp-highlight-tag([^\"]*)\">([^<]+)<").matcher(s);
            if (mTag.find()) {
                d.tagUpcoming = mTag.group(1).contains("upcoming");
                d.tagText = xmlUnescape(mTag.group(2)).trim();
            }

            Matcher mLink = Pattern.compile(
                    "src=\"(/images/[^\"]+?)\"[^>]*width=\"16\"[^>]*>.*?<span class=\"sprite-text\">([^<]+)</span>").matcher(s);
            while (mLink.find() && d.linkTexts.size() < 8) {
                d.linkIcons.add(wikiAbs(mLink.group(1)));
                d.linkTexts.add(xmlUnescape(mLink.group(2)).trim());
            }
            if (d.linkTexts.isEmpty()) {
                Matcher mTxt = Pattern.compile("<span class=\"sprite-text\">([^<]+)</span>").matcher(s);
                while (mTxt.find() && d.linkTexts.size() < 8) {
                    d.linkTexts.add(xmlUnescape(mTxt.group(1)).trim());
                }
            }
            if (!d.title.isEmpty() && !d.imageUrl.isEmpty()) out.add(d);
        }
        return out;
    }

    /** 官网数据拿不到时的内置内容（与当前官网一致），保证卡片任何时候都不是空白。 */
    private static List<WikiCardData> defaultWikiCards() {
        List<WikiCardData> list = new ArrayList<>();

        WikiCardData a = new WikiCardData();
        a.imageUrl = "https://minecraft.wiki/images/thumb/Wilderness_Bound_main_page_light.png/640px-Wilderness_Bound_main_page_light.png";
        a.title = "Wilderness Bound";
        a.link = "https://minecraft.wiki/w/Wilderness_Bound";
        a.tagText = "Latest";
        a.linkTexts.add("Dappled Forest");
        a.linkTexts.add("Abandoned Camp");
        a.linkTexts.add("Straw Bed");
        a.linkTexts.add("Cushion");
        a.linkIcons.add("https://minecraft.wiki/images/BiomeSprite_dappled-forest.png");
        a.linkIcons.add("https://minecraft.wiki/images/EnvSprite_abandoned-camp.png");
        a.linkIcons.add("https://minecraft.wiki/images/BlockSprite_straw-bed.png");
        a.linkIcons.add("https://minecraft.wiki/images/thumb/ItemSprite_white-cushion.png/16px-ItemSprite_white-cushion.png");
        list.add(a);

        WikiCardData b = new WikiCardData();
        b.imageUrl = "https://minecraft.wiki/images/thumb/Minwi_Mainpage_Highlight_-_Fourth_Drop_2026_Light.png/640px-Minwi_Mainpage_Highlight_-_Fourth_Drop_2026_Light.png";
        b.title = "Fourth Drop 2026";
        b.link = "https://minecraft.wiki/w/Fourth_Drop_2026";
        b.tagText = "Upcoming";
        b.tagUpcoming = true;
        b.linkTexts.add("Ice Caves");
        b.linkTexts.add("Frostbite");
        b.linkTexts.add("Ice Ball");
        b.linkTexts.add("Icicle");
        b.linkTexts.add("Ice Crystal");
        b.linkIcons.add("https://minecraft.wiki/images/BiomeSprite_ice-caves.png");
        b.linkIcons.add("https://minecraft.wiki/images/EntitySprite_frostbite.png");
        b.linkIcons.add("https://minecraft.wiki/images/ItemSprite_ice-ball.png");
        b.linkIcons.add("https://minecraft.wiki/images/Invicon_Icicle.png");
        b.linkIcons.add("https://minecraft.wiki/images/BlockSprite_ice-crystal.png");
        list.add(b);

        return list;
    }

    /** 读缓存图；没有缓存就读打包进来的兜底图（保证首次启动也能看到画面）。 */
    private static Image cachedCardImage(String url, int index) {
        try {
            Path f = wikiCacheDir().resolve(wikiCacheName(url));
            if (Files.isRegularFile(f)) {
                Image img = new Image(f.toUri().toString(), false);
                if (!img.isError() && img.getWidth() > 0) return img;
            }
        } catch (Exception ignored) { }
        try (var in = HomeView.class.getResourceAsStream("/wiki_card" + (index + 1) + ".png")) {
            if (in != null) {
                Image img = new Image(in);
                if (!img.isError() && img.getWidth() > 0) return img;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static Image cachedSmallImage(String url) {
        try {
            Path f = wikiCacheDir().resolve(wikiCacheName(url));
            if (Files.isRegularFile(f)) {
                Image img = new Image(f.toUri().toString(), false);
                if (!img.isError() && img.getWidth() > 0) return img;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 把 16:9 的原图裁成 2:1（卡片比例），居中裁，别把主体切歪。 */
    private static void cropImageTo2by1(ImageView iv, Image img) {
        if (img == null || img.getWidth() < 1 || img.getHeight() < 1) return;
        double w = img.getWidth();
        double h = img.getHeight();
        double want = w / 2.0;
        if (h > want) {
            iv.setViewport(new Rectangle2D(0, (h - want) / 2.0, w, want));
        } else {
            iv.setViewport(null);
        }
    }

    /** 后台下载一张图并写缓存；成功后在 UI 线程替换。失败就保持现有图。 */
    private static void refreshImageAsync(ImageView target, String url) {
        if (url == null || url.isEmpty()) return;
        CompletableFuture.supplyAsync(() -> {
            try { return httpGetBytes(url); } catch (Exception e) { return null; }
        }).thenAccept(bytes -> {
            if (bytes == null || bytes.length < 200) return;
            try {
                Files.write(wikiCacheDir().resolve(wikiCacheName(url)), bytes);
            } catch (Exception ignored) { }
            Platform.runLater(() -> {
                try {
                    Image img = new Image(new java.io.ByteArrayInputStream(bytes));
                    if (!img.isError() && img.getWidth() > 0) {
                        target.setImage(img);
                        cropImageTo2by1(target, img);
                    }
                } catch (Exception ignored) { }
            });
        });
    }

    /** 一张卡：背景图 + 左上标题 + 右上角标 + 底部词条条。 */
    private StackPane buildOneWikiCard(WikiCardData d, int index) {
        StackPane pane = new StackPane();
        // 关键：别让内容的 pref 宽度参与分配。底部词条条是 HBox，pref 宽度等于所有词条之和，
        // 一旦让它算进卡片宽度，两张卡就会按内容抢地方，词条还会溢到隔壁卡上。
        // 这里把 pref/min 压成 0，宽度完全由外层 HBox 均分。
        pane.setPrefWidth(0);
        pane.setMinWidth(0);
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(pane, Priority.ALWAYS);
        pane.setStyle("-fx-background-color:#c9c9c9;");

        // 内容再宽也裁在本卡内
        Rectangle paneClip = new Rectangle();
        paneClip.widthProperty().bind(pane.widthProperty());
        paneClip.heightProperty().bind(pane.heightProperty());
        pane.setClip(paneClip);

        ImageView bg = new ImageView();
        bg.setPreserveRatio(true);
        bg.setSmooth(false);
        bg.fitWidthProperty().bind(pane.widthProperty());
        Image cached = cachedCardImage(d.imageUrl, index);
        if (cached != null) {
            bg.setImage(cached);
            cropImageTo2by1(bg, cached);
        }
        refreshImageAsync(bg, d.imageUrl);
        pane.getChildren().add(bg);

        Label title = new Label(wikiCn(d.title));
        title.setStyle("-fx-text-fill:white; -fx-font-size:19px; -fx-font-weight:bold;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.85), 7, 0.6, 0, 1);");
        StackPane.setAlignment(title, Pos.TOP_LEFT);
        StackPane.setMargin(title, new Insets(10, 0, 0, 16));
        pane.getChildren().add(title);

        if (!d.tagText.isEmpty()) {
            Label tag = new Label(wikiCn(d.tagText));
            tag.setStyle("-fx-background-color:" + (d.tagUpcoming ? "#8a63d2" : "#3fa34d") + ";"
                    + "-fx-text-fill:white; -fx-font-size:11px; -fx-font-weight:bold;"
                    + "-fx-padding:3 9 3 9; -fx-background-radius:3;");
            StackPane.setAlignment(tag, Pos.TOP_RIGHT);
            StackPane.setMargin(tag, new Insets(10, 12, 0, 0));
            pane.getChildren().add(tag);
        }

        if (!d.linkTexts.isEmpty()) {
            HBox links = new HBox(6);
            links.setAlignment(Pos.CENTER_LEFT);
            links.setPadding(new Insets(4, 12, 4, 12));
            links.setMaxWidth(Double.MAX_VALUE);
            links.setMinWidth(0);
            links.setStyle("-fx-background-color:rgba(0,0,0,0.42);");
            for (int i = 0; i < d.linkTexts.size(); i++) {
                HBox item = new HBox(4);
                item.setAlignment(Pos.CENTER_LEFT);
                ImageView icon = new ImageView();
                icon.setFitWidth(14);
                icon.setFitHeight(14);
                icon.setSmooth(false);
                String iconUrl = (i < d.linkIcons.size()) ? d.linkIcons.get(i) : null;
                if (iconUrl != null) {
                    Image ic = cachedSmallImage(iconUrl);
                    if (ic != null) icon.setImage(ic);
                    else refreshImageAsync(icon, iconUrl);
                }
                Label txt = new Label(wikiCn(d.linkTexts.get(i)));
                txt.setStyle("-fx-text-fill:white; -fx-font-size:10px;");
                item.getChildren().addAll(icon, txt);
                links.getChildren().add(item);
            }
            StackPane.setAlignment(links, Pos.BOTTOM_CENTER);
            // 保持自然高度，否则 HBox 会被 StackPane 拉成整卡高度、背景色糊满整张图
            links.setMaxHeight(Region.USE_PREF_SIZE);
            pane.getChildren().add(links);
        }

        if (d.link != null && !d.link.isEmpty()) {
            pane.setCursor(Cursor.HAND);
            pane.setOnMouseClicked(e -> {
                this.app.openUrl(d.link);
                e.consume();
            });
        }
        return pane;
    }

    private HBox buildWikiRow(List<WikiCardData> data) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setFillHeight(true);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setMaxHeight(Double.MAX_VALUE);
        for (int i = 0; i < data.size() && i < 2; i++) {
            row.getChildren().add(buildOneWikiCard(data.get(i), i));
        }
        return row;
    }

    private StackPane buildWikiCard(Region host) {
        StackPane content = new StackPane();
        // 开屏先用「上次成功抓到的官网内容」，没有才退回内置兜底
        List<WikiCardData> cached = loadWikiCards();
        content.getChildren().setAll(buildWikiRow((cached != null && cached.size() >= 2)
                ? cached : defaultWikiCards()));

        // 后台拉官网首页：拿到两张卡就落盘 + 刷新界面；下次启动开屏直接就是新内容
        CompletableFuture.supplyAsync(() -> {
            try {
                return parseWikiCards(fetchWikiHtml());
            } catch (Exception e) {
                System.err.println("[wiki] 首页抓取失败: " + e);
                return null;
            }
        }).thenAccept(list -> {
            if (list == null || list.size() < 2) return;
            saveWikiCards(list);
            System.out.println("[wiki] 官网卡片已更新: " + list.get(0).title + " / " + list.get(1).title);
            Platform.runLater(() -> content.getChildren().setAll(buildWikiRow(list)));
        });

        StackPane card = new StackPane(content);
        card.setStyle("-fx-background-color:rgba(255,255,255,0.75); -fx-background-radius:6; -fx-border-radius:6;"
                + "-fx-border-color:#bbbbbb; -fx-border-width:2;");
        // 横向撑满内容区；高度按「两张卡各 2:1」算（两卡之间留 8px 缝），
        // 再压一个「不超过可用高 42%」的上限：全屏后窗口变宽，只按宽度算高度会把
        // 下面的「你知道吗」面板和启动键挤出可见区。
        card.prefWidthProperty().bind(host.widthProperty().subtract(32));
        card.prefHeightProperty().bind(javafx.beans.binding.Bindings.max(
                javafx.beans.binding.Bindings.min(
                        card.prefWidthProperty().subtract(8).divide(4),
                        host.heightProperty().multiply(0.42)),
                150.0));
        card.setMinSize(0, Region.USE_PREF_SIZE);
        card.setMaxSize(Double.MAX_VALUE, Region.USE_PREF_SIZE);

        Rectangle clip = new Rectangle();
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        clip.widthProperty().bind(card.widthProperty());
        clip.heightProperty().bind(card.heightProperty());
        card.setClip(clip);

        return card;
    }
    // ================= BBSMC 地图区（网页式卡片网格） =================

    /** 卡片行容器（ScrollPane 里，每行两张卡）。 */
    private VBox mapGrid;
    /** 搜索栏（放在滚动区第一行，随内容一起滚动，不吸顶）。 */
    private HBox mapBar;
    /** MC百科入口（滚动区第一行，跟搜索栏一起滚）。 */
    private HBox mapMcRow;
    private ScrollPane mapScroll;
    private TextField mapSearch;
    private ComboBox<String> mapSort;
    private Label mapStatus;
    private Button mapMoreBtn;
    /** 当前已拿到手的地图条目（重排卡片时用）。 */
    private final java.util.List<BbsmcMaps.Item> mapItems = new java.util.ArrayList<>();
    /** 是否正在请求（避免连续回车/切排序打出一串并发请求）。 */
    private boolean mapLoading;
    /** 面板是否已经拉过第一批数据。 */
    private boolean mapLoadedOnce;
    /** 新一轮数据落地后要不要把滚动位置拉回顶部（等内容真正布局完再拉）。 */
    private boolean mapWantTop;
    /** 站点的命中总数，用于「共 N 张」。 */
    private int mapTotal;
    /** 每页条数（与站点默认一致）。 */
    private static final int MAP_PAGE = 20;

    /**
     * BBSMC 地图面板：把站点的「搜索 + 两列大图卡片」按网页样式映射到主页维基卡下方那块空白。
     * <p>
     * 数据走 api.bbsmc.net 的 v2 接口（与 Modrinth 同构），不解析网页、不开 WebView；
     * 卡片是 ScrollPane 里的两列行（每行两个等宽卡片），竖向滚动条常驻，滚轮上下翻。
     * 下载时优先取 cdn.bbsmc.net 直链，在启动器内下完并解到实例 saves；
     * 作者只上传网盘（disk_only）的资源没有直链，会把网盘页交给浏览器并明确提示。
     */
    /**
     * 滚动区最上面的站点入口：抠好的图标 + 名称，点一下打开官网。
     */
    private VBox buildEntry(String iconPath, String label, String url) {
        ImageView iv = new ImageView();
        try (var in = HomeView.class.getResourceAsStream(iconPath)) {
            if (in != null) {
                Image icon = new Image(in);
                if (!icon.isError() && icon.getWidth() > 0) iv.setImage(icon);
            }
        } catch (Exception ignored) { }
        iv.setFitHeight(34);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);

        Label name = new Label(label);
        name.setStyle("-fx-text-fill:#5b6169; -fx-font-size:13px;");

        VBox box = new VBox(3, iv, name);
        box.setAlignment(Pos.TOP_CENTER);
        box.setMinWidth(Region.USE_PREF_SIZE);
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.setPadding(new Insets(1, 8, 1, 3));
        box.setCursor(Cursor.HAND);
        box.setOnMouseEntered(e -> name.setStyle("-fx-text-fill:" + Ui.ACCENT + "; -fx-font-size:13px;"));
        box.setOnMouseExited(e -> name.setStyle("-fx-text-fill:#5b6169; -fx-font-size:13px;"));
        box.setOnMouseClicked(e -> {
            try {
                app.openUrl(url);
            } catch (Exception ex) {
                Ui.error("打不开 " + label, String.valueOf(ex.getMessage()));
            }
        });
        return box;
    }

    /** 滚动区最上面那一排站点入口：MC百科 + MC维基百科 + BBSMC，靠左横排。 */
    private HBox buildMcRow() {
        HBox row = new HBox(14,
                this.buildEntry("/mcmod_icon.png", "MC百科", "https://www.mcmod.cn/"),
                this.buildEntry("/mcwiki_icon.png", "MC维基百科", "https://zh.minecraft.wiki/"),
                this.buildEntry("/bbsmc_icon.png", "BBSMC", "https://bbsmc.net/"));
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(0, 0, 0, 2));
        row.setMinWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private VBox buildMapPanel() {
        VBox panel = new VBox(6);
        panel.setPadding(new Insets(0, 0, 0, 0));
        panel.setStyle("-fx-background-color:transparent;");
        panel.setMinWidth(0);
        panel.setMinHeight(0);
        panel.setMaxWidth(Double.MAX_VALUE);
        panel.setMaxHeight(Double.MAX_VALUE);

        mapSearch = new TextField();
        mapSearch.setPromptText("搜索 地图…");
        mapSearch.setPrefColumnCount(5);
        mapSearch.setStyle("-fx-background-color:#f3f4f6; -fx-background-radius:6; -fx-border-radius:6;"
                + "-fx-border-color:#d7dbe0; -fx-border-width:1; -fx-padding:6 12; -fx-font-size:14px;"
                + "-fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
        HBox.setHgrow(mapSearch, Priority.ALWAYS);
        mapSearch.setOnAction(e -> reloadMaps());
        // 边打字边搜会打出一串请求：停顿 500ms 再发
        PauseTransition debounce = new PauseTransition(Duration.millis(500));
        debounce.setOnFinished(e -> reloadMaps());
        mapSearch.textProperty().addListener((o, a, b) -> debounce.playFromStart());

        mapSort = new ComboBox<>();
        mapSort.getItems().addAll("热门", "最新");
        mapSort.getSelectionModel().select(0);
        mapSort.setPrefWidth(96);
        mapSort.setMinWidth(96);
        mapSort.setStyle("-fx-background-color:#f3f4f6; -fx-background-radius:6; -fx-border-radius:6;"
                + "-fx-border-color:#d7dbe0; -fx-border-width:1; -fx-font-size:13px;");
        mapSort.setOnAction(e -> reloadMaps());

        // 工具栏只留「搜索 + 排序」，和网页顶部一致；它放在滚动区里，翻页时跟着一起走
        HBox bar = new HBox(6, mapSearch, mapSort);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMinWidth(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        this.mapBar = bar;

        mapGrid = new VBox(6);
        mapGrid.setPadding(new Insets(0, 2, 4, 2));
        mapGrid.setStyle("-fx-background-color:transparent;");
        mapGrid.setFillWidth(true);

        // MC百科 / MC维基百科 入口放在滚动内容的最前面：跟搜索栏、卡片一起随滚动条上下走
        HBox mcRow = this.buildMcRow();
        this.mapMcRow = mcRow;
        mapGrid.getChildren().addAll(mcRow, bar);

        mapScroll = new ScrollPane(mapGrid);
        mapScroll.setFitToWidth(true);
        mapScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        mapScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        mapScroll.setPannable(false);
        // 滚动区按内容（十几张卡片）算出来的 pref/min 高度会把整个窗口顶高，
        // 这里明确不要它参与尺寸计算，只让 VBox 的 Vgrow 分配高度
        mapScroll.setMinHeight(0);
        mapScroll.setPrefHeight(0);
        mapScroll.setStyle("-fx-background:transparent; -fx-background-color:transparent;"
                + "-fx-border-color:transparent; -fx-padding:0; -fx-background-insets:0;");
        VBox.setVgrow(mapScroll, Priority.ALWAYS);
        // 内容高度变了说明这一批卡片已经排好版，这时再把滚动位置归零，
        // 否则 setVvalue(0) 会在布局前执行、随后被 ScrollPane 自己的焦点滚动覆盖。
        mapGrid.heightProperty().addListener((o, ov, nv) -> {
            if (mapWantTop && nv.doubleValue() > 10) {
                mapWantTop = false;
                mapScroll.setVvalue(0);
                Platform.runLater(() -> mapScroll.setVvalue(0));
            }
        });
        // 视口尺寸一变（窗口启动时从小归位、或用户拉伸窗口），ScrollPane 会按比例
        // 保留旧位置，这里直接把它拉回顶部，避免首屏看到的是中间几行
        mapScroll.heightProperty().addListener((o, ov, nv) -> {
            if (!mapItems.isEmpty() && nv.doubleValue() > 40) scrollMapsToTop();
        });

        mapStatus = new Label("");
        mapStatus.setStyle("-fx-text-fill:#8a9099; -fx-font-size:10.5px;");
        mapStatus.setTextOverrun(OverrunStyle.ELLIPSIS);

        mapMoreBtn = new Button("加载更多");
        mapMoreBtn.setStyle("-fx-background-color:#f0f2f5; -fx-text-fill:#1f2328; -fx-background-radius:6;"
                + "-fx-font-size:11px; -fx-padding:2 9; -fx-cursor:hand;");
        mapMoreBtn.setVisible(false);
        mapMoreBtn.setOnAction(e -> loadMaps(false));

        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        HBox foot = new HBox(8, mapStatus, footSpacer, mapMoreBtn);
        foot.setAlignment(Pos.CENTER_LEFT);

        // 搜索栏是滚动内容的第一行，随内容一起滚；面板只留滚动区
        panel.getChildren().add(mapScroll);

        // 面板第一次进场景时再拉数据，避免主页没显示就开始发请求
        panel.sceneProperty().addListener((o, was, now) -> {
            if (now != null && !mapLoadedOnce) {
                mapLoadedOnce = true;
                reloadMaps();
                // 窗口从小尺寸归位后 ScrollPane 会按比例保留旧位置，1.2s 后再归一次顶
                javafx.animation.PauseTransition settle =
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(1200));
                settle.setOnFinished(e -> scrollMapsToTop());
                settle.play();
            }
        });
        return panel;
    }

    /** 把地图滚动位置拉回顶部：连发两轮 runLater，避开布局与焦点滚动的时序。 */
    private void scrollMapsToTop() {
        if (mapScroll == null) return;
        mapWantTop = true;
        Platform.runLater(() -> mapScroll.setVvalue(0));
        Platform.runLater(() -> Platform.runLater(() -> mapScroll.setVvalue(0)));
    }

    /** 按 2 张一行重排卡片（数据变了或「加载更多」后调用）。 */
    private void rebuildMapRows() {
        if (mapGrid == null) return;
        // 只清卡片行，搜索栏（mapBar）和 MC百科入口（mapMcRow）留在最前面，免得重建时丢焦点
        mapGrid.getChildren().removeIf(n -> n != mapBar && n != mapMcRow);
        if (mapItems.isEmpty()) {
            Label empty = new Label("暂无地图");
            empty.setStyle("-fx-text-fill:#8a9099; -fx-font-size:12px;");
            empty.setPadding(new Insets(16, 0, 0, 4));
            mapGrid.getChildren().add(empty);
            return;
        }
        for (int i = 0; i < mapItems.size(); i += 2) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.TOP_LEFT);
            row.setFillHeight(true);
            row.setMinWidth(0);
            row.setMaxWidth(Double.MAX_VALUE);

            VBox a = buildMapCard(mapItems.get(i));
            HBox.setHgrow(a, Priority.ALWAYS);
            row.getChildren().add(a);

            VBox b = (i + 1 < mapItems.size()) ? buildMapCard(mapItems.get(i + 1)) : null;
            if (b != null) {
                HBox.setHgrow(b, Priority.ALWAYS);
                row.getChildren().add(b);
            } else {
                // 落单的最后一张：补一个弹性占位，保证它只占半行
                Region filler = new Region();
                filler.setMinWidth(0);
                HBox.setHgrow(filler, Priority.ALWAYS);
                row.getChildren().add(filler);
            }
            // 两列严格等宽：不管标题/简介内容多长，卡片都各占半行
            row.widthProperty().addListener((o, ov, nv) -> {
                double w = Math.max(70, (nv.doubleValue() - 8) / 2);
                a.setPrefWidth(w);
                if (b != null) b.setPrefWidth(w);
            });
            mapGrid.getChildren().add(row);
        }
    }

    /** 重新拉第一页（换关键词 / 换排序）。 */
    private void reloadMaps() {
        if (mapGrid == null) return;
        mapItems.clear();
        mapTotal = 0;
        loadMaps(true);
    }

    /** 拉一页地图；reset=true 时替换内容，否则追加。 */
    private void loadMaps(boolean reset) {
        if (mapGrid == null || mapLoading) return;
        mapLoading = true;
        if (mapMoreBtn != null) mapMoreBtn.setDisable(true);
        mapStatus.setText("正在加载…");
        String q = mapSearch == null ? "" : mapSearch.getText();
        String idx = (mapSort != null && mapSort.getSelectionModel().getSelectedIndex() == 1) ? "newest" : "downloads";
        int offset = reset ? 0 : mapItems.size();
        Ui.async2(() -> BbsmcMaps.search(q, idx, offset, MAP_PAGE), res -> {
            mapLoading = false;
            if (res == null) return;
            if (reset) mapItems.clear();
            mapItems.addAll(res.items);
            mapTotal = res.total;
            rebuildMapRows();
            // 新一批数据落地：滚动位置回到顶部（等内容排好版再拉，否则会被布局覆盖）
            if (reset) scrollMapsToTop();
            String msg = mapTotal > 0
                    ? "地图 · 共 " + BbsmcMaps.num(mapTotal) + " 张 · 已显示 " + mapItems.size() + " 张（可滚动查看）"
                    : "没有找到地图";
            if (q != null && !q.isBlank()) msg = "「" + q.trim() + "」· " + msg;
            mapStatus.setText(msg);
            mapMoreBtn.setVisible(mapTotal > mapItems.size());
            mapMoreBtn.setDisable(res.items.isEmpty());
        }, err -> {
            mapLoading = false;
            mapStatus.setText("加载失败：" + (err.getMessage() == null ? "网络异常" : err.getMessage()));
            if (mapMoreBtn != null) mapMoreBtn.setDisable(false);
        });
    }

    /**
     * 一张网页式地图卡：大封面 + 左下角项目小图标 + 标题/作者/简介/标签 + 底部下载数与下载键。
     * 卡宽由所在行平分（Hgrow），封面高度固定，不随窗口宽度算比例。
     */
    private VBox buildMapCard(BbsmcMaps.Item it) {
        VBox card = new VBox(0);
        card.setStyle("-fx-background-color:rgba(255,255,255,0.75); -fx-background-radius:8; -fx-border-radius:8;"
                + "-fx-border-color:#e1e4e8; -fx-border-width:1;");
        card.setMinWidth(0);
        card.setPrefWidth(200);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        // 整卡可点：进 BBSMC 官网详情页（下载在官网完成）
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> openMapPage(it));

        // ---- 封面（等比铺满后裁掉溢出部分，图片本身绝不拉伸变形） ----
        ImageView cover = new ImageView();
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        cover.setImage(Ui.GRASS_BLOCK);

        StackPane coverBox = new StackPane(cover);
        coverBox.setStyle("-fx-background-color:#dfe3e8;");
        coverBox.setMinHeight(76);
        coverBox.setPrefHeight(76);
        coverBox.setMaxHeight(76);
        coverBox.setMinWidth(0);
        cover.fitWidthProperty().bind(coverBox.widthProperty());
        Rectangle clip = new Rectangle();
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clip.widthProperty().bind(coverBox.widthProperty());
        clip.heightProperty().bind(coverBox.heightProperty());
        coverBox.setClip(clip);

        // 左上角项目图标（网页里骑在封面左侧的小方块）
        ImageView mini = new ImageView(Ui.GRASS_BLOCK);
        mini.setFitWidth(28);
        mini.setFitHeight(28);
        mini.setPreserveRatio(false);
        mini.setSmooth(true);
        StackPane miniBox = new StackPane(mini);
        miniBox.setMinSize(30, 30);
        miniBox.setPrefSize(30, 30);
        miniBox.setMaxSize(30, 30);
        miniBox.setStyle("-fx-background-color:rgba(255,255,255,0.75); -fx-background-radius:6; -fx-border-color:#e1e4e8;"
                + "-fx-border-radius:6; -fx-border-width:1;");
        StackPane.setAlignment(miniBox, Pos.BOTTOM_LEFT);
        StackPane.setMargin(miniBox, new Insets(0, 0, -10, 9));
        coverBox.getChildren().add(miniBox);

        // ---- 文字区 ----
        Label titleL = new Label(it.title);
        titleL.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:#1f2328;");
        titleL.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleL.setMaxWidth(Double.MAX_VALUE);
        titleL.setMinWidth(0);

        StringBuilder au = new StringBuilder();
        if (it.author != null && !it.author.isBlank()) au.append("by ").append(it.author);
        if (it.modified > 0) au.append(au.length() > 0 ? " · " : "").append("更新于 ").append(BbsmcMaps.ago(it.modified));
        Label authorL = new Label(au.toString());
        authorL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#8a9099;");
        authorL.setTextOverrun(OverrunStyle.ELLIPSIS);
        authorL.setMaxWidth(Double.MAX_VALUE);
        authorL.setMinWidth(0);

        Label descL = new Label(it.description == null || it.description.isBlank()
                ? "（作者没有写简介）" : it.description);
        descL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#5b6068;");
        descL.setWrapText(true);
        descL.setMaxHeight(18);
        descL.setMinHeight(18);
        descL.setTextOverrun(OverrunStyle.ELLIPSIS);
        descL.setMaxWidth(Double.MAX_VALUE);
        descL.setMinWidth(0);

        javafx.scene.layout.FlowPane tagBox = new javafx.scene.layout.FlowPane(4, 4);
        tagBox.setMinWidth(0);
        tagBox.setMaxWidth(Double.MAX_VALUE);
        tagBox.setPrefWrapLength(180);
        for (int i = 0; i < it.tags.size() && i < 3; i++) tagBox.getChildren().add(mapTag(it.tags.get(i)));

        Label dlL = new Label("↓ " + BbsmcMaps.num(it.downloads));
        dlL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#3b4048;");
        Label followL = new Label("♥ " + it.follows);
        followL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#3b4048;");
        HBox numBox = new HBox(8, dlL, followL);
        numBox.setAlignment(Pos.CENTER_LEFT);

        // 不再内置下载：卡内只留统计，点整卡跳 BBSMC 官网详情页下载
        Label goL = new Label("官网下载 ↗");
        goL.setStyle("-fx-font-size:12.5px; -fx-text-fill:" + Ui.ACCENT + "; -fx-cursor:hand;");
        Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox bottom = new HBox(6, numBox, bottomSpacer, goL);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setMinWidth(0);

        VBox body = new VBox(3, titleL, authorL, descL, tagBox, bottom);
        body.setPadding(new Insets(8, 8, 5, 8));
        body.setMinWidth(0);
        body.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(body, Priority.ALWAYS);

        card.getChildren().addAll(coverBox, body);

        // ---- 两张图各自异步就位，失败保持草方块占位 ----
        if (it.thumb != null && !it.thumb.isBlank()) {
            Image c = IconCache.cached(it.thumb, 360);
            if (c != null) cover.setImage(c);
            else IconCache.ensure(it.thumb, 360, cover::setImage);
        }
        if (it.icon != null && !it.icon.isBlank()) {
            Image c = IconCache.cached(it.icon, 34);
            if (c != null) mini.setImage(c);
            else IconCache.ensure(it.icon, 34, mini::setImage);
        }
        return card;
    }



    /** 标签小胶囊。 */
    private static Label mapTag(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-background-color:#f0f2f5; -fx-background-radius:4; -fx-padding:1 6;"
                + "-fx-font-size:11.5px; -fx-text-fill:#5b6068;");
        return l;
    }

    /** 点卡片 → 打开 BBSMC 官网详情页（下载统一在官网完成）。 */
    private void openMapPage(BbsmcMaps.Item it) {
        try {
            app.openUrl(it.pageUrl());
        } catch (Exception ex) {
            Ui.error("打不开官网页面", String.valueOf(ex.getMessage()));
        }
    }

    /** 点「下载」：先查该版本的文件形态，直链就地下载，网盘则提示并给浏览器链接。 */
    private void startMapDownload(BbsmcMaps.Item item, Button btn) {
        if (item.latestVersion == null || item.latestVersion.isBlank()) {
            Ui.error("暂时无法下载", "这张地图在站点上还没有可下载的版本。");
            return;
        }
        btn.setDisable(true);
        btn.setText("查询…");
        Ui.async2(() -> BbsmcMaps.release(item.latestVersion), rel -> {
            if (!rel.directDownloadable()) {
                btn.setDisable(false);
                btn.setText("网盘");
                String plat = BbsmcMaps.platformLabel(rel.platform.isBlank() ? rel.diskUrl : rel.platform);
                boolean go = Ui.confirm("该地图只提供网盘下载",
                        "《" + item.title + "》的作者没有在 BBSMC 上传直链文件，只放了 " + plat + " 分享链接。\n\n"
                                + "这类资源启动器拿不到文件本身，无法在启动器内自动下载；\n"
                                + "要现在用浏览器打开网盘页面吗？（需要登录网盘后自己转存下载）");
                if (go && rel.diskUrl != null && !rel.diskUrl.isBlank()) {
                    app.openUrl(rel.diskUrl);
                }
                return;
            }
            runMapDownload(item, rel, btn);
        }, err -> {
            btn.setDisable(false);
            btn.setText("下载");
            Ui.error("获取下载地址失败", err);
        });
    }

    /** 真正下载 + 解压安装到实例存档目录。 */
    private void runMapDownload(BbsmcMaps.Item item, BbsmcMaps.Release rel, Button btn) {
        Path saves = savesDirForMap();
        if (saves == null) {
            btn.setDisable(false);
            btn.setText("下载");
            Ui.error("还没有可用的游戏实例",
                    "地图要装进某个实例的存档目录（saves）。\n请先在左侧列表里选中一个已安装的版本，再点下载。");
            return;
        }
        app.showToast("开始下载《" + item.title + "》…");
        Ui.async2(() -> {
            Path tmp = Files.createTempDirectory("jwh-map-");
            try {
                Path zip = tmp.resolve("map.zip");
                long total = Http.contentLength(rel.url);
                Http.DownloadControl ctl = new Http.DownloadControl() {
                    @Override
                    public void onBytes(long bytes) {
                        String t = total > 0
                                ? Math.min(99, (int) (bytes * 100 / total)) + "%"
                                : (bytes / (1024 * 1024)) + "MB";
                        Platform.runLater(() -> btn.setText(t));
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                };
                // 地图包是 ZIP：走统一入口，结构校验通过才解压（避免半截包解出坏存档）
                if (!com.jwh.launcher.download.Downloader.fetch(zip, -1,
                        com.jwh.launcher.download.ArtifactProbe.Kind.ZIP, "map-download", ctl,
                        com.jwh.launcher.download.Downloader.Policy.of(300, 900), rel.url)) {
                    throw new java.io.IOException("下载失败（连接被中断或服务器拒绝）");
                }
                Path unz = tmp.resolve("unz");
                Files.createDirectories(unz);
                ModpackImporter.unzip(zip, unz);
                Path root = ModpackImporter.detectRoot(unz);
                Files.createDirectories(saves);
                String installed;
                if (Files.isRegularFile(root.resolve("level.dat"))) {
                    // 单世界散文件包：整包放进以地图名命名的世界目录
                    String base = safeWorldName(item.title);
                    Path world = saves.resolve(base);
                    if (Files.exists(world)) world = saves.resolve(base + "-" + Math.abs(item.id.hashCode() % 1000));
                    Files.createDirectories(world);
                    try (var s = Files.list(root)) {
                        for (Path p : s.toList()) {
                            Path dst = world.resolve(p.getFileName().toString()).normalize();
                            if (!dst.startsWith(world)) throw new java.io.IOException("压缩包内有非法路径");
                            Files.move(p, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    installed = world.getFileName().toString();
                } else {
                    // 已带世界文件夹（或多个子目录）：直接并进 saves
                    ModpackImporter.mergeInto(root, saves);
                    installed = "存档目录";
                }
                return installed;
            } finally {
                try {
                    ModpackImporter.deleteRecursively(tmp);
                } catch (Exception ignored) {
                }
            }
        }, installed -> {
            btn.setText("已下载");
            btn.setDisable(true);
            app.showToast("《" + item.title + "》已" + ("存档目录".equals(installed) ? "安装到" : "放入") + " " + saves.toAbsolutePath());
        }, err -> {
            btn.setText("重试");
            btn.setDisable(false);
            Ui.error("下载失败", err);
        });
    }

    /** 地图落点：当前选中实例的 saves；没开隔离就用全局存档目录。无实例返回 null。 */
    private Path savesDirForMap() {
        if (!app.config.versionIsolation) {
            return app.config.minecraftPath().resolve("saves");
        }
        LocalVersions.LocalVersion sel = versionList.getSelectionModel().getSelectedItem();
        if (sel == null && !versionList.getItems().isEmpty()) sel = versionList.getItems().get(0);
        if (sel == null) return null;
        return app.config.gameDirFor(sel.id()).resolve("saves");
    }

    /** 地图标题 → 合法的世界文件夹名（Windows 不允许 \ / : * ? " < > |）。 */
    private static String safeWorldName(String title) {
        String s = title == null ? "map" : title.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isBlank() ? "map" : s;
    }


    /** 启动游戏 + 实例切换箭头：共用一块蓝色底座，两者之间一条竖线；底座下沿再嵌一行已选版本号。 */
    private VBox buildLaunchGroup() {
        Region sep = new Region();
        sep.setPrefWidth(1);
        sep.setMinWidth(1);
        sep.setMaxWidth(1);
        sep.setPrefHeight(22);
        sep.setStyle("-fx-background-color:rgba(255,255,255,0.30);");

        Polygon tri = new Polygon(0.0, 7.0, 6.0, 1.0, 12.0, 7.0);
        tri.setFill(Color.WHITE);
        this.launchArrow = new StackPane(tri);
        this.launchArrow.setPrefWidth(30);
        this.launchArrow.setMinWidth(30);
        this.launchArrow.setPrefHeight(38);
        this.launchArrow.setCursor(Cursor.HAND);
        this.launchArrow.setStyle("-fx-background-color:transparent;");
        this.launchArrow.setOnMouseClicked(e -> this.toggleInstancePopup(this.launchArrow));

        HBox btnRow = new HBox(this.launchBtn, sep, this.launchArrow);
        btnRow.setAlignment(Pos.CENTER);
        btnRow.setPrefWidth(143);
        btnRow.setMinWidth(143);
        btnRow.setPrefHeight(38);

        // 已选版本号：直接嵌在蓝色底座下沿，白字，超出省略
        launchVerLabel.setStyle("-fx-text-fill:rgba(255,255,255,0.82); -fx-font-size:11px;");
        launchVerLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        launchVerLabel.setMaxWidth(133);
        HBox verRow = new HBox(launchVerLabel);
        verRow.setAlignment(Pos.CENTER);
        verRow.setPadding(new Insets(0, 0, 6, 0));

        VBox group = new VBox(0, btnRow, verRow);
        group.setAlignment(Pos.CENTER);
        group.setMaxWidth(Region.USE_PREF_SIZE);
        group.setMinWidth(Region.USE_PREF_SIZE);
        group.setMaxHeight(Region.USE_PREF_SIZE); // 防止被父容器纵向拉伸
        group.setMinHeight(Region.USE_PREF_SIZE);
        group.setStyle("-fx-background-color:#7ED957; -fx-background-radius:8;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 5, 0, 0, 2);");
        return group;
    }

    /** 箭头点击：展开/收起已安装版本列表（向上弹出）。 */
    private void toggleInstancePopup(javafx.scene.Node anchor) {
        if (this.instancePopup != null && this.instancePopup.isShowing()) {
            this.instancePopup.hide();
            return;
        }
        if (anchor == null || anchor.getScene() == null) {
            return;
        }
        if (this.instancePopup == null) {
            this.instancePopup = new Popup();
            this.instancePopup.setHideOnEscape(true);
        }
        this.instancePopup.getContent().setAll(this.buildInstanceMenu());
        // autoHide 先关：在 MOUSE_CLICKED 里直接 show 且 autoHide=true 时，
        // JavaFX 会把同一次点击当成“外部点击”立刻把弹层关掉（表现为点了没反应）。
        this.instancePopup.setAutoHide(false);
        javafx.stage.Window owner = anchor.getScene().getWindow();
        if (owner != null && owner.isShowing()) {
            this.instancePopup.show(owner, 0, 0);
        } else {
            this.instancePopup.show(anchor, 0, 0);
        }
        this.positionInstancePopup(anchor);
        javafx.application.Platform.runLater(() -> {
            if (this.instancePopup != null && this.instancePopup.isShowing()) {
                this.instancePopup.setAutoHide(true);
            }
        });
    }

    /** 弹层贴着箭头上沿、右缘与箭头右缘对齐（并夹在屏幕内，绝不出屏）。 */
    private void positionInstancePopup(javafx.scene.Node anchor) {
        if (this.instancePopup == null || !this.instancePopup.isShowing()) {
            return;
        }
        javafx.scene.Node content = this.instancePopup.getContent().get(0);
        content.applyCss();
        content.autosize();
        Bounds ab = anchor.localToScreen(anchor.getBoundsInLocal());
        Bounds cb = content.localToScreen(content.getBoundsInLocal());
        if (ab == null || cb == null) {
            return;
        }
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        double w = Math.max(cb.getWidth(), content.prefWidth(-1));
        double h = Math.max(cb.getHeight(), content.prefHeight(-1));
        double x = ab.getMaxX() - w;
        double y = ab.getMinY() - h - 6;
        x = Math.min(x, screen.getMaxX() - w - 8);
        x = Math.max(screen.getMinX() + 8, x);
        y = Math.max(screen.getMinY() + 8, y);
        this.instancePopup.setX(x);
        this.instancePopup.setY(y);
    }

    /** 实例下拉列表：单击切换选中（有选中效果），双击直接启动。 */
    private javafx.scene.Node buildInstanceMenu() {
        VBox list = new VBox(4);
        // 顶部留出 30px，给悬停气泡「实例管理／删除」使用（气泡自绘在弹层内部，
        // 不用 Tooltip —— 独立 Popup 里的 Tooltip 会被弹层的 autoHide 立即关掉）
        list.setPadding(new Insets(30, 6, 6, 6));
        list.setPrefWidth(292);
        list.setStyle("-fx-background-color:white; -fx-background-radius:12;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.20), 14, 0, 0, 4);");
        List<LocalVersions.LocalVersion> items = this.versionList.getItems();
        if (items.isEmpty()) {
            Label empty = new Label("还没有已安装的游戏版本");
            empty.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px; -fx-padding:10;");
            list.getChildren().add(empty);
        } else {
            LocalVersions.LocalVersion sel = this.versionList.getSelectionModel().getSelectedItem();
            for (LocalVersions.LocalVersion v : items) {
                list.getChildren().add(this.buildInstanceItem(v, v.equals(sel)));
            }
        }
        Label hint = new Label();
        hint.setStyle("-fx-background-color:rgba(38,42,50,0.94); -fx-text-fill:white;"
                + "-fx-font-size:12px; -fx-padding:3 9 3 9; -fx-background-radius:7;");
        hint.setVisible(false);
        hint.setManaged(false);
        hint.setMouseTransparent(true);
        this.instanceHint = hint;
        StackPane wrap = new StackPane(list, hint);
        StackPane.setAlignment(list, Pos.TOP_LEFT);
        StackPane.setAlignment(hint, Pos.TOP_LEFT);
        this.instanceMenuWrap = wrap;
        return wrap;
    }

    /** 在某一行图标上方显示气泡提示（自绘，随图标位置移动）。 */
    private void showRowHint(javafx.scene.Node anchor, String text) {
        if (this.instanceHint == null || this.instanceMenuWrap == null) {
            return;
        }
        Label h = this.instanceHint;
        h.setText(text);
        h.setVisible(true);
        Bounds b = anchor.localToScene(anchor.getBoundsInLocal());
        if (b == null) {
            return;
        }
        double hw = h.prefWidth(-1);
        double hh = h.prefHeight(-1);
        double x = b.getMinX() + b.getWidth() / 2 - hw / 2;
        double y = b.getMinY() - hh - 6;
        x = Math.max(6, Math.min(x, Math.max(6, this.instanceMenuWrap.getWidth() - hw - 6)));
        y = Math.max(3, y);
        h.resizeRelocate(x, y, hw, hh);
    }

    private void hideRowHint() {
        if (this.instanceHint != null) {
            this.instanceHint.setVisible(false);
        }
    }

    private javafx.scene.Node buildInstanceItem(LocalVersions.LocalVersion v, boolean selected) {
        ImageView ic = new ImageView(iconFor(v));
        ic.setFitWidth(28);
        ic.setFitHeight(28);
        ic.setPreserveRatio(true);
        ic.setSmooth(false);
        Label name = new Label(v.shortName());
        name.setStyle("-fx-font-size:13px; -fx-text-fill:" + Ui.TEXT + ";");
        name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        Region spacer = new Region();
        spacer.setMinWidth(6);
        // 行尾「⋯」：打开该实例的实例管理页。
        // 用 Button 而不是 Label：Label 在这套 Popup 里点不中（命中区域过小/被行节点抢走事件），
        // 换成实体按钮后 26x26 的点击区域稳定可点。
        Button more = new Button("\u22ef");
        String moreNormal = "-fx-background-color:transparent; -fx-font-size:18px; -fx-text-fill:#a8adb3;"
                + "-fx-padding:0; -fx-background-radius:6;";
        more.setStyle(moreNormal);
        more.setMinSize(26, 26);
        more.setPrefSize(26, 26);
        more.setMaxSize(26, 26);
        more.setFocusTraversable(false);
        more.setCursor(Cursor.HAND);
        more.setOnMouseEntered(e -> {
            more.setStyle("-fx-background-color:transparent; -fx-font-size:18px; -fx-text-fill:" + Ui.ACCENT + ";"
                    + "-fx-padding:0; -fx-background-radius:6;");
            this.showRowHint(more, "实例管理");
        });
        more.setOnMouseExited(e -> {
            more.setStyle(moreNormal);
            this.hideRowHint();
        });
        more.setOnAction(e -> {
            if (this.instancePopup != null) {
                this.instancePopup.hide();
            }
            this.openInstanceManager(v);
        });
        // 行尾灰色叉号：删除该实例（与主页「删除版本」同一套逻辑，会二次确认）
        Button del = new Button("\u2715");
        String delNormal = "-fx-background-color:transparent; -fx-font-size:13px; -fx-text-fill:#a8adb3;"
                + "-fx-padding:0; -fx-background-radius:6;";
        del.setStyle(delNormal);
        del.setMinSize(26, 26);
        del.setPrefSize(26, 26);
        del.setMaxSize(26, 26);
        del.setFocusTraversable(false);
        del.setCursor(Cursor.HAND);
        del.setOnMouseEntered(e -> {
            del.setStyle("-fx-background-color:transparent; -fx-font-size:13px; -fx-text-fill:#d64545;"
                    + "-fx-padding:0; -fx-background-radius:6;");
            this.showRowHint(del, "删除");
        });
        del.setOnMouseExited(e -> {
            del.setStyle(delNormal);
            this.hideRowHint();
        });
        del.setOnAction(e -> {
            if (this.instancePopup != null) {
                this.instancePopup.hide();
            }
            this.deleteVersion(v);
        });
        HBox row = new HBox(4, ic, name, spacer, more, del);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 10, 6, 8));
        row.setCursor(Cursor.HAND);
        row.getProperties().put("jwh-v", v);
        // 悬停只改透明度，背景色统一由 applyInstanceRowStyles() 按选中态刷，
        // 这样单击切换时不必重建节点，双击计数不会被中断。
        row.setOnMouseEntered(e -> row.setOpacity(0.82));
        row.setOnMouseExited(e -> row.setOpacity(1.0));
        row.setOnMouseClicked(e -> {
            this.versionList.getSelectionModel().select(v);
            if (e.getClickCount() >= 2) {
                if (this.instancePopup != null) {
                    this.instancePopup.hide();
                }
                // 双击：打开该实例的「实例管理」页（不是直接启动游戏）
                this.openInstanceManager(v);
            } else {
                this.applyInstanceRowStyles();
            }
        });
        this.applyInstanceRowStyle(row, selected);
        return row;
    }

    /** 按当前选中版本，刷新下拉里每一行的高亮。 */
    private void applyInstanceRowStyles() {
        if (this.instanceMenuWrap == null) {
            return;
        }
        VBox list = null;
        for (javafx.scene.Node n : this.instanceMenuWrap.getChildren()) {
            if (n instanceof VBox vb) {
                list = vb;
                break;
            }
        }
        if (list == null) {
            return;
        }
        LocalVersions.LocalVersion sel = this.versionList.getSelectionModel().getSelectedItem();
        for (javafx.scene.Node n : list.getChildren()) {
            if (n instanceof HBox row) {
                Object v = row.getProperties().get("jwh-v");
                this.applyInstanceRowStyle(row, v instanceof LocalVersions.LocalVersion lv && lv.equals(sel));
            }
        }
    }

    private void applyInstanceRowStyle(HBox row, boolean selected) {
        row.setStyle("-fx-background-color:" + (selected ? "rgba(63,169,245,0.16)" : "transparent")
                + "; -fx-background-radius:8;");
    }

    /** 打开指定实例的「实例管理」子页面（融入主窗口，左上角返回键回主页）。 */
    private void openInstanceManager(LocalVersions.LocalVersion v) {
        if (v == null) {
            return;
        }
        this.versionList.getSelectionModel().select(v);
        this.app.showSubPage("实例管理 - " + v.id(),
                InstanceManagerView.content(this.app, v.id()),
                () -> this.app.showHome());
    }

    /** 辅助按钮统一样式。 */
    private static String auxBtnStyle() {
        return "-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-background-radius:6; -fx-padding:8 10;";
    }

    private void updateSelection(LocalVersions.LocalVersion v) {
        verTitle.setText(v.displayName());
        launchVerLabel.setText(v.shortName());
        // 记住这次选择：切页回来、重启启动器都停在这个版本上
        if (!v.id().equals(app.config.selectedVersionId)) {
            app.config.selectedVersionId = v.id();
            ConfigManager.save(app.config);
        }
        verMeta.setText("类型：" + v.kind() + "  " + (v.hasJar() ? "主程序已就绪" : "主程序缺失，请先安装或修复"));
        // 推荐 Java 提示（读取版本 JSON 官方标注）
        int rec = app.gameLauncher.requiredJavaMajor(v.id());
        if (rec > 0) {
            verMeta.setText(verMeta.getText() + "\n该版本官方推荐 Java " + rec + "（可在「设置」页调整 Java 与内存）");
        }
    }

    /**
     * 主页小人注视鼠标：把光标在场景中的位置换算成头部相对身体的偏转角。
     *
     * <p>130px 当作「观者到屏幕」的距离，越远角度越接近极限，
     * 鼠标跑到窗口角落时也只是扭头 ~50°，不会拧成麻花。
     */
    private void aimHomeSkinAt(javafx.scene.input.MouseEvent ev) {
        if (this.homeSkin == null) {
            return;
        }
        Bounds b = this.homeSkin.localToScene(this.homeSkin.getLayoutBounds());
        if (b.getWidth() <= 0 || b.getHeight() <= 0) {
            return;
        }
        // 画布里人物不是正中（cx = W*0.452），头心按这个比例取，否则加宽画布后注视会整体偏右
        double headX = b.getMinX() + b.getWidth() * 0.452;
        double headY = b.getMinY() + b.getHeight() * 0.24;
        double yawDeg = Math.toDegrees(Math.atan2(ev.getSceneX() - headX, 130.0));
        double pitchDeg = Math.toDegrees(Math.atan2(ev.getSceneY() - headY, 130.0));
        // 符号已实测校准：鼠标在右 → 头朝右（yaw 直接照传），鼠标在下 → 低头（pitch 直接照传）
        this.homeSkin.setHeadAim(yawDeg, pitchDeg);
    }

    /**
     * 主页小人的皮肤来源 = 皮肤库「正在使用」的那张。
     *
     * <p>皮肤库点「使用这个皮肤」就是把选中的 PNG 覆盖写到
     * {@code <minecraftDir>/skins/<uuid 去掉横线>.png}，所以主页只读这一个文件，
     * 就天然与皮肤库同步；换皮肤后回到主页会立刻是新皮肤。
     * 没有账号 / 该账号还没设过皮肤时保留当前画面，不弹任何错误。
     */
    private void loadHomeSkin() {
        if (this.homeSkin == null) {
            return;
        }
        LauncherConfig.Account acc = null;
        int idx = this.app.config.selectedAccount;
        if (idx >= 0 && idx < this.app.config.accounts.size()) {
            acc = this.app.config.accounts.get(idx);
        }
        if (acc != null && acc.uuid != null && !acc.uuid.isBlank()) {
            Path p = Path.of(this.app.config.minecraftDir)
                    .resolve("skins")
                    .resolve(acc.uuid.replace("-", "") + ".png");
            if (Files.isRegularFile(p)) {
                this.homeSkin.setSkin(p);
            }
        }
        this.homeSkin.render();
    }

    /** 刷新版本列表（扫描放后台：版本目录多时避免阻塞 UI，表现为点击未响应）。 */
    public void refresh() {
        // 皮肤库里「使用这个皮肤」就是把 <minecraftDir>/skins/<uuid>.png 覆盖写入，
        // 所以主页小人只要读这一个文件，就自动跟皮肤库保持同步。
        this.loadHomeSkin();
        // 先记住当前选中项：下面 setAll 会清空 selection，旧逻辑随后直接 selectFirst，
        // 于是"换了版本、切一下页面回来又跳回第一项"。选中项持久化在 config，重启也能记住。
        LocalVersions.LocalVersion keep = versionList.getSelectionModel().getSelectedItem();
        String keepId = keep != null ? keep.id() : app.config.selectedVersionId;
        // HMCL 式：只显示最终可玩版本（被加载器继承的基础原版自动隐藏）
        Ui.async2(() -> app.localVersions.scanVisible(), vis -> {
            versionList.getItems().setAll(vis);
            instTitle.setText(vis.isEmpty() ? "缺少游戏版本" : "已安装版本");
            LocalVersions.LocalVersion pick = null;
            if (keepId != null && !keepId.isBlank()) {
                for (LocalVersions.LocalVersion x : vis) {
                    if (keepId.equals(x.id())) {
                        pick = x;
                        break;
                    }
                }
            }
            if (pick != null) {
                versionList.getSelectionModel().select(pick);
            } else if (versionList.getSelectionModel().getSelectedItem() == null && !vis.isEmpty()) {
                versionList.getSelectionModel().selectFirst();
            }
            if (vis.isEmpty()) {
                // 一个实例都没有：启动按钮下方的版本号位置显示「未安装游戏实例」，信息卡同步清空
                showNoInstance();
            }
        }, e -> { /* 扫描失败保持当前列表 */ });
    }

    /** 一个可玩实例都没有时的空状态文案（启动按钮下方 + 版本信息卡）。 */
    private void showNoInstance() {
        launchVerLabel.setText(NO_INSTANCE_TEXT);
        verTitle.setText(NO_INSTANCE_TEXT);
        verMeta.setText("点左侧「去下载版本 / 加载器」安装游戏版本或加载器后再启动。");
    }

    /** 主页「多人游戏」入口：进入内置联机页（房间号联机，不用填服务器地址、不用邀请码）。 */
    private void multiplayer() {
        LocalVersions.LocalVersion v = versionList.getSelectionModel().getSelectedItem();
        if (v == null) {
            Ui.error("无法启动", "请先在左侧选择一个已安装的版本。");
            return;
        }
        app.showMultiplayer();
    }

    /** 给「多人游戏」面板用：直接把游戏拉起来连到指定地址（房间号联机成功走这里）。 */
    public void launchToServer(String addr) {
        LocalVersions.LocalVersion v = versionList.getSelectionModel().getSelectedItem();
        if (v == null) {
            Ui.error("无法启动", "请先在左侧选择一个已安装的版本。");
            return;
        }
        if (v.mcBase() != null && !supportsQuickPlay(v.id())) {
            app.showToast("当前实例（" + v.id() + "）不支持直达服务器，将普通启动；"
                    + "陶瓦已经把房主的房间广播进游戏多人列表，进「多人游戏」列表里点「陶瓦房间」即可。");
        }
        pendingQuickPlay = addr;
        launch();
    }

    public LauncherApp app() {
        return app;
    }

    /** 该版本的启动参数里有没有 quickPlayMultiplayer（整合包实例看 inheritsFrom 的基础版本）。 */
    private boolean supportsQuickPlay(String id) {
        try {
            Path p = app.config.versionsPath().resolve(id).resolve(id + ".json");
            if (!Files.isRegularFile(p)) return false;
            String s = Files.readString(p, StandardCharsets.UTF_8);
            if (s.contains("quickPlayMultiplayer")) return true;
            Matcher m = Pattern.compile("\"inheritsFrom\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
            if (!m.find()) return false;
            String base = m.group(1);
            Path bp = app.config.versionsPath().resolve(base).resolve(base + ".json");
            if (!Files.isRegularFile(bp)) return false;
            return Files.readString(bp, StandardCharsets.UTF_8).contains("quickPlayMultiplayer");
        } catch (Exception e) {
            return false;
        }
    }

    private void launch() {
        LocalVersions.LocalVersion v = versionList.getSelectionModel().getSelectedItem();
        if (v == null) {
            Ui.error("无法启动", "请先在左侧选择一个已安装的版本。");
            return;
        }
        // 账号
        LauncherConfig.Account acc;
        try {
            acc = app.auth.currentAccount(true);
        } catch (Exception e) {
            Ui.error("账号登录失败", e);
            return;
        }
        if (acc == null) {
            Ui.error("未登录", "请先登录（顶栏\"登录\"按钮：正版或离线均可）。");
            return;
        }
        // Java：由「设置」页统一配置（自动匹配或手动指定），此处按配置解析
        LauncherApp.JavaChoice jc = app.resolveJava(v.id(), null);
        if (jc.javaExe() == null) {
            boolean go = Ui.confirm("未找到 Java",
                    "该版本需要 Java " + jc.needMajor() + "，但电脑上没有检测到满足要求的 Java。\n"
                            + "点「确定」前往官网下载 Java " + jc.needMajor() + "（Adoptium），装好后回到这里即可自动识别。");
            if (go) app.openUrl("https://adoptium.net/zh-CN/temurin/releases/?version=" + Math.max(jc.needMajor(), 21));
            return;
        }
        if (jc.actualMajor() < jc.needMajor()) {
            boolean go = Ui.confirm("Java 版本不够",
                    "该版本需要 Java " + jc.needMajor() + "，当前可用的是 Java " + jc.actualMajor() + "，可能无法启动。\n"
                            + "点「确定」前往官网下载 Java " + jc.needMajor() + "；点「否」仍用当前 Java 启动（可能报错）。");
            if (go) {
                app.openUrl("https://adoptium.net/zh-CN/temurin/releases/?version=" + Math.max(jc.needMajor(), 21));
                return;
            }
        }
        // 版本过高：新版本 MC 在远高于推荐的 Java 上可能出现兼容问题（典型：语言列表加载失败、调不了语言）
        if (jc.actualMajor() - jc.needMajor() >= 2) {
            boolean go = Ui.confirm("Java 版本过高",
                    "该版本官方推荐 Java " + jc.needMajor() + "，当前配置的是 Java " + jc.actualMajor()
                            + "（高出较多），可能出现兼容问题（例如游戏内语言列表加载失败、调不了语言）。\n"
                            + "点「确定」前往官网下载 Java " + jc.needMajor() + "；点「否」仍用当前 Java 启动。");
            if (go) {
                app.openUrl("https://adoptium.net/zh-CN/temurin/releases/?version=" + Math.max(jc.needMajor(), 21));
                return;
            }
        }
        Path javaExe = jc.javaExe();
        if (jc.autoUpgraded()) {
            app.gameLog.appendText("[JWH] 已按版本自动选择 Java " + jc.actualMajor() + "（该版本需要 Java " + jc.needMajor() + "）\n");
        }
        // 文件检查
        try {
            var missing = app.gameLauncher.checkMissing(v.id());
            if (!missing.isEmpty()) {
                Ui.error("文件缺失", String.join("\n", missing));
                return;
            }
        } catch (Exception e) {
            Ui.error("检查失败", e);
            return;
        }

        // Mod 兼容性预检：缺少前置 / 冲突 / 版本不匹配，有问题弹窗提示解决办法，可取消启动
        Path modsDir = app.config.gameDirFor(v.id()).resolve("mods");
        List<ModCheck.Issue> modIssues = ModCheck.check(modsDir, v.mcBase(), v.loaderVer(),
                Runtime.version().feature());
        if (!modIssues.isEmpty()) {
            StringBuilder sb = new StringBuilder("检测到 " + modIssues.size() + " 个 Mod 兼容性问题：\n\n");
            for (ModCheck.Issue it : modIssues) {
                sb.append("【").append(it.type()).append("】").append(it.modFile()).append("\n")
                        .append("  ").append(it.message()).append("\n")
                        .append("  解决办法：").append(it.solution()).append("\n\n");
            }
            sb.append("仍要启动吗？（建议先按上面的办法处理后重启）");
            if (!Ui.confirm("Mod 兼容性检查", sb.toString())) {
                app.setStatus("已取消启动（存在 Mod 兼容性问题）");
                return;
            }
        }

        app.gameLog.clear();
        app.setStatus("正在启动 " + v.id());
        launchBtn.setDisable(true);
        // 进游戏就把背景音乐停下，免得和游戏声音撞在一起
        if (musicBar() != null) musicBar().pauseForGame();

        // 旧版本曾内置投放 CustomSkinLoader 皮肤模组；该模组在部分版本/加载器下会导致启动崩溃，
        // 现已停用注入。启动前清掉本启动器此前投放到 mods 的固定名 jar（只删这份，不动整合包
        // 自带或用户自装的其它 CustomSkinLoader jar）。离线身份与换肤仍由本地 SkinServer
        // （authlib-injector）完成，不依赖该模组。
        try {
            Path legacySkinJar = app.config.gameDirFor(v.id())
                    .resolve("mods").resolve("customskinloader-universal.jar");
            Files.deleteIfExists(legacySkinJar);
        } catch (Exception ignored) {
        }

        // 离线账号一律启动本地认证/皮肤服务：
        // 新版 MC（1.21.2+ / 26.x）必须注入 authlib-injector 模拟正版认证，否则进“试玩 Demo”；
        // 同时有皮肤时实现换肤。
        SkinServer skinServer = null;
        if (acc.type == LauncherConfig.Account.Type.OFFLINE) {
            try {
                Path skinsDir = java.nio.file.Path.of(app.config.minecraftDir).resolve("skins");
                skinServer = new SkinServer(skinsDir, uuidNoDash -> {
                    for (LauncherConfig.Account a : app.config.accounts) {
                        if (a.uuid != null && a.uuid.replace("-", "").equals(uuidNoDash)) {
                            return a.userName;
                        }
                    }
                    return null;
                });
            } catch (Exception e) {
                Ui.error("本地认证服务启动失败（离线模式可能无法进入完整版）", e);
                launchBtn.setDisable(false);
                return;
            }
        }
        final SkinServer ss = skinServer;
        // 「多人游戏」带的地址只用这一次，无论成功失败都不留给下一次普通启动
        final String quickPlay = pendingQuickPlay;
        pendingQuickPlay = null;

        // 后台启动：避免阻塞 FX
        Ui.async(() -> {
            Process p = app.gameLauncher.launch(v.id(), acc, javaExe, ss, quickPlay, line -> {
                // 联机端口由陶瓦自己扫（它监听游戏发出的局域网广播），启动器不再解析游戏日志
                Platform.runLater(() -> {
                    app.gameLog.appendText(line + "\n");
                });
            }, code -> {
                List<ModCheck.Issue> issues = ModCheck.check(modsDir, v.mcBase(), v.loaderVer(),
                        Runtime.version().feature());
                Platform.runLater(() -> {
                    if (ss != null) ss.stop();
                    launchBtn.setDisable(false);
                    // 回到启动器，音乐从进游戏前的位置接着放
                    if (musicBar() != null) musicBar().resumeAfterGame();
                    app.setStatus("游戏退出，退出码 " + code);
                    app.gameLog.appendText("\n===== 游戏已退出（代码 " + code + "）=====\n");
                    if (code != 0) {
                        StringBuilder sb = new StringBuilder("游戏异常退出（代码 " + code + "）。\n\n");
                        if (!issues.isEmpty()) {
                            sb.append("可能由以下 Mod 兼容性问题导致：\n\n");
                            for (ModCheck.Issue it : issues) {
                                sb.append("【").append(it.type()).append("】").append(it.modFile()).append("\n")
                                        .append("  ").append(it.message()).append("\n")
                                        .append("  解决办法：").append(it.solution()).append("\n\n");
                            }
                        } else {
                            sb.append("未检测到明显的 Mod 依赖问题。可在「实例管理 → Mod 模组」中逐个禁用 Mod 排查，")
                                    .append("或查看「设置 → 日志」中的游戏输出日志定位原因。");
                        }
                        Ui.error("游戏异常退出", sb.toString());
                    }
                });
            });
            // 配置的 Java 路径失效时清空（设置页可重新配置）
            if (app.config.javaPath != null && !app.config.javaPath.isBlank()
                    && !Files.exists(Path.of(app.config.javaPath))) {
                app.config.javaPath = "";
            }
            // 联机端口/进程识别都由陶瓦内核自己完成，这里不需要再交进程号
            return null;
        }, ok -> {
            launchBtn.setDisable(false);
            app.setStatus("游戏进程已启动");
            app.gameLog.appendText("===== 游戏已启动（关闭启动器不影响游戏运行）=====\n");
        }, err -> {
            List<ModCheck.Issue> issues = ModCheck.check(modsDir, v.mcBase(), v.loaderVer(),
                    Runtime.version().feature());
            Platform.runLater(() -> {
                if (ss != null) ss.stop();
                launchBtn.setDisable(false);
                // 启动失败也把音乐恢复，别让播放器一直哑着
                if (musicBar() != null) musicBar().resumeAfterGame();
                app.setStatus("启动失败");
                app.gameLog.appendText("===== 启动失败：" + err.getMessage() + " =====\n");
                StringBuilder sb = new StringBuilder("游戏启动失败：" + err.getMessage() + "\n\n");
                if (!issues.isEmpty()) {
                    sb.append("可能由以下 Mod 兼容性问题导致：\n\n");
                    for (ModCheck.Issue it : issues) {
                        sb.append("【").append(it.type()).append("】").append(it.modFile()).append("\n")
                                .append("  ").append(it.message()).append("\n")
                                .append("  解决办法：").append(it.solution()).append("\n\n");
                    }
                } else {
                    sb.append("若本版本使用了 Mod，可先在「实例管理 → Mod 模组」中逐个禁用后重试；")
                            .append("仍失败请查看「设置 → 日志」中的游戏输出日志定位原因。");
                }
                Ui.error("启动失败", sb.toString());
            });
        });
    }

    private void repairVersion() {
        LocalVersions.LocalVersion v = versionList.getSelectionModel().getSelectedItem();
        if (v == null) return;
        Ui.asyncProgress(progress -> {
            try {
                new GameInstaller(app.config).repair(v.id(), (step, done, total) ->
                        progress.accept(step + (total > 0 ? "  " + done + "/" + total : "")));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, s -> app.setStatus(s), ok -> {
            app.setStatus("修复完成");
            refresh();
            Ui.info("修复完成", "缺失文件已重新下载。");
        }, err -> Ui.error("修复失败", err));
    }

    private void deleteVersion() {
        deleteVersion(versionList.getSelectionModel().getSelectedItem());
    }

    private void deleteVersion(LocalVersions.LocalVersion v) {
        if (v == null) return;
        if (!Ui.confirm("删除版本", "确定删除版本 " + v.id() + " 吗？\n（saves/mods 等数据会一并删除，请谨慎操作）")) {
            return;
        }
        Path dir = app.localVersions.versionDir(v.id());
        try {
            java.util.stream.Stream<Path> walk = Files.walk(dir);
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                }
            });
            walk.close();
        } catch (Exception e) {
            Ui.error("删除失败", e);
            return;
        }
        refresh();
    }
}
