package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 日志页：展示游戏输出日志（与主页共享同一块日志，启动游戏后在此查看）。
 */
public class LogView {

    private final LauncherApp app;

    public LogView(LauncherApp app) {
        this.app = app;
    }

    public VBox root() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color:" + Ui.BG + ";");

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label t = new Label("游戏输出");
        t.setStyle("-fx-font-size:16px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        Button clear = new Button("清空日志");
        clear.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        clear.setOnAction(e -> app.gameLog.clear());
        titleRow.getChildren().addAll(t, clear);

        TextArea log = app.gameLog; // 共享日志区
        log.setEditable(false);
        log.setWrapText(true);
        log.setStyle("-fx-control-inner-background:" + Ui.BG_LIGHT + "; -fx-text-fill:" + Ui.TEXT + "; -fx-font-family:Consolas,monospace; -fx-font-size:13px;");
        VBox.setVgrow(log, Priority.ALWAYS);

        Label hint = new Label("游戏启动后的输出会实时显示在这里；若启动无反应可在此查看报错信息。");
        hint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        hint.setWrapText(true);

        box.getChildren().addAll(titleRow, log, hint);
        return box;
    }
}
