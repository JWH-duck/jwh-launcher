package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.auth.LittleSkinAuth;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.util.Http;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LittleSkin 皮肤站登录弹窗（启动器内置，不跳网页）。
 * <p>流程：填账号密码 → POST /authserver/authenticate 拿令牌 → 读角色纹理 →
 * 把皮肤/披风下载到本地 skins 目录 → 写入账号列表并选中。
 * 没有账号时点「去 LittleSkin 注册」直接打开官网注册页。
 */
public class LittleSkinLoginDialog {

    private static LittleSkinLoginDialog active;

    private final LauncherApp app;
    private final Runnable onDone;

    private Stage stage;
    private TextField account;
    private PasswordField password;
    private CheckBox remember;
    private Label status;
    private Button loginBtn;
    private Button registerBtn;

    private volatile boolean busy;

    public LittleSkinLoginDialog(LauncherApp app, Runnable onDone) {
        this.app = app;
        this.onDone = onDone;
    }

    public void show() {
        if (active != null && active.stage != null && active.stage.isShowing()) {
            active.stage.toFront();
            return;
        }
        active = this;

        stage = new Stage();
        stage.setTitle("LittleSkin 皮肤站登录 · JWH");

        Label title = new Label("使用 LittleSkin 皮肤站账号登录");
        title.setStyle("-fx-font-size:16px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");

        Label intro = new Label("LittleSkin 是公益 Minecraft 皮肤站。登录后皮肤与披风由皮肤站同步，"
                + "游戏内也走它的外置登录，联机服务器认这个身份。");
        intro.setWrapText(true);
        intro.setPrefWidth(430);
        intro.setMaxWidth(430);
        intro.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        intro.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");

        account = new TextField();
        account.setPromptText("LittleSkin 邮箱或用户名");
        account.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-background-color:" + Ui.PANEL + ";");
        account.setPrefHeight(34);

        password = new PasswordField();
        password.setPromptText("密码");
        password.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-background-color:" + Ui.PANEL + ";");
        password.setPrefHeight(34);

        remember = new CheckBox("记住账号（密码不保存，下次需要重输）");
        remember.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        remember.setSelected(false);

        // 上次记住的账号直接回填，省得每次重敲
        if (app.config.lsSavedUser != null && !app.config.lsSavedUser.isBlank()) {
            account.setText(app.config.lsSavedUser);
            remember.setSelected(true);
        }

        loginBtn = new Button("登录");
        loginBtn.setStyle("-fx-background-color:" + Ui.ACCENT
                + "; -fx-text-fill:white; -fx-font-weight:bold; -fx-font-size:14px;");
        loginBtn.setMaxWidth(Double.MAX_VALUE);
        loginBtn.setPrefHeight(38);
        loginBtn.setOnAction(e -> doLogin());
        account.setOnAction(e -> password.requestFocus());
        password.setOnAction(e -> doLogin());

        registerBtn = new Button("没有账号？去 LittleSkin 注册");
        registerBtn.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-text-fill:" + Ui.TEXT + ";");
        registerBtn.setMaxWidth(Double.MAX_VALUE);
        registerBtn.setOnAction(e -> openUrl(LittleSkinAuth.REGISTER));

        status = new Label("填好账号密码后点「登录」。");
        status.setWrapText(true);
        status.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");

        Hyperlink site = new Hyperlink("打开 LittleSkin 官网");
        site.setStyle("-fx-text-fill:" + Ui.ACCENT + ";");
        site.setOnAction(e -> openUrl(LittleSkinAuth.HOME));

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        cancelBtn.setOnAction(e -> stage.close());

        HBox bottom = new HBox(10, site, new Label(), cancelBtn);
        HBox.setHgrow(bottom.getChildren().get(1), Priority.ALWAYS);
        bottom.setAlignment(Pos.CENTER_LEFT);

        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        VBox box = new VBox(12, title, intro, account, password, remember, loginBtn, registerBtn, status, spacer, bottom);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:" + Ui.BG + ";");

        Scene scene = new Scene(box, 480, 400);
        Ui.applyStylesheet(scene);
        stage.setScene(scene);
        stage.setMinWidth(440);
        stage.setMinHeight(370);
        stage.setOnHidden(e -> {
            if (active == this) active = null;
        });
        stage.show();
        account.requestFocus();
    }

    /** 执行登录：后台线程做网络请求，回来后写账号、拉皮肤、刷新界面。 */
    private void doLogin() {
        if (busy) return;
        String user = account.getText() == null ? "" : account.getText().trim();
        String pass = password.getText() == null ? "" : password.getText();
        if (user.isBlank()) {
            setStatus("请先填 LittleSkin 邮箱或用户名。");
            return;
        }
        if (pass.isBlank()) {
            setStatus("请先填密码。");
            return;
        }
        setStatus("正在向 LittleSkin 验证账号…");
        loginBtn.setDisable(true);
        busy = true;

        new Thread(() -> {
            try {
                LittleSkinAuth.Result r = LittleSkinAuth.authenticate(LittleSkinAuth.DEFAULT_API, user, pass, "");
                // 拉皮肤 + 披风；站点上没设置就留空，不阻断登录
                LittleSkinAuth.Textures tex = null;
                String skinFile = "";
                String capeFile = "";
                Boolean slim = null;
                try {
                    tex = LittleSkinAuth.fetchTextures(LittleSkinAuth.DEFAULT_API, r.profileId);
                    // 与皮肤库页「同步」写同一套文件名，登录后皮肤库里直接能看到卡片
                    Path dir = app.config.minecraftPath().resolve("skins").resolve("library");
                    if (tex != null && tex.hasSkin()) {
                        Files.createDirectories(dir);
                        String name = "皮肤站-" + r.profileName + ".png";
                        Path p = dir.resolve(name);
                        if (Http.download(tex.skinUrl, p)) {
                            skinFile = name;
                            slim = tex.slim ? Boolean.TRUE : Boolean.FALSE;
                        }
                    }
                    if (tex != null && tex.hasCape()) {
                        Files.createDirectories(dir);
                        String name = "皮肤站-" + r.profileName + "-cape.png";
                        Path p = dir.resolve(name);
                        if (Http.download(tex.capeUrl, p)) capeFile = name;
                    }
                } catch (Exception ignored) {
                    // 纹理拿不到不影响登入，后面在皮肤库页还能手动重同步
                }

                LauncherConfig.Account acc = new LauncherConfig.Account();
                acc.type = LauncherConfig.Account.Type.LITTLESKIN;
                acc.displayName = r.profileName;
                acc.userName = r.profileName;
                acc.uuid = LittleSkinAuth.withDashes(r.profileId);
                acc.accessToken = r.accessToken;
                acc.refreshToken = r.clientToken;
                acc.lsApiRoot = LittleSkinAuth.DEFAULT_API;
                acc.skinFile = skinFile;
                acc.capeFile = capeFile;
                acc.skinSlim = slim;

                String finalUser = user;
                final String skinInfo = skinFile.isBlank() ? "（该角色未设置皮肤）" : "（皮肤已同步）";

                Platform.runLater(() -> {
                    app.config.accounts.add(acc);
                    app.config.selectedAccount = app.config.accounts.size() - 1;
                    // 只记账号，不保存密码（配置文件是明文的）
                    app.config.lsSavedUser = remember.isSelected() ? finalUser : "";
                    ConfigManager.save(app.config);
                    busy = false;
                    loginBtn.setDisable(false);
                    setStatus("登录成功：" + acc.displayName + skinInfo);
                    if (onDone != null) onDone.run();
                    app.refreshAll();
                    app.showToast("LittleSkin 登录成功：" + acc.displayName);
                    stage.close();
                });
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                Platform.runLater(() -> {
                    busy = false;
                    loginBtn.setDisable(false);
                    setStatus("登录失败：" + msg);
                });
            }
        }, "littleskin-login").start();
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                setStatus("当前系统不支持直接打开浏览器，请手动访问：" + url);
            }
        } catch (Exception ex) {
            setStatus("打开浏览器失败：" + ex.getMessage() + "\n请手动访问：" + url);
        }
    }
}
