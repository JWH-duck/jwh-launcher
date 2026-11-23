package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.update.Updater;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 关于页：启动器版本信息、自更新入口与正版登录授权声明（展示于左侧导航「关于」）。
 */
public class AboutView {

    /** 正版登录授权声明（用户指定文案，原样展示）。 */
    public static final String LOGIN_NOTICE =
            "正版微软账号登录依托 JWH-Launcher 自主注册的 Azure 应用完成授权。"
                    + "本程序不会收集你的微软账号密码，仅临时获取授权令牌用于验证 Minecraft 所有权；"
                    + "登录服务可用性受微软平台策略、风控规则影响。";

    private final LauncherApp app;

    public AboutView(LauncherApp app) {
        this.app = app;
    }

    public VBox root() {
        VBox box = new VBox(14);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:" + Ui.BG + ";");

        Label title = new Label("关于");
        title.setStyle("-fx-font-size:18px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");

        // 启动器信息卡
        VBox infoCard = card();
        Label name = new Label("JWH Launcher - Minecraft 启动器");
        name.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        Label version = new Label("当前版本：v" + LauncherApp.APP_VERSION);
        version.setStyle("-fx-font-size:13px; -fx-text-fill:" + Ui.TEXT_DIM + ";");
        infoCard.getChildren().addAll(name, version);

        // 自更新卡
        VBox updateCard = card();
        Label updateTitle = new Label("软件更新");
        updateTitle.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        Updater updater = new Updater(LauncherApp.appDir(), LauncherApp.APP_VERSION);
        Label source = new Label("更新源：" + updater.manifestUrl());
        source.setWrapText(true);
        source.setMaxWidth(560);
        source.setStyle("-fx-font-size:12px; -fx-text-fill:" + Ui.TEXT_DIM + ";");
        Button checkBtn = new Button("检查更新");
        checkBtn.setStyle(auxBtnStyle());
        Label state = new Label("启动时也会自动检查，发现新版本会提示。");
        state.setStyle("-fx-font-size:12px; -fx-text-fill:" + Ui.TEXT_DIM + ";");
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(checkBtn, state);
        Label howto = new Label("作者如何发布更新：在更新源放置 manifest.json；常规更新只需上传新的程序 jar，"
                + "在 manifest 的 jar 段填写直链、大小与 sha256，玩家只替换程序本体、不重下自带运行时；"
                + "大版本可另传完整压缩包作为兜底。启动器目录放一个 update-url.txt 写入 manifest 直链即可，无需重新打包。");
        howto.setWrapText(true);
        howto.setMaxWidth(560);
        howto.setStyle("-fx-font-size:12px; -fx-text-fill:" + Ui.TEXT_DIM + "; -fx-line-spacing:3;");
        updateCard.getChildren().addAll(updateTitle, source, row, howto);

        checkBtn.setOnAction(e -> checkUpdate(checkBtn, state, updater));

        // 登录授权声明卡
        VBox noticeCard = card();
        Label noticeTitle = new Label("正版账号登录声明");
        noticeTitle.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        Label notice = new Label(LOGIN_NOTICE);
        notice.setWrapText(true);
        notice.setMaxWidth(560);
        notice.setStyle("-fx-font-size:13px; -fx-text-fill:" + Ui.TEXT_DIM + "; -fx-line-spacing:4;");
        noticeCard.getChildren().addAll(noticeTitle, notice);

        // 底部占位 + 版权
        VBox spacer = new VBox();
        VBox.setVgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox foot = new HBox();
        foot.setAlignment(Pos.CENTER);
        Label copy = new Label("JWH-Launcher · 仅用于个人与朋友间正常游玩");
        copy.setStyle("-fx-font-size:12px; -fx-text-fill:" + Ui.TEXT_DIM + ";");
        foot.getChildren().add(copy);

        box.getChildren().addAll(title, infoCard, updateCard, noticeCard, spacer, foot);
        return box;
    }

    /** 检查更新：有新版则询问并下载应用，无新版提示已最新。 */
    private void checkUpdate(Button btn, Label state, Updater updater) {
        btn.setDisable(true);
        state.setText("正在检查更新…");
        Ui.async2(updater::check, info -> {
            btn.setDisable(false);
            if (info == null) {
                state.setText("当前已是最新版本 v" + LauncherApp.APP_VERSION);
                Ui.info("检查更新", "当前已是最新版本 v" + LauncherApp.APP_VERSION);
                return;
            }
            state.setText("发现新版本 v" + info.version() + "，等待确认下载…");
            String notes = info.notes() == null || info.notes().isBlank() ? "" : "\n\n更新内容：\n" + info.notes();
            boolean go = Ui.confirm("发现新版本 v" + info.version(),
                    "当前版本 v" + LauncherApp.APP_VERSION + "，发现新版本 v" + info.version() + "。" + notes
                            + "\n\n是否现在下载并更新？更新完成后启动器会自动重启。");
            if (go) {
                btn.setDisable(true);
                state.setText("正在下载更新包，完成后自动重启…");
                app.startUpdate(info);
            } else {
                state.setText("已取消更新，下次启动时会再次提醒。");
            }
        }, e -> {
            btn.setDisable(false);
            state.setText("检查失败（无法访问更新源或网络异常）。");
        });
    }

    private static String auxBtnStyle() {
        return "-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";"
                + "-fx-background-radius:6; -fx-padding:8 14;";
    }

    private static VBox card() {
        VBox card = new VBox(8);
        card.setPadding(new Insets(14));
        card.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-background-radius:10;"
                + "-fx-border-color:" + Ui.BORDER + "; -fx-border-radius:10;");
        card.setMaxWidth(600);
        return card;
    }
}
