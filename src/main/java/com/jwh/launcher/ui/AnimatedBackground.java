package com.jwh.launcher.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.geometry.Insets;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 内置动态背景：紫粉末地夜景底图 + 全息粒子漂浮 / 闪烁，复刻 Wallpaper Engine 壁纸
 * （holo_particles 特效）的观感。仅「默认背景」模式使用；图片 / 透明模式由调用方移除本节点。
 *
 * <p>参数对照 WE effect.json：particle_color 品红(1,0,1)、secondary 紫(0.667,0,1)，
 * alpha 0.09、speed 0.75、holo_flicker 0.4、amount 0.43、radius 0.15、scale 3、brightness 2.2。</p>
 */
public class AnimatedBackground {

    /** 粒子配色：品红 / 紫 / 浅紫，少量近白高光点。 */
    private static final Color[] PALETTE = {
            Color.color(1.0, 0.0, 1.0),
            Color.color(0.667, 0.0, 1.0),
            Color.color(0.85, 0.35, 1.0),
            Color.color(0.95, 0.85, 1.0)
    };

    /** 粒子数量（对应 amount 0.43，按窗口密度取 ~78 个）。 */
    private static final int PARTICLE_COUNT = 78;

    private final StackPane root = new StackPane();
    private final Pane particleLayer = new Pane();
    private final List<Particle> particles = new ArrayList<>();
    private final Random rnd = new Random();
    private Timeline timeline;
    private boolean running;

    public AnimatedBackground() {
        Image bg = new Image(AnimatedBackground.class.getResourceAsStream("/default_bg.jpg"));
        BackgroundSize size = new BackgroundSize(1, 1, true, true, false, true);
        BackgroundImage bi = new BackgroundImage(bg,
                BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER, size);
        // 深紫黑兜底填充：底图缺失 / 加载失败时不露白
        BackgroundFill fill = new BackgroundFill(Color.web("#171026"), CornerRadii.EMPTY, Insets.EMPTY);
        root.setBackground(new Background(new BackgroundFill[]{fill}, new BackgroundImage[]{bi}));
        // 背景层不拦截鼠标事件，点击穿透到上层界面
        root.setPickOnBounds(false);
        particleLayer.setPickOnBounds(false);
        root.getChildren().add(particleLayer);

        spawnAll();

        timeline = new Timeline();
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.getKeyFrames().add(new KeyFrame(Duration.millis(16), e -> tick()));
    }

    public Node node() {
        return root;
    }

    /** 启动粒子动画。 */
    public void start() {
        if (running) return;
        running = true;
        timeline.play();
    }

    /** 停止粒子动画（主题重建 / 切换背景模式时调用，避免多个时间线并行）。 */
    public void stop() {
        running = false;
        if (timeline != null) timeline.stop();
    }

    private void spawnAll() {
        double w = Math.max(root.getWidth(), 800);
        double h = Math.max(root.getHeight(), 500);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            Particle p = new Particle();
            p.radius = 1.6 + rnd.nextDouble() * 2.2;
            Color c = PALETTE[rnd.nextInt(PALETTE.length)];
            p.node = new Circle(p.radius, c);
            // 同色高斯辉光，模拟 holo_particles 的发光点
            DropShadow glow = new DropShadow(BlurType.GAUSSIAN, c, p.radius * 2.8, 0.5, 0, 0);
            p.node.setEffect(glow);
            p.x = rnd.nextDouble() * w;
            p.y = rnd.nextDouble() * h;
            p.vx = (rnd.nextDouble() - 0.5) * 0.12;
            p.vy = -(0.12 + rnd.nextDouble() * 0.22);
            p.baseOpacity = 0.20 + rnd.nextDouble() * 0.24;
            p.phase = rnd.nextDouble() * Math.PI * 2;
            p.flickerSpeed = 0.02 + rnd.nextDouble() * 0.03;
            p.node.setTranslateX(p.x);
            p.node.setTranslateY(p.y);
            p.node.setOpacity(p.baseOpacity);
            particles.add(p);
            particleLayer.getChildren().add(p.node);
        }
    }

    private void tick() {
        double w = root.getWidth();
        double h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        for (Particle p : particles) {
            p.x += p.vx;
            p.y += p.vy;
            p.phase += p.flickerSpeed;

            if (p.y < -8) {
                p.y = h + 8;
                p.x = rnd.nextDouble() * w;
            }
            if (p.x < -8) p.x = w + 8;
            else if (p.x > w + 8) p.x = -8;

            p.node.setTranslateX(p.x);
            p.node.setTranslateY(p.y);
            // holo_flicker 0.4：透明度按正弦脉动
            double op = p.baseOpacity * (0.68 + 0.32 * Math.sin(p.phase));
            p.node.setOpacity(Math.max(0, op));
        }
    }

    /** 单个粒子状态。 */
    private static final class Particle {
        Circle node;
        double x, y, vx, vy;
        double baseOpacity, phase, flickerSpeed, radius;
    }
}
