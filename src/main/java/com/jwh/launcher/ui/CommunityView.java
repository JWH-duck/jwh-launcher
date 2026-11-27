package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import com.jwh.launcher.download.BbsmcMaps;
import com.jwh.launcher.download.IconCache;
import javafx.animation.PauseTransition;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.FlowPane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 社区页：站点百科入口、地图搜索与地图卡片网格、右侧「您知道吗」事实面板。
 * 布局对照设计稿「资讯.svg」；功能数据先占位，后续接 BBSMC / DYK 等真实数据源。
 */
public class CommunityView {

    private final LauncherApp app;

    /** 白框轮播：官网精选更新卡，一次只显示一张，左下角圆点切换。 */
    private final List<WikiHighlight.Card> highlightCards = new ArrayList<>();
    private int highlightSel = 0;
    private StackPane highlightLayer;
    private HBox highlightDots;
    /** 自动轮播后台定时线程：每 5 秒切下一张并循环；手动点圆点不打断节奏。 */
    private ScheduledExecutorService autoExecutor;
    private ScheduledFuture<?> autoTask;

    // ===== BBSMC 地图板块（搜索 + 两列卡片网格，内部滚动） =====
    private HBox searchBar;
    private VBox mapGrid;
    private ScrollPane mapScroll;
    private TextField mapSearch;
    private ComboBox<String> mapSort;
    private Label mapStatus;
    private Button mapMoreBtn;
    private final List<BbsmcMaps.Item> mapItems = new ArrayList<>();
    private boolean mapLoading;
    private boolean mapLoadedOnce;
    private boolean mapWantTop;
    private int mapTotal;
    private static final int MAP_PAGE = 20;

    /**
     * 「你知道吗」事实池（从主页旧版 DYK 面板迁入）。共 11 批 33 条，
     * 点「更多事实…」在面板内原地淡出换一批，不跳转外部页面；离线可用。
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

    public CommunityView(LauncherApp app) {
        this.app = app;
    }

    public Node root() {
        // 外层垫层：与主页同源的群像背景（CSS cover，避免 ImageView 原生尺寸撑大容器）
        StackPane wrap = new StackPane();
        java.net.URL bgUrl = getClass().getResource("/home_mobs.png");
        String bgCss = bgUrl == null
                ? "-fx-background-color:#0c0c0c;"
                : "-fx-background-color:#0c0c0c; -fx-background-image:url('" + bgUrl.toExternalForm()
                        + "'); -fx-background-size:cover; -fx-background-position:center;";
        wrap.setStyle(bgCss);
        wrap.setMinSize(0, 0);

        // 黑罩压暗底层背景（与主页一致：#0c0c0c @ 76%）
        javafx.scene.shape.Rectangle shade = new javafx.scene.shape.Rectangle();
        shade.setFill(Color.color(0.047, 0.047, 0.047, 0.76));
        shade.widthProperty().bind(wrap.widthProperty());
        shade.heightProperty().bind(wrap.heightProperty());
        wrap.getChildren().add(shade);

        // 内容区
        VBox page = new VBox(16);
        page.setPadding(new Insets(16, 20, 16, 20));
        page.setFillWidth(true);
        // 页面不向上报最小尺寸：换批文字多少只在内部滚动/换行，不能把外层布局顶大
        page.setMinSize(0, 0);

        HBox columns = new HBox(24);
        columns.setAlignment(Pos.TOP_LEFT);
        columns.setMinSize(0, 0);
        columns.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(columns, Priority.ALWAYS);

        VBox leftCol = buildLeftColumn();
        VBox rightCol = buildRightColumn();
        HBox.setHgrow(leftCol, Priority.ALWAYS);
        HBox.setHgrow(rightCol, Priority.ALWAYS);
        leftCol.setMaxHeight(Double.MAX_VALUE);
        rightCol.setMaxHeight(Double.MAX_VALUE);
        columns.getChildren().addAll(leftCol, rightCol);

        page.getChildren().add(columns);
        wrap.getChildren().add(page);
        return wrap;
    }

    // ===== 左列：横幅 + 搜索行 + 地图卡片网格（BBSMC 真实数据，内部滚动） =====
    private VBox buildLeftColumn() {
        VBox col = new VBox(16);
        col.setMinSize(0, 0);
        col.setPrefWidth(0);
        col.setMaxWidth(Double.MAX_VALUE);
        col.setMaxHeight(Double.MAX_VALUE);

        // 顶部白框：官网精选更新卡单页轮播
        col.getChildren().add(buildHighlightBanner());

        // 搜索行：搜索框 + 排序下拉 + 放大镜
        searchBar = new HBox(8);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setMinWidth(0);
        searchBar.setMaxWidth(Double.MAX_VALUE);

        mapSearch = new TextField();
        mapSearch.setPromptText("搜索 地图...");
        mapSearch.setStyle("-fx-background-color:#733e6f; -fx-text-fill:#ffffff;"
                + " -fx-prompt-text-fill:rgba(255,255,255,0.7); -fx-background-radius:10; -fx-border-radius:10;"
                + " -fx-border-color:rgba(255,255,255,0.18); -fx-padding:8 12; -fx-font-size:14px;"
                + " -fx-focus-color:transparent; -fx-faint-focus-color:transparent;");
        HBox.setHgrow(mapSearch, Priority.ALWAYS);
        mapSearch.setOnAction(e -> reloadMaps());
        PauseTransition debounce = new PauseTransition(Duration.millis(500));
        debounce.setOnFinished(e -> reloadMaps());
        mapSearch.textProperty().addListener((o, a, b) -> debounce.playFromStart());

        mapSort = new ComboBox<>();
        mapSort.getItems().addAll("热门", "最新");
        mapSort.getSelectionModel().select(0);
        mapSort.setPrefWidth(96);
        mapSort.getStyleClass().add("jwh-sort-combo");
        mapSort.setStyle("-fx-background-color:transparent; -fx-text-fill:#ffffff;"
                + " -fx-background-radius:10; -fx-border-radius:10; -fx-border-color:rgba(255,255,255,0.18);"
                + " -fx-font-size:13px;");
        mapSort.setOnAction(e -> reloadMaps());

        javafx.scene.shape.Circle lens = new javafx.scene.shape.Circle(7);
        lens.setFill(Color.TRANSPARENT);
        lens.setStroke(Color.web("#1f2328"));
        lens.setStrokeWidth(2);
        javafx.scene.shape.Line handle = new javafx.scene.shape.Line(5.5, 5.5, 9.5, 9.5);
        handle.setStroke(Color.web("#1f2328"));
        handle.setStrokeWidth(2);
        StackPane searchIcon = new StackPane(lens, handle);
        Button searchBtn = new Button();
        searchBtn.setGraphic(searchIcon);
        searchBtn.setPrefSize(38, 38);
        searchBtn.setStyle("-fx-background-color:#ffffff; -fx-background-radius:10; -fx-border-radius:10; -fx-cursor:hand;");
        searchBtn.setOnAction(e -> reloadMaps());

        searchBar.getChildren().addAll(mapSearch, mapSort, searchBtn);
        col.getChildren().add(searchBar);

        // 地图网格：两列卡片，在左列剩余高度内内部滚动，不参与把窗口顶大
        mapGrid = new VBox(8);
        mapGrid.setFillWidth(true);
        mapGrid.setMinWidth(0);
        mapGrid.setMaxWidth(Double.MAX_VALUE);
        mapScroll = new ScrollPane(mapGrid);
        mapScroll.setFitToWidth(true);
        mapScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        mapScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        mapScroll.setPannable(true);
        mapScroll.setMinHeight(0);
        mapScroll.setPrefHeight(0);
        mapScroll.setMaxHeight(Double.MAX_VALUE);
        mapScroll.setStyle("-fx-background-color:transparent; -fx-background:transparent;"
                + "-fx-padding:0; -fx-background-insets:0; -fx-border-width:0;");
        VBox.setVgrow(mapScroll, Priority.ALWAYS);
        mapGrid.heightProperty().addListener((o, ov, nv) -> {
            if (mapWantTop && nv.doubleValue() > 10) {
                mapWantTop = false;
                mapScroll.setVvalue(0);
                Platform.runLater(() -> mapScroll.setVvalue(0));
            }
        });
        col.getChildren().add(mapScroll);

        // 底部状态行：命中统计 + 加载更多
        mapStatus = new Label("");
        mapStatus.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:11px;");
        mapStatus.setTextOverrun(OverrunStyle.ELLIPSIS);
        mapMoreBtn = new Button("加载更多");
        mapMoreBtn.setStyle("-fx-background-color:#ffffff; -fx-text-fill:#1f2328; -fx-background-radius:8;"
                + " -fx-font-size:11px; -fx-padding:3 10; -fx-cursor:hand;");
        mapMoreBtn.setVisible(false);
        mapMoreBtn.setOnAction(e -> loadMaps(false));
        Region footSpacer = new Region();
        HBox.setHgrow(footSpacer, Priority.ALWAYS);
        HBox foot = new HBox(8, mapStatus, footSpacer, mapMoreBtn);
        foot.setAlignment(Pos.CENTER_LEFT);
        col.getChildren().add(foot);

        // 第一次进场景再拉数据，避免页面没显示就发请求
        col.sceneProperty().addListener((o, was, now) -> {
            if (now != null && !mapLoadedOnce) {
                mapLoadedOnce = true;
                reloadMaps();
            }
        });
        return col;
    }

    /**
     * 顶部白框轮播：层内只放当前这张精选卡，底部左下角一排圆点切换；
     * 数据先读本地缓存/内置兜底，后台再拉 minecraft.wiki 官网刷新。
     */
    private AnchorPane buildHighlightBanner() {
        AnchorPane banner = new AnchorPane();
        banner.setStyle("-fx-background-color:#ffffff; -fx-background-radius:12;");
        // 高度锁成固定值：卡片是 2:1 扁卡，不能让内容 pref 反推、也不参与 Vgrow 争抢剩余高度，
        // 否则白框会被一路拉高，把下面的搜索行和地图卡压到底部。
        banner.setPrefHeight(200);
        banner.setMinHeight(200);
        banner.setMaxHeight(Region.USE_PREF_SIZE);

        // 圆角裁剪，避免卡片封面/词条条溢出白框圆角
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(banner.widthProperty());
        clip.heightProperty().bind(banner.heightProperty());
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        banner.setClip(clip);

        highlightLayer = new StackPane();
        highlightLayer.setMaxWidth(Double.MAX_VALUE);
        highlightLayer.setMaxHeight(Double.MAX_VALUE);
        AnchorPane.setTopAnchor(highlightLayer, 0.0);
        AnchorPane.setBottomAnchor(highlightLayer, 0.0);
        AnchorPane.setLeftAnchor(highlightLayer, 0.0);
        AnchorPane.setRightAnchor(highlightLayer, 0.0);
        banner.getChildren().add(highlightLayer);

        highlightDots = new HBox(8);
        highlightDots.setAlignment(Pos.CENTER_LEFT);
        // 圆点固定在卡片左下角、词条条（文本介绍）上方。
        AnchorPane.setLeftAnchor(highlightDots, 16.0);
        AnchorPane.setBottomAnchor(highlightDots, 28.0);
        banner.getChildren().add(highlightDots);

        highlightCards.addAll(WikiHighlight.initialCards());

        // 自动轮播：后台定时线程每 5 秒切下一张并循环；手动点圆点不打断节奏。
        startAutoCarousel();

        showCard(0, false);

        WikiHighlight.refreshAsync().thenAccept(list -> {
            if (list == null) return;
            Platform.runLater(() -> {
                highlightCards.clear();
                highlightCards.addAll(list);
                highlightSel = 0;
                showCard(0, false);
            });
        });
        return banner;
    }

    /** 滑动切换到指定卡片：旧卡向左滑出、新卡从右滑入；animate=false 时直接铺入。 */
    private void showCard(int index, boolean animate) {
        if (highlightCards.isEmpty()) {
            Label tip = new Label("社区精选 / 公告");
            tip.setStyle("-fx-text-fill:#9aa0a8; -fx-font-size:16px;");
            highlightLayer.getChildren().setAll(tip);
            rebuildDots();
            stopAutoCarousel();
            return;
        }
        if (index < 0 || index >= highlightCards.size()) index = 0;
        highlightSel = index;

        Node next = WikiHighlight.buildCard(highlightCards.get(index), index, app);
        Node old = highlightLayer.getChildren().isEmpty() ? null : highlightLayer.getChildren().get(0);
        double w = highlightLayer.getWidth();

        if (!animate || old == null || w <= 0) {
            highlightLayer.getChildren().setAll(next);
        } else {
            next.setTranslateX(w);
            highlightLayer.getChildren().add(next);
            TranslateTransition tOld = new TranslateTransition(Duration.millis(280), old);
            tOld.setToX(-w);
            TranslateTransition tNew = new TranslateTransition(Duration.millis(280), next);
            tNew.setToX(0);
            tOld.setInterpolator(Interpolator.EASE_BOTH);
            tNew.setInterpolator(Interpolator.EASE_BOTH);
            tOld.setOnFinished(e -> {
                highlightLayer.getChildren().setAll(next);
                next.setTranslateX(0);
            });
            tOld.play();
            tNew.play();
        }
        rebuildDots();
    }

    /** 启动后台定时轮播：5 秒间隔，循环；线程为守护线程，随 JVM 退出。 */
    private void startAutoCarousel() {
        stopAutoCarousel();
        autoExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jwh-carousel");
            t.setDaemon(true);
            return t;
        });
        autoTask = autoExecutor.scheduleAtFixedRate(this::nextSlide, 5, 5, TimeUnit.SECONDS);
    }

    /** 停止后台定时轮播。 */
    private void stopAutoCarousel() {
        if (autoTask != null) { autoTask.cancel(false); autoTask = null; }
        if (autoExecutor != null) { autoExecutor.shutdownNow(); autoExecutor = null; }
    }

    /** 自动轮播：后台线程触发，切回 FX 线程循环到下一张。 */
    private void nextSlide() {
        Platform.runLater(() -> {
            if (highlightCards.size() > 1) {
                int next = (highlightSel + 1) % highlightCards.size();
                showCard(next, true);
            }
        });
    }

    /** 重建左下角圆点指示器；点击圆点滑动到对应卡片。 */
    private void rebuildDots() {
        highlightDots.getChildren().clear();
        for (int i = 0; i < highlightCards.size(); i++) {
            int idx = i;
            boolean active = i == highlightSel;
            Circle dot = new Circle(active ? 5 : 4);
            dot.setFill(active ? Color.web("#ffffff") : Color.rgb(255, 255, 255, 0.45));
            dot.setStroke(Color.rgb(0, 0, 0, 0.35));
            dot.setStrokeWidth(1);
            dot.setCursor(Cursor.HAND);
            dot.setOnMouseClicked(e -> {
                if (idx != highlightSel) showCard(idx, true);
            });
            highlightDots.getChildren().add(dot);
        }
    }

    /** 把地图滚动位置拉回顶部：连发两轮 runLater，避开布局与焦点滚动时序。 */
    private void scrollMapsToTop() {
        if (mapScroll == null) return;
        mapWantTop = true;
        Platform.runLater(() -> mapScroll.setVvalue(0));
        Platform.runLater(() -> Platform.runLater(() -> mapScroll.setVvalue(0)));
    }

    /** 按两张一行重排卡片（数据变了或「加载更多」后调用）。 */
    private void rebuildMapRows() {
        if (mapGrid == null) return;
        mapGrid.getChildren().removeIf(n -> n != searchBar);
        if (mapItems.isEmpty()) {
            Label empty = new Label(mapLoading ? "正在加载…" : "暂无地图");
            empty.setStyle("-fx-text-fill:#b9bfc7; -fx-font-size:12px;");
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
                Region filler = new Region();
                filler.setMinWidth(0);
                HBox.setHgrow(filler, Priority.ALWAYS);
                row.getChildren().add(filler);
            }
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

    /** 拉一页地图；reset=true 替换内容，否则追加。 */
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

    /** 一张地图卡：大封面 + 左下角项目小图标 + 标题/作者/简介/标签 + 下载关注数，整卡点进官网详情页。 */
    private VBox buildMapCard(BbsmcMaps.Item it) {
        VBox card = new VBox(0);
        card.setStyle("-fx-background-color:#ffffff; -fx-background-radius:8; -fx-border-radius:8;"
                + " -fx-border-color:rgba(0,0,0,0.08); -fx-border-width:1;");
        card.setMinWidth(0);
        card.setPrefWidth(200);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMaxHeight(Double.MAX_VALUE);
        card.setCursor(Cursor.HAND);
        card.setOnMouseClicked(e -> openMapPage(it));

        // ---- 封面（等比铺满后裁掉溢出部分，图片不拉伸变形） ----
        ImageView cover = new ImageView();
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        cover.setImage(Ui.GRASS_BLOCK);
        StackPane coverBox = new StackPane(cover);
        coverBox.setStyle("-fx-background-color:#eaeaea;");
        coverBox.setMinHeight(100);
        coverBox.setPrefHeight(100);
        coverBox.setMaxHeight(100);
        coverBox.setMinWidth(0);
        cover.fitWidthProperty().bind(coverBox.widthProperty());
        Rectangle clip = new Rectangle();
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        clip.widthProperty().bind(coverBox.widthProperty());
        clip.heightProperty().bind(coverBox.heightProperty());
        coverBox.setClip(clip);

        // 左上角项目图标（骑在封面左下的小方块）
        ImageView mini = new ImageView(Ui.GRASS_BLOCK);
        mini.setFitWidth(28);
        mini.setFitHeight(28);
        mini.setPreserveRatio(false);
        mini.setSmooth(true);
        StackPane miniBox = new StackPane(mini);
        miniBox.setMinSize(30, 30);
        miniBox.setPrefSize(30, 30);
        miniBox.setMaxSize(30, 30);
        miniBox.setStyle("-fx-background-color:#ffffff; -fx-background-radius:6; -fx-border-color:rgba(0,0,0,0.1);"
                + " -fx-border-radius:6; -fx-border-width:1;");
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
        authorL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#6b7280;");
        authorL.setTextOverrun(OverrunStyle.ELLIPSIS);
        authorL.setMaxWidth(Double.MAX_VALUE);
        authorL.setMinWidth(0);

        Label descL = new Label(it.description == null || it.description.isBlank()
                ? "（作者没有写简介）" : it.description);
        descL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#6b7280;");
        descL.setWrapText(true);
        descL.setMaxHeight(18);
        descL.setMinHeight(18);
        descL.setTextOverrun(OverrunStyle.ELLIPSIS);
        descL.setMaxWidth(Double.MAX_VALUE);
        descL.setMinWidth(0);

        FlowPane tagBox = new FlowPane(4, 4);
        tagBox.setMinWidth(0);
        tagBox.setMaxWidth(Double.MAX_VALUE);
        tagBox.setPrefWrapLength(180);
        for (int i = 0; i < it.tags.size() && i < 3; i++) tagBox.getChildren().add(mapTag(it.tags.get(i)));

        Label dlL = new Label("↓ " + BbsmcMaps.num(it.downloads));
        dlL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#6b7280;");
        Label followL = new Label("♥ " + it.follows);
        followL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#6b7280;");
        HBox numBox = new HBox(8, dlL, followL);
        numBox.setAlignment(Pos.CENTER_LEFT);

        Label goL = new Label("官网下载 ↗");
        goL.setStyle("-fx-font-size:12.5px; -fx-text-fill:#3FA9F5; -fx-cursor:hand;");
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
        l.setStyle("-fx-background-color:#eef0f3; -fx-background-radius:4; -fx-padding:1 6;"
                + " -fx-font-size:11.5px; -fx-text-fill:#6b7280;");
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

    // ===== 右列：百科入口 + 事实面板 =====
    private VBox buildRightColumn() {
        VBox col = new VBox(16);
        col.setMinSize(0, 0);
        col.setPrefWidth(0);
        col.setMaxWidth(Double.MAX_VALUE);
        col.setMaxHeight(Double.MAX_VALUE);

        // 三个站点百科入口：圆形图标 + 名称
        HBox entries = new HBox(24);
        entries.setAlignment(Pos.TOP_CENTER);
        entries.setPadding(new Insets(8, 0, 0, 0));
        entries.getChildren().addAll(
                entry("/mcmod_icon.png", "MC百科", "https://www.mcmod.cn/"),
                entry("/mcwiki_icon.png", "MC维基百科", "https://zh.minecraft.wiki/"),
                entry("/bbsmc_icon.png", "BBSMC", "https://bbsmc.net/"));
        col.getChildren().add(entries);

        // 「您知道吗」事实面板（事实池与轮换逻辑从主页旧版 DYK 面板迁入）
        VBox fact = new VBox(14);
        fact.setStyle("-fx-background-color:rgba(255,255,255,0.10); -fx-background-radius:12;"
                + " -fx-border-radius:12; -fx-border-color:rgba(255,255,255,0.10);");
        fact.setPadding(new Insets(20));
        fact.setMinSize(0, 220);
        fact.setPrefWidth(0);
        fact.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(fact, Priority.ALWAYS);

        Label title = new Label("您知道吗...");
        title.setStyle("-fx-font-size:18px; -fx-font-weight:bold; -fx-text-fill:#ffffff;");

        VBox facts = new VBox(3);
        facts.setMinSize(0, 0);
        facts.setPrefWidth(0);
        facts.setMaxWidth(Double.MAX_VALUE);
        final int[] sel = {0};
        Runnable renderFacts = () -> {
            facts.getChildren().clear();
            for (String s : DYK_GROUPS[sel[0]]) {
                facts.getChildren().add(dykFact(s));
            }
        };
        renderFacts.run();

        // 事实区放进可滚动容器：面板高度只由布局决定，不会因每组行数不同而跳动
        ScrollPane scroller = new ScrollPane(facts);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setPannable(true);
        scroller.setMinSize(0, 0);
        scroller.setPrefWidth(0);
        scroller.setPrefHeight(0);
        scroller.setMaxWidth(Double.MAX_VALUE);
        scroller.setMaxHeight(Double.MAX_VALUE);
        scroller.setStyle("-fx-background-color:transparent; -fx-background:transparent;"
                + "-fx-padding:0; -fx-background-insets:0; -fx-border-width:0;");
        VBox.setVgrow(scroller, Priority.ALWAYS);

        Button more = new Button("更多事实...");
        more.setMaxWidth(Double.MAX_VALUE);
        more.setPrefHeight(38);
        more.setStyle("-fx-background-color:#1f1529; -fx-text-fill:#ffffff; -fx-background-radius:10;"
                + " -fx-font-size:14px; -fx-cursor:hand;");
        // 原地淡出 → 换一批 → 淡入，不跳转外部页面
        more.setOnAction(e -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(120), facts);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(ev -> {
                sel[0] = (sel[0] + 1) % DYK_GROUPS.length;
                renderFacts.run();
                scroller.setVvalue(0.0);
                FadeTransition fadeIn = new FadeTransition(Duration.millis(160), facts);
                fadeIn.setFromValue(0.0);
                fadeIn.setToValue(1.0);
                fadeIn.play();
            });
            fadeOut.play();
        });

        fact.getChildren().addAll(title, scroller, more);
        col.getChildren().add(fact);

        return col;
    }

    /** 单条事实：TextFlow 保证窄栏内自动换行，不会被省略号截断。 */
    private TextFlow dykFact(String text) {
        Text t = new Text("• " + text);
        t.setFont(Font.font(12.5));
        t.setFill(Color.rgb(255, 255, 255, 0.72));
        TextFlow flow = new TextFlow(t);
        flow.setMinWidth(0);
        flow.setPrefWidth(0);
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.setLineSpacing(1.5);
        return flow;
    }

    /** 单个站点入口：白底圆形图标 + 下方名称，点击外链打开。 */
    private VBox entry(String iconRes, String name, String url) {
        StackPane circle = new StackPane();
        circle.setPrefSize(64, 64);
        circle.setMinSize(64, 64);
        circle.setMaxSize(64, 64);
        circle.setStyle("-fx-background-color:#ffffff; -fx-background-radius:32;");
        ImageView iv = new ImageView(loadImage(iconRes));
        iv.setFitWidth(36);
        iv.setFitHeight(36);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        circle.getChildren().add(iv);

        Label nameL = new Label(name);
        nameL.setStyle("-fx-text-fill:#ffffff; -fx-font-size:13px;");

        VBox box = new VBox(8, circle, nameL);
        box.setAlignment(Pos.TOP_CENTER);
        box.setCursor(Cursor.HAND);
        box.setOnMouseClicked(e -> {
            try {
                app.openUrl(url);
            } catch (Exception ex) {
                app.showToast("打不开 " + name);
            }
        });
        return box;
    }

    private Image loadImage(String res) {
        try (var in = getClass().getResourceAsStream(res)) {
            return in == null ? null : new Image(in);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 进入页面时刷新（占位：后续接地图 / 事实数据）。 */
    public void refresh() {
    }
}
