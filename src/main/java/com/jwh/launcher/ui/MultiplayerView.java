package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.net.RelayService;
import com.jwh.launcher.net.RoomCodeService;
import com.jwh.launcher.net.Terracotta;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 「多人游戏」弹窗内容：居中悬浮两栏（左创建房间 / 右加入房间）。
 *
 * <p>外壳透明 Stage 由 {@link MultiplayerDialog} 提供；本类只构建面板内容并绑定联机逻辑。
 * 联机内核是自管的 EasyTier（见 {@link RelayService}），房号体系沿用发号服务：
 * 房主建房前向服务器要一个 6 位短号 + 完整码，朋友只报 6 位，启动器换回完整码并派生同一张虚拟网。</p>
 */
public final class MultiplayerView {

    private MultiplayerView() {
    }

    private static Label shortLabel;
    private static Label fullLabel;
    private static Label statusLabel;
    private static Label playerLabel;
    private static TextField codeField;
    private static Button hostBtn;
    private static Button joinBtn;
    private static VBox codeBox;

    // ---- 起始两栏页（房间内页已迁至 MultiplayerRoomView 主界面内置页） ----
    private static VBox lobbyPage;

    /** 状态栏当前的真实联机状态文字，复制提示盖住它之后用它还原。 */
    private static String stableStatus = "";

    /** 本次建房从发号服务器拿到的 6 位短号（空 = 没走服务器）。 */
    static volatile String activeShort = "";
    /** 本次建房从发号服务器拿到的完整码。 */
    static volatile String expectedCode = "";

    private static boolean guestAutoLaunched;
    private static String lastRoom = "";
    /** 弹窗关闭回调：房主进入 host 态切到内置房间页时由 render 触发。 */
    private static Runnable onCloseAction;

    // ---- 弹窗配色（对齐多人游戏稿面） ----
    private static final String PANEL_BG = "#2B1C31";
    private static final String PANEL_BORDER = "rgba(232,158,203,0.16)";
    private static final String ICON_BOX = "#3D2640";
    private static final String INPUT_BG = "#3E3044";
    private static final String INPUT_BORDER = "rgba(232,158,203,0.35)";
    private static final String DIM = "#a893b0";
    private static final String TITLE = "#f2eef4";
    /** 栏内按钮 / 输入框统一内容宽（对齐稿面：按钮不撑满整栏、两侧留白居中）。 */
    private static final double CONTENT_W = 290;

    public static VBox panel(LauncherApp app, HomeView home, Runnable onClose) {
        // 每次进入弹窗都按最新设置同步发号服务地址
        RoomCodeService.configure(app.config.effectiveRoomServer(), app.config.effectiveRoomToken());
        onCloseAction = onClose;

        // ---- 标题行：标题 + 关闭钮 ----
        Label titleLabel = new Label("多人游戏");
        titleLabel.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:" + TITLE + ";");
        Button closeX = new Button("✕");
        closeX.setStyle("-fx-background-color:#0B060F; -fx-text-fill:#cfc4d4; -fx-border-width:0;"
                + " -fx-background-radius:9; -fx-padding:3 8; -fx-cursor:hand; -fx-font-size:13px;");
        closeX.setOnAction(e -> { if (onClose != null) onClose.run(); });
        closeX.setOnMouseEntered(e -> closeX.setStyle("-fx-background-color:#241629; -fx-text-fill:#ffffff; -fx-border-width:0;"
                + " -fx-background-radius:9; -fx-padding:3 8; -fx-cursor:hand; -fx-font-size:13px;"));
        closeX.setOnMouseExited(e -> closeX.setStyle("-fx-background-color:#0B060F; -fx-text-fill:#cfc4d4; -fx-border-width:0;"
                + " -fx-background-radius:9; -fx-padding:3 8; -fx-cursor:hand; -fx-font-size:13px;"));
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(titleLabel, titleSpacer, closeX);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        // ---- 左栏：创建房间 ----
        Label hostTitle = new Label("创建房间");
        hostTitle.setStyle("-fx-text-fill:" + TITLE + "; -fx-font-size:15px; -fx-font-weight:bold;");

        Label hostDesc = new Label("以当前版本与模组创建一个局域网房间，生成 6 位邀请码后分享给好友即可联机。");
        hostDesc.setWrapText(true);
        hostDesc.setMinWidth(0);
        hostDesc.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        hostDesc.setAlignment(Pos.CENTER);

        shortLabel = new Label("------");
        shortLabel.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:24px; -fx-font-weight:bold;");
        shortLabel.setOnMouseClicked(e -> copyOut(activeShort.isEmpty() ? expectedCode : activeShort, "房间号"));
        fullLabel = new Label("U/---- ---- ---- ----");
        fullLabel.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        fullLabel.setOnMouseClicked(e -> copyOut(expectedCode, "完整房间码"));
        codeBox = new VBox(2, shortLabel, fullLabel);
        codeBox.setAlignment(Pos.CENTER);
        codeBox.setVisible(false);
        codeBox.setManaged(false);

        hostBtn = new Button("创建房间");
        stylePrimary(hostBtn);

        Label hostHint = new Label("创建后自动生成邀请码，好友可直接加入");
        hostHint.setWrapText(true);
        hostHint.setMinWidth(0);
        hostHint.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10px;");
        hostHint.setAlignment(Pos.CENTER);

        // 左栏内容区：说明 + 房间号（codeBox 待机隐藏）。
        VBox hostContent = new VBox(2, hostDesc, codeBox);
        hostContent.setAlignment(Pos.CENTER);
        hostContent.setMinWidth(0);

        // ---- 右栏：加入房间 ----
        Label joinTitle = new Label("加入房间");
        joinTitle.setStyle("-fx-text-fill:" + TITLE + "; -fx-font-size:15px; -fx-font-weight:bold;");

        codeField = new TextField();
        codeField.setPromptText("请输入 6 位邀请码");
        codeField.setPrefWidth(CONTENT_W);
        codeField.setMaxWidth(CONTENT_W);
        // 边输边分组：只留 0-9 / A-Z，超过 16 位截断（6 位短号也走这条通道，不受影响）
        codeField.setTextFormatter(new TextFormatter<String>(ch -> {
            String n = Terracotta.normalizeRoom(ch.getControlNewText());
            if (ch.isContentChange() && ch.getControlNewText().isEmpty()) return ch;
            String body = n.startsWith("U/") ? n.substring(2) : n;
            return body.replace("-", "").length() <= 16 ? ch : null;
        }));
        codeField.setStyle("-fx-background-color:" + INPUT_BG + "; -fx-text-fill:" + TITLE + ";"
                + "-fx-prompt-text-fill:#9a86a0; -fx-background-radius:12;"
                + "-fx-border-color:" + INPUT_BORDER + "; -fx-border-radius:12; -fx-padding:10 12; -fx-font-size:13px;");

        joinBtn = new Button("加入房间");
        stylePrimary(joinBtn);

        Label joinHint = new Label("输入房主分享的邀请码，回车即可加入");
        joinHint.setWrapText(true);
        joinHint.setMinWidth(0);
        joinHint.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:10px;");
        joinHint.setAlignment(Pos.CENTER);

        // 左右两栏用 5 行 × 2 列 GridPane 对齐：图标 / 标题 / 内容 / 按钮 / 小字。
        // 按钮固定在第 3 行，两侧按钮处于同一水平线。
        GridPane cards = new GridPane();
        cards.setAlignment(Pos.TOP_CENTER);
        cards.setVgap(18);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setPercentWidth(50);
        c0.setFillWidth(true);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50);
        c1.setFillWidth(true);
        cards.getColumnConstraints().addAll(c0, c1);

        cards.add(hostIcon(), 0, 0);
        cards.add(guestIcon(), 1, 0);
        cards.add(hostTitle, 0, 1);
        cards.add(joinTitle, 1, 1);
        cards.add(hostContent, 0, 2);
        cards.add(codeField, 1, 2);
        cards.add(hostBtn, 0, 3);
        cards.add(joinBtn, 1, 3);
        cards.add(hostHint, 0, 4);
        cards.add(joinHint, 1, 4);

        // 每个格子内水平居中、顶部对齐。
        for (javafx.scene.Node n : cards.getChildren()) {
            GridPane.setHalignment(n, javafx.geometry.HPos.CENTER);
            GridPane.setValignment(n, javafx.geometry.VPos.TOP);
        }

        // 底部状态条界面已隐藏；statusLabel/playerLabel 仍在后台收集联机状态，只是不再挂到场景。
        statusLabel = new Label();
        playerLabel = new Label();

        // 起始两栏页。房主进入 host 态后由 render 关闭弹窗、切到主界面内置房间页。
        lobbyPage = new VBox(cards);

        VBox root = new VBox(12, titleRow, lobbyPage);
        root.setPadding(new Insets(14, 20, 14, 20));
        root.setPrefWidth(760);
        root.setMinWidth(760);
        root.setStyle("-fx-background-color:" + PANEL_BG + "; -fx-background-radius:18; -fx-border-radius:18;"
                + " -fx-border-color:" + PANEL_BORDER + ";");

        // ---- 状态订阅 ----
        RelayService.setListener(s -> Platform.runLater(() -> render(app, home, s)));
        render(app, home, RelayService.state());

        hostBtn.setPrefWidth(CONTENT_W);
        hostBtn.setMaxWidth(CONTENT_W);
        joinBtn.setPrefWidth(CONTENT_W);
        joinBtn.setMaxWidth(CONTENT_W);

        hostBtn.setOnAction(e -> {
            if (RelayService.state().isHost()) {
                stopHost();
                return;
            }
            startHost(app);
        });

        joinBtn.setOnAction(e -> joinRoom(app));
        codeField.setOnAction(e -> joinRoom(app));

        return root;
    }

    // ------------------------------------------------------------------ 建房 / 断开

    private static void startHost(LauncherApp app) {
        hostBtn.setDisable(true);
        joinBtn.setDisable(true);
        statusLabel.setText(RoomCodeService.configured()
                ? "正在向发号服务器申请房间号…" : "正在准备联机通道…");
        Ui.async2(() -> {
            if (!RoomCodeService.configured()) {
                throw new java.io.IOException("这台启动器没有配发号服务（设置 → 联机房间号）。"
                        + "联机需要一个完整房间码来派生网络，请先把发号服务打开。");
            }
            RoomCodeService.Ticket t = RoomCodeService.alloc(playerName(app));
            activeShort = t.shortCode;
            expectedCode = t.code;
            Platform.runLater(() -> showShortCode(t.shortCode, false));
            RelayService.host(t.code, t.shortCode);
            return t;
        }, res -> {
            hostBtn.setDisable(false);
            joinBtn.setDisable(false);
            RoomCodeService.Ticket t = (RoomCodeService.Ticket) res;
            statusLabel.setText("房间号 " + t.shortCode + " 已分配，正在建立联机通道…");
        }, err -> {
            hostBtn.setDisable(false);
            joinBtn.setDisable(false);
            System.err.println("[ROOM] alloc failed, server=" + RoomCodeService.serverAddress());
            err.printStackTrace();
            statusLabel.setText("创建房间失败：" + err.getMessage());
            app.showToast("创建房间失败，详见联机状态");
        });
    }

    static void stopHost() {
        RoomCodeService.release(activeShort);
        RelayService.idle();
        guestAutoLaunched = false;
        lastRoom = "";
        activeShort = "";
        expectedCode = "";
        clearCodes();
        statusLabel.setText("已断开联机。");
    }

    // ------------------------------------------------------------------ 加入

    private static void joinRoom(LauncherApp app) {
        String raw = codeField.getText() == null ? "" : codeField.getText().trim();
        if (raw.isEmpty()) {
            statusLabel.setText("先填房间号：6 位数字，或房主给的完整码。");
            return;
        }
        hostBtn.setDisable(true);
        joinBtn.setDisable(true);
        guestAutoLaunched = false;
        statusLabel.setText(RoomCodeService.isShortCode(raw)
                ? "正在把 6 位短号换成完整码…" : "正在解析房间信息…");
        Ui.async2(() -> {
            RoomCodeService.Target t = RoomCodeService.target(raw);
            lastRoom = t.code;
            Platform.runLater(() -> statusLabel.setText("已找到房间，正在建立联机通道…"));
            RelayService.join(t.code, t.port, RoomCodeService.isShortCode(raw) ? raw : "");
            return t;
        }, res -> {
            hostBtn.setDisable(false);
            joinBtn.setDisable(false);
            RoomCodeService.Target t = (RoomCodeService.Target) res;
            if (t.port <= 0) {
                statusLabel.setText("房主还没有开局域网（服务器上没有端口信息）。"
                        + "让房主进游戏、按 Esc 点「对局域网开放」，等他那边准备好了再点一次「加入房间」。");
            } else {
                statusLabel.setText("房主游戏端口 " + t.port + " 已找到，正在建立联机通道（可能需要点一次 UAC）…");
            }
        }, err -> {
            hostBtn.setDisable(false);
            joinBtn.setDisable(false);
            statusLabel.setText("连接失败：" + err.getMessage());
        });
    }

    // ------------------------------------------------------------------ 状态渲染

    private static void render(LauncherApp app, HomeView home, RelayService.State s) {
        if (statusLabel == null) return;
        String kind = s.kind == null ? "" : s.kind;
        boolean hostSide = kind.startsWith("host-");
        boolean guestSide = kind.startsWith("guest-");

        if (!s.room.isEmpty()) {
            expectedCode = s.room;
            fullLabel.setText(s.room);
            fullLabel.setStyle("-fx-text-fill:" + Ui.ACCENT + "; -fx-font-size:11px;");
        }

        switch (kind) {
            case "offline":
                statusLabel.setText("还没有联机。房主点左边的「创建房间」，朋友在右边填房间号点「加入房间」。");
                break;
            case "host-starting":
                statusLabel.setText(s.detail.isEmpty() ? "正在建立联机通道…" : s.detail);
                break;
            case "host-waiting-port":
                statusLabel.setText("【还差最后一步】" + (s.detail.isEmpty()
                        ? "现在启动游戏 → 进存档 → 按 Esc →「对局域网开放」→「创建局域网世界」；"
                          + "看到聊天栏「本地游戏已在端口 xxxxx 上开启」后，这里会自动变成「房间已就绪」。"
                        : s.detail));
                break;
            case "host-ready":
                RoomCodeService.updatePort(RelayService.hostPort());
                if (!activeShort.isEmpty()) {
                    showShortCode(activeShort, true);
                }
                statusLabel.setText("房间已就绪！房间号 " + (activeShort.isEmpty() ? expectedCode : activeShort)
                        + "，朋友报这个号就能进你的世界"
                        + (s.url.isEmpty() ? "。" : "（他那边会自动连到 " + s.url + "）。"));
                break;
            case "guest-starting":
                statusLabel.setText(s.detail.isEmpty()
                        ? "正在建立联机通道（大约 5–15 秒，可能需要点一次 UAC）…" : s.detail);
                break;
            case "guest-connecting":
                statusLabel.setText(s.detail.isEmpty() ? "正在连接房主…" : s.detail);
                break;
            case "guest-ready":
                statusLabel.setText("已连上房主的游戏" + (s.url.isEmpty() ? "" : "（" + s.url + "）")
                        + "，正在启动游戏…");
                if (!guestAutoLaunched) {
                    guestAutoLaunched = true;
                    String addr = s.url;
                    app.showHome();
                    home.launchToServer(addr);
                }
                break;
            case "error":
                statusLabel.setText("联机中断：" + (s.error.isEmpty() ? "未知错误。" : s.error));
                break;
            default:
                statusLabel.setText("联机状态：" + kind);
                break;
        }

        int vpid = RelayService.pid();
        if (hostSide) {
            playerLabel.setText((vpid > 0 ? "联机内核已启动（pid " + vpid + "）；" : "")
                    + "你在这张网里是 " + RelayService.HOST_IP);
        } else if (guestSide) {
            playerLabel.setText((vpid > 0 ? "联机内核已启动（pid " + vpid + "）；" : "")
                    + "你在这张网里是 " + RelayService.GUEST_IP);
        } else {
            playerLabel.setText("");
        }

        // 主按钮：建房中/已建房 → 变「断开联机」；其余 → 「创建房间」
        if (hostSide) {
            hostBtn.setText("断开联机");
            styleGhost(hostBtn);
        } else {
            hostBtn.setText("创建房间");
            stylePrimary(hostBtn);
        }

        // 记住真实状态，供复制提示结束后还原
        stableStatus = statusLabel.getText() == null ? "" : statusLabel.getText();

        // 房主态：关闭起始弹窗，切到主界面内置房间页；其余状态留在起始两栏。
        if (hostSide) {
            if (onCloseAction != null) onCloseAction.run();
            app.showMultiplayerRoom();
        }
    }

    /**
     * 显示 6 位短号。{@code active=false} 表示号已从服务器拿到、但通道还没建好
     * （此时朋友查号能查到，但连不上），用灰色区分；host-ready 之后才点亮。
     */
    private static void showShortCode(String shortCode, boolean active) {
        if (shortLabel == null || shortCode == null || shortCode.isBlank()) return;
        shortLabel.setText(shortCode);
        shortLabel.setStyle("-fx-text-fill:" + (active ? Ui.ACCENT : "#8a9199")
                + "; -fx-font-size:24px; -fx-font-weight:bold;");
        if (codeBox != null) {
            codeBox.setVisible(true);
            codeBox.setManaged(true);
        }
    }

    private static void clearCodes() {
        if (shortLabel != null) {
            shortLabel.setText("------");
            shortLabel.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:24px; -fx-font-weight:bold;");
        }
        if (fullLabel != null) {
            fullLabel.setText("U/---- ---- ---- ----");
            fullLabel.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        }
        if (codeBox != null) {
            codeBox.setVisible(false);
            codeBox.setManaged(false);
        }
    }

    private static void copyOut(String text, String what) {
        if (text == null || text.isBlank()) return;
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
        if (statusLabel != null) {
            // 复制提示只闪 3 秒就还原真实联机状态，
            // 否则会把「还在等本机游戏开局域网」这类关键阶段永久盖住，房主会误以为已经建好房。
            String restore = stableStatus;
            statusLabel.setText(what + "已复制：" + text);
            javafx.animation.PauseTransition pt =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
            pt.setOnFinished(e -> {
                if (statusLabel != null && restore != null && !restore.isEmpty()) {
                    statusLabel.setText(restore);
                }
            });
            pt.play();
        }
    }

    /** 用当前选中账号的名字当房间里的显示名。 */
    private static String playerName(LauncherApp app) {
        try {
            com.jwh.launcher.config.LauncherConfig.Account acc = app.auth.currentAccount(false);
            if (acc != null && acc.displayName != null && !acc.displayName.isBlank()) return acc.displayName;
        } catch (Exception ignored) {
        }
        return "JWH-Player";
    }

    // ------------------------------------------------------------------ 图标

    /** 左栏：房子 + 加号（创建房间）位图图标，直接取设计稿抠图以保证造型一致。 */
    private static final Image HOST_IMG = loadIcon("/icons/mp_host.png");
    /** 右栏：双人（加入房间）位图图标。 */
    private static final Image GUEST_IMG = loadIcon("/icons/mp_guest.png");

    private static StackPane hostIcon() {
        return imageBox(HOST_IMG);
    }

    private static StackPane guestIcon() {
        return imageBox(GUEST_IMG);
    }

    private static StackPane imageBox(Image img) {
        StackPane box = new StackPane();
        box.setPrefSize(78, 78);
        box.setMinSize(78, 78);
        box.setMaxSize(78, 78);
        box.setStyle("-fx-background-color:" + ICON_BOX + "; -fx-background-radius:20;");
        if (img != null) {
            ImageView view = new ImageView(img);
            view.setFitWidth(78);
            view.setFitHeight(78);
            view.setSmooth(true);
            box.getChildren().add(view);
        }
        return box;
    }

    private static Image loadIcon(String path) {
        try (java.io.InputStream in = MultiplayerView.class.getResourceAsStream(path)) {
            return in == null ? null : new Image(in);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 按钮样式

    private static void stylePrimary(Button b) {
        b.setStyle("-fx-background-color:linear-gradient(to bottom, #eca6d6 0%, #d97ec2 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:13px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-padding:9 16; -fx-cursor:hand;");
        b.setOnMouseEntered(e -> b.setStyle("-fx-background-color:linear-gradient(to bottom, #f2b8de 0%, #e38ccb 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:13px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-padding:9 16; -fx-cursor:hand;"));
        b.setOnMouseExited(e -> b.setStyle("-fx-background-color:linear-gradient(to bottom, #eca6d6 0%, #d97ec2 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:13px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-padding:9 16; -fx-cursor:hand;"));
    }

    private static void styleGhost(Button b) {
        b.setStyle("-fx-background-color:" + INPUT_BG + "; -fx-text-fill:" + TITLE
                + "; -fx-font-size:13px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-color:" + INPUT_BORDER + "; -fx-border-radius:14;"
                + " -fx-padding:11 18; -fx-cursor:hand;");
        b.setOnMouseEntered(e -> b.setStyle("-fx-background-color:#4a3a52; -fx-text-fill:" + TITLE
                + "; -fx-font-size:13px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-color:" + INPUT_BORDER + "; -fx-border-radius:14;"
                + " -fx-padding:9 16; -fx-cursor:hand;"));
        b.setOnMouseExited(e -> b.setStyle("-fx-background-color:" + INPUT_BG + "; -fx-text-fill:" + TITLE
                + "; -fx-font-size:13px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-color:" + INPUT_BORDER + "; -fx-border-radius:14;"
                + " -fx-padding:9 16; -fx-cursor:hand;"));
    }
}
