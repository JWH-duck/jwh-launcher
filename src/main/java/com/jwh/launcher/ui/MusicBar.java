package com.jwh.launcher.ui;

import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 主页「现在播放」迷你播放器：上一首 / 播放·暂停 / 下一首 / 播放菜单。
 * <p>
 * 曲目全部内置在启动器包里（{@code src/main/resources/music}，打进 jar 的 {@code /music} 下），
 * 玩家在界面里看不到任何「添加 / 打开文件夹 / 重新扫描」入口，播放菜单只列歌名。
 * 想加歌只能改工程资源后重新打包，符合「内置、不可由玩家自行更改」的要求。
 * <p>
 * 播放核心是 JavaFX MediaPlayer（包里已带 jfxmedia.dll / gstreamer-lite.dll，支持 mp3、aac/m4a、wav）。
 * jar 内资源先落到临时目录再播，避免解码器对 jar URL 随机访问的限制。
 */
public final class MusicBar extends VBox {

    /** 认的扩展名：JavaFX Media 能直接播的几种。 */
    private static final String[] EXTS = {".mp3", ".m4a", ".aac", ".wav"};

    /** 默认音量：在全量基础上两次下调 20%，0.8 × 0.8 = 0.64。 */
    private static final double VOLUME = 0.65;

    /** 内置曲目所在的 classpath 目录。 */
    private static final String RES_DIR = "music/";

    /**
     * 播放菜单的固定播放顺序，与内置歌单一致。
     * 文件名本身不带序号（菜单里会重新编号），顺序只由这张表决定；
     * 表里没有的曲目按文件名字母序排在最后，新增歌曲不会打乱已有顺序。
     */
    private static final List<String> PLAYLIST = List.of(
            "sweden", "aria math", "haggstrom", "mice on venus", "subwoofer lullaby",
            "danny", "living mice", "taswell", "wet hands", "moog city 2",
            "clark", "dreiton", "minecraft");

    // 图标（坐标即像素，避免不同机器字体里 ⏮ ▶ 之类符号渲染不一致）
    private static final String ICON_PLAY = "M 1 0 L 13 7 L 1 14 Z";
    private static final String ICON_PAUSE = "M 0 0 h 4 v 14 h -4 Z M 8 0 h 4 v 14 h -4 Z";
    // 上一首 / 下一首互为严格镜像：竖条在外侧、三角尖朝内
    private static final String ICON_PREV = "M 0 0 h 3 v 14 h -3 Z M 12 0 L 4 7 L 12 14 Z";
    private static final String ICON_NEXT = "M 11 0 h 3 v 14 h -3 Z M 2 0 L 10 7 L 2 14 Z";
    private static final String ICON_LIST = "M 0 1 h 14 v 2 h -14 Z M 0 6 h 14 v 2 h -14 Z M 0 11 h 14 v 2 h -14 Z";

    /** 紧凑版：为塞进顶栏右上角而整体缩一号（行距、字号、进度条、按钮都变小）。 */
    private final boolean compact;
    /** 控制按钮命中区边长：常规 32px，紧凑 26px。 */
    private final int iconSize;

    private final Label title = new Label("启动器里还没有内置音乐");
    private final Label time = new Label("");
    private final SimpleDoubleProperty progress = new SimpleDoubleProperty(0);
    private final Region barFill = new Region();
    private final Pane barPane = new Pane();
    private final SVGPath playIcon = new SVGPath();

    /** 内置曲目资源路径，形如 {@code music/xxx.mp3}。 */
    private final List<String> tracks = new ArrayList<>();
    private int index = -1;
    private MediaPlayer player;
    /** 随机播放用；每次打开启动器、每次从游戏退回都重新抽一首。 */
    private final java.util.Random rnd = new java.util.Random();
    /** 「上一首」的回退栈：记录真实播过的顺序，让回退有意义（没有栈时随机挑）。 */
    private final java.util.ArrayDeque<Integer> history = new java.util.ArrayDeque<>();

    public MusicBar() {
        this(false);
    }

    /**
     * @param compact 顶栏右上角用的紧凑版：顶栏只有 68px 高，常规版三行放不下
     */
    public MusicBar(boolean compact) {
        this.compact = compact;
        this.iconSize = compact ? 26 : 32;
        int barH = compact ? 2 : 3;
        setSpacing(compact ? 2 : 3);
        setPadding(new javafx.geometry.Insets(compact ? 3 : 7, compact ? 8 : 9, compact ? 3 : 7, compact ? 8 : 9));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color:transparent;");
        // 空白处不吃鼠标：音乐条右侧与「启动游戏」按钮有一点重叠，
        // 若整块 Region 参与命中，按钮左侧就点不动了。
        setPickOnBounds(false);

        // ---- 第一行：曲名 + 时间 ----
        title.setFont(javafx.scene.text.Font.font(compact ? 12 : 13));
        title.setTextFill(Color.web(Ui.TEXT));
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        time.setFont(javafx.scene.text.Font.font(compact ? 10 : 11));
        time.setTextFill(Color.web(Ui.TEXT_DIM));
        time.setMinWidth(Region.USE_PREF_SIZE);
        HBox top = new HBox(6, title, time);
        top.setAlignment(Pos.CENTER_LEFT);

        // ---- 第二行：细进度条（自绘，JavaFX ProgressBar 在 4px 高度下填充几乎看不见）----
        Region barTrack = new Region();
        barTrack.setStyle("-fx-background-color:rgba(0,0,0,0.10); -fx-background-radius:2;");
        barTrack.setPrefHeight(barH);
        barTrack.setMinHeight(barH);
        barTrack.setMaxHeight(barH);
        barTrack.prefWidthProperty().bind(barPane.widthProperty());
        barFill.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-background-radius:2;");
        barFill.setPrefHeight(barH);
        barFill.setMinHeight(barH);
        barFill.setMaxHeight(barH);
        barPane.getChildren().addAll(barTrack, barFill);
        barPane.setPrefHeight(barH);
        barPane.setMinHeight(barH);
        barPane.setMaxHeight(barH);
        barPane.setMaxWidth(Double.MAX_VALUE);
        barPane.setVisible(false);   // 没有曲目时不占视觉重量
        barFill.prefWidthProperty().bind(barPane.widthProperty().multiply(progress));

        // ---- 第三行：控制按钮（四个同一套样式：无底色，只靠图标明暗反馈）----
        playIcon.setContent(ICON_PLAY);
        StackPane playBtn = iconNode(playIcon, "播放 / 暂停", this::toggle);
        StackPane prevBtn = iconBtn(ICON_PREV, "上一首", this::prev);
        StackPane nextBtn = iconBtn(ICON_NEXT, "下一首", this::next);
        StackPane listBtn = iconBtn(ICON_LIST, "播放菜单", () -> showMenu(this));

        HBox ctrl = new HBox(compact ? 10 : 14, prevBtn, playBtn, nextBtn, listBtn);
        ctrl.setMaxWidth(Double.MAX_VALUE);
        ctrl.setAlignment(compact ? Pos.CENTER_LEFT : Pos.CENTER);

        getChildren().addAll(top, barPane, ctrl);

        scanBuiltinTracks();
        renderTitle();
    }

    // ------------------------------------------------------------------ 图标按钮

    private StackPane iconBtn(String path, String tip, Runnable action) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        return iconNode(icon, tip, action);
    }

    /** 统一按钮：四个控制键共用同一尺寸与同一颜色，无底色，hover 只提亮图标。 */
    private StackPane iconNode(SVGPath icon, String tip, Runnable action) {
        icon.setFill(Color.web(Ui.TEXT_DIM));
        // 图标按 1.25 倍放大，但按钮命中区域仍是 32px（紧凑版 26px），视觉更饱满又不拥挤
        double scale = compact ? 1.1 : 1.25;
        icon.setScaleX(scale);
        icon.setScaleY(scale);
        StackPane p = new StackPane(icon);
        p.setPrefSize(iconSize, iconSize);
        p.setMinSize(iconSize, iconSize);
        p.setMaxSize(iconSize, iconSize);
        p.setCursor(Cursor.HAND);
        p.setOnMouseEntered(e -> icon.setFill(Color.web(Ui.ACCENT)));
        p.setOnMouseExited(e -> icon.setFill(Color.web(Ui.TEXT_DIM)));
        p.setOnMouseClicked(e -> action.run());
        Tooltip.install(p, new Tooltip(tip));
        return p;
    }

    // ------------------------------------------------------------------ 内置歌单

    /**
     * 扫描内置 {@code music/} 下的音频。外置 resources 目录优先；回退到打包 jar 条目或 IDE resources 目录。
     */
    private void scanBuiltinTracks() {
        tracks.clear();
        try {
            com.jwh.launcher.util.Resources.listDir(RES_DIR, MusicBar::isAudio).stream()
                    .sorted(MusicBar::compareTracks)
                    .forEach(tracks::add);
        } catch (Exception ignored) {
            // 扫描失败就保持空列表，界面显示「还没有内置音乐」
        }
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase();
        for (String ext : EXTS) {
            if (n.endsWith(ext)) return true;
        }
        return false;
    }

    /** 展示名：去掉目录与扩展名。 */
    private static String displayName(String res) {
        String n = res.substring(res.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** 曲目在歌单里的位置；不在表里返回 {@code Integer.MAX_VALUE}（排最后）。 */
    private static int playlistOrder(String res) {
        String key = displayName(res).toLowerCase().replaceFirst("^c418\\s*-\\s*", "").trim();
        int i = PLAYLIST.indexOf(key);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** 菜单排序：先按内置歌单顺序，再退回文件名字母序，保证顺序稳定。 */
    private static int compareTracks(String a, String b) {
        int c = Integer.compare(playlistOrder(a), playlistOrder(b));
        return c != 0 ? c : a.compareToIgnoreCase(b);
    }

    /**
     * 把包内音频落到临时目录再交给解码器：jar URL 不保证可随机访问，
     * 落在本地文件上最稳。
     * <p>缓存文件名带内容长度前缀：既能在同内容时直接复用，又不会在包内音频
     * 换版本时撞上「同名不同内容」，更不会被另一个仍占着旧文件的启动器实例卡住覆盖。
     */
    private static File materialize(String res) throws IOException {
        // 外置资源本身就是本地文件，直接交给解码器，不必再落临时目录。
        Path ext = com.jwh.launcher.util.Resources.external(res);
        if (ext != null) return ext.toFile();
        URL url = MusicBar.class.getResource("/" + res);
        if (url == null) throw new IOException("内置音频缺失: " + res);
        long expected = url.openConnection().getContentLengthLong();
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "jwh-launcher-music");
        Files.createDirectories(dir);
        String simple = res.substring(res.lastIndexOf('/') + 1);
        Path out = dir.resolve((expected > 0 ? expected + "-" : "") + simple);
        if (Files.isRegularFile(out) && expected > 0 && Files.size(out) == expected) {
            return out.toFile();
        }
        try (var in = url.openStream()) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out.toFile();
    }

    // ------------------------------------------------------------------ 播放

    /** 启动器打开时随机播一首（不再固定从第一首开始）。 */
    public void autoPlay() {
        if (tracks.isEmpty()) return;
        if (player == null) playIndex(pickRandom(-1), false);
    }

    /** 点「启动游戏」时暂停（图标回到 ▶）。 */
    public void pauseForGame() {
        if (player == null) return;
        player.pause();
        setPlayingIcon(false);
    }

    /**
     * 更新落地前调用：停止并 dispose 解码器，松开对外置音频文件（resources/music/*.mp3）
     * 的占用句柄。否则更新覆盖正在播放的那首 mp3 时，Windows 会以「文件正由另一进程使用」
     * 拒绝覆盖，导致资源层更新失败。dispose 后 gstreamer 句柄是异步释放的，调用方落地文件
     * 时需带短暂重试窗口。
     */
    public void releaseForUpdate() {
        stopPlayer();
    }

    /** 更新落地结束后是否可以恢复播放。 */
    public boolean hasTracks() {
        return !tracks.isEmpty();
    }

    /**
     * 游戏退出、回到启动器时：不接着上次的位置续播，而是换一首随机的新曲从头开始，
     * 让每次开完游戏回来都是「重新放一遍」而不是「从半截继续」。
     */
    public void resumeAfterGame() {
        if (tracks.isEmpty()) return;
        playIndex(pickRandom(index), false);
    }

    /** 随机取一首，尽量不和 {@code exclude} 重复（只有一首歌时只能重复）。 */
    private int pickRandom(int exclude) {
        if (tracks.size() <= 1) return 0;
        int i;
        do {
            i = rnd.nextInt(tracks.size());
        } while (i == exclude);
        return i;
    }

    private void toggle() {
        if (tracks.isEmpty()) {
            Ui.info("音乐", "启动器里没有内置音乐文件。");
            return;
        }
        if (player == null) {
            playIndex(index < 0 ? pickRandom(-1) : index, false);
        } else if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
            setPlayingIcon(false);
        } else {
            player.play();
            setPlayingIcon(true);
        }
    }

    private void prev() {
        if (tracks.isEmpty()) return;
        // 有历史就回退到真实播过的那一首；没有就随机挑一首
        if (!history.isEmpty()) {
            playIndex(history.pop(), false);
        } else {
            playIndex(pickRandom(index), false);
        }
    }

    private void next() {
        if (tracks.isEmpty()) return;
        // 无顺序：下一首也是随机换，不按歌单顺序往后排
        playIndex(pickRandom(index), true);
    }

    private void playIndex(int i) {
        playIndex(i, true);
    }

    private void playIndex(int i, boolean record) {
        if (tracks.isEmpty()) return;
        int target = ((i % tracks.size()) + tracks.size()) % tracks.size();
        if (record && index >= 0 && index != target) {
            history.push(index);
            if (history.size() > tracks.size() * 2) {
                history.removeLast();
            }
        }
        index = target;
        stopPlayer();
        String res = tracks.get(index);
        try {
            File f = materialize(res);
            Media media = new Media(f.toURI().toString());
            player = new MediaPlayer(media);
            player.setVolume(VOLUME);
            player.setOnReady(() -> {
                renderTitle();
                barPane.setVisible(true);
                player.play();
                setPlayingIcon(true);
            });
            player.setOnEndOfMedia(this::next);
            player.currentTimeProperty().addListener((o, a, b) -> {
                if (player == null) return;
                Duration total = player.getTotalDuration();
                double t = total == null ? 0 : total.toMillis();
                if (t > 0) {
                    double cur = Math.min(b.toMillis(), t);
                    progress.set(cur / t);
                    time.setText(fmt(b) + " / " + fmt(total));
                }
            });
            player.setOnError(() -> {
                Ui.info("音乐", "这首播不了：" + displayName(res));
                setPlayingIcon(false);
            });
        } catch (Exception ex) {
            Ui.error("音乐播放失败", ex);
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.stop();
                player.dispose();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    private void setPlayingIcon(boolean playing) {
        playIcon.setContent(playing ? ICON_PAUSE : ICON_PLAY);
    }

    private void renderTitle() {
        if (tracks.isEmpty()) {
            title.setText("启动器里还没有内置音乐");
            time.setText("");
            barPane.setVisible(false);
            return;
        }
        if (index < 0) {
            title.setText("点 ▶ 开始播放（共 " + tracks.size() + " 首）");
            time.setText("");
            return;
        }
        // 随机播放：标题只显示曲名，不带「第几首」的序号，避免看着像按顺序放
        title.setText(displayName(tracks.get(index)));
    }

    private static String fmt(Duration d) {
        if (d == null || d.isUnknown()) return "--:--";
        long sec = Math.max(0, (long) d.toSeconds());
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    // ------------------------------------------------------------------ 播放菜单（只有歌曲）

    private void showMenu(Region anchor) {
        ContextMenu menu = new ContextMenu();
        menu.setStyle("-fx-font-size:12.5px;");
        if (tracks.isEmpty()) {
            MenuItem empty = new MenuItem("启动器里还没有内置音乐");
            empty.setDisable(true);
            menu.getItems().add(empty);
        } else {
            for (int i = 0; i < tracks.size(); i++) {
                final int idx = i;
                MenuItem it = new MenuItem((i == index ? "▶  " : "     ") + (i + 1) + ". " + displayName(tracks.get(i)));
                it.setOnAction(e -> playIndex(idx));
                menu.getItems().add(it);
            }
        }
        // 顶栏在窗口最上方：菜单要往下弹，往上弹会出屏
        menu.show(anchor, compact ? javafx.geometry.Side.BOTTOM : javafx.geometry.Side.TOP, 0, compact ? 6 : -6);
    }
}
