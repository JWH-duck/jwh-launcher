package com.jwh.launcher.net;

import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.OSUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 陶瓦联机（Terracotta）内核封装 —— 与 HMCL 同一条接入路径。
 *
 * <p>陶瓦是一个自带 EasyTier 的 Rust 单文件程序，不需要管理员权限、不需要装 wintun：
 * 启动器用 {@code terracotta --hmcl <file>} 把它拉起来，它就绪后把本机 HTTP 控制端口
 * 写进 {@code <file>}（{@code {"port":12345}}），之后启动器只通过这个
 * 回环 HTTP 接口（{@code /state}、{@code /state/scanning}、{@code /state/guesting}）驱动
 * 建房 / 加入，不用自己拼 EasyTier 参数。</p>
 *
 * <p>房间码（{@code U/XXXX-XXXX-XXXX-SSSS}）本身就是加密随机生成的 EasyTier 网络名+密钥，
 * 空间约 34^16，撞号概率可忽略；房主与房客角色对称，谁点「创建房间」谁就是房主。</p>
 */
public final class Terracotta {

    /** 节点列表改由我们自己的发号服务下发，以后换节点不用重新发版。 */
    private static final String NODE_LIST_URL = "https://jwhl.top/api/nodes";

    /**
     * 远程列表拉不到（或拉到的全是废节点）时用的兜底节点。
     *
     * <p>2026-11-14 实况：陶瓦内置的 public.easytier.top / public2.easytier.cn 已无 DNS 记录，
     * 官方列表给的 etnode.zkitefly.eu.org 返回的是无效跳转（no valid connector url found）。
     * 双方拿不到协调节点就永远停在「正在穿透 NAT 建立连接」——本文件兜底节点当天实测握手成功。</p>
     */
    private static final String[] FALLBACK_NODES = {"tcp://43.228.79.100:11010"};

    private static final String EXE = "terracotta.exe";
    private static final String[] COMPANIONS = {"VCRUNTIME140.DLL"};

    private Terracotta() {
    }

    /** 联机阶段（与陶瓦 /state 的 state 字段一一对应，另加本机侧的两个）。 */
    public static final class State {
        public final String kind;
        public final int index;
        public final String room;
        public final String url;
        public final String difficulty;
        public final int exceptionType;
        public final List<String> players;
        public final String error;

        private State(String kind, int index, String room, String url, String difficulty,
                      int exceptionType, List<String> players, String error) {
            this.kind = kind;
            this.index = index;
            this.room = room == null ? "" : room;
            this.url = url == null ? "" : url;
            this.difficulty = difficulty == null ? "" : difficulty;
            this.exceptionType = exceptionType;
            this.players = players == null ? List.of() : players;
            this.error = error == null ? "" : error;
        }

        static State of(String kind) {
            return new State(kind, -1, "", "", "", -1, null, "");
        }

        static State error(String msg) {
            return new State("fatal", -1, "", "", "", -1, null, msg);
        }

        public boolean isHostSide() {
            return kind.startsWith("host-");
        }

        public boolean isGuestSide() {
            return kind.startsWith("guest-");
        }
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static volatile Process process;
    private static volatile int port = -1;
    private static volatile long seenIndex = Long.MIN_VALUE;
    private static volatile State current = State.of("offline");
    private static volatile Consumer<State> listener;
    private static volatile boolean active = true;
    private static volatile Thread poller;
    private static volatile List<String> nodes;
    private static volatile int fails;

    // ---------------------------------------------------------------- 对外状态

    public static State state() {
        return current;
    }

    /** UI 打开本页时设 true（500ms 轮询），离开时设 false（5s）。 */
    public static void setActive(boolean on) {
        active = on;
    }

    public static void setListener(Consumer<State> l) {
        listener = l;
    }

    // ---------------------------------------------------------------- 本机内核

    /** 陶瓦数据目录（与内核自身用的 %TEMP%\terracotta 分开，放我们释放的可执行文件）。 */
    public static Path dataDir() {
        return ConfigManager.configPath().getParent().resolve("terracotta");
    }

    /**
     * 从 jar 释放 terracotta.exe（及同目录 VCRUNTIME140.DLL），返回可执行文件路径。
     * 已存在且大小一致就直接用，避免每次启动都写 9MB。
     */
    public static Path ensureKernel() throws IOException {
        Path dir = dataDir();
        Files.createDirectories(dir);
        Path exe = dir.resolve(EXE);
        if (!Files.isRegularFile(exe) || Files.size(exe) < 8_000_000L) {
            extract("/terracotta/" + EXE, exe);
        }
        for (String c : COMPANIONS) {
            Path p = dir.resolve(c);
            if (!Files.isRegularFile(p) || Files.size(p) < 50_000L) {
                extract("/terracotta/" + c, p);
            }
        }
        return exe;
    }

    private static void extract(String res, Path target) throws IOException {
        try (InputStream in = com.jwh.launcher.util.Resources.open(res.substring(1))) {
            if (in == null) {
                throw new IOException("启动器内置的陶瓦联机内核缺失（" + res + "）。请使用完整版启动器压缩包，或等待资源层更新补齐。");
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 启动（或复用已在运行的）内核，返回后 {@link #port()} 可用。
     * 已有实例在跑时陶瓦会作「附属进程」复用同一个端口，这里也能正确拿到。
     */
    public static synchronized void start() throws IOException {
        if (port > 0 && ping()) return;

        Path dir = dataDir();
        Path exe = ensureKernel();
        Path portFile = dir.resolve("jwh-port.json");
        try {
            Files.deleteIfExists(portFile);
        } catch (IOException ignored) {
        }

        ProcessBuilder pb = new ProcessBuilder(exe.toString(), "--hmcl", portFile.toAbsolutePath().toString());
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        process = p;
        pump(p, dir.resolve("terracotta.log"));

        long deadline = System.currentTimeMillis() + 16_000L;
        int found = -1;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(portFile)) {
                try {
                    String txt = Files.readString(portFile, StandardCharsets.UTF_8);
                    JSONObject o = new JSONObject(txt);
                    if (o.has("port")) {
                        found = o.getInt("port");
                        break;
                    }
                } catch (Exception ignored) {
                    // 文件可能还在改名/未写完，下一轮再读
                }
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (found <= 0) found = readLockPort();
        if (found <= 0) {
            throw new IOException("陶瓦内核没有在 16 秒内就绪（"
                    + (p.isAlive() ? "进程在跑但没写出端口" : "进程已退出，退出码 " + p.exitValue())
                    + "）。日志：" + dir.resolve("terracotta.log"));
        }
        port = found;
        fails = 0;
        seenIndex = Long.MIN_VALUE;
        startPoller();
        try {
            pollOnce();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 首轮拿不到状态不算失败，轮询线程会继续补
        }
    }

    /** 陶瓦崩溃时（进程已退出但端口还在）判断服务是否真的活着。 */
    private static boolean ping() {
        try {
            return http("/meta").status == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 内核把端口写在 %TEMP%\terracotta\terracotta.lock（2 字节大端）里，作为兜底来源。 */
    private static int readLockPort() {
        try {
            String tmp = System.getenv("TEMP");
            if (tmp == null) return -1;
            Path lock = Path.of(tmp, "terracotta", "terracotta.lock");
            if (!Files.isRegularFile(lock)) return -1;
            byte[] b = Files.readAllBytes(lock);
            if (b.length < 2) return -1;
            return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        } catch (Exception e) {
            return -1;
        }
    }

    public static int port() {
        return port;
    }

    private static void pump(Process p, Path logFile) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 2_000_000) sb.setLength(0);
                    if (sb.length() % 200 < line.length()) {
                        try {
                            Files.writeString(logFile, sb.toString(), StandardCharsets.UTF_8);
                        } catch (IOException ignored) {
                        }
                    }
                }
                try {
                    Files.writeString(logFile, sb.toString(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            } catch (IOException ignored) {
            }
        }, "jwh-terracotta-log");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 状态轮询

    private static void startPoller() {
        if (poller != null && poller.isAlive()) return;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(active ? 500 : 5_000);
                } catch (InterruptedException e) {
                    return;
                }
                if (port <= 0) continue;
                try {
                    pollOnce();
                    fails = 0;
                } catch (Exception e) {
                    if (++fails >= 5) {
                        setState(State.error("陶瓦内核已退出或无法访问，点「断开联机」后重试。"));
                    }
                }
            }
        }, "jwh-terracotta-poll");
        t.setDaemon(true);
        poller = t;
        t.start();
    }

    private static synchronized void pollOnce() throws IOException, InterruptedException {
        Resp resp = http("/state");
        if (resp.status != 200) throw new IOException("HTTP " + resp.status);
        JSONObject o = new JSONObject(resp.body);
        int idx = o.optInt("index", -1);
        if (idx >= 0 && idx <= seenIndex) return;
        if (idx >= 0) seenIndex = idx;
        setState(parse(o));
    }

    private static State parse(JSONObject o) {
        String kind = o.optString("state", "waiting");
        String room = o.optString("room", "");
        String url = o.optString("url", "");
        String diff = o.has("difficulty") ? o.optString("difficulty", "") : "";
        int type = o.has("type") ? o.optInt("type", -1) : -1;
        List<String> players = new ArrayList<>();
        JSONArray arr = o.optJSONArray("profiles");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String name = p.optString("name", "");
                String k = p.optString("kind", "");
                players.add("GUEST".equals(k) || "LOCAL".equals(k) ? name + "（朋友）" : name + "（房主）");
            }
        }
        return new State(kind, o.optInt("index", -1), room, url, diff, type, players, "");
    }

    private static void setState(State s) {
        current = s;
        Consumer<State> l = listener;
        if (l != null) l.accept(s);
    }

    // ---------------------------------------------------------------- 控制接口

    /** 读内核真实状态（读不到返回空串）。 */
    private static String realKind() {
        try {
            Resp r = http("/state");
            if (r.status == 200) return new JSONObject(r.body).optString("state", "");
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 陶瓦的 {@code /state/scanning} 与 {@code /state/guesting} 只接受 waiting 态：
     * guesting 在其它状态直接返回 400，scanning 则静默不生效。
     * 所以「刚当过房主又想加别人的房」「刚进过别人的房又想自己开房」之前，
     * 必须先把内核打回待机，否则按钮怎么点都不会成功。
     */
    private static void ensureIdle() throws IOException, InterruptedException {
        String k = realKind();
        if (k.isEmpty() || "waiting".equals(k)) return;

        http("/state/ide");
        for (int i = 0; i < 20; i++) {
            Thread.sleep(250);
            if ("waiting".equals(realKind())) {
                seenIndex = Long.MIN_VALUE;
                setState(State.of("waiting"));
                return;
            }
        }
        throw new IOException("上一个联机状态没能退出，请点「断开联机」后再试。");
    }

    /** 以房主身份开房：陶瓦会扫描本机 Minecraft 的局域网广播，扫到端口才真正建房。 */
    public static void host(String playerName) throws IOException, InterruptedException {
        host(playerName, "");
    }

    /**
     * 指定房间码建房（自建发号服务下发的码）。传空串则让陶瓦自己随机生成。
     *
     * <p>注意：陶瓦对不合法（不满足 7 的倍数校验）的码不会报错，而是静默换成随机码，
     * 所以调用方拿到 {@code host-ok} 后要把房间码回读核对一遍，见
     * {@link com.jwh.launcher.ui.MultiplayerView}。</p>
     */
    public static void host(String playerName, String room) throws IOException, InterruptedException {
        ensureStarted();
        ensureIdle();
        StringBuilder path = new StringBuilder("/state/scanning?player=").append(enc(playerName));
        if (room != null && !room.isBlank() && isRoomComplete(room)) {
            path.append("&room=").append(enc(room));
        }
        for (String n : nodes()) path.append("&public_nodes=").append(enc(n));
        Resp r = http(path.toString());
        if (r.status != 200) throw new IOException("HTTP " + r.status);
        setState(State.of("host-scanning"));
        fails = 0;
    }

    /** 加入房间；房间码格式不对或当前不在待机态时服务端返回 400。 */
    public static void join(String room, String playerName) throws IOException, InterruptedException {
        ensureStarted();
        ensureIdle();
        StringBuilder path = new StringBuilder("/state/guesting?room=").append(enc(room))
                .append("&player=").append(enc(playerName));
        for (String n : nodes()) path.append("&public_nodes=").append(enc(n));
        Resp r = http(path.toString());
        if (r.status == 400) {
            throw new IOException("房间码无效（格式不对，或内核当前不处于待机状态）。");
        }
        if (r.status != 200) throw new IOException("HTTP " + r.status);
        setState(State.of("guest-connecting"));
        fails = 0;
    }

    /** 回待机（「返回主页」/「断开联机」）。 */
    public static void reset() {
        try {
            if (port > 0) http("/state/ide");
        } catch (Exception ignored) {
        }
        seenIndex = Long.MIN_VALUE;
        setState(State.of("waiting"));
    }

    /** 优雅退出内核。 */
    public static void shutdown() {
        try {
            if (port > 0) http("/panic?peaceful=true");
        } catch (Exception ignored) {
        }
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
        port = -1;
        process = null;
        seenIndex = Long.MIN_VALUE;
        setState(State.of("offline"));
    }

    private static void ensureStarted() throws IOException, InterruptedException {
        if (port <= 0 || !ping()) start();
    }

    // ---------------------------------------------------------------- 公共节点

    /** 拉一次公共节点列表（失败就空表，陶瓦自带默认节点）。 */
    private static List<String> nodes() {
        List<String> cached = nodes;
        if (cached != null) return cached;
        List<String> list = new ArrayList<>();
        try {
            String txt = Http.getText(NODE_LIST_URL);
            JSONArray arr = new JSONArray(txt);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (!u.isBlank()) list.add(u);
            }
        } catch (Exception ignored) {
        }
        // 拉到空表就等于什么都没拉到：再让陶瓦用自己那套死节点，双方必然卡在「穿透 NAT」。
        if (list.isEmpty()) {
            for (String s : FALLBACK_NODES) list.add(s);
        }
        nodes = list;
        return list;
    }

    // ---------------------------------------------------------------- 房间码

    /** 房间码字符集：0-9 与去掉 I、O 的大写字母（共 34 个）。 */
    private static final String ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    /**
     * 把玩家输入（可能带空格/小写/缺前缀）规范成陶瓦的 {@code U/XXXX-XXXX-XXXX-XXXX}。
     * 输入不足 16 位时按已输入部分拼一半格式，方便边输边看。
     */
    public static String normalizeRoom(String raw) {
        if (raw == null) return "";
        String s = raw.toUpperCase().replaceAll("[^0-9A-Z]", "");
        if (s.startsWith("U")) s = s.substring(1); // 去掉最前面的 U（U/ 的 U）
        if (s.length() > 16) s = s.substring(0, 16);
        StringBuilder sb = new StringBuilder("U/");
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    /** 完整房间码（16 个有效字符）才允许提交。 */
    public static boolean isRoomComplete(String normalized) {
        if (normalized == null) return false;
        String body = normalized.startsWith("U/") ? normalized.substring(2) : normalized;
        String hex = body.replace("-", "");
        if (hex.length() != 16) return false;
        for (char c : hex.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- HTTP 工具

    private static final class Resp {
        final int status;
        final String body;

        Resp(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    private static Resp http(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), r.body());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    /** 让 UI 也能知道内核日志在哪。 */
    public static Path logPath() {
        return dataDir().resolve("terracotta.log");
    }

    /** 当前平台是否有内置内核（目前只随包分发 Windows x86_64）。 */
    public static boolean supported() {
        return OSUtil.currentOs() == OSUtil.OS.WINDOWS;
    }

    /** 把内核输出末尾若干行取出来贴到界面（排错用）。 */
    public static String tailLog(int lines) {
        try {
            Path p = logPath();
            if (!Files.isRegularFile(p)) return "";
            List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (Exception e) {
            return "";
        }
    }

    /** 调试用：把字符串写到内核日志目录（不影响产品行为）。 */
    static void note(String msg) {
        try {
            Path p = dataDir().resolve("launcher-note.log");
            Files.createDirectories(p.getParent());
            try (OutputStream out = Files.newOutputStream(p, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
