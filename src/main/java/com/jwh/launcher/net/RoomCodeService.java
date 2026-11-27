package com.jwh.launcher.net;

import com.jwh.launcher.config.ConfigManager;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 自建「发号服务」的客户端。
 *
 * <p>陶瓦内核默认只给 16 位随机房间码（{@code U/XXXX-XXXX-XXXX-XXXX}）。这里接一层可选的中心发号：
 * 房主建房前先向自己的发号服务器要一个「6 位短号 + 完整码」的组合，用完整码去建房；朋友只喊
 * 6 位短号，启动器负责向同一台服务器换成完整码再交给内核。</p>
 *
 * <p>短号与完整码的对应只影响「怎么把号码交给对方」，不影响连接本身：陶瓦的完整码里自带
 * EasyTier 网络名与密钥，建好房之后服务器完全不参与流量。所以发号服务挂了不影响已经开着的房间，
 * 只会影响「新号码的解释」。这里另外做了本地缓存，解析过的短号离线也能再用。</p>
 */
public final class RoomCodeService {

    private RoomCodeService() {
    }

    /** 一张发号结果：短号 + 完整房间码 + 过期时间戳（秒）。 */
    public static final class Ticket {
        public final String shortCode;
        public final String code;
        public final long expires;

        Ticket(String shortCode, String code, long expires) {
            this.shortCode = shortCode == null ? "" : shortCode;
            this.code = code == null ? "" : code;
            this.expires = expires;
        }
    }

    /** 一次解析结果：完整房间码 + 房主当前登记的游戏端口（0 = 房主还没开局域网）。 */
    public static final class Target {
        public final String code;
        public final int port;
        public final String player;

        Target(String code, int port, String player) {
            this.code = code == null ? "" : code;
            this.port = port;
            this.player = player == null ? "" : player;
        }
    }

    private static final Pattern SHORT_RE = Pattern.compile("^\\d{6}$");

    /** 发号请求在连接超时/瞬时 IO 错误时的尝试次数（首试 + 2 次重试），扛住单次丢包。 */
    private static final int MAX_ATTEMPTS = 3;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static volatile String base = "";
    private static volatile String token = "";

    private static volatile boolean beating;
    private static volatile Thread beater;
    /** 房主当前登记的游戏端口，心跳会一起上报。 */
    private static volatile int beatPort;

    // ---------------------------------------------------------------- 配置

    /** 由 UI 在进入多人页时调用（地址留空 = 不启用发号服务）。 */
    public static void configure(String server, String tk) {
        String s = server == null ? "" : server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        base = s;
        token = tk == null ? "" : tk.trim();
    }

    public static boolean configured() {
        return !base.isBlank();
    }

    public static String serverAddress() {
        return base;
    }

    /** 纯 6 位数字 = 短号；其余一律当完整房间码处理。 */
    public static boolean isShortCode(String raw) {
        return raw != null && SHORT_RE.matcher(raw.trim()).matches();
    }

    // ---------------------------------------------------------------- 业务

    /** 房主取号：服务器分配一个没被占用的短号和对应的合法完整码。 */
    public static Ticket alloc(String player) throws IOException, InterruptedException {
        requireConfigured();
        JSONObject body = new JSONObject();
        body.put("player", player == null ? "" : player);
        JSONObject r = post("/api/alloc", body);
        String shortCode = r.optString("short", "");
        String code = r.optString("code", "");
        if (shortCode.isBlank() || code.isBlank()) {
            throw new IOException("发号服务返回的数据不完整。");
        }
        if (!Terracotta.isRoomComplete(code)) {
            throw new IOException("发号服务给了一个陶瓦不认的房间码（" + code + "），请检查服务端版本。");
        }
        cachePut(shortCode, code);
        return new Ticket(shortCode, code, r.optLong("expires", 0));
    }

    /**
     * 把玩家输入换成一个完整房间码：6 位数字走服务器查表（失败时用本地缓存），
     * 16 位完整码原样返回。
     */
    public static String resolve(String input) throws IOException, InterruptedException {
        String raw = input == null ? "" : input.trim();
        if (!isShortCode(raw)) {
            String normalized = Terracotta.normalizeRoom(raw);
            if (!Terracotta.isRoomComplete(normalized)) {
                throw new IOException("房间号要么是 6 位数字，要么是 16 位完整码（U/XXXX-XXXX-XXXX-XXXX）。");
            }
            return normalized;
        }
        if (!configured()) {
            String cached = cacheGet(raw);
            if (!cached.isEmpty()) return cached;
            throw new IOException("6 位短号需要发号服务，但这台启动器把发号服务关了（设置 → 联机房间号）；让房主把完整码发给你。");
        }
        try {
            JSONObject r = get("/api/resolve?short=" + raw);
            String code = r.optString("code", "");
            if (code.isBlank() || !Terracotta.isRoomComplete(code)) {
                throw new IOException("发号服务没给出可用的房间码。");
            }
            cachePut(raw, code);
            return code;
        } catch (IOException e) {
            String cached = cacheGet(raw);
            if (!cached.isEmpty()) return cached;
            throw e;
        }
    }

    /**
     * 解析玩家输入，同时拿到房主登记的游戏端口。
     *
     * <p>6 位短号走服务器查表；16 位完整码直接用，另外向服务器反查一次端口
     * （查不到不影响连接流程，只是会提示「房主没开局域网」）。</p>
     */
    public static Target target(String input) throws IOException, InterruptedException {
        String raw = input == null ? "" : input.trim();
        if (isShortCode(raw)) {
            if (!configured()) {
                throw new IOException("6 位短号需要发号服务，但这台启动器把发号服务关了"
                        + "（设置 → 联机房间号）；让房主把完整码发给你。");
            }
            JSONObject r = get("/api/resolve?short=" + raw);
            String code = r.optString("code", "");
            if (code.isBlank() || !Terracotta.isRoomComplete(code)) {
                throw new IOException("发号服务没给出可用的房间码。");
            }
            cachePut(raw, code);
            return new Target(code, r.optInt("port", 0), r.optString("player", ""));
        }

        String normalized = Terracotta.normalizeRoom(raw);
        if (!Terracotta.isRoomComplete(normalized)) {
            throw new IOException("房间号要么是 6 位数字，要么是 16 位完整码（U/XXXX-XXXX-XXXX-XXXX）。");
        }
        int port = 0;
        String player = "";
        if (configured()) {
            try {
                JSONObject r = get("/api/resolve?code=" + java.net.URLEncoder.encode(normalized, StandardCharsets.UTF_8));
                port = r.optInt("port", 0);
                player = r.optString("player", "");
            } catch (Exception ignored) {
                // 端口只是锦上添花：查不到就让用户问房主
            }
        }
        return new Target(normalized, port, player);
    }

    /** 释放短号（房主点「断开联机」时调用）。 */
    public static void release(String shortCode) {
        stopHeartbeat();
        if (!configured() || shortCode == null || shortCode.isBlank()) return;
        try {
            JSONObject body = new JSONObject();
            body.put("short", shortCode);
            post("/api/release", body);
        } catch (Exception ignored) {
            // 释放失败无所谓，服务器会按时长自动回收
        }
    }

    // ---------------------------------------------------------------- 心跳

    /**
     * 房主建房成功后开始续期：每 30 秒一次，服务器按 90 分钟无心跳回收。
     * 心跳里带上房主的游戏端口，房客解析短号时才能知道该连哪个端口。
     */
    public static synchronized void startHeartbeat(String shortCode) {
        startHeartbeat(shortCode, 0);
    }

    public static synchronized void startHeartbeat(String shortCode, int port) {
        stopHeartbeat();
        if (!configured() || shortCode == null || shortCode.isBlank()) return;
        beating = true;
        beatPort = port;
        String s = shortCode;
        Thread t = new Thread(() -> {
            while (beating) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (!beating) return;
                try {
                    JSONObject body = new JSONObject();
                    body.put("short", s);
                    if (beatPort > 0) body.put("port", beatPort);
                    post("/api/beat", body);
                } catch (Exception ignored) {
                    // 网络抖动不打断心跳，下一轮再试
                }
            }
        }, "jwh-room-beat");
        t.setDaemon(true);
        beater = t;
        t.start();
    }

    /** 房主晚一点才开局域网时，补登记端口。 */
    public static void updatePort(int port) {
        if (port > 0) beatPort = port;
    }

    public static synchronized void stopHeartbeat() {
        beating = false;
        Thread t = beater;
        if (t != null && t.isAlive()) t.interrupt();
        beater = null;
    }

    // ---------------------------------------------------------------- 本地缓存

    private static Path cachePath() {
        return ConfigManager.configPath().getParent().resolve("roomcache.json");
    }

    private static JSONObject cacheLoad() {
        try {
            Path p = cachePath();
            if (!Files.isRegularFile(p)) return new JSONObject();
            return new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void cachePut(String shortCode, String code) {
        try {
            JSONObject o = cacheLoad();
            o.put(shortCode, code);
            Path p = cachePath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, o.toString(2), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String cacheGet(String shortCode) {
        try {
            String v = cacheLoad().optString(shortCode, "");
            return Terracotta.isRoomComplete(v) ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- HTTP

    private static void requireConfigured() throws IOException {
        if (!configured()) {
            throw new IOException("发号服务已关闭（设置 → 联机房间号 里填了 off），当前只能用陶瓦随机码。");
        }
    }

    private static JSONObject get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (!token.isEmpty()) b.header("X-Token", token);
        return send(b.build());
    }

    private static JSONObject post(String path, JSONObject body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (!token.isEmpty()) b.header("X-Token", token);
        return send(b.build());
    }

    private static JSONObject send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r = null;
        IOException last = null;
        // 传输层（连接超时/瞬时 IO 错误）自动重试；拿到响应后的业务错误不重试。
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                break;
            } catch (IOException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(600L);
                }
            }
        }
        if (r == null) {
            throw new IOException("连不上发号服务器（" + base + "）：" + describe(last), last);
        }
        String body = r.body() == null ? "" : r.body();
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (Exception e) {
            throw new IOException("发号服务返回了非 JSON 内容（HTTP " + r.statusCode() + "）。");
        }
        if (r.statusCode() / 100 != 2) {
            String err = o.optString("error", "");
            if (r.statusCode() == 403) err = "发号服务拒绝访问，口令（Token）不对。";
            if (err.isBlank()) err = "发号服务返回 HTTP " + r.statusCode();
            throw new IOException(err);
        }
        if (!o.optBoolean("ok", false)) {
            throw new IOException(o.optString("error", "发号服务返回失败。"));
        }
        return o;
    }

    private static String describe(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("Connection refused")) return "服务没启动或端口不对。";
        if (m.contains("timed out") || m.contains("Timeout")) return "连接超时，检查地址/端口/防火墙。";
        if (m.contains("UnknownHost") || m.contains("nodename")) return "域名解析失败。";
        return m.isBlank() ? "网络错误。" : m;
    }
}
