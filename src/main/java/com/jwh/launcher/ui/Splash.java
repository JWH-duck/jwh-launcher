package com.jwh.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * 启动加载页。
 *
 * <p>透明无边框窗口，方块场景图直接浮在桌面壁纸上（白底已在打包资源里去成 alpha），
 * 下方一条紫色进度线和状态文案。它只负责「先出画 + 报进度」，
 * 真正的初始化仍在 {@code LauncherApp.boot()} 里跑，这样玩家打开启动器时
 * 看到的第一帧就是加载页，而不是白屏或未响应。
 */
public final class Splash {

    private static final double IMG_H = 600;
    private static final double BAR_W = 360;

    private static Stage stage;
    private static Label msg;
    private static Region fill;
    private static boolean closed;
    private static double curFrac;
    private static javafx.animation.Timeline fillAnim;

    private Splash() {
    }

    /** 显示加载页；任何异常都静默吞掉并退回「没有加载页」的旧行为，不阻断启动。 */
    public static void show() {
        if (stage != null) return;
        try {
            VBox box = new VBox(12);
            box.setAlignment(Pos.BOTTOM_CENTER);
            box.setPickOnBounds(false);
            // JavaFX 默认样式表会给 scene 根节点铺一层浅色背景，必须显式清掉才能透出桌面
            box.setStyle("-fx-background-color:transparent;");
            box.setPadding(new Insets(0, 0, 28, 0));

            StackPane art = new StackPane();
            art.setPickOnBounds(false);
            Image img = null;
            try (var in = Splash.class.getResourceAsStream("/splash.png")) {
                if (in != null) img = new Image(in);
            }
            if (img != null && !img.isError()) {
                ImageView iv = new ImageView(img);
                iv.setFitHeight(IMG_H);
                iv.setPreserveRatio(true);
                iv.setSmooth(true);
                art.getChildren().add(iv);
            } else {
                Label brand = new Label("JWH Launcher");
                brand.setStyle("-fx-font-size:34px; -fx-font-weight:bold; -fx-text-fill:#ffffff;"
                        + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.85), 10, 0.35, 0, 2);");
                art.getChildren().add(brand);
            }

            // 进度线：半透明白轨道 + 紫色推进（配色取自图里的地狱门）
            // 轨道要带外发光，否则在浅色壁纸上会完全看不见
            StackPane track = new StackPane();
            track.setPrefSize(BAR_W, 7);
            track.setMinSize(BAR_W, 7);
            track.setMaxSize(BAR_W, 7);
            track.setStyle("-fx-background-color:#14141c; -fx-background-radius:4;"
                    + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.75), 10, 0.5, 0, 2);");
            Region rail = new Region();
            rail.setStyle("-fx-background-color:rgba(255,255,255,0.16); -fx-background-radius:3;");
            fill = new Region();
            fill.setStyle("-fx-background-color:linear-gradient(to right, #a259ff, #d7b0ff);"
                    + "-fx-background-radius:3;"
                    + "-fx-effect:dropshadow(gaussian, rgba(168,85,247,0.95), 12, 0.55, 0, 0);");
            // 宽度完全由 prefWidth 决定，进度动画直接动 prefWidth
            fill.setMaxWidth(Region.USE_PREF_SIZE);
            fill.setPrefWidth(0);
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            track.getChildren().addAll(rail, fill);

            msg = new Label("正在启动…");
            msg.setStyle("-fx-text-fill:#ffffff; -fx-font-size:13.5px;"
                    + "-fx-font-weight:bold;"
                    + "-fx-background-color:rgba(10,10,16,0.60); -fx-background-radius:11;"
                    + "-fx-padding:4 12 4 12;"
                    + "-fx-effect:dropshadow(gaussian, rgba(0,0,0,0.75), 8, 0.4, 0, 1);");
            msg.setAlignment(Pos.CENTER);

            box.getChildren().addAll(art, track, msg);

            Scene scene = new Scene(box);
            scene.setFill(Color.TRANSPARENT);

            Stage s = new Stage();
            s.initStyle(StageStyle.TRANSPARENT);
            s.setAlwaysOnTop(true);
            s.setResizable(false);
            s.setScene(scene);
            s.setTitle("JWH Launcher");
            try (var icon = Splash.class.getResourceAsStream("/icon_256.png")) {
                if (icon != null) s.getIcons().add(new Image(icon));
            } catch (Exception ignored) {
            }
            stage = s;
            s.show();
            centerOn(s);
        } catch (Throwable t) {
            stage = null;
            fill = null;
            msg = null;
        }
    }

    /** 立即设置进度与文案（不做动画），用于加载页首帧。 */
    public static void progressNow(String text, double frac) {
        if (stage == null) return;
        final double f = Math.max(0.0, Math.min(1.0, frac));
        curFrac = f;
        Runnable r = () -> {
            if (closed || msg == null || fill == null) return;
            msg.setText(text);
            fill.setPrefWidth(Math.round(BAR_W * f));
        };
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    /**
     * 更新进度（0~1）与状态文案；可在任意线程调用。
     * 进度条宽度用 240ms 补间动画平滑爬升，而不是瞬间跳变——玩家能看清「在加载」。
     */
    public static void progress(String text, double frac) {
        if (stage == null) return;
        final double f = Math.max(curFrac, Math.max(0.0, Math.min(1.0, frac)));
        curFrac = f;
        Runnable r = () -> {
            if (closed || msg == null || fill == null) return;
            msg.setText(text);
            if (fillAnim != null) {
                fillAnim.stop();
            }
            final double target = Math.round(BAR_W * f);
            final double from = fill.getPrefWidth();
            if (target <= from) {
                fill.setPrefWidth(target);
                return;
            }
            fillAnim = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(fill.prefWidthProperty(), from)),
                    new KeyFrame(Duration.millis(200),
                            new KeyValue(fill.prefWidthProperty(), target,
                                    Interpolator.EASE_OUT)));
            fillAnim.play();
        };
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    /** 淡出并关闭加载页。主窗已 show 时调用，视觉上是「加载页退场、启动器浮现」。 */
    public static void finish() {
        if (stage == null || closed) return;
        closed = true;
        Runnable r = () -> {
            try {
                // 主窗已经 show，先把置顶关掉，让加载页退到主窗后面再淡出，
                // 否则它会盖住刚出现的启动器 300ms。
                try {
                    stage.setAlwaysOnTop(false);
                    stage.toBack();
                } catch (Exception ignored) {
                }
                Scene sc = stage.getScene();
                if (sc == null || sc.getRoot() == null) {
                    stage.close();
                    return;
                }
                FadeTransition ft = new FadeTransition(Duration.millis(300), sc.getRoot());
                ft.setFromValue(1.0);
                ft.setToValue(0.0);
                ft.setOnFinished(e -> {
                    try {
                        stage.close();
                    } catch (Exception ignored) {
                    }
                });
                ft.play();
            } catch (Exception e) {
                try {
                    stage.close();
                } catch (Exception ignored) {
                }
            }
        };
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    private static void centerOn(Stage s) {
        try {
            Rectangle2D vb = Screen.getPrimary().getVisualBounds();
            s.setX(vb.getMinX() + (vb.getWidth() - s.getWidth()) / 2.0);
            s.setY(vb.getMinY() + (vb.getHeight() - s.getHeight()) / 2.0 - vb.getHeight() * 0.02);
        } catch (Exception ignored) {
        }
    }
}
