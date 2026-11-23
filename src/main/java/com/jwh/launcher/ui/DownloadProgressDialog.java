package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * 下载进度弹窗：主题自适应（浅色背景 → 白底深字；深色背景 → 黑底浅字，文字与底色相反）。
 * 进度条固定长度代表下载总进度；右下角「取消」按钮点击后立即置位取消标志，不等待后台线程，
 * 残缺文件由下载层（Http / Downloader）在下个检查点清理。
 */
public class DownloadProgressDialog {

    private final Stage stage;
    private final Label titleLabel;
    private final Label message;
    private final ProgressBar bar;
    private final Button cancelBtn;
    private final Runnable onCancel;
    private volatile boolean cancelled;

    public DownloadProgressDialog(LauncherApp app, String title, Runnable onCancel) {
        this.onCancel = onCancel;
        boolean dark = isDark(app);

        String bg = dark ? "#1b1f26" : "#ffffff";
        String text = dark ? "#f0f2f5" : "#1f2328";
        String dim = dark ? "#9aa0a6" : "#6b7280";
        String border = dark ? "rgba(255,255,255,0.14)" : "rgba(31,35,40,0.18)";
        String btnBg = dark ? "rgba(255,255,255,0.10)" : "rgba(31,35,40,0.06)";
        String btnHover = dark ? "rgba(255,255,255,0.18)" : "rgba(31,35,40,0.12)";

        stage = new Stage(StageStyle.UTILITY);
        stage.setTitle(title == null ? "下载进度" : title);

        titleLabel = new Label(title == null ? "下载进度" : title);
        titleLabel.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:" + text + ";");

        message = new Label("准备下载…");
        message.setWrapText(true);
        message.setMaxWidth(400);
        message.setStyle("-fx-text-fill:" + dim + "; -fx-font-size:13px;");

        // 固定长度进度条：代表下载总进度，不随窗口拉伸
        bar = new ProgressBar(0);
        bar.setPrefWidth(400);
        bar.setMinWidth(400);
        bar.setMaxWidth(400);
        bar.setStyle("-fx-accent:" + Ui.ACCENT + ";");

        cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color:" + btnBg + "; -fx-text-fill:" + text
                + "; -fx-background-radius:6; -fx-padding:6 20; -fx-cursor:hand;");
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle("-fx-background-color:" + btnHover
                + "; -fx-text-fill:" + text + "; -fx-background-radius:6; -fx-padding:6 20; -fx-cursor:hand;"));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle("-fx-background-color:" + btnBg
                + "; -fx-text-fill:" + text + "; -fx-background-radius:6; -fx-padding:6 20; -fx-cursor:hand;"));
        cancelBtn.setOnAction(e -> cancel());

        HBox btnRow = new HBox();
        btnRow.setAlignment(Pos.CENTER_RIGHT);
        btnRow.getChildren().add(cancelBtn);

        VBox box = new VBox(12, titleLabel, message, bar, btnRow);
        box.setPadding(new Insets(20, 22, 16, 22));
        box.setStyle("-fx-background-color:" + bg + "; -fx-background-radius:14;"
                + "-fx-border-radius:14; -fx-border-color:" + border + ";");

        Scene scene = new Scene(box);
        Ui.applyStylesheet(scene);
        stage.setScene(scene);
        stage.setResizable(false);
        stage.initModality(Modality.WINDOW_MODAL);

        javafx.stage.Window owner = Ui.dialogOwner;
        if (owner == null) {
            for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                if (w instanceof Stage s && s.isShowing()) {
                    owner = w;
                    break;
                }
            }
        }
        if (owner != null && owner.isShowing()) {
            stage.initOwner(owner);
        }
        stage.setOnShown(ev -> centerOnOwner());
        // 点窗口关闭按钮（X）等同点「取消」
        stage.setOnCloseRequest(e -> {
            if (!cancelled) cancel();
        });
    }

    /** 深色背景（图片 / 透明模式）用黑底浅字，否则白底深字。 */
    private static boolean isDark(LauncherApp app) {
        if (app == null || app.config == null) return false;
        String m = app.config.bgMode;
        return "default".equals(m) || "image".equals(m) || "transparent".equals(m);
    }

    private void centerOnOwner() {
        try {
            javafx.stage.Window o = stage.getOwner();
            if (o != null) {
                stage.setX(o.getX() + (o.getWidth() - stage.getWidth()) / 2);
                stage.setY(o.getY() + (o.getHeight() - stage.getHeight()) / 2);
            }
        } catch (Exception ignored) {
        }
    }

    public void show() {
        Platform.runLater(() -> {
            if (!stage.isShowing()) {
                stage.show();
                centerOnOwner();
            }
        });
    }

    /** 更新状态文案（线程安全）。 */
    public void setMessage(String text) {
        Platform.runLater(() -> message.setText(text == null ? "" : text));
    }

    /** 更新进度（线程安全）；NaN / 负值 = 不确定滚动动画。 */
    public void setProgress(double v) {
        Platform.runLater(() -> {
            if (Double.isNaN(v) || v < 0) {
                bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            } else {
                bar.setProgress(Math.min(1.0, Math.max(0.0, v)));
            }
        });
    }

    /**
     * 请求取消：立即置位（不等待后台线程退出），禁用取消按钮并提示「正在取消…」。
     * 真正的中断与残缺文件清理由下载层在检查点完成，结束后调用 {@link #close()}。
     */
    public void cancel() {
        if (cancelled) return;
        cancelled = true;
        Platform.runLater(() -> {
            cancelBtn.setDisable(true);
            message.setText("正在取消…");
        });
        Runnable r = onCancel;
        if (r != null) r.run();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** 关闭弹窗（线程安全）。 */
    public void close() {
        Platform.runLater(() -> {
            if (stage.isShowing()) stage.close();
        });
    }
}
