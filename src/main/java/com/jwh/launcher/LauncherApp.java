package com.jwh.launcher;

import com.jwh.launcher.auth.AuthService;
import com.jwh.launcher.auth.MicrosoftAuthenticator;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.game.GameLauncher;
import com.jwh.launcher.game.JavaFinder;
import com.jwh.launcher.game.LocalVersions;
import com.jwh.launcher.download.ModpackInstaller;
import com.jwh.launcher.ui.DownloadView;
import com.jwh.launcher.ui.HomeView;
import com.jwh.launcher.ui.AboutView;
import com.jwh.launcher.ui.LogView;
import com.jwh.launcher.ui.LoginView;
import com.jwh.launcher.ui.MultiplayerView;
import com.jwh.launcher.ui.SettingsView;
import com.jwh.launcher.ui.SkinsView;
import com.jwh.launcher.ui.Ui;
import com.jwh.launcher.util.ModpackImporter;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 主窗口：左侧导航（主页 / 下载 / 设置）+ 右侧内容区 + 顶栏账号区 + 底部状态栏。
 * 全局共享配置、账号服务、版本扫描与 Java 列表。
 */
public class LauncherApp extends Application {

    /** 应用版本号：打包时由 jpackage 注入 app-version，jar 直跑时兜底。 */
    public static final String APP_VERSION =
            System.getProperty("jpackage.app-version", "1.6.0");

    /** 启动器所在目录（自更新文件、头像日志等落点）。打包后为 exe 所在目录，jar 直跑时为工作目录。 */
    public static java.nio.file.Path appDir() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null && !exe.isBlank()) {
            java.nio.file.Path p = java.nio.file.Path.of(exe).toAbsolutePath();
            if (Files.isRegularFile(p)) return p.getParent();
        }
        return java.nio.file.Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
    }

    public LauncherConfig config;
    public AuthService auth;
    public GameLauncher gameLauncher;
    public LocalVersions localVersions;
    public List<JavaFinder.JavaRuntime> javaRuntimes;
    /** 当前已选 Java 的路径（由设置页 / 主页修改）。 */
    public volatile String selectedJavaPath = "";

    /**
     * Java 匹配结果。
     * javaExe 为 null = 电脑上没有可用的 Java；actualMajor 为实际将用的 Java 主版本；
     * needMajor 为该版本官方要求；autoUpgraded = 因需求自动从配置/默认切到更高版本。
     */
    public record JavaChoice(Path javaExe, int needMajor, int actualMajor, boolean autoUpgraded) {
        public boolean satisfied() {
            return javaExe != null && actualMajor >= needMajor;
        }
    }

    /**
     * 解析启动游戏用的 Java（随游戏版本自动匹配，不再死守单一版本）：
     * 1) 手动指定：尊重选择（实际版本若低于需求，由 UI 弹窗提示）；
     * 2) 配置指定：满足需求则用，不满足视为失效交给自动匹配；
     * 3) 自动匹配：从已装 Java 中选 >= 需求的最低版本；都没有则用最高版本（标记不满足，由 UI 引导下载）。
     */
    public JavaChoice resolveJava(String mcVersion, JavaFinder.JavaRuntime manual) {
        int need = gameLauncher.requiredJavaMajor(mcVersion);
        // 1) 手动指定
        if (manual != null && !manual.isAuto() && manual.javaExe() != null) {
            return new JavaChoice(manual.javaExe(), need, javaMajorOf(manual), false);
        }
        // 2) 配置指定：满足需求才用
        String cfg = config.javaPath;
        if (cfg != null && !cfg.isBlank()) {
            Path p = Path.of(cfg);
            if (Files.exists(p)) {
                int m = majorOfPath(p);
                if (m >= need) return new JavaChoice(p, need, m, false);
                // 低于需求 → 继续自动匹配
            }
        }
        // 3) 自动匹配（后台扫描未完成时同步补扫一次，仅启动/设置场景，可接受等待）
        if (javaRuntimes == null) {
            javaRuntimes = JavaFinder.find();
            if (selectedJavaPath.isEmpty() && !javaRuntimes.isEmpty()) {
                selectedJavaPath = javaRuntimes.get(0).javaExe().toString();
            }
        }
        if (javaRuntimes != null && !javaRuntimes.isEmpty()) {
            JavaFinder.JavaRuntime best = null;
            for (var r : javaRuntimes) {
                if (r.major() >= need && (best == null || r.major() < best.major())) best = r;
            }
            if (best != null) return new JavaChoice(best.javaExe(), need, best.major(), true);
            // 全部低于需求：用最高版本，标记不满足（UI 引导下载）
            JavaFinder.JavaRuntime top = javaRuntimes.get(0);
            return new JavaChoice(top.javaExe(), need, top.major(), false);
        }
        return new JavaChoice(null, need, -1, false);
    }

    private int javaMajorOf(JavaFinder.JavaRuntime r) {
        if (r.major() > 0) return r.major();
        return majorOfPath(r.javaExe());
    }

    private int majorOfPath(Path javaExe) {
        try {
            return JavaFinder.parseMajor(JavaFinder.readVersion(javaExe));
        } catch (Exception e) {
            return -1;
        }
    }

    /** 打开外部网页（如 Java 下载引导、维基页面）。 */
    public void openUrl(String url) {
        try {
            getHostServices().showDocument(url);
            return;
        } catch (Throwable ignored) {
            // 宿主机没有配置默认浏览器处理器时退回 AWT
        }
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Throwable ignored) {
            // 两种方式都不可用时静默，避免打断界面
        }
    }

    /** 下载并应用更新：完成后自动重启启动器（供「关于」页手动检查与启动自动检查共用）。 */
    public void startUpdate(com.jwh.launcher.update.Updater.UpdateInfo info) {
        Ui.asyncProgress(progress -> {
                    try {
                        new com.jwh.launcher.update.Updater(appDir(), APP_VERSION).downloadAndApply(info);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                },
                progress -> setStatus(progress),
                ok -> {
                    Ui.info("更新完成", "更新包已就绪，启动器将自动重启并完成更新。");
                    System.exit(0);
                },
                e -> Ui.error("更新失败", e));
    }

    private final StackPane content = new StackPane();
    /** 全页详情覆盖层：独立页面铺满整个窗口，盖住顶栏账号区、左侧导航与状态栏。 */
    private final StackPane pageLayer = new StackPane();
    private final Label statusLabel = new Label("就绪");
    /** 主窗口根布局（背景随自定义主题刷新）。 */
    private BorderPane root;
    /** 主窗口（透明模式下用于无边框拖动 / 最小化 / 关闭）。 */
    private Stage stage;
    private double dragX, dragY;
    /** 顶栏账号区：头像 + ID + 登录类型。 */
    private final javafx.scene.image.ImageView avatarView = new javafx.scene.image.ImageView();
    private final Label idLabel = new Label("…");
    private final Label typeLabel = new Label("");
    /** 顶栏右上角的内置音乐播放器（全页面共用一份，切页不中断播放）。 */
    private com.jwh.launcher.ui.MusicBar musicBar;

    /** 顶栏音乐播放器（可能尚未构建，调用方自行判空）。 */
    public com.jwh.launcher.ui.MusicBar musicBar() {
        return musicBar;
    }
    private HomeView homeView;
    private DownloadView downloadView;
    private LoginView loginView;
    private SkinsView skinsView;
    private SettingsView settingsView;
    private LogView logView;
    private AboutView aboutView;
    /** 共享游戏输出日志区（主页已移除，展示于左侧导航「日志」页）。 */
    public final javafx.scene.control.TextArea gameLog = new javafx.scene.control.TextArea();

    /** 主界面 Scene（在加载页阶段就建好并预热，主窗一开就是成品）。 */
    private Scene bootScene;
    /** 是否透明窗口模式（加载页阶段已按配置决定）。 */
    private boolean transparentWindow;
    /** 加载页的加载项清单（懒构建，一次）。 */
    private java.util.List<BootStep> bootPlanList;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        // 加载页先顶上：这里立刻返回，让 JavaFX 先把加载页画出来，
        // 再照下面的加载项一项一项往下做——玩家看到的第一帧是加载页而不是白屏/未响应。
        com.jwh.launcher.ui.Splash.show();
        com.jwh.launcher.ui.Splash.progressNow("正在启动 JWH Launcher…", 0.05);
        // 必须等加载页真的画出来再往下走：初始化是同步跑的，一旦直接开跑，
        // 加载页会在被渲染出来之前就被主窗盖掉（实测靠帧计数仍然太早，改用固定停顿）。
        javafx.animation.PauseTransition gate =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(280));
        gate.setOnFinished(e -> bootStep(stage, 0));
        gate.play();
    }

    /** 加载页上的一项：一条状态文案 + 目标进度 + 真正要干的活。 */
    private static final class BootStep {
        final String text;
        final double frac;
        final Runnable work;
        /** 非空表示这一项是异步的：把「干完的通知」交给它，由它自己在后台跑完再回调。 */
        java.util.function.Consumer<Runnable> async;

        BootStep(String text, double frac, Runnable work) {
            this.text = text;
            this.frac = frac;
            this.work = work;
        }

        /** 异步项：耗时联网的活放后台线程，跑完调 done，加载页照常动画不卡。 */
        static BootStep async(String text, double frac, java.util.function.Consumer<Runnable> job) {
            BootStep s = new BootStep(text, frac, null);
            s.async = job;
            return s;
        }
    }

    /**
     * 按顺序执行加载项：报进度 → 干活 → 歇一小下（让进度条动画和界面重绘跟得上）
     * → 下一项。全部走完（进度到 100%）才打开启动器。
     */
    private void bootStep(Stage stage, int idx) {
        if (bootPlanList == null) {
            bootPlanList = buildBootPlan(stage);
        }
        if (idx >= bootPlanList.size()) {
            finishBoot(stage);
            return;
        }
        BootStep s = bootPlanList.get(idx);
        com.jwh.launcher.ui.Splash.progress(s.text, s.frac);
        if (s.async != null) {
            // 异步项：等它回调「干完了」再进下一项；最多等 8 秒，站点再慢也不把加载页拖死
            final java.util.concurrent.atomic.AtomicBoolean settled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Runnable done = () -> {
                if (!settled.compareAndSet(false, true)) return;
                javafx.application.Platform.runLater(() -> pauseThenNext(stage, idx));
            };
            javafx.animation.PauseTransition guard =
                    new javafx.animation.PauseTransition(javafx.util.Duration.seconds(8));
            guard.setOnFinished(e -> done.run());
            guard.play();
            try {
                s.async.accept(done);
            } catch (Throwable t) {
                System.err.println("[boot] " + s.text + " 启动失败: " + t);
                done.run();
            }
            return;
        }
        try {
            s.work.run();
        } catch (Throwable t) {
            // 单项失败不阻断整个启动，跳到下一项（与旧行为一致：初始化出错也不该卡死在加载页）
            System.err.println("[boot] " + s.text + " 失败: " + t);
        }
        pauseThenNext(stage, idx);
    }

    /** 160ms 让进度条动画看得见地往前爬，又不至于把启动拖得太长，然后进下一项。 */
    private void pauseThenNext(Stage stage, int idx) {
        javafx.animation.PauseTransition step =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(160));
        step.setOnFinished(e -> bootStep(stage, idx + 1));
        step.play();
    }

    /**
     * 加载页上真正要做的事：配置 → 环境 → 账号 → 启动模块 → Java → 内存/下载源
     * → 主题 → 界面骨架 → 各子页面预建 → 渲染预热 → 资源。共 15 项。
     */
    private java.util.List<BootStep> buildBootPlan(Stage stage) {
        java.util.List<BootStep> plan = new java.util.ArrayList<>();
        plan.add(new BootStep("正在读取配置文件…", 0.09, () -> config = ConfigManager.load()));
        plan.add(new BootStep("正在校验运行环境…", 0.15, this::sanitizeConfig));
        plan.add(new BootStep("正在初始化账号服务…", 0.21, () -> {
            MicrosoftAuthenticator.init(config);
            auth = new AuthService(config);
        }));
        plan.add(new BootStep("正在准备游戏启动模块…", 0.27, () -> {
            gameLauncher = new GameLauncher(config);
            localVersions = new LocalVersions(config);
            if (config.javaPath != null && !config.javaPath.isBlank()) {
                selectedJavaPath = config.javaPath;
            }
        }));
        plan.add(new BootStep("正在探测 Java 运行时…", 0.33, () ->
                Ui.async2(JavaFinder::find, found -> {
                    javaRuntimes = found;
                    if (selectedJavaPath.isEmpty() && !found.isEmpty()) {
                        selectedJavaPath = found.get(0).javaExe().toString();
                    }
                }, e -> { /* 静默：无 Java 时由设置页 / 启动引导兜底 */ })));
        plan.add(new BootStep("正在分配内存与检测下载源…", 0.39, () -> {
            applyAutoMemory();
            autoSelectSource();
        }));
        plan.add(new BootStep("正在应用主题与界面样式…", 0.45, () -> {
            transparentWindow = "transparent".equals(config.bgMode);
            if (transparentWindow) {
                stage.initStyle(StageStyle.TRANSPARENT);
            }
            Ui.applyTheme(config);
            // 信息提示改走窗口内 toast（不弹窗），错误与危险确认仍用弹窗
            Ui.toastSink = this::showToast;
        }));
        plan.add(new BootStep("正在构建主界面框架…", 0.53, () -> {
            bootScene = buildScene(stage);
            Ui.applyStylesheet(bootScene);
        }));
        plan.add(new BootStep("正在预建下载中心页面…", 0.62, this::ensureDownload));
        // 皮肤站账号：启动时就把皮肤/披风拉到本地（后台线程，页面动画不卡），
        // 这样进皮肤库页直接有内容，不用等进页面才联网。
        plan.add(BootStep.async("正在同步 LittleSkin 皮肤站…", 0.66, done -> {
            new Thread(() -> {
                try {
                    if (com.jwh.launcher.auth.LittleSkinSync.prefetch(config)) {
                        ConfigManager.save(config);
                    }
                } catch (Throwable t) {
                    System.err.println("[boot] LittleSkin 同步失败: " + t);
                } finally {
                    done.run();
                }
            }, "JWH-LittleSkin-Prefetch").start();
        }));
        plan.add(new BootStep("正在预建实例与皮肤库页面…", 0.69, () -> {
            ensureSkins();
            ensureSettings();
        }));
        plan.add(new BootStep("正在预建日志与账号页面…", 0.75, () -> {
            ensureLog();
            ensureAbout();
            loginContent();
        }));
        plan.add(new BootStep("正在预热界面渲染…", 0.83, () -> {
            prepareStage(stage);
            warmUpLayout();
        }));
        plan.add(new BootStep("正在加载界面资源…", 0.90, () -> {
            loadIcons(stage);
            warmUpLayout();
        }));
        plan.add(new BootStep("正在保存运行配置…", 0.96, () -> {
            ConfigManager.save(config);
            warmUpLayout();
        }));
        plan.add(new BootStep("准备就绪", 1.0, () -> { }));
        return plan;
    }

    /** 把 Scene 挂到主窗（还不 show），设好标题尺寸。 */
    private void prepareStage(Stage stage) {
        if (bootScene == null) return;
        stage.setScene(bootScene);
        stage.setTitle("JWH Launcher - Minecraft 启动器 v" + APP_VERSION);
        // 打开时窗口尺寸（1000×650），可自由拖拽调整；最小尺寸保证下载中心布局不拥挤
        stage.setWidth(1000);
        stage.setHeight(650);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        if (transparentWindow) {
            // 无边框窗口不提供系统调整手柄，锁定尺寸保证布局稳定
            stage.setResizable(false);
        }
    }

    private void loadIcons(Stage stage) {
        try {
            stage.getIcons().add(new javafx.scene.image.Image(getClass().getResourceAsStream("/icon_256.png")));
        } catch (Exception ignored) {
        }
    }

    /**
     * 渲染预热：在没有 show 的情况下把整棵界面树跑一遍 CSS 与布局。
     * 这一步做完，主窗一出现就是排好版的成品，不会再「先露白再慢慢排」。
     */
    private void warmUpLayout() {
        if (bootScene == null || bootScene.getRoot() == null) return;
        try {
            bootScene.getRoot().applyCss();
            bootScene.getRoot().layout();
        } catch (Throwable ignored) {
        }
    }

    /** 进度到 100% 后按住一会儿，让玩家看清「加载完成」，再打开启动器。 */
    private void finishBoot(Stage stage) {
        com.jwh.launcher.ui.Splash.progress("载入完成，正在打开启动器…", 1.0);
        javafx.animation.PauseTransition hold =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(460));
        hold.setOnFinished(e -> showLauncher(stage));
        hold.play();
    }

    /** 真正的「打开启动器」：主窗 show → 加载页淡出 → 钉尺寸 → 环境自检 → 后台查更新。 */
    private void showLauncher(Stage stage) {
        if (bootScene == null) {
            // 极端兜底：界面没建出来也别卡死在加载页
            bootScene = buildScene(stage);
            stage.setScene(bootScene);
            stage.setTitle("JWH Launcher - Minecraft 启动器 v" + APP_VERSION);
        }
        final Scene scene = bootScene;
        // 全屏 ↔ 窗口来回切时，Windows 下偶发 Scene 尺寸停在全屏尺寸：
        // 窗口缩回来了、内容却还按 1920×1080 排版，看起来就是「整个界面突然变大、右边和下面被裁掉」。
        // 进全屏时记下原尺寸，退出时把窗口尺寸和布局都强制刷回去。
        final double[] winSize = {stage.getWidth(), stage.getHeight()};
        stage.fullScreenProperty().addListener((ov, wasFull, isFull) -> {
            if (isFull) {
                winSize[0] = stage.getWidth();
                winSize[1] = stage.getHeight();
            } else {
                javafx.application.Platform.runLater(() -> {
                    if (!stage.isFullScreen()) {
                        stage.setWidth(winSize[0]);
                        stage.setHeight(winSize[1]);
                    }
                    scene.getRoot().requestLayout();
                });
            }
        });
        stage.show();
        // 弹窗（Ui.confirm / Ui.error）统一挂到主窗口，避免弹窗跑到窗口后面被误判为“没反应”
        com.jwh.launcher.ui.Ui.dialogOwner = stage;
        // 主窗出现后让加载页淡出（300ms），视觉上就是加载页退场、启动器浮现
        com.jwh.launcher.ui.Splash.finish();
        // 首次显示时窗口会被撑开：要么按内容 pref（地图卡片一行行累加），
        // 要么被系统恢复成上次那个矩形（实测会停在 1136×739 @392,91）。
        // 这里在 show 后立刻钉一次、再延时 500ms 钉一次，压过系统的恢复。
        stage.setWidth(1000);
        stage.setHeight(650);
        javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(() -> {
            stage.setWidth(1000);
            stage.setHeight(650);
        }));
        javafx.animation.PauseTransition pinSize =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
        pinSize.setOnFinished(e -> {
            stage.setWidth(1000);
            stage.setHeight(650);
        });
        pinSize.play();

        // 环境自检：缺 Java（jar 版）或缺游戏版本时弹窗引导去官网下载
        showEnvironmentGuide(stage);

        // 后台静默检查更新：发现新版本时弹窗询问（无网/更新源不可用时静默跳过）
        javafx.application.Platform.runLater(() -> Ui.async2(
                () -> new com.jwh.launcher.update.Updater(appDir(), APP_VERSION).check(),
                info -> {
                    if (info != null) {
                        String notes = info.notes() == null || info.notes().isBlank() ? "" : "\n\n更新内容：\n" + info.notes();
                        boolean go = Ui.confirm("发现新版本 v" + info.version(),
                                "当前版本 v" + APP_VERSION + "，发现新版本 v" + info.version() + "。" + notes
                                        + "\n\n是否现在下载并更新？更新完成后启动器会自动重启。");
                        if (go) startUpdate(info);
                    }
                },
                e -> { /* 静默忽略检查失败，不影响使用 */ }));

        // 后台静默补齐缺失/过期的外置资源层（主程序已是最新时也会触发；层文件用到时才读取，无需重启）
        javafx.application.Platform.runLater(() -> Ui.async2(
                () -> new com.jwh.launcher.update.Updater(appDir(), APP_VERSION).pendingLayers(),
                layers -> {
                    if (layers != null && !layers.isEmpty()) {
                        Ui.asyncProgress(progress -> {
                                    try {
                                        new com.jwh.launcher.update.Updater(appDir(), APP_VERSION).applyLayers(layers);
                                    } catch (Exception ex) {
                                        throw new RuntimeException(ex);
                                    }
                                },
                                this::setStatus,
                                ok -> { },
                                e -> { /* 静默忽略补层失败，不影响使用 */ });
                    }
                },
                e -> { /* 静默忽略补层失败，不影响使用 */ }));

        stage.setOnCloseRequest(e -> {
            ConfigManager.save(config);
            Platform.exit();
        });
    }


    /**
     * 配置消毒：配置文件里的路径可能来自其他电脑（打包分发后首次运行，或
     * 拷贝了别人的配置）。启动时校验关键路径，不存在的自动回退默认值，
     * 避免启动器去读其它电脑的路径（如 C:\Users\xxx）而报错。
     */
    private void sanitizeConfig() {
        boolean changed = false;
        // 游戏目录：非默认值且不存在 → 回退默认；默认值（含便携 .minecraft）即使首次不存在也保留（下载时会自动创建）
        Path mc = Path.of(config.minecraftDir);
        if (!Files.isDirectory(mc) && !config.minecraftDir.equals(LauncherConfig.DEFAULT_MC_DIR)) {
            config.minecraftDir = LauncherConfig.DEFAULT_MC_DIR;
            changed = true;
        }
        // Java 路径：不存在 → 清空（改回自动检测）
        if (!config.javaPath.isBlank() && !Files.isRegularFile(Path.of(config.javaPath))) {
            config.javaPath = "";
            changed = true;
        }
        // 自定义背景图：不存在 → 清空并回退默认背景
        if (!config.bgImagePath.isBlank() && !Files.isRegularFile(Path.of(config.bgImagePath))) {
            config.bgImagePath = "";
            if ("image".equals(config.bgMode)) {
                config.bgMode = "default";
            }
            changed = true;
        }
        if (changed) {
            ConfigManager.save(config);
        }
    }

    /**
     * 环境自检引导：jar 版未装 Java 17+，或未检测到任何已安装游戏版本时，
     * 弹出引导窗并提供「打开官网下载」「去下载中心安装」入口。
     */
    private void showEnvironmentGuide(Stage stage) {
        boolean packaged = System.getProperty("jpackage.app-path") != null;
        boolean needJava = !packaged && !isJava17Plus(System.getProperty("java.version", ""));
        boolean noGame = !hasAnyGameVersion();

        if (!needJava && !noGame) return;

        javafx.scene.control.Dialog<Void> dlg = new javafx.scene.control.Dialog<>();
        dlg.initOwner(stage);
        dlg.setTitle("缺少运行环境");
        VBox body = new VBox(10);
        body.setPadding(new Insets(14));
        body.setPrefWidth(430);

        Label head = new Label(needJava
                ? "检测到你的电脑未安装 Java 17+ 运行时，JWH 启动器需要 Java 才能运行。"
                : "检测到你的电脑还没有安装 Minecraft 游戏版本：");
        head.setStyle("-fx-font-size:15px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        head.setWrapText(true);
        body.getChildren().add(head);

        if (needJava) {
            Label l = new Label("点击下方「确定」将直接打开 Java 官网（Adoptium Temurin）下载页面，"
                    + "安装完成后即可使用启动器。");
            l.setWrapText(true);
            l.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
            Label link = new Label("Java 官网：https://adoptium.net/zh-CN/temurin/releases/");
            link.setStyle("-fx-text-fill:" + Ui.ACCENT + "; -fx-underline:true; -fx-font-size:13px;");
            body.getChildren().addAll(l, link);
            if (noGame) {
                Label extra = new Label("（另：检测到你也没有游戏版本，可之后在「下载中心」一键下载游戏本体）");
                extra.setWrapText(true);
                extra.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");
                body.getChildren().add(extra);
            }
        } else {
            Label l = new Label("可到 Minecraft 官网下载游戏客户端，也可以直接使用本启动器「下载中心」"
                    + "一键下载游戏本体（推荐，国内用 BMCLAPI 镜像更快）。");
            l.setWrapText(true);
            l.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
            body.getChildren().add(l);
        }

        // ---- 按钮区 ----
        if (needJava) {
            // 用户要求：点确定后直接去 Java 官网下载
            Button go = new Button("确定，去下载 Java");
            go.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white; -fx-font-weight:bold; -fx-padding:8 20;");
            go.setOnAction(e -> {
                try {
                    getHostServices().showDocument("https://adoptium.net/zh-CN/temurin/releases/");
                } catch (Exception ex) {
                    Ui.error("无法打开浏览器", ex);
                }
                dlg.close();
            });
            HBox btns = new HBox(10);
            btns.setAlignment(Pos.CENTER_LEFT);
            btns.getChildren().add(go);
            body.getChildren().add(btns);
        } else {
            Button openSite = new Button("打开 Minecraft 官网下载");
            openSite.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white; -fx-font-weight:bold; -fx-padding:8 20;");
            openSite.setOnAction(e -> {
                try {
                    getHostServices().showDocument("https://www.minecraft.net/zh-hans");
                } catch (Exception ex) {
                    Ui.error("无法打开浏览器", ex);
                }
                dlg.close();
            });
            final Button openDl;
            if (noGame) {
                openDl = new Button("去「下载中心」安装游戏");
                openDl.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
                openDl.setOnAction(e -> {
                    dlg.close();
                    showDownload();
                });
            } else {
                openDl = null;
            }
            HBox btns = new HBox(10);
            btns.setAlignment(Pos.CENTER_LEFT);
            btns.getChildren().add(openSite);
            if (openDl != null) btns.getChildren().add(openDl);
            body.getChildren().add(btns);
        }

        dlg.getDialogPane().setContent(body);
        dlg.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        javafx.scene.control.Button ok = (javafx.scene.control.Button) dlg.getDialogPane()
                .lookupButton(javafx.scene.control.ButtonType.CLOSE);
        ok.setText("知道了");
        dlg.show();
    }

    /** Java 版本号 >= 17 判定。 */
    private static boolean isJava17Plus(String ver) {
        if (ver == null || ver.isBlank()) return false;
        try {
            String[] parts = ver.split("\\.");
            int major;
            if (parts[0].equals("1")) {
                major = parts.length > 1 ? Integer.parseInt(parts[1]) : 8;
            } else {
                major = Integer.parseInt(parts[0]);
            }
            return major >= 17;
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否存在至少一个已安装的游戏版本（versions/<id>/<id>.json）。 */
    private boolean hasAnyGameVersion() {
        java.nio.file.Path dir = config.versionsPath();
        if (!java.nio.file.Files.isDirectory(dir)) return false;
        try (var stream = java.nio.file.Files.list(dir)) {
            return stream.filter(java.nio.file.Files::isDirectory).anyMatch(vd ->
                    java.nio.file.Files.exists(vd.resolve(vd.getFileName() + ".json")));
        } catch (Exception e) {
            return false;
        }
    }

    private Scene buildScene(Stage stage) {
        root = new BorderPane();
        root.setStyle(bgStyle());

        // 顶栏：图标 + 标题 + 账号
        HBox top = buildTopBar(stage);
        root.setTop(top);

        // 左侧导航
        VBox nav = buildNav();
        root.setLeft(nav);

        // 内容区
        homeView = new HomeView(this);
        // 首屏只建主页：下载中心 / 皮肤库 / 设置 / 日志 / 关于 / 账号管理首次点开时才创建。
        // 这几个页面构造时要拉版本清单、皮肤站数据、读目录，一次性全建会让打开启动器时白卡好几秒；
        // 懒建后打开瞬间只做首页，卡顿分摊到「真的点进那一页」的时候。
        downloadView = null;
        loginView = null;
        skinsView = null;
        settingsView = null;
        logView = null;
        aboutView = null;
        gameLog.setEditable(false);
        gameLog.setWrapText(true);
        gameLog.setStyle("-fx-control-inner-background:" + Ui.BG_LIGHT + "; -fx-text-fill:#1f2328; -fx-font-family:Consolas,monospace; -fx-font-size:13px;");
        content.getChildren().add(homeView.root());
        root.setCenter(content);

        // 底部状态栏
        statusLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");
        BorderPane bottom = new BorderPane();
        bottom.setPadding(new Insets(4, 12, 4, 12));
        bottom.setStyle("-fx-background-color:" + Ui.BG_LIGHT + ";");
        bottom.setLeft(statusLabel);
        root.setBottom(bottom);

        // 锁定初始布局尺寸：与 PCL 启动器一致（875×554），用户可拖拽调整
        root.setPrefSize(875, 554);
        // 全页详情覆盖层叠在整窗之上：页面打开时顶栏/左导航/状态栏全部被覆盖
        pageLayer.setVisible(false);
        pageLayer.setPickOnBounds(true);
        StackPane shell = new StackPane(root, pageLayer);
        Scene scene = new Scene(shell);
        // 整合包拖入导入（与 PCL 一致：把 .zip/.jar/.mrpack 拖进窗口即触发）
        enablePackDrop(scene);
        // 透明模式下 Scene 必须透明，否则会挡住桌面壁纸
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        return scene;
    }

    /** 拖放支持：接受整合包压缩包，松手后弹窗确认名字并导入。 */
    private void enablePackDrop(Scene scene) {
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                for (java.io.File f : e.getDragboard().getFiles()) {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".mrpack")) {
                        e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                        break;
                    }
                }
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            boolean ok = false;
            if (e.getDragboard().hasFiles()) {
                java.io.File f = e.getDragboard().getFiles().stream()
                        .filter(x -> {
                            String n = x.getName().toLowerCase();
                            return n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".mrpack");
                        })
                        .findFirst().orElse(null);
                if (f != null) {
                    importModpack(f);
                    ok = true;
                }
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /** 拖入整合包：弹窗确认名字后，后台解压并合并到游戏目录。 */
    private void importModpack(java.io.File zipFile) {
        boolean go = Ui.confirm("导入整合包",
                "检测到整合包：\n" + zipFile.getName() + "\n\n是否导入？\n"
                        + "将自动解压合并到游戏目录：" + config.minecraftDir + "\n"
                        + "（同名文件会被覆盖，导入前建议先备份存档/世界）");
        if (!go) return;
        String lowerName = zipFile.getName().toLowerCase();
        if (lowerName.endsWith(".mrpack")) {
            // Modrinth 整合包：完整安装（装依赖加载器 + 建独立实例 + 在线下载全部 mods + 合并配置）
            setStatus("正在解析整合包…");
            Ui.async2(() -> {
                try {
                    ModpackInstaller inst = new ModpackInstaller(config);
                    return inst.installLocal(zipFile.toPath(), (step, done, total) ->
                            javafx.application.Platform.runLater(() -> setStatus(step)));
                } catch (Throwable t) {
                    throw new RuntimeException(t.getMessage(), t);
                }
            }, packId -> {
                refreshAll();
                showToast("整合包导入完成（" + packId + "）。可在主页 / 下载中心查看已安装版本。");
            }, err -> Ui.error("导入整合包失败", err));
            return;
        }
        // 普通压缩包（.zip / .jar）：解压合并到游戏目录
        setStatus("正在解析整合包…");
        Ui.async2(() -> {
            Path tmp = Files.createTempDirectory("jwh-pack-");
            ModpackImporter.unzip(zipFile.toPath(), tmp);
            Path root = ModpackImporter.detectRoot(tmp);
            String type = ModpackImporter.detectType(root);
            // CurseForge 结构（manifest.json + overrides/）：真正要装的内容在 overrides/ 里，
            // 直接合并 root 会把 overrides 当成一个子目录搬进游戏目录，Mod 就落不到 mods/ 了
            Path payload = Files.isDirectory(root.resolve("overrides")) ? root.resolve("overrides") : root;
            ModpackImporter.mergeInto(payload, Path.of(config.minecraftDir));
            ModpackImporter.deleteRecursively(tmp);
            return type;
        }, type -> {
            refreshAll();
            showToast("整合包导入完成（" + type + "）。可在主页 / 下载中心查看已安装版本。");
        }, err -> Ui.error("导入整合包失败", err));
    }

    private HBox buildTopBar(Stage stage) {
        HBox top = new HBox(12);
        top.setPadding(new Insets(10, 16, 10, 16));
        top.setAlignment(Pos.CENTER_LEFT);
        top.setStyle("-fx-background-color:" + Ui.BG_LIGHT + ";");

        // 透明（无边框）模式下：顶栏可拖动窗口
        if (stage != null && "transparent".equals(config.bgMode)) {
            top.setOnMousePressed(e -> {
                dragX = e.getScreenX() - stage.getX();
                dragY = e.getScreenY() - stage.getY();
            });
            top.setOnMouseDragged(e -> {
                stage.setX(e.getScreenX() - dragX);
                stage.setY(e.getScreenY() - dragY);
            });
        }

        // 左上角：玩家头像 + ID + 登录类型（整块可点击，点击进入账号管理/登录页）
        avatarView.setFitWidth(48);
        avatarView.setFitHeight(48);
        avatarView.setPreserveRatio(true);
        // 方形头像（极小圆角），关闭平滑以保持 MC 像素脸清晰锐利
        avatarView.setSmooth(false);
        javafx.scene.shape.Rectangle avatarClip = new javafx.scene.shape.Rectangle(48, 48);
        avatarClip.setArcWidth(6);
        avatarClip.setArcHeight(6);
        avatarView.setClip(avatarClip);
        setDefaultAvatar();
        idLabel.setStyle("-fx-font-size:17px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        idLabel.setMinWidth(120);
        typeLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        typeLabel.setMinWidth(120);
        VBox idBox = new VBox(1);
        idBox.setAlignment(Pos.CENTER_LEFT);
        idBox.getChildren().addAll(idLabel, typeLabel);

        HBox userBox = new HBox(10);
        userBox.setAlignment(Pos.CENTER_LEFT);
        userBox.getChildren().addAll(avatarView, idBox);
        userBox.setStyle("-fx-background-color:transparent; -fx-background-radius:8; -fx-padding:4 10; -fx-cursor:hand;");
        userBox.setOnMouseEntered(e -> userBox.setStyle(
                "-fx-background-color:rgba(0,0,0,0.06); -fx-background-radius:8; -fx-padding:4 10; -fx-cursor:hand;"));
        userBox.setOnMouseExited(e -> userBox.setStyle(
                "-fx-background-color:transparent; -fx-background-radius:8; -fx-padding:4 10; -fx-cursor:hand;"));
        userBox.setOnMouseClicked(e -> {
            showSubPage("账号管理", loginContent(), () -> showHome());
            refreshAccountButton();
        });

        top.getChildren().addAll(userBox);
        HBox spacer = new HBox();
        // 只做弹性占位，避免 MAX_VALUE 宽度在窗口变窄时把左侧头像/ID 挤成省略号
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        // 右上角：内置音乐播放器（原这里显示版本号，版本号已按需求去掉；
        // 版本信息仍可在窗口标题与「关于」页看到）
        musicBar = new com.jwh.launcher.ui.MusicBar(true);
        musicBar.setMinWidth(0);
        musicBar.setPrefWidth(238);
        musicBar.setMaxWidth(238);
        top.getChildren().addAll(spacer, musicBar);
        // 打开启动器就随机放一首；点「启动游戏」时会自动暂停（见 HomeView.launch）
        javafx.application.Platform.runLater(musicBar::autoPlay);

        // 透明（无边框）模式下补最小化 / 关闭按钮
        if (stage != null && "transparent".equals(config.bgMode)) {
            Button minBtn = new Button("—");
            minBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:" + Ui.TEXT + "; -fx-font-size:15px; -fx-padding:2 8; -fx-cursor:hand;");
            minBtn.setOnAction(e -> stage.setIconified(true));
            Button closeBtn = new Button("✕");
            closeBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:14px; -fx-padding:2 8; -fx-cursor:hand;");
            closeBtn.setOnMouseEntered(e -> closeBtn.setStyle("-fx-background-color:#e5484d; -fx-text-fill:white; -fx-font-size:14px; -fx-padding:2 8; -fx-cursor:hand; -fx-background-radius:5;"));
            closeBtn.setOnMouseExited(e -> closeBtn.setStyle("-fx-background-color:transparent; -fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:14px; -fx-padding:2 8; -fx-cursor:hand;"));
            closeBtn.setOnAction(e -> stage.close());
            top.getChildren().addAll(minBtn, closeBtn);
        }

        refreshAccountButton();
        return top;
    }

    /**
     * 切换内容区页面并播放入场动画（PCL 风格：新页面从右侧滑入 + 淡入）。
     * 同一个页面重复设置时不再重放动画，避免返回时原地抖一下。
     */
    private void setContent(javafx.scene.Node node) {
        if (node == null) return;
        if (content.getChildren().size() == 1 && content.getChildren().get(0) == node) return;
        content.getChildren().setAll(node);
        Ui.pageIn(node);
    }

    /** 切换到下载中心页。 */
    public void showDownload() {
        exitSub();
        hideFullPage();
        DownloadView v = ensureDownload();
        setContent(v.root());
        v.refresh();
        if (downloadNavBtn != null) setNavActive(downloadNavBtn);
        setStatus("下载中心");
    }

    /**
     * 切换到皮肤库页（左侧导航「皮肤库」，紧挨「下载中心」下面）。
     * 每次进入都重扫 skins/library，保证导入 / 删除后列表是最新的。
     */
    public void showSkins() {
        exitSub();
        hideFullPage();
        SkinsView v = ensureSkins();
        setContent(v.root());
        v.refresh();
        if (skinsNavBtn != null) setNavActive(skinsNavBtn);
        setStatus("皮肤库");
    }

    /** 主页「多人游戏」→ 内置联机页（房间号联机，左上角「< 返回」回主页）。 */
    public void showMultiplayer() {
        if (homeView == null) return;
        showSubPage("多人游戏 · 房间号联机",
                MultiplayerView.content(this, homeView), this::showHome);
    }

    /**
     * 切换到全页面详情页（如下载中心的资源详情）：顶上不出现全局「< 返回」键，
     * 页面自己带右上角返回按钮，返回时由页面调用 showDownload()/其他主页方法。
     */
    public void showFullPage(String status, javafx.scene.Node page) {
        exitSub();
        // 页面内容带外边距铺满整窗（顶栏/左导航/状态栏全部被盖住）
        javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(page);
        holder.setPadding(new Insets(10));
        // 覆盖层自身也铺底色：避免 10px 边缘透出下层的导航/状态栏文字（残影）
        holder.setStyle("-fx-background-color:" + Ui.BG + ";");
        hidingPage = false;
        pageLayer.getChildren().setAll(holder);
        pageLayer.setVisible(true);
        Ui.pageIn(holder);
        setStatus(status);
    }

    /** 覆盖层是否正在播放收起动画（防止重复触发叠加）。 */
    private boolean hidingPage = false;

    /** 收起全页详情：覆盖页向右滑出 + 淡出，动画结束后再清空隐藏。 */
    private void hideFullPage() {
        if (pageLayer == null || !pageLayer.isVisible() || hidingPage) return;
        if (pageLayer.getChildren().isEmpty()) {
            pageLayer.setVisible(false);
            return;
        }
        hidingPage = true;
        javafx.scene.Node cur = pageLayer.getChildren().get(0);
        Ui.pageOut(cur, () -> {
            hidingPage = false;
            // 只清掉本次退场的那个页面：期间若换了新详情页，保留新的
            if (pageLayer.getChildren().size() == 1 && pageLayer.getChildren().get(0) == cur) {
                pageLayer.getChildren().clear();
                pageLayer.setVisible(false);
            }
        });
    }

    /** 切换到设置页。 */
    public void showSettings() {
        showSettings(false);
    }

    /** 切换到设置页；atMemory=true 时自动滚动到「内存分配」区块（主页 Java/内存 按钮用）。 */
    public void showSettings(boolean atMemory) {
        exitSub();
        hideFullPage();
        setContent(settingsNode());
        setNavActiveFor(settingsView);
        setStatus("设置");
        if (atMemory) {
            javafx.application.Platform.runLater(() -> {
                javafx.scene.Node mem = ensureSettings().memoryRow();
                if (settingsScroll == null || mem == null) return;
                scrollToMemory(mem, 0);
            });
        }
    }

    /**
     * 滚动定位到内存区块。布局未就绪（bounds 为 0）时自动延时重试，
     * 直到坐标可用为止（最多约 1 秒），确保任何窗口尺寸/DPI 下都稳定。
     */
    private void scrollToMemory(javafx.scene.Node mem, int attempt) {
        double contentH = settingsScroll.getContent().getBoundsInLocal().getHeight();
        double viewH = settingsScroll.getViewportBounds().getHeight();
        double y = mem.getBoundsInParent().getMinY();
        if ((contentH <= 0 || viewH <= 0 || y <= 0) && attempt < 20) {
            javafx.animation.PauseTransition pt =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(50));
            pt.setOnFinished(e -> scrollToMemory(mem, attempt + 1));
            pt.play();
            return;
        }
        double memH = mem.getBoundsInParent().getHeight();
        double max = Math.max(0, contentH - viewH);
        // 目标：内存区块显示在视口垂直中间（区块过高时退化为顶部对齐）
        double target = y - Math.max(0, (viewH - memH) / 2);
        double vv = max <= 0 ? 0 : Math.max(0, Math.min(1, target / max));
        settingsScroll.setVvalue(vv);
        flashHighlight(mem);
    }

    /** 定位到目标区块后：蓝色高亮闪烁 1.2 秒，让用户一眼看到滚动到了哪里。 */
    private void flashHighlight(javafx.scene.Node n) {
        final String orig = n.getStyle() == null ? "" : n.getStyle();
        n.setStyle(orig + " -fx-background-color: rgba(64,158,255,0.30); -fx-background-radius: 6;");
        javafx.animation.PauseTransition pt =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(1200));
        pt.setOnFinished(e -> n.setStyle(orig));
        pt.play();
    }

    /** 设置页滚动容器（定位到指定设置区块时用）。 */
    private javafx.scene.control.ScrollPane settingsScroll;

    /** 设置页内容（内容较长，包滚动容器，避免底部被窗口裁掉）。 */
    private javafx.scene.Node settingsNode() {
        SettingsView sv = ensureSettings();
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(sv.root());
        sp.setFitToWidth(true);
        sp.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background-color:" + Ui.BG + "; -fx-background:transparent;"
                + "-fx-border-color:null; -fx-padding:0;");
        settingsScroll = sp;
        return sp;
    }

    private Button settingsNavBtn;
    private Button homeNavBtn;
    private Button downloadNavBtn;

    /** 导航「皮肤库」按钮（位于「下载中心」下方）。 */
    private Button skinsNavBtn;

    /** 设置页导航按钮（主题重建后重新绑定）。 */
    public void setSettingsNavBtn(Button b) {
        settingsNavBtn = b;
    }

    // ---- 子页面机制：融入主窗口的次级页面，用左上角返回键（<）回上一页 ----
    private Button backBtn;
    private Runnable subBack = null;

    /** 进入子页面：内容区切换为该页面，左上角显示「< 返回」。 */
    public void showSubPage(String status, javafx.scene.Node page, Runnable onBack) {
        subBack = onBack;
        hideFullPage();
        setContent(page);
        if (backBtn != null) {
            backBtn.setVisible(true);
            backBtn.setManaged(true);
        }
        setStatus(status);
    }

    /** 点击左上角返回键：回到上一页（仅子页面时有效）。 */
    public void goBack() {
        if (subBack == null) return;
        Runnable r = subBack;
        subBack = null;
        if (backBtn != null) {
            backBtn.setVisible(false);
            backBtn.setManaged(false);
        }
        r.run();
    }

    /** 切换到主页面时清除子页面状态（不执行返回回调）。 */
    private void exitSub() {
        subBack = null;
        if (backBtn != null) {
            backBtn.setVisible(false);
            backBtn.setManaged(false);
        }
    }

    /** 底部状态栏 toast：3 秒后自动恢复原文字（避免连续提示互相覆盖）。 */
    private long toastTs = 0;

    public void showToast(String msg) {
        final long ts = System.currentTimeMillis();
        toastTs = ts;
        final String prev = statusLabel.getText();
        statusLabel.setText(msg == null ? "" : msg);
        javafx.animation.Timeline tl = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(3200), e -> {
                    if (toastTs == ts) statusLabel.setText(prev);
                }));
        tl.play();
    }

    private void setNavActiveFor(Object view) {
        if (view == settingsView && settingsNavBtn != null) {
            setNavActive(settingsNavBtn);
        }
    }

    /** 根背景样式：default=渐变 / image=自定义图片 / transparent=透明。 */
    public String bgStyle() {
        String mode = config.bgMode == null ? "default" : config.bgMode;
        if ("image".equals(mode)) {
            String path = config.bgImagePath;
            if (path != null && !path.isBlank() && Files.isRegularFile(Path.of(path))) {
                String uri = Path.of(path).toUri().toString();
                return "-fx-background-color:" + Ui.BG + ";"
                        + " -fx-background-image: url('" + uri + "');"
                        + " -fx-background-size: cover;"
                        + " -fx-background-position: center;";
            }
            // 图片失效时回退渐变
            return "-fx-background-color:" + Ui.BG_GRADIENT + ";";
        }
        if ("transparent".equals(mode)) {
            // 真透明：露出桌面壁纸（无边框窗口见 start()）
            return "-fx-background-color: transparent;";
        }
        return "-fx-background-color:" + Ui.BG_GRADIENT + ";";
    }

    /**
     * 自定义外观保存后调用：刷新主题色 + 重建整窗（顶栏/导航/内容区），
     * 使「自定义背景 / 文字颜色」立即生效。
     */
    public void applyThemeAndRebuild() {
        Ui.applyTheme(config);
        if (root == null) return;
        root.setStyle(bgStyle());
        root.setTop(buildTopBar(stage));
        root.setLeft(buildNav());
        homeView = new HomeView(this);
        // 主题重建：非首屏页面同样保持懒建，避免「应用外观」时又把六个页面全建一遍
        downloadView = null;
        skinsView = null;
        settingsView = null;
        logView = null;
        aboutView = null;
        loginView = null;
        hideFullPage();
        setContent(homeView.root());
        BorderPane bottom = new BorderPane();
        bottom.setPadding(new Insets(4, 12, 4, 12));
        bottom.setStyle("-fx-background-color:" + Ui.BG_LIGHT + ";");
        bottom.setLeft(statusLabel);
        root.setBottom(bottom);
        setStatus("外观已应用");
    }

    /** 刷新左上角头像与 ID/登录类型显示。 */
    public void refreshAccountButton() {
        LauncherConfig.Account acc = null;
        try {
            acc = auth.currentAccount(false);
        } catch (Exception ignored) {
        }
        if (acc == null) {
            idLabel.setText("未登录");
            typeLabel.setText("点击登录");
            setDefaultAvatar();
            return;
        }
        idLabel.setText(acc.displayName);
        typeLabel.setText(switch (acc.type) {
            case MICROSOFT -> "正版登录";
            case LITTLESKIN -> "LittleSkin 登录";
            default -> "离线登录";
        });
        loadAvatar(acc);
    }

    /** 左上角默认头像（直接用 48×48 图标，1:1 不缩放，避免重采样变糊）。 */
    private void setDefaultAvatar() {
        try {
            avatarView.setImage(new javafx.scene.image.Image(
                    getClass().getResourceAsStream("/icon_48.png")));
            avatarView.setViewport(null);
            avatarView.setFitWidth(48);
            avatarView.setFitHeight(48);
            avatarView.setPreserveRatio(true);
            avatarView.setSmooth(false);
        } catch (Exception ignored) {
        }
    }

    /**
     * 把图里的 8×8 逻辑脸放大成 out×out 的硬边图。
     * ox/oy 是逻辑脸在原图里的起点，step 是「一个逻辑像素在原图里占几个物理像素」：
     * 整张 64×64 皮肤 → ox=8,oy=8,step=1；crafatar 已裁好的 64×64 头像 → ox=0,oy=0,step=8。
     * JavaFX 的 ImageView 在放大小图时会做插值，8×8 的脸放大 6 倍后就成了糊块，所以这里自己逐像素铺。
     */
    private static javafx.scene.image.Image pixelHead(javafx.scene.image.Image img,
                                                     int ox, int oy, int step, int out) {
        if (img == null || img.isError()) return null;
        javafx.scene.image.PixelReader pr = img.getPixelReader();
        if (pr == null) return null;
        if (img.getWidth() < ox + 8 * step || img.getHeight() < oy + 8 * step) return null;
        int cell = out / 8;
        if (cell < 1) return null;
        javafx.scene.image.WritableImage wi = new javafx.scene.image.WritableImage(out, out);
        javafx.scene.image.PixelWriter pw = wi.getPixelWriter();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                javafx.scene.paint.Color c = pr.getColor(ox + x * step, oy + y * step);
                for (int dy = 0; dy < cell; dy++) {
                    for (int dx = 0; dx < cell; dx++) {
                        pw.setColor(x * cell + dx, y * cell + dy, c);
                    }
                }
            }
        }
        return wi;
    }

    /** 把整张皮肤（64×64 / 32×64）的脸部铺到顶栏：像素级放大成 48×48 图，1:1 显示。 */
    private void applyHead(javafx.scene.image.Image skin) {
        javafx.scene.image.Image head = pixelHead(skin, 8, 8, 1, 48);
        if (head == null) throw new IllegalStateException("skin too small");
        avatarView.setImage(head);
        avatarView.setViewport(null);
        avatarView.setFitWidth(48);
        avatarView.setFitHeight(48);
        avatarView.setPreserveRatio(true);
        avatarView.setSmooth(false);
    }

    /** 加载玩家头像：离线优先本地皮肤头部；正版走官方 sessionserver 拿当前皮肤裁剪脸部（HMCL 同款，实时反映已换皮肤）。 */
    private void loadAvatar(LauncherConfig.Account acc) {
        if (acc == null || acc.uuid == null || acc.uuid.isBlank()) {
            setDefaultAvatar();
            return;
        }
        String uuidNoDash = acc.uuid.replace("-", "");
        // LittleSkin 的 UUID 在 Mojang / Crafatar 那边不存在，联网只会白等到超时（还会卡住 FX 线程），
        // 直接用本地已同步的皮肤裁脸；本地没有就用默认头像。
        if (acc.type == LauncherConfig.Account.Type.LITTLESKIN) {
            for (Path cand : new Path[]{
                    Path.of(config.minecraftDir).resolve("skins").resolve(uuidNoDash + ".png"),
                    Path.of(config.minecraftDir).resolve("skins").resolve("library")
                            .resolve("皮肤站-" + acc.displayName + ".png")}) {
                try {
                    if (Files.isRegularFile(cand)) {
                        javafx.scene.image.Image img = new javafx.scene.image.Image(cand.toUri().toString(), false);
                        if (!img.isError()) {
                            applyHead(img);
                            return;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            setDefaultAvatar();
            return;
        }
        // 皮肤库里点过「使用这个皮肤」→ skins/<uuid>.png 就是当前生效皮肤：
        // 离线 / 正版都先按它裁脸，换完皮肤左上角头像立刻跟着变，
        // 正版不能再死等 Mojang 线上那套旧皮肤（那是官方账号皮肤，换不了）。
        if (acc.type == LauncherConfig.Account.Type.OFFLINE
                || acc.type == LauncherConfig.Account.Type.MICROSOFT) {
            try {
                Path local = Path.of(config.minecraftDir).resolve("skins").resolve(uuidNoDash + ".png");
                if (Files.isRegularFile(local)) {
                    javafx.scene.image.Image img = new javafx.scene.image.Image(local.toUri().toString(), false);
                    if (!img.isError()) {
                        applyHead(img);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (acc.type == LauncherConfig.Account.Type.OFFLINE) {
            try {
                Path skin = Path.of(config.minecraftDir).resolve("skins").resolve(uuidNoDash + ".png");
                if (Files.isRegularFile(skin)) {
                    javafx.scene.image.Image img = new javafx.scene.image.Image(skin.toUri().toString(), false);
                    if (!img.isError()) {
                        applyHead(img);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
            fallbackAvatar(uuidNoDash, avatarGen);
            return;
        }
        loadMicrosoftAvatar(uuidNoDash);
    }

    /** 头像刷新代际：防止旧线程的慢请求覆盖新头像。 */
    private volatile long avatarGen;

    /** 正版头像：官方 sessionserver.mojang.com 实时拉取该账号当前皮肤并裁剪 8x8 脸部（HMCL 同款）；失败回退 Crafatar 镜像。 */
    private void loadMicrosoftAvatar(String uuidNoDash) {
        final long gen = ++avatarGen;
        new Thread(() -> {
            try {
                String profile = com.jwh.launcher.util.Http.getText(
                        "https://sessionserver.mojang.com/session/minecraft/profile/" + uuidNoDash);
                org.json.JSONObject p = new org.json.JSONObject(profile);
                String value = "";
                if (p.has("properties") && !p.isNull("properties")) {
                    org.json.JSONArray props = p.optJSONArray("properties");
                    for (int i = 0; i < props.length(); i++) {
                        if ("textures".equals(props.getJSONObject(i).optString("name"))) {
                            value = props.getJSONObject(i).optString("value", "");
                            break;
                        }
                    }
                }
                String skinUrl = "";
                if (!value.isBlank()) {
                    org.json.JSONObject textures = new org.json.JSONObject(
                            new String(java.util.Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8));
                    if (textures.optJSONObject("textures") != null
                            && textures.optJSONObject("textures").optJSONObject("SKIN") != null) {
                        skinUrl = textures.optJSONObject("textures").optJSONObject("SKIN").optString("url", "");
                    }
                }
                if (skinUrl.isBlank()) throw new IllegalStateException("no skin url");
                byte[] skinBytes = com.jwh.launcher.util.Http.getBytes(skinUrl);
                if (skinBytes == null || skinBytes.length == 0) throw new IllegalStateException("skin empty");
                avatarLog("官方链路 OK，皮肤 " + skinBytes.length + "B，转 FX 线程解码");
                final byte[] bytes = skinBytes;
                javafx.application.Platform.runLater(() -> {
                    if (gen != avatarGen) return; // 已被更新的刷新覆盖，丢弃旧结果
                    try {
                        // 关键：JavaFX 的 Image(InputStream) 必须在 FX 线程构造，否则可能静默不显示
                        javafx.scene.image.Image skin = new javafx.scene.image.Image(
                                new java.io.ByteArrayInputStream(bytes));
                        if (skin.isError()) throw new IllegalStateException("skin decode failed");
                        applyHead(skin);
                        avatarLog("头像已显示 " + skin.getWidth() + "x" + skin.getHeight());
                    } catch (Exception ex) {
                        avatarLog("FX 解码失败: " + ex);
                        fallbackAvatar(uuidNoDash, gen);
                    }
                });
            } catch (Exception e) {
                avatarLog("官方链路失败: " + e);
                fallbackAvatar(uuidNoDash, gen);
            }
        }, "JWH-Microsoft-Avatar").start();
    }

    /** 官方通道失败后的回退：Crafatar 镜像 → 全部失败保持当前头像。
     *  <p>注意：{@code new Image(httpsUrl)} 会同步发起 HTTP 请求，绝不能放在 FX 线程里，
     *  否则网络一慢整个界面就卡成「未响应」。这里改成后台线程取字节，再回 FX 解码。 */
    private void fallbackAvatar(String uuidNoDash, long gen) {
        new Thread(() -> {
            try {
                byte[] bytes = com.jwh.launcher.util.Http.getBytes(
                        "https://crafatar.com/avatars/" + uuidNoDash + "?size=64&overlay=true");
                if (bytes == null || bytes.length == 0) throw new IllegalStateException("crafatar empty");
                javafx.application.Platform.runLater(() -> {
                    if (gen != avatarGen) return;
                    try {
                        javafx.scene.image.Image img = new javafx.scene.image.Image(
                                new java.io.ByteArrayInputStream(bytes));
                        if (img.isError()) throw new IllegalStateException("crafatar decode failed");
                        // crafatar 的 size=64 已经是「裁好的头」，整张就是脸：按 8×8 逻辑网格取样，不再偏移 (8,8)
                        javafx.scene.image.Image head = pixelHead(img, 0, 0, 8, 48);
                        if (head == null) throw new IllegalStateException("crafatar size bad");
                        avatarView.setImage(head);
                        avatarView.setViewport(null);
                        avatarView.setFitWidth(48);
                        avatarView.setFitHeight(48);
                        avatarView.setPreserveRatio(true);
                        avatarView.setSmooth(false);
                    } catch (Exception ex) {
                        avatarLog("回退头像失败: " + ex);
                    }
                });
            } catch (Exception ex) {
                avatarLog("回退头像失败: " + ex);
            }
        }, "JWH-Crafatar-Avatar").start();
    }

    /** 头像诊断日志（启动器所在目录 jwh-avatar.log），方便排查加载链路。 */
    private void avatarLog(String msg) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(System.getProperty("user.dir"), "jwh-avatar.log");
            java.nio.file.Files.writeString(p,
                    java.time.LocalTime.now() + " " + msg + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** 导航按钮列表，用于高亮当前页。 */
    private final List<Button> navButtons = new java.util.ArrayList<>();

    private VBox buildNav() {
        navButtons.clear(); // 主题重建时避免重复累积
        VBox nav = new VBox(6);
        nav.setPadding(new Insets(16, 10, 16, 10));
        nav.setPrefWidth(160);
        nav.setStyle("-fx-background-color:transparent;");

        // 左上角返回键：仅在子页面时显示
        backBtn = new Button("< 返回");
        backBtn.setMaxWidth(Double.MAX_VALUE);
        backBtn.getStyleClass().add("nav-btn");
        backBtn.setVisible(false);
        backBtn.setManaged(false);
        backBtn.setOnAction(e -> goBack());

        Button home = navButton("主页");
        homeNavBtn = home;
        home.setOnAction(e -> showHome());

        Button download = navButton("下载中心");
        downloadNavBtn = download;
        download.setOnAction(e -> {
            exitSub();
            hideFullPage();
            DownloadView v = ensureDownload();
            setContent(v.root());
            v.refresh();
            setNavActive(download);
            setStatus("下载中心");
        });

        // 皮肤库：就在「下载中心」下面，独立页面（左侧预览 + 右侧皮肤列表）
        Button skins = navButton("皮肤库");
        skinsNavBtn = skins;
        skins.setOnAction(e -> showSkins());

        Button settings = navButton("设置");
        settings.setOnAction(e -> {
            exitSub();
            hideFullPage();
            setContent(settingsNode());
            setNavActive(settings);
            setStatus("设置");
        });
        setSettingsNavBtn(settings);

        Button log = navButton("日志");
        log.setOnAction(e -> {
            exitSub();
            hideFullPage();
            setContent(ensureLog().root());
            setNavActive(log);
            setStatus("日志");
        });

        Button about = navButton("关于");
        about.setOnAction(e -> {
            exitSub();
            hideFullPage();
            setContent(ensureAbout().root());
            setNavActive(about);
            setStatus("关于");
        });

        nav.getChildren().addAll(backBtn, home, download, skins, settings, log, about);
        setNavActive(home);
        return nav;
    }

    /** 切回主页（导航「主页」按钮与子页面返回共用）。 */
    public void showHome() {
        exitSub();
        hideFullPage();
        setContent(homeView.root());
        homeView.refresh();
        if (homeNavBtn != null) setNavActive(homeNavBtn);
        setStatus("主页");
    }

    private void setNavActive(Button active) {
        for (Button b : navButtons) {
            b.getStyleClass().removeAll("active");
            if (b == active) b.getStyleClass().add("active");
        }
    }

    private Button navButton(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        b.getStyleClass().add("nav-btn");
        navButtons.add(b);
        return b;
    }

    public void setStatus(String s) {
        statusLabel.setText(s);
    }

    public void refreshAll() {
        homeView.refresh();
        if (downloadView != null) downloadView.refresh();
        refreshAccountButton();
    }

    /**
     * 自动分配模式：按本机物理内存推算安全值并写入配置（含持久化）。
     * 自定义模式不检测、保持玩家手动值。检测在后台进行，完成后刷新设置页控件。
     */
    public void applyAutoMemory() {
        if (config == null || !"auto".equals(config.memoryMode)) return;
        Ui.async2(() -> {
            int max = com.jwh.launcher.util.MemoryProbe.recommendMaxMemoryMB();
            int min = com.jwh.launcher.util.MemoryProbe.recommendMinMemoryMB(max);
            if (max != config.maxMemory || min != config.minMemory) {
                config.maxMemory = max;
                config.minMemory = min;
                ConfigManager.save(config);
            }
            return null;
        }, ok -> {
            if (settingsView != null) settingsView.refreshMemoryControls();
        }, e -> { /* 探测失败保持现有值 */ });
    }
    /**
     * 自动下载源（PCL 同款）：auto 模式下后台测速 BMCLAPI 与官方 Mojang，
     * 选中往返延迟较快者作为本次会话的实际下载源；测速失败保持默认 BMCLAPI。
     */
    public void autoSelectSource() {
        if (config == null || !"auto".equals(config.contentSource)) return;
        Ui.async2(() -> {
            config.resolvedSource = com.jwh.launcher.util.Mirror.autoSelectSource();
            return null;
        }, ok -> {
            if (downloadView != null) downloadView.refresh();
        }, e -> { /* 测速失败保持默认 bmclapi */ });
    }

    // ---- 非首屏页面懒创建：首次点开时才构造，把构建成本分摊到真正进入那一刻 ----

    private DownloadView ensureDownload() {
        if (downloadView == null) downloadView = new DownloadView(this);
        return downloadView;
    }

    private SkinsView ensureSkins() {
        if (skinsView == null) skinsView = new SkinsView(this);
        return skinsView;
    }

    private SettingsView ensureSettings() {
        if (settingsView == null) settingsView = new SettingsView(this);
        return settingsView;
    }

    private LogView ensureLog() {
        if (logView == null) logView = new LogView(this);
        return logView;
    }

    private AboutView ensureAbout() {
        if (aboutView == null) aboutView = new AboutView(this);
        return aboutView;
    }

    private javafx.scene.Node loginContent() {
        if (loginView == null) loginView = new LoginView(this);
        return loginView.content();
    }
}
