package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.config.LauncherConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 账号管理（融入主窗口的子页面，不弹窗）：账号列表 + 微软正版登录 /
 * 离线登录（内联输入用户名）/ 删除 / 皮肤设置。由 LauncherApp.showSubPage
 * 承载，左上角「< 返回」回到主页。微软授权码流程（MicrosoftLoginDialog）
 * 与「缺少环境/插件」类提示仍保留弹窗。
 */
public class LoginView {

    private final LauncherApp app;

    public LoginView(LauncherApp app) {
        this.app = app;
    }

    /** 构建账号管理页面（直接作为主窗口内容区）。 */
    public VBox content() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color:" + Ui.BG + ";");
        // 必须显式要求撑满：否则父容器只按 pref 高度放置，列表会被压成 0 高度整块消失
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        box.setFillWidth(true);

        ListView<LauncherConfig.Account> list = new ListView<>();
        list.setPrefHeight(300);
        list.setMinHeight(220); // 兜底：窗口再矮也不许把列表压没
        list.setPlaceholder(new javafx.scene.control.Label(
                "还没有账号，先点下面「微软正版登录 / LittleSkin 登录」或用「添加离线账号」"));
        list.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";");
        list.setCellFactory(c -> new ListCell<>() {
            @Override
            protected void updateItem(LauncherConfig.Account acc, boolean empty) {
                super.updateItem(acc, empty);
                if (empty || acc == null) {
                    setText(null);
                } else {
                    String type = switch (acc.type) {
                        case MICROSOFT -> "【正版】";
                        case LITTLESKIN -> "【LittleSkin】";
                        default -> "【离线】";
                    };
                    String skinMark = (acc.skinFile != null && !acc.skinFile.isBlank()) ? "   [已换肤]" : "";
                    String capeMark = (acc.capeFile != null && !acc.capeFile.isBlank()) ? "   [有披风]" : "";
                    setText(type + " " + acc.displayName + skinMark + capeMark);
                    setStyle("-fx-text-fill:" + Ui.TEXT + ";");
                }
            }
        });
        refreshList(list);
        list.getSelectionModel().select(app.config.selectedAccount);
        VBox.setVgrow(list, Priority.ALWAYS);

        // 皮肤库已独立成左侧导航「皮肤库」页面（SkinsView），这里只管账号

        // 选中切换
        list.getSelectionModel().selectedIndexProperty().addListener((obs, o, n) -> {
            if (n != null && n.intValue() >= 0 && n.intValue() < app.config.accounts.size()) {
                app.auth.selectAccount(n.intValue());
                app.refreshAll();
            }
        });

        // --- 按钮区：微软登录 + 内联离线登录输入行 + 删除 ---
        HBox btns = new HBox(8);
        btns.setAlignment(Pos.CENTER_LEFT);

        Button ms = new Button("微软正版登录");
        ms.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white; -fx-font-weight:bold;");
        ms.setOnAction(e -> {
            // 104 式正版登录：双 Tab 弹窗（WebView 网页登录 / 设备码登录），回调拿到微软 token
            MicrosoftLoginDialog dlg = new MicrosoftLoginDialog(
                    (accessToken, refreshToken) -> {
                        Ui.async(() -> {
                            org.json.JSONObject msToken = new org.json.JSONObject();
                            msToken.put("access_token", accessToken);
                            if (refreshToken != null && !refreshToken.isBlank()) {
                                msToken.put("refresh_token", refreshToken);
                            }
                            app.auth.loginMicrosoftWithTokens(msToken);
                            return null;
                        }, ok -> {
                            refreshList(list);
                            app.refreshAll();
                            app.showToast("已登录正版账号。");
                        }, err -> Ui.error("正版登录失败", err));
                    },
                    err -> Ui.error("Microsoft 登录", err),
                    app.config);
            dlg.show();
        });

        Button ls = new Button("LittleSkin 登录");
        ls.setStyle("-fx-background-color:#0DB9C6; -fx-text-fill:white; -fx-font-weight:bold;");
        ls.setOnAction(e -> {
            LittleSkinLoginDialog dlg = new LittleSkinLoginDialog(app, () -> {
                refreshList(list);
                app.refreshAll();
            });
            dlg.show();
        });

        TextField offlineName = new TextField();
        offlineName.setPromptText("输入离线用户名…");
        offlineName.setPrefWidth(170);
        offlineName.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-text-fill:" + Ui.TEXT + ";");
        Button offline = new Button("添加离线账号");
        offline.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-text-fill:" + Ui.TEXT + ";");
        offline.setOnAction(e -> {
            String name = offlineName.getText() == null ? "" : offlineName.getText().trim();
            if (name.isBlank()) {
                Ui.error("离线登录", "请输入游戏内用户名。");
                return;
            }
            app.auth.addOfflineAccount(name);
            offlineName.clear();
            refreshList(list);
            app.refreshAll();
            app.showToast("已添加离线账号：" + name);
        });

        Button del = new Button("删除选中账号");
        del.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        del.setOnAction(e -> {
            int idx = list.getSelectionModel().getSelectedIndex();
            if (idx >= 0) {
                app.auth.removeAccount(idx);
                refreshList(list);
                app.refreshAll();
            }
        });

        btns.getChildren().addAll(ms, ls, offlineName, offline, del);
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        btns.getChildren().add(spacer);

        box.getChildren().addAll(list, btns);
        // 诊断日志：账号页每次构建都记一行，列表高度变化也记一行，
        // 方便下次再出现“账号列表空白”时直接从 stdout 判断是没数据还是被压没了
        System.out.println("[ui] account page built, accounts=" + app.config.accounts.size());
        list.heightProperty().addListener((obs, o, n) -> System.out.println("[ui] account list height=" + n));
        return box;
    }

    private void refreshList(ListView<LauncherConfig.Account> list) {
        list.getItems().setAll(app.config.accounts);
        if (app.config.selectedAccount >= 0 && app.config.selectedAccount < list.getItems().size()) {
            list.getSelectionModel().select(app.config.selectedAccount);
        }
    }
}
