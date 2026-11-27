package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;

import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * 「多人游戏」居中弹窗外壳：透明 Stage，避免系统白色标题栏。
 * 面板内容与联机逻辑见 {@link MultiplayerView}。
 */
public final class MultiplayerDialog {

    private MultiplayerDialog() {
    }

    /** 当前打开的多人弹窗 Stage，进入房间内页时兜底关闭用。 */
    private static Stage current;

    /** 关闭当前多人弹窗（若已打开），重复调用安全。 */
    public static void closeCurrent() {
        Stage dlg = current;
        current = null;
        if (dlg == null) return;
        try {
            dlg.hide();
        } catch (Exception ignored) {
        }
        try {
            dlg.close();
        } catch (Exception ignored) {
        }
    }

    public static void show(LauncherApp app, HomeView home) {
        Stage dlg = new Stage(StageStyle.TRANSPARENT);
        dlg.initModality(Modality.WINDOW_MODAL);
        current = dlg;

        Window owner = Ui.dialogOwner;
        if (owner == null) {
            for (Window w : Window.getWindows()) {
                if (w instanceof Stage s && s.isShowing()) {
                    owner = w;
                    break;
                }
            }
        }
        if (owner != null && owner.isShowing()) {
            dlg.initOwner(owner);
        }

        javafx.scene.layout.VBox root = MultiplayerView.panel(app, home, dlg::close);

        Scene scene = new Scene(root, Color.TRANSPARENT);
        Ui.applyStylesheet(scene);
        dlg.setScene(scene);
        dlg.setResizable(false);

        dlg.setOnShown(ev -> {
            Window o = dlg.getOwner();
            if (o != null) {
                dlg.setX(o.getX() + (o.getWidth() - dlg.getWidth()) / 2);
                dlg.setY(o.getY() + (o.getHeight() - dlg.getHeight()) / 2);
            }
        });

        dlg.show();
    }
}
