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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 「多人游戏」内置页（不弹窗，走 LauncherApp.showSubPage 融进主窗口）。
 *
 * <p>联机内核是自管的 EasyTier（见 {@link RelayService}），不经过陶瓦。
 * 房号体系沿用发号服务：房主建房前向服务器要一个 6 位短号 + 完整码，朋友只报 6 位，
 * 启动器换回完整码并派生同一张虚拟网。发号服务器只管发号与登记端口，游戏流量走中继转发。</p>
 */
public final class MultiplayerView {

    private MultiplayerView() {
    }

    private static Label shortLabel;
    private static Label fullLabel;
    private static Label statusLabel;
    private static Label playerLabel;
    private static Label serverLabel;
    private static TextField codeField;

    /** 状态栏当前的真实联机状态文字，复制提示盖住它之后用它还原。 */
    private static String stableStatus = "";

    /** 本次建房从发号服务器拿到的 6 位短号（空 = 没走服务器）。 */
    private static volatile String activeShort = "";
    /** 本次建房从发号服务器拿到的完整码。 */
    private static volatile String expectedCode = "";

    private static boolean guestAutoLaunched;
    private static String lastRoom = "";

    public static VBox content(LauncherApp app, HomeView home) {
        // 每次进入本页都按最新设置同步发号服务地址
        RoomCodeService.configure(app.config.effectiveRoomServer(), app.config.effectiveRoomToken());

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        Label head = new Label("多人游戏 · 直连联机");
        head.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:14px; -fx-font-weight:bold;");
        Label sub = new Label(RoomCodeService.configured()
                ? "房主建房会从你的发号服务器拿一个 6 位房间号，朋友报这 6 位就能进；"
                  + "服务器只负责发号和登记端口，游戏流量走公共中继，不经过服务器。"
                : "房主建房后拿到一串 16 位房间码，朋友粘进去就能进同一个世界。"
                  + "联机内核随启动器分发，朋友不用另装任何客户端。");
        sub.setWrapText(true);
        sub.setMinWidth(0);
        sub.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        // ---- 左卡：创建房间 ----
        Label hostTitle = new Label("创建房间（我是房主）");
        hostTitle.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:13px; -fx-font-weight:bold;");

        shortLabel = new Label("------");
        shortLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:26px; -fx-font-weight:bold;");
        shortLabel.setOnMouseClicked(e -> copyOut(activeShort.isEmpty() ? expectedCode : activeShort, "房间号"));

        fullLabel = new Label("U/---- ---- ---- ----");
        fullLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
        fullLabel.setOnMouseClicked(e -> copyOut(expectedCode, "完整房间码"));

        Label codeHint = new Label(RoomCodeService.configured()
                ? "上面大号是 6 位房间号，下面是完整码（点一下都能复制）。"
                : "创建后这里显示房间码，点一下可复制，把它发给朋友。");
        codeHint.setWrapText(true);
        codeHint.setMinWidth(0);
        codeHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");

        Button hostBtn = new Button("创建房间");
        stylePrimary(hostBtn);
        Button stopBtn = new Button("断开联机");
        styleGhost(stopBtn);

        Label hostFlow = new Label("房主流程：点「创建房间」（会弹一次管理员授权，联机必须建虚拟网卡）→ "
                + "启动游戏进世界 → 游戏里按 Esc 点「对局域网开放」；\n"
                + "启动器会自己扫到端口并登记到服务器，朋友这时报号就能连进来。");
        hostFlow.setWrapText(true);
        hostFlow.setMinWidth(0);
        hostFlow.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");

        HBox hostBtns = new HBox(8, hostBtn, stopBtn);
        hostBtns.setAlignment(Pos.CENTER_LEFT);

        VBox codeBox = new VBox(2, shortLabel, fullLabel);
        VBox hostCard = card(hostTitle, codeBox, codeHint, hostFlow, spacer(), hostBtns);

        // ---- 右卡：加入房间 ----
        Label joinTitle = new Label("加入房间（我是朋友）");
        joinTitle.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:13px; -fx-font-weight:bold;");

        Label joinCap = new Label(RoomCodeService.configured()
                ? "房主给你的房间号（6 位数字，或完整码）"
                : "房主给你的房间码");
        joinCap.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        codeField = new TextField();
        codeField.setPromptText(RoomCodeService.configured() ? "123456  或  U/XXXX-XXXX-XXXX-XXXX" : "U/XXXX-XXXX-XXXX-XXXX");
        codeField.setPrefWidth(190);
        codeField.setMaxWidth(Double.MAX_VALUE);
        // 边输边分组：只留 0-9 / A-Z，超过 16 位截断（6 位短号也走这条通道，不受影响）
        codeField.setTextFormatter(new TextFormatter<String>(ch -> {
            String n = Terracotta.normalizeRoom(ch.getControlNewText());
            if (ch.isContentChange() && ch.getControlNewText().isEmpty()) return ch;
            String body = n.startsWith("U/") ? n.substring(2) : n;
            return body.replace("-", "").length() <= 16 ? ch : null;
        }));
        codeField.setStyle("-fx-background-color:#ffffff; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-prompt-text-fill:#8a9099; -fx-background-radius:6;"
                + "-fx-border-color:#d7dbe0; -fx-border-radius:6; -fx-padding:8 10 8 10; -fx-font-size:15px;");

        Button joinBtn = new Button("加入房间");
        stylePrimary(joinBtn);

        Label joinFlow = new Label(RoomCodeService.configured()
                ? "填 6 位短号时，启动器会先向发号服务器换成完整码、顺便拿到房主的游戏端口；\n"
                  + "连上后自动启动游戏并直接进房主的世界（同样会弹一次管理员授权）。"
                : "连上后启动器会自动启动游戏并直接进房主的世界。");
        joinFlow.setWrapText(true);
        joinFlow.setMinWidth(0);
        joinFlow.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");

        VBox joinCard = card(joinTitle, joinCap, codeField, joinFlow, spacer(), joinBtn);

        // 两张卡并排；窗口窄时靠弹性最小宽度自动收，不硬挤
        HBox cards = new HBox(12, hostCard, joinCard);
        cards.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(hostCard, Priority.ALWAYS);
        HBox.setHgrow(joinCard, Priority.ALWAYS);
        hostCard.setMinWidth(240);
        joinCard.setMinWidth(240);
        VBox.setVgrow(cards, Priority.ALWAYS);

        // ---- 底部状态 ----
        Label statusCap = new Label("联机状态");
        statusCap.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
        statusLabel = new Label();
        statusLabel.setWrapText(true);
        statusLabel.setMinWidth(0);
        statusLabel.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:12px;");

        playerLabel = new Label();
        playerLabel.setWrapText(true);
        playerLabel.setMinWidth(0);
        playerLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");

        serverLabel = new Label(RoomCodeService.configured()
                ? "发号服务：" + RoomCodeService.serverAddress()
                : "发号服务：未配置（当前只能用完整房间码，没有 6 位短号）");
        serverLabel.setWrapText(true);
        serverLabel.setMinWidth(0);
        serverLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");

        // serverLabel（发号服务地址）按要求不在界面展示，字段保留但不加入状态区。
        VBox statusBox = new VBox(4, statusCap, statusLabel, playerLabel);
        statusBox.setPadding(new Insets(10, 12, 10, 12));
        statusBox.setStyle("-fx-background-color: rgba(255,255,255,0.75); -fx-background-radius:10;"
                + "-fx-border-color:#e3e6ea; -fx-border-radius:10;");

        root.getChildren().addAll(head, sub, cards, statusBox);

        // ---- 状态订阅 ----
        RelayService.setListener(s -> Platform.runLater(() -> render(app, home, s)));
        render(app, home, RelayService.state());

        hostBtn.setOnAction(e -> {
            if (RelayService.state().isHost()) {
                app.showToast("已经在房间里：" + (activeShort.isEmpty() ? expectedCode : activeShort));
                return;
            }
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
                statusLabel.setText("创建房间失败：" + err.getMessage());
                app.showToast("创建房间失败，详见联机状态");
            });
        });

        stopBtn.setOnAction(e -> {
            RoomCodeService.release(activeShort);
            RelayService.idle();
            guestAutoLaunched = false;
            lastRoom = "";
            activeShort = "";
            expectedCode = "";
            clearCodes();
            statusLabel.setText("已断开联机。");
            app.showToast("已断开联机");
        });

        joinBtn.setOnAction(e -> {
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
        });

        return root;
    }

    private static void render(LauncherApp app, HomeView home, RelayService.State s) {
        if (statusLabel == null) return;
        String kind = s.kind == null ? "" : s.kind;

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
        if (kind.startsWith("host-")) {
            playerLabel.setText((vpid > 0 ? "联机内核已启动（pid " + vpid + "）；" : "")
                    + "你在这张网里是 " + RelayService.HOST_IP);
        } else if (kind.startsWith("guest-")) {
            playerLabel.setText((vpid > 0 ? "联机内核已启动（pid " + vpid + "）；" : "")
                    + "你在这张网里是 " + RelayService.GUEST_IP);
        } else {
            playerLabel.setText("");
        }

        // 记住真实状态，供复制提示结束后还原
        stableStatus = statusLabel.getText() == null ? "" : statusLabel.getText();
    }

    /**
     * 显示 6 位短号。{@code active=false} 表示号已从服务器拿到、但通道还没建好
     * （此时朋友查号能查到，但连不上），用灰色区分；host-ready 之后才点亮。
     */
    private static void showShortCode(String shortCode, boolean active) {
        if (shortLabel == null || shortCode == null || shortCode.isBlank()) return;
        shortLabel.setText(shortCode);
        shortLabel.setStyle("-fx-text-fill:" + (active ? Ui.ACCENT : "#8a9199")
                + "; -fx-font-size:26px; -fx-font-weight:bold;");
    }

    private static void clearCodes() {
        if (shortLabel != null) {
            shortLabel.setText("------");
            shortLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:26px; -fx-font-weight:bold;");
        }
        if (fullLabel != null) {
            fullLabel.setText("U/---- ---- ---- ----");
            fullLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
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

    private static Region spacer() {
        Region r = new Region();
        VBox.setVgrow(r, Priority.ALWAYS);
        return r;
    }

    private static VBox card(javafx.scene.Node... nodes) {
        VBox box = new VBox(8, nodes);
        box.setPadding(new Insets(14, 14, 14, 14));
        box.setStyle("-fx-background-color: rgba(255,255,255,0.75); -fx-background-radius:10;"
                + "-fx-border-color:#e3e6ea; -fx-border-radius:10;");
        return box;
    }

    private static void stylePrimary(Button b) {
        b.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:#ffffff; -fx-font-size:13px;"
                + "-fx-background-radius:6; -fx-padding:8 18 8 18; -fx-cursor:hand;");
    }

    private static void styleGhost(Button b) {
        b.setStyle("-fx-background-color:#ffffff; -fx-text-fill:" + Ui.TEXT + "; -fx-font-size:13px;"
                + "-fx-background-radius:6; -fx-border-color:#d7dbe0; -fx-border-radius:6;"
                + "-fx-padding:8 16 8 16; -fx-cursor:hand;");
    }
}
