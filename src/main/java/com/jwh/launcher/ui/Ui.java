package com.jwh.launcher.ui;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * UI 公共工具：后台任务执行（结果回到 FX 线程）、提示对话框、主题色。
 * 主题色为可变静态字段，由 SettingsView「自定义背景 / 文字颜色」在保存后刷新，
 * 界面随后整体重建以应用新主题。
 */
public final class Ui {

    public static String BG = "#f5f6f8";
    public static String BG_LIGHT = "#e9ebee";
    public static String PANEL = "rgba(255,255,255,0.75)";
    public static String BORDER = "rgba(255,255,255,0.14)";
    public static final String ACCENT = "#3FA9F5";
    public static String TEXT = "#1f2328";
    public static String TEXT_DIM = "#6b7280";
    /** 当前是否深色界面：深色背景下文字 / 控件自动用浅色方案（自动反转的判定位）。 */
    public static boolean DARK = true;

    /** 白色渐变背景（默认主题，用于主窗口与卡片）。 */
    public static String BG_GRADIENT =
            "linear-gradient(to bottom right, #ffffff 0%, #f4f6f9 55%, #e9edf2 100%)";

    /** 按用户配置刷新主题色。 */
    public static void applyTheme(com.jwh.launcher.config.LauncherConfig cfg) {
        String mode = cfg.bgMode == null ? "default" : cfg.bgMode;
        // 三种背景均为深色：自动按深色界面反色（浅色文字 + 深色控件）
        DARK = true;
        // 深色背景下，若历史遗留的自定义字色仍是深色（旧浅色主题保存下来的黑/深灰），
        // 直接判为不适用，自动回退到浅色默认值，避免深底黑字。
        TEXT = resolveTextColor(cfg.textColor, "#e8eaed");
        TEXT_DIM = resolveTextColor(cfg.dimColor, "#b9bfc7");
        // 面板/背景色随背景模式调整：均用半透明深色，让背景透出
        switch (mode) {
            case "image" -> {
                BG = "rgba(14,17,22,0.55)";
                BG_LIGHT = "rgba(20,24,30,0.72)";
                PANEL = "rgba(30,35,44,0.66)";
            }
            case "transparent" -> {
                // 真透明窗口：面板更透，让桌面壁纸透出
                BG = "rgba(8,10,14,0.30)";
                BG_LIGHT = "rgba(12,16,22,0.50)";
                PANEL = "rgba(20,25,32,0.45)";
            }
            default -> {
                // 内置动态夜景：深半透明面板，让底图与粒子透出
                BG = "rgba(14,17,22,0.55)";
                BG_LIGHT = "rgba(20,24,30,0.72)";
                PANEL = "rgba(30,35,44,0.66)";
            }
        }
    }

    /** 判断十六进制颜色是否偏暗（深底上不可读）。解析失败按「非暗」处理，不拦截用户显式选色。 */
    private static boolean isDarkHex(String hex) {
        if (hex == null || hex.isBlank()) return false;
        String h = hex.trim().replace("#", "");
        try {
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            if (h.length() != 6) return false;
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
            return luminance < 0.40;
        } catch (Exception e) {
            return false;
        }
    }

    /** 深色背景下解析生效字色：空值或偏暗的历史遗留色回退到 fallback 浅色。 */
    public static String resolveTextColor(String cfgVal, String fallback) {
        if (cfgVal == null || cfgVal.isBlank() || isDarkHex(cfgVal)) return fallback;
        return cfgVal;
    }
    public static final javafx.scene.image.Image GRASS_BLOCK = loadImage("/grass_block.png");
    /** 愚人节版本图标：粘液块（MC 官方贴图，HMCL 同款）。 */
    public static final javafx.scene.image.Image SLIME_BLOCK = loadImage("/slime_block.png");
    /** 远古版图标：工作台正面（MC 官方贴图，HMCL 同款）。 */
    public static final javafx.scene.image.Image CRAFTING_TABLE = loadImage("/crafting_table_front.png");

    /** 皮肤预览「走动」开关图标：深色线稿用于浅色背景，白色线稿用于深色背景。 */
    public static final javafx.scene.image.Image WALK_DARK = loadImage("/walk_icon_dark.png", 64);
    public static final javafx.scene.image.Image WALK_LIGHT = loadImage("/walk_icon_light.png", 64);

    private static javafx.scene.image.Image loadImage(String res, int size) {
        try {
            java.io.InputStream in = Ui.class.getResourceAsStream(res);
            return in == null ? null : new javafx.scene.image.Image(in, size, size, true, true);
        } catch (Exception e) {
            return null;
        }
    }

    private static javafx.scene.image.Image loadImage(String res) {
        return loadImage(res, 32);
    }

    /** 加载全局样式表到场景。 */
    public static void applyStylesheet(javafx.scene.Scene scene) {
        java.net.URL css = Ui.class.getResource("/style.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "jwh-worker");
        t.setDaemon(true);
        return t;
    });

    private Ui() {
    }

    /** 在后台线程执行任务，成功/失败回调在 FX 线程。 */
    public static void async(java.util.concurrent.Callable<Void> task,
                             Consumer<String> onSuccess, Consumer<Exception> onError) {
        POOL.submit(() -> {
            try {
                task.call();
                if (onSuccess != null) Platform.runLater(() -> onSuccess.accept(null));
            } catch (Exception e) {
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(e));
                } else {
                    Platform.runLater(() -> error("操作失败", e));
                }
            }
        });
    }

    /** 泛型版本：后台任务可携带返回值。 */
    public static <T> void async2(java.util.concurrent.Callable<T> task,
                                  Consumer<T> onSuccess, Consumer<Exception> onError) {
        POOL.submit(() -> {
            try {
                T result = task.call();
                if (onSuccess != null) Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(e));
                } else {
                    Platform.runLater(() -> error("操作失败", e));
                }
            }
        });
    }

    /** 带进度回调的异步任务（进度回调也切到 FX 线程）。 */
    public static void asyncProgress(
            java.util.function.Consumer<java.util.function.Consumer<String>> taskWithProgress,
            Consumer<String> progress,
            Consumer<String> onSuccess,
            Consumer<Exception> onError) {
        POOL.submit(() -> {
            try {
                taskWithProgress.accept(s -> Platform.runLater(() -> progress.accept(s)));
                if (onSuccess != null) Platform.runLater(() -> onSuccess.accept(null));
            } catch (Exception e) {
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(e));
                } else {
                    Platform.runLater(() -> error("操作失败", e));
                }
            }
        });
    }

    public static void error(String title, Exception e) {
        error(title, describe(e));
    }

    /** 拼接异常类名、message 与根因链，避免弹窗只剩一句被截断的路径。 */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 6) {
            if (depth > 0) sb.append("\n原因：");
            sb.append(cur.getClass().getSimpleName());
            String msg = cur.getMessage();
            if (msg != null && !msg.isBlank()) sb.append(": ").append(msg);
            cur = cur.getCause();
            depth++;
        }
        return sb.toString().isBlank() ? "未知错误" : sb.toString();
    }

    /** 主窗口句柄：弹窗挂到它身上，避免弹窗跑到主窗后面/被当成“没反应”。 */
    public static volatile javafx.stage.Window dialogOwner = null;

    /** 弹窗统一样式：放大字号 + 归属主窗口 + 居中置顶。 */
    private static void styleDialog(Alert a) {
        try {
            a.getDialogPane().setStyle("-fx-font-size:14px;");
        } catch (Exception ignored) {
        }
        try {
            javafx.stage.Window owner = dialogOwner;
            if (owner == null) {
                for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                    if (w instanceof javafx.stage.Stage s && s.isShowing()) {
                        owner = s;
                        break;
                    }
                }
            }
            if (owner != null && owner.isShowing()) {
                a.initOwner(owner);
                javafx.stage.Window o = owner;
                a.setOnShown(ev -> {
                    try {
                        a.setX(o.getX() + (o.getWidth() - a.getWidth()) / 2);
                        a.setY(o.getY() + (o.getHeight() - a.getHeight()) / 2);
                        javafx.stage.Window self = a.getDialogPane().getScene().getWindow();
                        if (self != null) {
                            self.requestFocus();
                        }
                        a.getDialogPane().requestFocus();
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 列表滚轮接管：无论 ListView 处于何种布局/嵌套，滚轮事件都会先经过
     * 本过滤器，直接驱动垂直滚动条（decrement/increment），保证滚轮一定
     * 能滚动列表（修复实例管理等处原生滚轮失效）。无滚动需求时交给默认行为。
     */
    public static void bindWheelScroll(javafx.scene.control.ListView<?> list) {
        list.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            javafx.scene.control.ScrollBar bar = null;
            for (javafx.scene.Node n : list.lookupAll(".scroll-bar")) {
                if (n instanceof javafx.scene.control.ScrollBar b
                        && b.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                    bar = b;
                    break;
                }
            }
            if (bar == null || !bar.isVisible()) return; // 无滚动需求
            double d = e.getDeltaY();
            if (d == 0) return;
            int steps = (int) Math.ceil(Math.abs(d) / 12.0); // 一格 deltaY≈40 → 3 步
            if (d > 0) {
                for (int i = 0; i < steps; i++) bar.decrement();
            } else {
                for (int i = 0; i < steps; i++) bar.increment();
            }
            e.consume();
        });
    }

    /** 页面切换动效时长（毫秒）；-Djwh.pageAnimMs=0 可关闭动画（低配/无障碍）。 */
    private static int pageAnimMs() {
        try {
            int v = Integer.parseInt(System.getProperty("jwh.pageAnimMs", "230").trim());
            if (v < 0) v = 0;
            if (v > 2000) v = 2000;
            return v;
        } catch (Exception e) {
            return 230;
        }
    }

    /** 页面切换动效（进入）：新页面从右侧滑入并淡入，PCL 风格的翻页手感。 */
    public static void pageIn(javafx.scene.Node node) {
        if (node == null) return;
        int ms = pageAnimMs();
        if (ms == 0) {
            node.setTranslateX(0);
            node.setOpacity(1);
            return;
        }
        node.setTranslateX(52);
        node.setOpacity(0);
        javafx.animation.TranslateTransition tt = new javafx.animation.TranslateTransition(
                javafx.util.Duration.millis(ms), node);
        tt.setFromX(52);
        tt.setToX(0);
        tt.setInterpolator(javafx.animation.Interpolator.SPLINE(0.22, 0.61, 0.36, 1));
        javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(Math.max(1, ms * 8 / 10)), node);
        ft.setFromValue(0);
        ft.setToValue(1);
        javafx.animation.ParallelTransition pt = new javafx.animation.ParallelTransition(tt, ft);
        pt.play();
    }

    /** 页面切换动效（退出）：向右滑出并淡出，结束后执行 after（清空覆盖层等收尾）。 */
    public static void pageOut(javafx.scene.Node node, Runnable after) {
        if (node == null) {
            if (after != null) after.run();
            return;
        }
        int ms = pageAnimMs();
        if (ms == 0) {
            node.setTranslateX(0);
            node.setOpacity(1);
            if (after != null) after.run();
            return;
        }
        javafx.animation.TranslateTransition tt = new javafx.animation.TranslateTransition(
                javafx.util.Duration.millis(Math.max(1, ms * 8 / 10)), node);
        tt.setFromX(node.getTranslateX());
        tt.setToX(52);
        tt.setInterpolator(javafx.animation.Interpolator.SPLINE(0.55, 0.06, 0.68, 0.19));
        javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(Math.max(1, ms * 7 / 10)), node);
        ft.setFromValue(node.getOpacity());
        ft.setToValue(0);
        javafx.animation.ParallelTransition pt = new javafx.animation.ParallelTransition(tt, ft);
        pt.setOnFinished(e -> {
            node.setTranslateX(0);
            node.setOpacity(1);
            if (after != null) after.run();
        });
        pt.play();
    }

    public static void error(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, null, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(title);
        javafx.scene.control.Label body = new javafx.scene.control.Label(msg == null ? "" : msg);
        body.setWrapText(true);
        body.setMaxWidth(460);
        body.setStyle("-fx-font-size:13px;");
        a.getDialogPane().setContent(body);
        a.getDialogPane().setPrefWidth(520);
        styleDialog(a);
        a.showAndWait();
    }

    /** 信息提示 sink：LauncherApp 启动时注册后，info 改为窗口内 toast（不弹窗）。 */
    public static volatile java.util.function.Consumer<String> toastSink = null;

    public static void info(String title, String msg) {
        String text = (title == null || title.isBlank() ? "" : title + "：") + msg;
        if (toastSink != null) {
            toastSink.accept(text);
            return;
        }
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(title);
        styleDialog(a);
        a.showAndWait();
    }

    public static boolean confirm(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        a.setTitle(title);
        a.setHeaderText(title);
        styleDialog(a);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    /**
     * 整合包导出选项：勾选是否把存档一起打包 + 选包格式（CurseForge / Modrinth）。
     *
     * @return null=取消；否则 [0]=是否含存档(1/0)、[1]=格式(0=CurseForge, 1=Modrinth)
     */
    public static int[] packExportOptions(String title, String msg, boolean hasSaves) {
        javafx.scene.control.Label body = new javafx.scene.control.Label(msg);
        body.setWrapText(true);
        body.setMaxWidth(470);

        javafx.scene.control.CheckBox saves = new javafx.scene.control.CheckBox("包含存档 saves（世界进度一起打包）");
        if (!hasSaves) {
            saves.setText("包含存档 saves（这个实例里没有存档）");
            saves.setDisable(true);
            saves.setSelected(false);
        }

        javafx.scene.control.RadioButton cf = new javafx.scene.control.RadioButton(
                "CurseForge 结构（.zip）：PCL / HMCL / CurseForge 认");
        javafx.scene.control.RadioButton mr = new javafx.scene.control.RadioButton(
                "Modrinth 结构（.mrpack）：Modrinth App / Prism 认");
        javafx.scene.control.ToggleGroup tg = new javafx.scene.control.ToggleGroup();
        cf.setToggleGroup(tg);
        mr.setToggleGroup(tg);
        cf.setSelected(true);

        javafx.scene.control.Label hint = new javafx.scene.control.Label(
                "两种格式都能再拖回本启动器导入；内容一样，只是清单文件不同。");
        hint.setWrapText(true);
        hint.setMaxWidth(470);
        hint.setStyle("-fx-text-fill:" + TEXT_DIM + "; -fx-font-size:12px;");

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(10,
                body, new javafx.scene.control.Separator(), saves, cf, mr, hint);
        box.setPadding(new javafx.geometry.Insets(4, 0, 0, 0));

        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.YES, ButtonType.NO);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().setContent(box);
        a.getDialogPane().setPrefWidth(520);
        styleDialog(a);
        if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return null;
        return new int[]{saves.isSelected() ? 1 : 0, mr.isSelected() ? 1 : 0};
    }

    /**
     * 单行文本输入弹窗（服务器地址之类）。
     *
     * @return 用户填的内容（已 trim）；取消或留空返回 null
     */
    public static String promptText(String title, String msg, String initial, String placeholder) {
        javafx.scene.control.Label body = new javafx.scene.control.Label(msg);
        body.setWrapText(true);
        body.setMaxWidth(430);

        javafx.scene.control.TextField field = new javafx.scene.control.TextField(initial == null ? "" : initial);
        field.setPromptText(placeholder == null ? "" : placeholder);
        field.setPrefWidth(430);
        field.setStyle("-fx-background-color:" + BG_LIGHT + "; -fx-text-fill:" + TEXT + ";"
                + "-fx-prompt-text-fill:#8a9099; -fx-background-radius:6;"
                + "-fx-border-color:" + BORDER + "; -fx-border-radius:6; -fx-padding:7 9 7 9; -fx-font-size:13px;");

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(10, body, field);
        box.setPadding(new javafx.geometry.Insets(4, 0, 0, 0));

        javafx.scene.control.ButtonType okType =
                new javafx.scene.control.ButtonType("确定", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancelType =
                new javafx.scene.control.ButtonType("取消", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "", okType, cancelType);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().setContent(box);
        a.getDialogPane().setPrefWidth(500);
        styleDialog(a);
        // 弹窗打开后光标直接落在输入框里
        javafx.application.Platform.runLater(field::requestFocus);
        if (a.showAndWait().orElse(cancelType) != okType) return null;
        String v = field.getText() == null ? "" : field.getText().trim();
        return v.isEmpty() ? null : v;
    }

    /** 进度对话框句柄：后台线程可安全更新状态文本与进度值，结束后关闭对话框。 */
    public static final class ProgressHandle {
        private final javafx.stage.Stage stage;
        private final javafx.scene.control.Label label;
        private final javafx.scene.control.ProgressBar bar;

        private ProgressHandle(javafx.stage.Stage stage, javafx.scene.control.Label label,
                               javafx.scene.control.ProgressBar bar) {
            this.stage = stage;
            this.label = label;
            this.bar = bar;
        }

        /** 更新状态文本（线程安全，内部切回 FX 线程）。 */
        public void setMessage(String msg) {
            if (msg == null) return;
            Platform.runLater(() -> label.setText(msg));
        }

        /**
         * 更新确定进度值（线程安全，内部切回 FX 线程）。
         * 传入 NaN 或负值时回退为不确定滚动动画；值按 0..1 截断。
         */
        public void setProgress(double v) {
            Platform.runLater(() -> {
                if (bar == null) return;
                if (Double.isNaN(v) || v < 0) {
                    bar.setProgress(javafx.scene.control.ProgressBar.INDETERMINATE_PROGRESS);
                } else {
                    bar.setProgress(Math.min(1.0, Math.max(0.0, v)));
                }
            });
        }

        /** 关闭进度对话框（线程安全）。 */
        public void close() {
            Platform.runLater(stage::close);
        }
    }

    /**
     * 打开一个带确定进度条的模态进度对话框（非阻塞等待），用于下载/更新等后台长任务。
     * 任务在后台线程执行：状态文本通过 {@link ProgressHandle#setMessage} 更新，
     * 进度值通过 {@link ProgressHandle#setProgress} 上报；未上报数值时进度条保持 0。
     */
    public static ProgressHandle progressDialog(String title, String message) {
        javafx.stage.Stage stage = new javafx.stage.Stage(javafx.stage.StageStyle.UTILITY);
        stage.setTitle(title);

        javafx.scene.control.Label label = new javafx.scene.control.Label(message == null ? "" : message);
        label.setWrapText(true);
        label.setMaxWidth(430);
        label.setStyle("-fx-text-fill:" + TEXT + "; -fx-font-size:13px;");

        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar();
        bar.setProgress(0.0);
        bar.setPrefWidth(430);
        bar.setStyle("-fx-accent:" + ACCENT + ";");

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(12, label, bar);
        box.setPadding(new javafx.geometry.Insets(20, 22, 20, 22));
        box.setStyle("-fx-background-color:" + BG + ";");

        javafx.scene.Scene scene = new javafx.scene.Scene(box);
        applyStylesheet(scene);
        stage.setScene(scene);
        stage.setResizable(false);
        stage.initModality(javafx.stage.Modality.WINDOW_MODAL);

        javafx.stage.Window owner = dialogOwner;
        if (owner == null) {
            for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                if (w instanceof javafx.stage.Stage s && s.isShowing()) {
                    owner = w;
                    break;
                }
            }
        }
        if (owner != null && owner.isShowing()) {
            stage.initOwner(owner);
        }
        stage.setOnShown(ev -> {
            try {
                javafx.stage.Window o = stage.getOwner();
                if (o != null) {
                    stage.setX(o.getX() + (o.getWidth() - stage.getWidth()) / 2);
                    stage.setY(o.getY() + (o.getHeight() - stage.getHeight()) / 2);
                }
            } catch (Exception ignored) {
            }
        });
        stage.show();
        return new ProgressHandle(stage, label, bar);
    }
}
