package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.auth.LittleSkinAuth;
import com.jwh.launcher.config.LauncherConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.json.JSONObject;

/**
 * 账号登录页（主界面内置子页面，不弹窗）。
 *
 * <p>顶部三标签切换：离线登录 / 微软正版登录 / LittleSkin 登录；中央猫头图标 + 说明，
 * 下方为对应表单。离线直接落本地账号；微软走设备码授权弹窗；LittleSkin 账号密码
 * 经 {@link com.jwh.launcher.auth.AuthService#loginLittleSkin} 认证并同步皮肤。</p>
 */
public class AuthLoginView {

    private final LauncherApp app;

    // 配色（对齐多人游戏稿面粉系）
    private static final String TITLE = "#f2eef4";
    private static final String DIM = "#a893b0";
    private static final String FIELD_BG = "#f0e9f1";
    private static final String FIELD_TEXT = "#2a2134";
    private static final String FIELD_HINT = "#9a86a0";
    private static final String ACCENT = "#d97ec2";
    private static final String ACCENT_LIGHT = "#eca6d6";

    private static final double FORM_W = 340;

    private Label tabOffline;
    private Label tabMs;
    private Label tabLs;
    private Region underlineOffline;
    private Region underlineMs;
    private Region underlineLs;

    private Label intro;
    private StackPane formPane;
    private VBox offlinePanel;
    private VBox msPanel;
    private VBox lsPanel;

    private TextField offlineName;
    private TextField msAccount;
    private PasswordField msPassword;
    private CheckBox msRemember;
    private TextField lsAccount;
    private PasswordField lsPassword;
    private CheckBox lsRemember;

    public AuthLoginView(LauncherApp app) {
        this.app = app;
    }

    public VBox content() {
        VBox root = new VBox(0);
        root.setPadding(new Insets(18, 24, 18, 24));
        root.setStyle("-fx-background-color:#733E6F;");
        root.setMaxWidth(Double.MAX_VALUE);
        root.setMaxHeight(Double.MAX_VALUE);

        root.getChildren().add(buildTopRow());

        Region upper = new Region();
        VBox.setVgrow(upper, Priority.ALWAYS);
        root.getChildren().add(upper);

        root.getChildren().add(buildCenter());

        Region lower = new Region();
        VBox.setVgrow(lower, Priority.ALWAYS);
        root.getChildren().add(lower);

        switchTab(0);
        return root;
    }

    // ------------------------------------------------------------------ 顶部标签行

    private HBox buildTopRow() {
        HBox row = new HBox(22);
        row.setAlignment(Pos.CENTER_LEFT);

        tabOffline = tab("离线登录", 0);
        tabMs = tab("微软正版登录", 1);
        tabLs = tab("LittleSkin 登录", 2);
        underlineOffline = underline();
        underlineMs = underline();
        underlineLs = underline();

        VBox t0 = new VBox(4, tabOffline, underlineOffline);
        VBox t1 = new VBox(4, tabMs, underlineMs);
        VBox t2 = new VBox(4, tabLs, underlineLs);
        bindTab(t0, 0);
        bindTab(t1, 1);
        bindTab(t2, 2);
        row.getChildren().addAll(t0, t1, t2);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);

        Button refresh = new Button("↻");
        refresh.setStyle("-fx-background-color:transparent; -fx-text-fill:" + DIM + ";"
                + " -fx-border-color:" + DIM + "; -fx-border-radius:18; -fx-background-radius:18;"
                + " -fx-min-width:32; -fx-min-height:32; -fx-cursor:hand; -fx-font-size:15px;");
        refresh.setOnAction(e -> resetForm());
        row.getChildren().add(refresh);

        return row;
    }

    private Label tab(String text, int index) {
        Label l = new Label(text);
        l.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-cursor:hand;");
        l.setOnMouseClicked(e -> switchTab(index));
        return l;
    }

    private void bindTab(VBox box, int index) {
        box.setCursor(Cursor.HAND);
        box.setOnMouseClicked(e -> switchTab(index));
    }

    private Region underline() {
        Region r = new Region();
        r.setPrefHeight(2);
        r.setMaxWidth(Double.MAX_VALUE);
        r.setStyle("-fx-background-color:" + ACCENT + "; -fx-background-radius:2;");
        return r;
    }

    // ------------------------------------------------------------------ 中央表单区

    private VBox buildCenter() {
        ImageView cat = new ImageView();
        try (java.io.InputStream in = getClass().getResourceAsStream("/icons/login_cat.png")) {
            if (in != null) cat.setImage(new Image(in));
        } catch (Exception ignored) {
        }
        cat.setFitWidth(78);
        cat.setFitHeight(78);
        cat.setSmooth(true);

        intro = new Label();
        intro.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:12px;");
        intro.setAlignment(Pos.CENTER);

        buildOfflinePanel();
        buildMicrosoftPanel();
        buildLittleSkinPanel();

        formPane = new StackPane(offlinePanel, msPanel, lsPanel);
        formPane.setMaxWidth(FORM_W);

        VBox center = new VBox(14, cat, intro, formPane);
        center.setAlignment(Pos.CENTER);
        center.setMaxWidth(Double.MAX_VALUE);
        return center;
    }

    // ---- 离线 ----
    private void buildOfflinePanel() {
        offlineName = new TextField();
        offlineName.setPromptText("输入离线用户名");
        styleField(offlineName);

        Button addBtn = mainButton("添加离线账户");
        addBtn.setOnAction(e -> doAddOffline());

        Hyperlink del = link("删除当前账户", ACCENT_LIGHT);
        del.setOnAction(e -> doDeleteCurrent());

        Label note = new Label("离线账户仅用于单人与局域网，无法进入正版服务器");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill:" + DIM + "; -fx-font-size:11px;");
        note.setAlignment(Pos.CENTER);

        offlinePanel = formBox(offlineName, addBtn, centerWrap(del), note);
    }

    // ---- 微软正版 ----
    private void buildMicrosoftPanel() {
        msAccount = new TextField();
        msAccount.setPromptText("点击输入账号（邮箱）");
        styleField(msAccount);

        msPassword = new PasswordField();
        msPassword.setPromptText("点击输入密码");
        styleField(msPassword);

        msRemember = new CheckBox("记住密码");
        styleCheck(msRemember);
        Hyperlink forgot = link("忘记密码？", ACCENT_LIGHT);
        forgot.setOnAction(e -> app.openUrl("https://account.microsoft.com/"));
        HBox optRow = new HBox(10, msRemember, spacer(), forgot);
        optRow.setAlignment(Pos.CENTER_LEFT);

        Button loginBtn = mainButton("登录");
        loginBtn.setOnAction(e -> doMicrosoftLogin());

        Hyperlink register = link("没有账号？去 Microsoft 账号中心注册", ACCENT_LIGHT);
        register.setOnAction(e -> app.openUrl("https://signup.live.com/"));

        msPanel = formBox(msAccount, msPassword, optRow, loginBtn, centerWrap(register));
    }

    // ---- LittleSkin ----
    private void buildLittleSkinPanel() {
        lsAccount = new TextField();
        lsAccount.setPromptText("LittleSkin 账号 / 邮箱");
        styleField(lsAccount);
        if (app.config.lsSavedUser != null && !app.config.lsSavedUser.isBlank()) {
            lsAccount.setText(app.config.lsSavedUser);
        }

        lsPassword = new PasswordField();
        lsPassword.setPromptText("点击输入密码");
        styleField(lsPassword);

        lsRemember = new CheckBox("记住密码");
        styleCheck(lsRemember);
        lsRemember.setSelected(app.config.lsSavedUser != null && !app.config.lsSavedUser.isBlank());
        Hyperlink site = link("访问皮肤站", ACCENT_LIGHT);
        site.setOnAction(e -> app.openUrl(LittleSkinAuth.HOME));
        HBox optRow = new HBox(10, lsRemember, spacer(), site);
        optRow.setAlignment(Pos.CENTER_LEFT);

        Button loginBtn = mainButton("登录");
        loginBtn.setOnAction(e -> doLittleSkinLogin());

        Hyperlink register = link("没有账号？去 LittleSkin 皮肤站官网注册", ACCENT_LIGHT);
        register.setOnAction(e -> app.openUrl(LittleSkinAuth.REGISTER));

        lsPanel = formBox(lsAccount, lsPassword, optRow, loginBtn, centerWrap(register));
    }

    // ------------------------------------------------------------------ 动作

    private void doAddOffline() {
        String name = offlineName.getText() == null ? "" : offlineName.getText().trim();
        if (name.isBlank()) {
            app.showToast("请输入游戏内用户名。");
            return;
        }
        app.auth.addOfflineAccount(name);
        offlineName.clear();
        app.refreshAll();
        app.showToast("已添加离线账户：" + name);
        app.showHome();
    }

    private void doDeleteCurrent() {
        int idx = app.config.selectedAccount;
        if (idx < 0 || idx >= app.config.accounts.size()) {
            app.showToast("当前没有可删除的账户。");
            return;
        }
        LauncherConfig.Account acc = app.config.accounts.get(idx);
        app.auth.removeAccount(idx);
        app.refreshAll();
        app.showToast("已删除账户：" + (acc == null ? "" : acc.displayName));
    }

    private void doMicrosoftLogin() {
        MicrosoftLoginDialog dlg = new MicrosoftLoginDialog(
                (accessToken, refreshToken) -> Ui.async2(() -> {
                    JSONObject msToken = new JSONObject();
                    msToken.put("access_token", accessToken);
                    if (refreshToken != null && !refreshToken.isBlank()) {
                        msToken.put("refresh_token", refreshToken);
                    }
                    app.auth.loginMicrosoftWithTokens(msToken);
                    return null;
                }, ok -> {
                    app.refreshAll();
                    app.showToast("已登录正版账号。");
                }, err -> Ui.error("正版登录失败", err)),
                err -> Ui.error("Microsoft 登录", err),
                app.config);
        dlg.show();
    }

    private void doLittleSkinLogin() {
        String user = lsAccount.getText() == null ? "" : lsAccount.getText().trim();
        String pass = lsPassword.getText() == null ? "" : lsPassword.getText();
        if (user.isBlank()) {
            app.showToast("请输入 LittleSkin 账号 / 邮箱。");
            return;
        }
        if (pass.isBlank()) {
            app.showToast("请输入密码。");
            return;
        }
        boolean remember = lsRemember.isSelected();
        Ui.async2(() -> app.auth.loginLittleSkin(user, pass, remember),
                acc -> {
                    lsPassword.clear();
                    app.refreshAll();
                    app.showToast("LittleSkin 登录成功：" + acc.displayName);
                    app.showHome();
                },
                err -> Ui.error("LittleSkin 登录失败", err));
    }

    private void resetForm() {
        offlineName.clear();
        msAccount.clear();
        msPassword.clear();
        lsAccount.clear();
        lsPassword.clear();
        msRemember.setSelected(false);
        lsRemember.setSelected(app.config.lsSavedUser != null && !app.config.lsSavedUser.isBlank());
    }

    private void switchTab(int index) {
        boolean[] active = {index == 0, index == 1, index == 2};
        Label[] labels = {tabOffline, tabMs, tabLs};
        Region[] unders = {underlineOffline, underlineMs, underlineLs};
        VBox[] panels = {offlinePanel, msPanel, lsPanel};
        String[] intros = {
                "离线登录 · 无需网络，仅本地使用",
                "微软正版 · 可使用正版皮肤与服务器",
                "LittleSkin · 第三方皮肤站账户"
        };

        for (int i = 0; i < 3; i++) {
            labels[i].setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-cursor:hand;"
                    + " -fx-text-fill:" + (active[i] ? TITLE : DIM) + ";");
            unders[i].setVisible(active[i]);
            unders[i].setManaged(active[i]);
            panels[i].setVisible(active[i]);
            panels[i].setManaged(active[i]);
        }
        intro.setText(intros[index]);
    }

    // ------------------------------------------------------------------ 样式辅助

    private VBox formBox(javafx.scene.Node... children) {
        VBox box = new VBox(14);
        box.setAlignment(Pos.TOP_CENTER);
        box.getChildren().addAll(children);
        return box;
    }

    private void styleField(TextField f) {
        f.setPrefHeight(44);
        f.setMaxWidth(Double.MAX_VALUE);
        f.setStyle("-fx-background-color:" + FIELD_BG + "; -fx-text-fill:" + FIELD_TEXT + ";"
                + " -fx-prompt-text-fill:" + FIELD_HINT + "; -fx-background-radius:12;"
                + " -fx-padding:0 14; -fx-font-size:13px;");
    }

    private void styleCheck(CheckBox c) {
        c.setStyle("-fx-text-fill:" + TITLE + "; -fx-font-size:12px; -fx-accent:" + ACCENT + ";");
    }

    private Button mainButton(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setPrefHeight(42);
        b.setStyle("-fx-background-color:linear-gradient(to bottom, " + ACCENT_LIGHT + " 0%, " + ACCENT + " 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:14px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-cursor:hand;");
        b.setOnMouseEntered(e -> b.setStyle("-fx-background-color:linear-gradient(to bottom, #f2b8de 0%, #e38ccb 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:14px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-cursor:hand;"));
        b.setOnMouseExited(e -> b.setStyle("-fx-background-color:linear-gradient(to bottom, " + ACCENT_LIGHT + " 0%, " + ACCENT + " 100%);"
                + " -fx-text-fill:#ffffff; -fx-font-size:14px; -fx-font-weight:bold;"
                + " -fx-background-radius:14; -fx-border-width:0; -fx-cursor:hand;"));
        return b;
    }

    private Hyperlink link(String text, String color) {
        Hyperlink h = new Hyperlink(text);
        h.setStyle("-fx-text-fill:" + color + "; -fx-border-width:0; -fx-underline:false; -fx-font-size:12px;");
        return h;
    }

    private HBox centerWrap(javafx.scene.Node n) {
        HBox h = new HBox(n);
        h.setAlignment(Pos.CENTER);
        return h;
    }

    private Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
