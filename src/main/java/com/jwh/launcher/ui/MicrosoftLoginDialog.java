package com.jwh.launcher.ui;

import com.jwh.launcher.auth.MicrosoftAuthenticator;
import com.jwh.launcher.config.LauncherConfig;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import org.json.JSONObject;

import java.awt.Desktop;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Microsoft 正版登录弹窗（PCL 同款：设备码登录）。
 *
 * 和 PCL 2.99 一模一样：使用内置登录应用，
 * 点击「开始登录」→ 获取设备码并自动复制 → 自动打开浏览器登录网页（microsoft.com/devicelogin）→
 * 在网页中输入设备码并完成密码登录 → 本窗口后台按 interval 自动轮询，拿到令牌即完成登录。
 * 该流程不经过 Minecraft 官方授权确认页，风控门槛低，无需自行注册任何 Azure 应用。
 */
public class MicrosoftLoginDialog {

    private static MicrosoftLoginDialog active;

    private final LauncherConfig config;
    private final BiConsumer<String, String> onTokens; // (access_token, refresh_token)
    private final Consumer<String> onError;

    private Stage stage;
    private Label status;
    private Button startBtn;
    private Button openSiteBtn;
    private Label codeLabel;
    private Label hintLabel;
    private Label stepLabel;

    private volatile boolean cancelled;
    private volatile long generation;
    private Thread worker;
    private AtomicBoolean cancelPoll = new AtomicBoolean(false);

    /** 兼容旧调用：无配置对象。 */
    public MicrosoftLoginDialog(BiConsumer<String, String> onTokens, Consumer<String> onError) {
        this(onTokens, onError, null);
    }

    /** 带配置对象：按内置/预置配置初始化认证器。 */
    public MicrosoftLoginDialog(BiConsumer<String, String> onTokens, Consumer<String> onError, LauncherConfig config) {
        this.config = config;
        this.onTokens = onTokens;
        this.onError = onError;
    }

    public void show() {
        if (active != null && active.stage != null && active.stage.isShowing()) {
            active.stage.toFront();
            return;
        }
        active = this;
        stage = new Stage();
        stage.setTitle("Microsoft 账号登录 · JWH");

        Label title = new Label("使用 Microsoft 账号登录 Minecraft Java 版（PCL 同款）");
        title.setStyle("-fx-font-size:16px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");

        MicrosoftAuthenticator.init(config);

        stepLabel = label("和 PCL 一样：点击「开始登录」后，会弹出一个设备码并自动打开浏览器登录网页。"
                + "在网页中输入设备码、用密码完成微软登录，本窗口会自动完成后续登录（无需经过官方确认页）。");
        stepLabel.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:13px;");

        startBtn = new Button("开始登录");
        startBtn.setId("microsoft-login-start");
        startBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white; -fx-font-weight:bold; -fx-font-size:14px;");
        startBtn.setMaxWidth(Double.MAX_VALUE);
        startBtn.setOnAction(e -> startDeviceLogin());

        // 设备码展示区（授权开始后可见）
        hintLabel = label("登录网页将自动开启，请在网页中输入设备码（已自动复制）。");
        hintLabel.setWrapText(true);
        hintLabel.setVisible(false);

        codeLabel = new Label();
        codeLabel.setId("microsoft-device-code");
        codeLabel.setStyle("-fx-font-size:34px; -fx-font-weight:bold; -fx-text-fill:" + Ui.ACCENT
                + "; -fx-alignment:center; -fx-padding:8 0 8 0;");
        codeLabel.setVisible(false);

        openSiteBtn = new Button("打开登录网页（重新打开）");
        openSiteBtn.setId("microsoft-open-site");
        openSiteBtn.setStyle("-fx-background-color:#3FA9F5;-fx-text-fill:white;-fx-font-weight:bold;");
        openSiteBtn.setVisible(false);
        openSiteBtn.setOnAction(e -> openLoginSite());

        status = label("点击「开始登录」后，在浏览器打开的网页中输入设备码即可。");
        status.setId("microsoft-login-status");

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        cancelBtn.setOnAction(e -> stage.close());

        HBox bottom = new HBox(10, cancelBtn);
        bottom.setAlignment(Pos.CENTER_RIGHT);

        VBox box = new VBox(12, title, stepLabel, startBtn, hintLabel, codeLabel, openSiteBtn, status, bottom);
        box.setPadding(new Insets(20, 20, 20, 20));
        box.setStyle("-fx-background-color:" + Ui.BG + ";");

        Scene scene = new Scene(box, 620, 560);
        Ui.applyStylesheet(scene);
        stage.setScene(scene);
        stage.setMinWidth(560);
        stage.setMinHeight(500);
        stage.setOnHidden(e -> {
            cancelled = true;
            cancelPoll.set(true);
            generation++;
            if (worker != null) worker.interrupt();
            if (active == this) active = null;
        });

        stage.show();
    }

    private Label label(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");
        return l;
    }

    /** 开始 PCL 同款设备码登录：请求设备码 → 自动复制并打开登录网页 → 后台轮询换令牌。 */
    private void startDeviceLogin() {
        if (worker != null && worker.isAlive()) {
            setStatus("正在登录中，请先在浏览器网页里输入设备码…");
            return;
        }
        long gen = ++generation;
        cancelPoll = new AtomicBoolean(false);
        setStatus("正在获取设备码…");
        worker = new Thread(() -> {
            JSONObject dev;
            try {
                dev = MicrosoftAuthenticator.requestDeviceCode();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (!isCurrent(gen)) return;
                    setStatus("获取设备码失败：" + safeMessage(e));
                });
                return;
            }
            final String userCode = dev.optString("user_code");
            final String website = dev.optString("verification_uri");
            Platform.runLater(() -> {
                if (!isCurrent(gen)) return;
                verificationUri = website;
                codeLabel.setText(userCode);
                codeLabel.setVisible(true);
                hintLabel.setText("登录网页将自动开启，请在网页中输入 " + userCode + "（已自动复制）。\n"
                        + "如果网页没有自动打开，请点击下方按钮打开「" + website + "」并输入上述设备码。");
                hintLabel.setVisible(true);
                openSiteBtn.setVisible(true);
                copyToClipboard(userCode);
                setStatus("已复制设备码，正在打开登录网页…");
            });
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                return;
            }
            openLoginSite();
            // 后台按 interval 轮询（对齐 PCL WorkThread）
            try {
                JSONObject token = MicrosoftAuthenticator.pollDeviceToken(dev, cancelPoll, (remain, lastError) -> {
                    setStatus(lastError == null
                            ? "等待你在网页中输入设备码并完成登录…（剩余约 " + remain + " 秒）"
                            : "正在等待授权…");
                    return !cancelPoll.get();
                });
                final String access = token.optString("access_token");
                final String refresh = token.optString("refresh_token", "");
                if (access == null || access.isBlank()) {
                    throw new IllegalStateException("微软没有返回访问令牌，请重新登录。");
                }
                Platform.runLater(() -> {
                    if (!isCurrent(gen)) return;
                    setStatus("登录成功！正在获取游戏档案…");
                    onTokens.accept(access, refresh);
                    stage.close();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (!isCurrent(gen)) return;
                    setStatus("登录失败：" + safeMessage(e));
                    startBtn.setText("重新开始登录");
                });
            }
        }, "JWH-Microsoft-Device-Login");
        worker.setDaemon(true);
        worker.start();
    }

    /** 打开（重新打开）登录网页：verification_uri。 */
    private void openLoginSite() {
        String url = currentVerificationUri();
        if (url == null || url.isBlank()) {
            setStatus("还没有获取到登录网页地址，请先点「开始登录」。");
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                copyToClipboard(url);
                setStatus("无法自动打开浏览器，登录网页地址已复制到剪贴板，请粘贴到浏览器地址栏打开。");
            }
        } catch (Exception e) {
            copyToClipboard(url);
            setStatus("打开浏览器失败：" + safeMessage(e) + " 登录网页地址已复制到剪贴板。");
        }
    }

    /** 当前会话的 verification_uri（保存在实例字段，避免跨线程丢失）。 */
    private volatile String verificationUri;

    /** 读取当前验证网页地址；无则返回 null。 */
    private String currentVerificationUri() {
        return verificationUri;
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text == null ? "" : text);
            Clipboard.getSystemClipboard().setContent(cc);
        } catch (Exception ignored) {
        }
    }

    /** 当前 generation 是否仍是有效登录会话（防旧线程覆盖新会话）。 */
    private boolean isCurrent(long gen) {
        return !cancelled && generation == gen;
    }

    /** 状态提示（跨线程安全）。 */
    private void setStatus(String text) {
        if (Platform.isFxApplicationThread()) {
            if (status != null) status.setText(text);
        } else {
            Platform.runLater(() -> {
                if (status != null) status.setText(text);
            });
        }
    }

    /** 把异常转成用户可读的中文提示（对齐 104 的 safeMessage）。 */
    private String safeMessage(Exception e) {
        if (e instanceof HttpTimeoutException) {
            return "连接微软超时，请检查网络后重试。";
        }
        if (e instanceof ConnectException || e instanceof UnknownHostException) {
            return "无法连接微软服务器，请检查网络或代理设置。";
        }
        if (e instanceof IOException || e instanceof IllegalStateException) {
            String msg = e.getMessage();
            return (msg == null || msg.isBlank()) ? "授权服务返回异常，请稍后重试。" : msg;
        }
        return "授权服务返回异常，请检查网络及应用设置后重试。";
    }
}
