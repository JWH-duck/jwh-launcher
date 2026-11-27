package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.net.RelayService;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.HashSet;
import java.util.Set;

/**
 * 多人游戏「房间内页」：主界面内置页面（占满中间内容区，不再是悬浮弹窗）。
 *
 * <p>起始的创建 / 加入房间两栏仍由 {@link MultiplayerView} 弹窗承载；房主进入 host 态后，
 * 弹窗关闭、由 {@link LauncherApp#showMultiplayerRoom()} 切到本页。版面对照设计稿：
 * 左右两个深色圆角面板——左面板房号 / 完整码 / 并排按钮 / 日志，右面板人数 / 玩家网格 / 底部开始游戏。
 * 联机内核目前只掌握本机自己，故玩家网格先呈现房主自己一张卡；在线名单与进出日志待通道支持后再扩充。</p>
 */
public class MultiplayerRoomView {

    private final LauncherApp app;
    private final HomeView home;

    // ---- 配色（对照多人房间稿面取色） ----
    private static final String PANEL_BG = "#1F1529";
    private static final String PANEL_BORDER = "rgba(232,158,203,0.12)";
    private static final String CARD_BG = "#2A2134";
    private static final String CARD_BORDER = "rgba(232,158,203,0.14)";
    private static final String SELF_BORDER = "rgba(232,158,203,0.55)";
    private static final String DIM = "#a893b0";
    private static final String TITLE = "#f2eef4";

    private Label roomShortLabel;
    private Label roomFullLabel;
    private Text countNumText;
    private GridPane playerGrid;
    private VBox logBox;
    private Button roomStartBtn;
    private Button roomLeaveBtn;
    private Button roomBottomStartBtn;
    private Button confirmKickBtn;
    private Button cancelKickBtn;
    private HBox kickBar;
    private VBox bottomSlot;
    private boolean kickArmed = false;
    private final Set<String> selectedKick = new HashSet<>();
    private VBox root;

    public MultiplayerRoomView(LauncherApp app, HomeView home) {
        this.app = app;
        this.home = home;
        build();
        RelayService.setListener(s -> Platform.runLater(() -> refresh(s)));
        refresh(RelayService.state());
    }

    public Node root() {
        return root;
    }

    public void refresh() {
        refresh(RelayService.state());
    }

    // ------------------------------------------------------------------ 装配

    private void build() {
        // ===================== 左面板 =====================
        roomShortLabel = new Label("房间号：------");
        roomShortLabel.setStyle("-fx-text-fill:" + TITLE + "; -fx-font-size:24px; -fx-font-weight:bold;");

        roomFullLabel = new Label("U/---- ---- ---- ----");
        roomFullLabel.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:12px;");
        Button copyBtn = copyIconButton();
        copyBtn.setOnAction(e -> copyOut(currentFullCode(), "完整房间码"));
        HBox fullRow = new HBox(10, roomFullLabel, copyBtn);
        fullRow.setAlignment(Pos.CENTER_LEFT);

        roomStartBtn = new Button("开始游戏");
        stylePrimary(roomStartBtn);
        roomLeaveBtn = new Button("断开联机");
        styleGhost(roomLeaveBtn);
        roomStartBtn.setMaxWidth(Double.MAX_VALUE);
        roomLeaveBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(roomStartBtn, Priority.ALWAYS);
        HBox.setHgrow(roomLeaveBtn, Priority.ALWAYS);
        HBox leftBtns = new HBox(12, roomStartBtn, roomLeaveBtn);
        leftBtns.setAlignment(Pos.CENTER_LEFT);

        logBox = new VBox(6);
        logBox.setAlignment(Pos.TOP_LEFT);

        // 上层面板：房号 / 完整码 / 操作按钮（高度固定，不随日志或联机状态伸缩）
        VBox topPanel = new VBox(20, roomShortLabel, fullRow, leftBtns);
        topPanel.setAlignment(Pos.TOP_LEFT);
        topPanel.setPadding(new Insets(22));
        panelStyle(topPanel);
        topPanel.setPrefHeight(168);
        topPanel.setMinHeight(168);
        topPanel.setMaxHeight(168);

        // 下层面板：独立的「联机信息」日志窗口，占满左列剩余高度，外框尺寸固定。
        VBox logPanel = new VBox(16, logBox);
        logPanel.setPadding(new Insets(16));
        panelStyle(logPanel);
        logPanel.setMinHeight(0);
        logPanel.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(logPanel, Priority.ALWAYS);

        VBox leftCol = new VBox(20, topPanel, logPanel);
        leftCol.setAlignment(Pos.TOP_LEFT);
        leftCol.setMinHeight(0);
        leftCol.setMaxHeight(Double.MAX_VALUE);

        // ===================== 右面板 =====================
        Text countText = new Text("当前房间 ");
        countText.setFill(Color.web(TITLE));
        countText.setStyle("-fx-font-size:16px; -fx-font-weight:bold;");
        countNumText = new Text("1 人");
        countNumText.setFill(Color.web(DIM));
        countNumText.setStyle("-fx-font-size:16px; -fx-font-weight:bold;");
        TextFlow countLabel = new TextFlow(countText, countNumText);

        playerGrid = new GridPane();
        playerGrid.setHgap(16);
        playerGrid.setVgap(16);
        playerGrid.setAlignment(Pos.TOP_LEFT);
        for (int i = 0; i < 4; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(25);
            cc.setHgrow(Priority.ALWAYS);
            playerGrid.getColumnConstraints().add(cc);
        }
        VBox.setVgrow(playerGrid, Priority.ALWAYS);

        // 底部操作区：普通模式单钮「踢出成员」；预选模式切为「确认踢出 / 取消」双钮。
        roomBottomStartBtn = new Button("踢出成员");
        stylePrimary(roomBottomStartBtn);
        roomBottomStartBtn.setMaxWidth(Double.MAX_VALUE);
        roomBottomStartBtn.setOnAction(e -> enterKickMode());

        confirmKickBtn = new Button("确认踢出");
        stylePrimary(confirmKickBtn);
        confirmKickBtn.setMaxWidth(Double.MAX_VALUE);
        confirmKickBtn.setDisable(true);
        HBox.setHgrow(confirmKickBtn, Priority.ALWAYS);
        cancelKickBtn = new Button("取消");
        styleGhost(cancelKickBtn);
        cancelKickBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(cancelKickBtn, Priority.ALWAYS);
        cancelKickBtn.setOnAction(e -> exitKickMode());
        kickBar = new HBox(12, confirmKickBtn, cancelKickBtn);
        kickBar.setAlignment(Pos.CENTER_LEFT);

        bottomSlot = new VBox();
        refreshBottomBar();

        VBox rightPanel = new VBox(20, countLabel, playerGrid, bottomSlot);
        rightPanel.setAlignment(Pos.TOP_LEFT);
        rightPanel.setPadding(new Insets(22));
        panelStyle(rightPanel);
        rightPanel.setMinHeight(0);
        rightPanel.setMaxHeight(Double.MAX_VALUE);

        // ===================== 整体：左右两列按 40/60 锁定比例，面板外框不随内容伸缩 =====================
        GridPane body = new GridPane();
        body.setHgap(20);
        ColumnConstraints colL = new ColumnConstraints();
        colL.setPercentWidth(40);
        colL.setHgrow(Priority.ALWAYS);
        ColumnConstraints colR = new ColumnConstraints();
        colR.setPercentWidth(60);
        colR.setHgrow(Priority.ALWAYS);
        body.getColumnConstraints().addAll(colL, colR);
        RowConstraints row = new RowConstraints();
        row.setVgrow(Priority.ALWAYS);
        row.setFillHeight(true);
        body.getRowConstraints().add(row);
        body.add(leftCol, 0, 0);
        body.add(rightPanel, 1, 0);
        VBox.setVgrow(body, Priority.ALWAYS);

        Button backBtn = new Button("‹ 返回");
        backBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:" + DIM
                + "; -fx-border-width:0; -fx-font-size:13px; -fx-cursor:hand; -fx-padding:2 4;");
        backBtn.setOnAction(e -> app.showHome());
        Label pageTitle = new Label("多人游戏");
        pageTitle.setStyle("-fx-text-fill:" + TITLE + "; -fx-font-size:15px; -fx-font-weight:bold;");
        HBox titleRow = new HBox(12, backBtn, pageTitle);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(16, titleRow, body);
        root.setPadding(new Insets(16, 20, 16, 20));
        root.setFillWidth(true);
        root.setMinSize(0, 0);

        // 操作绑定
        Runnable startGame = () -> {
            app.showHome();
            home.launchToServer(null);
        };
        roomStartBtn.setOnAction(e -> startGame.run());
        confirmKickBtn.setOnAction(e -> confirmKick());
        roomLeaveBtn.setOnAction(e -> {
            MultiplayerView.stopHost();
            app.showHome();
        });
    }

    private static void panelStyle(VBox panel) {
        panel.setStyle("-fx-background-color:" + PANEL_BG + "; -fx-background-radius:18;"
                + " -fx-border-radius:18; -fx-border-color:" + PANEL_BORDER + ";");
    }

    // ------------------------------------------------------------------ 刷新

    private void refresh(RelayService.State s) {
        if (roomShortLabel == null || s == null) return;
        String shortNo = MultiplayerView.activeShort.isEmpty() ? "------" : MultiplayerView.activeShort;
        roomShortLabel.setText("房间号：" + shortNo);
        String full = s.room.isEmpty() ? MultiplayerView.expectedCode : s.room;
        roomFullLabel.setText(full.isEmpty() ? "U/---- ---- ---- ----" : full);
        countNumText.setText("1 人");

        // 日志区：本机真实联机状态（当前通道不广播他人进出，故暂无他人进出行）。
        logBox.getChildren().clear();
        String msg = statusText(s);
        if (!msg.isEmpty()) {
            Label line = new Label(msg);
            line.setWrapText(true);
            line.setMinWidth(0);
            line.setStyle("-fx-text-fill:" + logColor(s) + "; -fx-font-size:12px;");
            logBox.getChildren().add(line);
        }

        playerGrid.getChildren().clear();
        playerGrid.add(buildCard("self", true), 0, 0);
    }

    private String currentFullCode() {
        String full = RelayService.state().room;
        return full == null || full.isEmpty() ? MultiplayerView.expectedCode : full;
    }

    private static String statusText(RelayService.State s) {
        String kind = s.kind == null ? "" : s.kind;
        switch (kind) {
            case "host-starting":
                return s.detail.isEmpty() ? "正在建立联机通道…" : s.detail;
            case "host-waiting-port":
                return "【还差最后一步】" + (s.detail.isEmpty()
                        ? "现在启动游戏 → 进存档 → 按 Esc →「对局域网开放」。" : s.detail);
            case "host-ready":
                return "房间已就绪！朋友报这个房间号就能进你的世界。";
            case "error":
                return "联机中断：" + (s.error.isEmpty() ? "未知错误。" : s.error);
            default:
                return s.detail == null ? "" : s.detail;
        }
    }

    private static String logColor(RelayService.State s) {
        if (s == null) return DIM;
        if ("error".equals(s.kind)) return "#e07a8a";
        if ("host-ready".equals(s.kind)) return "#7ecb8a";
        return DIM;
    }

    /** 成员卡片：头像 + 昵称 + 类型；房主带皇冠与高亮描边；预选模式下非房主卡右下角出现勾选框。 */
    private StackPane buildCard(String memberKey, boolean isHost) {
        String name = ownName();
        String type = "离线玩家";
        ImageView avatar = new ImageView();
        avatar.setFitWidth(56);
        avatar.setFitHeight(56);
        avatar.setSmooth(false);
        try {
            com.jwh.launcher.config.LauncherConfig.Account acc = app.auth.currentAccount(false);
            if (acc != null) {
                if (acc.displayName != null && !acc.displayName.isBlank()) name = acc.displayName;
                type = (acc.type == com.jwh.launcher.config.LauncherConfig.Account.Type.MICROSOFT
                        || acc.type == com.jwh.launcher.config.LauncherConfig.Account.Type.LITTLESKIN)
                        ? "正版用户" : "离线玩家";
                if (acc.avatarUrl != null && !acc.avatarUrl.isBlank()) {
                    try { avatar.setImage(new Image(acc.avatarUrl, true)); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        if (avatar.getImage() == null) {
            try (java.io.InputStream in = MultiplayerRoomView.class.getResourceAsStream("/avatar_steve.png")) {
                if (in != null) avatar.setImage(new Image(in));
            } catch (Exception ignored) { }
        }

        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-text-fill:" + TITLE + "; -fx-font-size:14px; -fx-font-weight:bold;");
        Label typeLabel = new Label(type);
        typeLabel.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        VBox info = new VBox(2, nameLabel, typeLabel);
        info.setAlignment(Pos.CENTER);

        StackPane card = new StackPane();
        card.setPrefSize(140, 150);
        card.setMinSize(0, 0);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setUserData(memberKey);
        card.setStyle("-fx-background-color:" + CARD_BG + "; -fx-background-radius:16;"
                + " -fx-border-color:" + (isHost ? SELF_BORDER : CARD_BORDER) + "; -fx-border-radius:16;");
        VBox inner = new VBox(10, avatar, info);
        inner.setAlignment(Pos.CENTER);
        card.getChildren().add(inner);

        if (isHost) {
            Label crown = new Label("♛");
            crown.setStyle("-fx-text-fill:#f2c14e; -fx-font-size:14px;");
            StackPane.setAlignment(crown, Pos.TOP_RIGHT);
            StackPane.setMargin(crown, new Insets(6, 8, 0, 0));
            card.getChildren().add(crown);

            Circle dot = new Circle(4, Color.web("#eca6d6"));
            StackPane.setAlignment(dot, Pos.TOP_LEFT);
            StackPane.setMargin(dot, new Insets(8, 0, 0, 8));
            card.getChildren().add(dot);
        }

        // 预选模式：非房主成员卡右下角挂圆形勾选框，点击切换选中。房主不能踢自己，不挂框。
        if (kickArmed && !isHost) {
            StackPane check = buildCheck(memberKey, selectedKick.contains(memberKey));
            StackPane.setAlignment(check, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(check, new Insets(0, 10, 10, 0));
            card.getChildren().add(check);
            card.getProperties().put("check", check);
            card.setOnMouseClicked(e -> toggleMember(memberKey));
        }
        return card;
    }

    /** 右下角圆形勾选框：未选描边空心，选中粉色填充 + 白色对勾。 */
    private StackPane buildCheck(String memberKey, boolean selected) {
        StackPane box = new StackPane();
        Circle ring = new Circle(10);
        ring.setFill(selected ? Color.web("#d97ec2") : Color.web(PANEL_BG));
        ring.setStroke(Color.web("#eca6d6"));
        ring.setStrokeWidth(1.5);
        Label mark = new Label("✓");
        mark.setStyle("-fx-text-fill:#ffffff; -fx-font-size:11px; -fx-font-weight:bold;");
        mark.setVisible(selected);
        box.getChildren().addAll(ring, mark);
        box.setUserData(memberKey);
        return box;
    }

    // ------------------------------------------------------------------ 踢出预选模式

    private void refreshBottomBar() {
        bottomSlot.getChildren().setAll(kickArmed ? kickBar : roomBottomStartBtn);
    }

    private void enterKickMode() {
        kickArmed = true;
        selectedKick.clear();
        refreshBottomBar();
        refresh(RelayService.state());
    }

    private void exitKickMode() {
        kickArmed = false;
        selectedKick.clear();
        refreshBottomBar();
        refresh(RelayService.state());
    }

    private void confirmKick() {
        if (selectedKick.isEmpty()) {
            app.setStatus("请先勾选要踢出的成员。");
            return;
        }
        // TODO: 联机成员通道接入后，向选中成员下发踢出指令并断开其虚拟网连接。
        app.setStatus("已请求踢出 " + selectedKick.size() + " 名成员（踢出指令待联机成员通道支持）。");
        exitKickMode();
    }

    private void toggleMember(String memberKey) {
        if (selectedKick.contains(memberKey)) selectedKick.remove(memberKey);
        else selectedKick.add(memberKey);
        refreshSelectionUi();
    }

    /** 仅刷新卡片勾选外观与确认钮禁用态，不重建整页。 */
    private void refreshSelectionUi() {
        for (Node n : playerGrid.getChildren()) {
            if (!(n instanceof StackPane card)) continue;
            Object boxObj = card.getProperties().get("check");
            String key = (String) card.getUserData();
            if (boxObj instanceof StackPane box && key != null) {
                boolean sel = selectedKick.contains(key);
                Circle ring = (Circle) box.getChildren().get(0);
                Label mark = (Label) box.getChildren().get(1);
                ring.setFill(sel ? Color.web("#d97ec2") : Color.web(PANEL_BG));
                mark.setVisible(sel);
            }
        }
        confirmKickBtn.setDisable(selectedKick.isEmpty());
    }

    private String ownName() {
        try {
            com.jwh.launcher.config.LauncherConfig.Account acc = app.auth.currentAccount(false);
            if (acc != null && acc.displayName != null && !acc.displayName.isBlank()) return acc.displayName;
        } catch (Exception ignored) { }
        return "JWH-Player";
    }

    // ------------------------------------------------------------------ 样式 / 复制

    private void copyOut(String text, String what) {
        if (text == null || text.isBlank()) return;
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
        app.showToast(what + "已复制：" + text);
    }

    /** 完整码行右侧的小复制图标钮。 */
    private static Button copyIconButton() {
        Button b = new Button("⧉");
        b.setStyle("-fx-background-color:transparent; -fx-text-fill:" + DIM
                + "; -fx-font-size:13px; -fx-border-width:0; -fx-padding:2 4; -fx-cursor:hand;");
        return b;
    }

    private static void stylePrimary(Button b) {
        b.setStyle("-fx-background-color:linear-gradient(to bottom, #eca6d6 0%, #d97ec2 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:14px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-padding:12 16; -fx-cursor:hand;");
        b.setOnMouseEntered(e -> b.setStyle("-fx-background-color:linear-gradient(to bottom, #f2b8de 0%, #e38ccb 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:14px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-padding:12 16; -fx-cursor:hand;"));
        b.setOnMouseExited(e -> b.setStyle("-fx-background-color:linear-gradient(to bottom, #eca6d6 0%, #d97ec2 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:14px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-padding:12 16; -fx-cursor:hand;"));
    }

    private static void styleGhost(Button b) {
        b.setStyle("-fx-background-color:" + PANEL_BG + "; -fx-text-fill:" + TITLE
                + "; -fx-font-size:14px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-color:" + SELF_BORDER + "; -fx-border-radius:14;"
                + " -fx-padding:12 16; -fx-cursor:hand;");
    }
}
