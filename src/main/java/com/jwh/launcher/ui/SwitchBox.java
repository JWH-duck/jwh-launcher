package com.jwh.launcher.ui;

import javafx.animation.TranslateTransition;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * 滑块开关（iOS 风格）：胶囊轨道 + 白色圆钮，点击切换。
 * 开 = 绿色轨道、圆钮滑到右侧；关 = 灰色轨道、圆钮在左侧。
 * 与文件系统操作无关，仅负责视觉与点击；业务侧通过 {@link #setOnAction(Runnable)} 接收切换事件。
 */
public class SwitchBox extends Region {

    private static final double W = 46, H = 26, THUMB = 22, GAP = 2;

    private final Label track;
    private final Label thumb;
    private boolean on;
    private Runnable onChange;

    public SwitchBox(boolean on) {
        this.on = on;
        setPrefSize(W, H);
        setMinSize(W, H);
        setMaxSize(W, H);
        setPickOnBounds(true);

        track = new Label();
        track.setPrefSize(W, H);
        track.setPickOnBounds(true);

        thumb = new Label();
        thumb.setPrefSize(THUMB, THUMB);
        thumb.setStyle("-fx-background-color:white; -fx-background-radius:" + (THUMB / 2) + ";"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 3, 0.3, 0, 1);");
        thumb.setLayoutX(GAP);
        thumb.setLayoutY((H - THUMB) / 2);

        getChildren().addAll(track, thumb);
        applyVisual(false);
        setOnMouseClicked(e -> flip());
    }

    public boolean isOn() {
        return on;
    }

    /** 外部强制设置状态（不触发 onChange）。 */
    public void setOn(boolean v) {
        if (on == v) return;
        on = v;
        applyVisual(true);
    }

    /** 切换事件（点击滑块时触发，此时视觉已更新）。 */
    public void setOnAction(Runnable r) {
        this.onChange = r;
    }

    private void flip() {
        on = !on;
        applyVisual(true);
        if (onChange != null) onChange.run();
    }

    private void applyVisual(boolean animate) {
        track.setStyle("-fx-background-color:" + (on ? "#34c759" : "#b4b4b4") + ";"
                + "-fx-background-radius:" + (H / 2) + ";");
        double toX = on ? W - THUMB - GAP * 2 : 0;
        if (animate) {
            TranslateTransition t = new TranslateTransition(Duration.millis(160), thumb);
            t.setToX(toX);
            t.play();
        } else {
            thumb.setTranslateX(toX);
        }
    }
}
