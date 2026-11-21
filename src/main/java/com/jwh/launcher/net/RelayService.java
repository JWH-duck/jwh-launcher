package com.jwh.launcher.net;

import com.jwh.launcher.config.ConfigManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 自管 EasyTier 中继联机通道。
 *
 * <p>背景：陶瓦联机（Terracotta）给内核注入 {@code --p2p-only}，只允许点对点直连、禁用中继转发。
 * 在 CGNAT + 对称 NAT + 无公网 IPv6 的网络里打洞成功率接近 0，于是永远停在「正在穿透 NAT」。
 * 本类不再调陶瓦内核，改成启动器自己拉起 EasyTier（不加 {@code --p2p-only}），
 * 打洞失败时由公共中继转发数据 —— 2026-11-15 跨机实测通过。</p>
 *
 * <p>房号体系保持不变：6 位短号与完整码仍由 {@link RoomCodeService} 向发号服务申请/解析，
 * 完整码 {@code U/XXXX-XXXX-YYYY-ZZZZ} 的前两段拼成 EasyTier 网络名后缀，后两段作为网络密钥，
 * 双方各自从同一个完整码派生出同一张虚拟网，不需要额外传输网络参数。</p>
 *
 * <p>虚拟网固定使用 {@code 10.144.144.1}（房主）与 {@code 10.144.144.2}（房客）：房主的 Minecraft
 * 局域网端口监听在 {@code 0.0.0.0}，房客直接用 {@code 10.144.144.1:端口} 连进来。</p>
 */
public final class RelayService {

    private RelayService() {
    }

    // ---------------------------------------------------------------- 常量

    /** 房主虚拟 IP。 */
    public static final String HOST_IP = "10.144.144.1";
    /** 房客虚拟 IP。 */
    public static final String GUEST_IP = "10.144.144.2";

    /** 网络名前缀。陶瓦用 scaffolding-mc-，这里用独立前缀，避免和陶瓦的网撞上。 */
    private static final String NET_PREFIX = "jwh-relay-";

    /** 公共中继节点。拉不到线上列表时用这份兜底（2026-11-15 全部实测 PEER=1）。 */
    private static final String[] FALLBACK_NODES = {
            "tcp://43.228.79.100:11010",
            "tcp://225284.xyz:11010",
            "tcp://183.230.36.171:11010",
            "tcp://easytier.weiai.org.cn:11010",
            "tcp://et.sumnight.top:11010",
    };

    private static final String EXE = "easytier-core.exe";
    private static final String[] COMPANIONS = {"Packet.dll", "WinDivert64.sys", "wintun.dll"};

    // ---------------------------------------------------------------- 状态

    /** 联机阶段。 */
    public static final class State {
        /** offline / host-starting / host-waiting-port / host-ready / guest-starting / guest-connecting / guest-ready / error */
        public final String kind;
        public final String room;
        public final String url;
        public final String detail;
        public final String error;

        private State(String kind, String room, String url, String detail, String error) {
            this.kind = kind == null ? "offline" : kind;
            this.room = room == null ? "" : room;
            this.url = url == null ? "" : url;
            this.detail = detail == null ? "" : detail;
            this.error = error == null ? "" : error;
        }

        static State of(String kind) {
            return new State(kind, "", "", "", "");
        }

        static State of(String kind, String room, String url, String detail) {
            return new State(kind, room, url, detail, "");
        }

        static State error(String msg) {
            return new State("error", "", "", "", msg == null ? "" : msg);
        }

        public boolean isHost() {
            return kind.startsWith("host-");
        }

        public boolean isGuest() {
            return kind.startsWith("guest-");
        }

        public boolean ready() {
            return "host-ready".equals(kind) || "guest-ready".equals(kind);
        }
    }

    private static volatile State current = State.of("offline");
    private static volatile Consumer<State> listener;
    private static volatile Thread worker;
    private static volatile boolean cancel;
    private static volatile int pid = -1;
    private static volatile String activeNetwork = "";
    private static volatile int hostPort;

    public static State state() {
        return current;
    }

    public static void setListener(Consumer<State> l) {
        listener = l;
    }

    private static void setState(State s) {
        current = s;
        Consumer<State> l = listener;
        if (l != null) {
            try {
                l.accept(s);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 房间码 → 网络参数

    /** 完整码的 16 个有效字符（去掉 U/ 和横线）。 */
    private static String body(String code) {
        String s = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (s.startsWith("U/")) s = s.substring(2);
        return s.replace("-", "");
    }

    /**
     * 从完整房间码派生 EasyTier 网络名。
     * {@code U/TZ1K-N7EH-L8JW-Z3FC} → {@code jwh-relay-TZ1K-N7EH}。
     */
    public static String networkName(String code) {
        String b = body(code);
        if (b.length() < 8) return "";
        return NET_PREFIX + b.substring(0, 4) + "-" + b.substring(4, 8);
    }

    /**
     * 从完整房间码派生 EasyTier 网络密钥。
     * {@code U/TZ1K-N7EH-L8JW-Z3FC} → {@code L8JW-Z3FC}。
     */
    public static String networkSecret(String code) {
        String b = body(code);
        if (b.length() < 16) return "";
        return b.substring(8, 12) + "-" + b.substring(12, 16);
    }

    // ---------------------------------------------------------------- 内核文件

    /** EasyTier 释放目录。 */
    public static Path dataDir() {
        return ConfigManager.configPath().getParent().resolve("relay");
    }

    public static Path logPath() {
        return dataDir().resolve("relay.log");
    }

    /** 从 jar 释放内核与配套 DLL（缺一个都起不来：Packet.dll 是静态加载依赖）。 */
    public static Path ensureKernel() throws IOException {
        Path dir = dataDir();
        Files.createDirectories(dir);
        Path exe = dir.resolve(EXE);
        if (!Files.isRegularFile(exe) || Files.size(exe) < 20_000_000L) {
            extract("/relay/" + EXE, exe);
        }
        for (String c : COMPANIONS) {
            Path p = dir.resolve(c);
            if (!Files.isRegularFile(p)) {
                extract("/relay/" + c, p);
            }
        }
        return exe;
    }

    private static void extract(String res, Path target) throws IOException {
        try (InputStream in = com.jwh.launcher.util.Resources.open(res.substring(1))) {
            if (in == null) {
                throw new IOException("启动器内置的联机内核缺失（" + res + "）。请使用完整版启动器压缩包，或等待资源层更新补齐。");
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---------------------------------------------------------------- 节点

    /** 中继节点：优先线上列表（换节点不用重新发版），拉不到用兜底。 */
    private static List<String> nodes() {
        List<String> list = new ArrayList<>();
        try {
            String txt = com.jwh.launcher.util.Http.getText("https://jwhl.top/api/nodes");
            org.json.JSONArray arr = new org.json.JSONArray(txt);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (!u.isBlank()) list.add(u);
            }
        } catch (Exception ignored) {
        }
        if (list.isEmpty()) {
            for (String s : FALLBACK_NODES) list.add(s);
        }
        return list;
    }

    // ---------------------------------------------------------------- 启动 / 停止

    private static Path pidFile() {
        return dataDir().resolve("relay.pid");
    }

    /**
     * 以管理员身份拉起 EasyTier（TUN 必须管理员，普通权限会报
     * {@code Failed to create adapter}）。这里生成一个自提权的 PowerShell 脚本，
     * 由它再用 {@code Start-Process -PassThru} 拿到真实 pid 写文件。
     */
    private static synchronized void launchKernel(String network, String secret, String ip, String hostname)
            throws IOException, InterruptedException {
        Path dir = dataDir();
        Path exe = ensureKernel();
        Path ps1 = dir.resolve("start-relay.ps1");

        StringBuilder a = new StringBuilder();
        a.append("'--network-name','").append(network).append("',");
        a.append("'--network-secret','").append(secret).append("',");
        a.append("'--ipv4','").append(ip).append("',");
        a.append("'--hostname','").append(hostname).append("',");
        a.append("'--instance-name','jwh-relay',");
        a.append("'--enable-kcp-proxy',");
        a.append("'-l','tcp://0.0.0.0:0','-l','udp://0.0.0.0:0'");
        for (String n : nodes()) {
            a.append(",'-p','").append(n).append("'");
        }

        StringBuilder s = new StringBuilder();
        s.append("$ErrorActionPreference='Stop'\r\n");
        s.append("$adm=([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())");
        s.append(".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)\r\n");
        s.append("if(-not $adm){\r\n");
        s.append("  Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','");
        s.append(ps1.toAbsolutePath().toString().replace("'", "''")).append("' -Verb RunAs -WindowStyle Hidden\r\n");
        s.append("  exit\r\n");
        s.append("}\r\n");
        s.append("$a=@(").append(a).append(")\r\n");
        // 换房间/重连时先清掉旧内核，否则会有两个实例抢同一张虚拟网卡。
        // 这一步和启动放在同一次提权里，用户只点一次 UAC。
        s.append("Get-Process -Name 'easytier-core' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue\r\n");
        s.append("Start-Sleep -Milliseconds 900\r\n");
        s.append("$p=Start-Process -FilePath '").append(exe.toAbsolutePath().toString().replace("'", "''"))
                .append("' -ArgumentList $a -WorkingDirectory '").append(dir.toAbsolutePath().toString().replace("'", "''"))
                .append("' -WindowStyle Hidden -PassThru")
                .append(" -RedirectStandardOutput '").append(logPath().toString().replace("'", "''"))
                .append("' -RedirectStandardError '").append(dir.resolve("relay.err.log").toString().replace("'", "''"))
                .append("'\r\n");
        s.append("$p.Id | Out-File -Encoding ascii -NoNewline '")
                .append(pidFile().toAbsolutePath().toString().replace("'", "''")).append("'\r\n");
        Files.writeString(ps1, s.toString(), StandardCharsets.US_ASCII);

        try {
            Files.deleteIfExists(pidFile());
        } catch (IOException ignored) {
        }

        // PowerShell 的输出必须落盘，不能留在管道里：一旦它打印了几行错误把管道缓冲区塞满，
        // 进程就会卡在写、Java 卡在读，只弹一次 UAC 的整个流程会永远停在「正在申请管理员权限」。
        Path launchLog = dir.resolve("launch.log");
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", ps1.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(launchLog.toFile());
        Process p = pb.start();
        try {
            // 提权脚本本身只做「拉起 + 写 pid」，正常 1~3 秒就退；给 30 秒是留给用户在 UAC 弹窗上点「是」。
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        int v = -1;
        long deadline = System.currentTimeMillis() + 8_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Files.isRegularFile(pidFile())) {
                    String t = Files.readString(pidFile(), StandardCharsets.US_ASCII).trim();
                    if (!t.isEmpty()) v = Integer.parseInt(t);
                }
            } catch (Exception ignored) {
            }
            if (v > 0) break;
            Thread.sleep(200);
        }
        if (v <= 0) {
            throw new IOException("没有拿到联机内核的管理员权限。请在弹窗里点「是」；"
                    + "如果没看到弹窗，检查是不是被安全软件拦了。");
        }
        pid = v;
        activeNetwork = network;
    }

    private static boolean processAlive() {
        int p = pid;
        if (p <= 0) return false;
        return ProcessHandle.of(p).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * 虚拟网卡上是否已经有目标 IP。
     *
     * <p>走 Java 原生网卡枚举：纯内存查询、毫秒级、不启动任何外部进程。
     * 之前这里用 {@code powershell.exe Get-NetIPAddress} 每 600ms 起一次进程，
     * 一旦某次 PowerShell 卡住不退出，{@code readAllBytes()} 就永久阻塞，
     * 整个建房流程会停在「正在申请管理员权限」那条状态上再也不动。</p>
     */
    private static boolean hasVirtualIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces())) {
                boolean up;
                try {
                    up = ni.isUp();
                } catch (Exception e) {
                    up = true;
                }
                if (!up) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress a = ia.getAddress();
                    if (a != null && ip.equals(a.getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // ---------------------------------------------------------------- 房主

    /**
     * 以房主身份建立通道：起 EasyTier（10.144.144.1），然后等本机游戏开局域网。
     * 扫到端口后立刻上报给发号服务，房客解析短号时就能拿到端口。
     *
     * @param code   完整房间码（发号服务下发）
     * @param shortCode 6 位短号（可为空）
     */
    public static void host(String code, String shortCode) {
        cancel = true;
        Thread old = worker;
        if (old != null && old.isAlive()) {
            try {
                old.join(1_500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        cancel = false;
        Thread t = new Thread(() -> {
            try {
                runHost(code, shortCode);
            } catch (Exception e) {
                setState(State.error("建立联机通道失败：" + describe(e)));
            }
        }, "jwh-relay-host");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private static void runHost(String code, String shortCode) throws Exception {
        if (!networkName(code).isEmpty()) {
            if (!processAlive() || !activeNetwork.equals(networkName(code)) || !hasVirtualIp(HOST_IP)) {
                setState(State.of("host-starting", code, HOST_IP, "正在申请管理员权限并启动联机内核…"));
                launchKernel(networkName(code), networkSecret(code), HOST_IP, "jwh-host");
            }
        } else {
            throw new IOException("房间码不完整，无法派生联机网络。");
        }

        setState(State.of("host-starting", code, HOST_IP, "正在建立虚拟网（大约 5–15 秒，可能需要点一次 UAC）…"));
        if (!waitVirtualIp(HOST_IP, 25)) {
            throw new IOException("虚拟网卡没有起来。可能是 UAC 被拒绝，或者在弹窗里没点「是」。");
        }

        setState(State.of("host-waiting-port", code, HOST_IP,
                "虚拟网已就绪。现在启动游戏 → 进存档 → 按 Esc →「对局域网开放」。"));
        int before = -1;
        int port = 0;
        long deadline = System.currentTimeMillis() + 15 * 60 * 1000L;
        long lastLog = 0L;
        while (!cancel && System.currentTimeMillis() < deadline) {
            Set<Integer> now = javaListenPorts();
            if (System.currentTimeMillis() - lastLog > 2000L) {
                lastLog = System.currentTimeMillis();
                writeScanLog(now);
            }
            if (before < 0) {
                before = 0;
                baseline = now;
            } else {
                Set<Integer> fresh = new LinkedHashSet<>(now);
                fresh.removeAll(baseline);
                if (!fresh.isEmpty()) {
                    int best = -1;
                    for (int v : fresh) {
                        if (v >= 1024 && v <= 65535 && v != 8080) best = Math.max(best, v);
                    }
                    if (best > 0) {
                        port = best;
                        break;
                    }
                }
            }
            Thread.sleep(700L);
        }
        if (cancel) return;
        if (port <= 0) {
            // 15 分钟没等到端口就回退成「等端口」，由 UI 提示用户重新开局域网
            setState(State.of("host-waiting-port", code, HOST_IP,
                    "还没有扫到局域网端口。请确认：游戏里已经点了「对局域网开放」，并且聊天栏出现了「本地游戏已在端口 xxxxx 上开启」。"));
            return;
        }

        hostPort = port;
        String url = HOST_IP + ":" + port;
        if (shortCode != null && !shortCode.isBlank()) {
            try {
                RoomCodeService.startHeartbeat(shortCode, port);
            } catch (Exception ignored) {
            }
        }
        setState(State.of("host-ready", code, url,
                "房间已就绪。朋友填房间号就能进，他那边会自动连到 " + url + "。"));
    }

    /** 记录「点创建房间时已存在的 java 监听端口」，之后新出现的才可能是游戏端口。 */
    private static volatile Set<Integer> baseline = new LinkedHashSet<>();

    /** 最近一次端口扫描的异常信息，写进 port-scan.log 便于排障。 */
    private static volatile String lastScanError = "";

    // ---------------------------------------------------------------- 房客

    /**
     * 以房客身份建立通道：起 EasyTier（10.144.144.2），再轮询是否已经能连到房主的端口。
     */
    public static void join(String code, int hostPort, String shortCode) {
        cancel = true;
        Thread old = worker;
        if (old != null && old.isAlive()) {
            try {
                old.join(1_500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        cancel = false;
        Thread t = new Thread(() -> {
            try {
                runGuest(code, hostPort);
            } catch (Exception e) {
                setState(State.error("加入房间失败：" + describe(e)));
            }
        }, "jwh-relay-guest");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    private static void runGuest(String code, int hostPort) throws Exception {
        if (hostPort <= 0) {
            throw new IOException("房主还没开局域网（服务器上没有端口信息）。让房主进游戏、按 Esc 点「对局域网开放」，"
                    + "启动器会自动把端口登记上来，然后再试一次。");
        }
        if (networkName(code).isEmpty()) {
            throw new IOException("房间码不完整，无法派生联机网络。");
        }

        if (!processAlive() || !activeNetwork.equals(networkName(code)) || !hasVirtualIp(GUEST_IP)) {
            setState(State.of("guest-starting", code, "", "正在申请管理员权限并启动联机内核…"));
            launchKernel(networkName(code), networkSecret(code), GUEST_IP, "jwh-guest");
        }

        setState(State.of("guest-starting", code, "", "正在建立虚拟网（大约 5–15 秒，可能需要点一次 UAC）…"));
        if (!waitVirtualIp(GUEST_IP, 25)) {
            throw new IOException("虚拟网卡没有起来。可能是 UAC 被拒绝，或者在弹窗里没点「是」。");
        }

        String url = HOST_IP + ":" + hostPort;
        setState(State.of("guest-connecting", code, url, "虚拟网已就绪，正在连接房主 " + url + "…"));

        long deadline = System.currentTimeMillis() + 60_000L;
        while (!cancel && System.currentTimeMillis() < deadline) {
            if (tcpReachable(HOST_IP, hostPort, 2_500)) {
                setState(State.of("guest-ready", code, url,
                        "已连上房主的游戏。在游戏里用「直接连接」填 " + url + " 就能进。"));
                return;
            }
            Thread.sleep(1_200L);
        }
        if (cancel) return;
        throw new IOException("60 秒内没连上房主（" + url + "）。确认房主还在房间里、游戏没退存档、"
                + "并且他那边的「对局域网开放」还开着。");
    }

    private static boolean waitVirtualIp(String ip, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!cancel && System.currentTimeMillis() < deadline) {
            if (hasVirtualIp(ip)) return true;
            Thread.sleep(600L);
        }
        return hasVirtualIp(ip);
    }

    private static boolean tcpReachable(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 端口扫描

    /**
     * 把最近一轮扫描结果落到 {@code relay\port-scan.log}。
     * 扫不到端口时把这份文件发出来，就能直接看出「当时一共看到哪些 java 端口、有没有异常」。
     */
    private static void writeScanLog(Set<Integer> now) {
        try {
            StringBuilder s = new StringBuilder();
            s.append("time=").append(java.time.LocalDateTime.now()).append("\r\n");
            s.append("java_listen=").append(now).append("\r\n");
            s.append("baseline=").append(baseline).append("\r\n");
            s.append("error=").append(lastScanError).append("\r\n");
            Files.writeString(dataDir().resolve("port-scan.log"), s.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /**
     * 本机所有「java 进程」在 0.0.0.0 上监听的端口。
     * Minecraft 开局域网时会在 0.0.0.0 上开会话端口，只认 java 进程就能避开启动器自身和系统服务。
     */
    private static Set<Integer> javaListenPorts() {
        Set<Integer> out = new LinkedHashSet<>();
        Path tmp = null;
        try {
            // 输出先写临时文件再读：既不会被管道缓冲区塞满卡住 netstat，
            // 也不会像 readAllBytes 那样在进程迟迟不退出时无限阻塞。
            tmp = Files.createTempFile("jwh-netstat-", ".txt");
            ProcessBuilder pb = new ProcessBuilder("netstat", "-ano", "-p", "TCP");
            pb.redirectErrorStream(true);
            pb.redirectOutput(tmp.toFile());
            Process p = pb.start();
            if (!p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out;
            }
            // 必须用 ISO_8859_1 读：netstat 的中文表头是 GBK 字节，而 Files.readString
            // 用 US_ASCII 是严格解码，遇到这些字节会抛 MalformedInputException，
            // 导致整个扫描被 catch 吞掉、永远返回空集合（表现就是扫不到端口）。
            String txt = Files.readString(tmp, StandardCharsets.ISO_8859_1);
            for (String line : txt.split("\r?\n")) {
                String t = line.trim();
                if (!t.startsWith("TCP")) continue;
                if (!t.contains("LISTENING")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 4) continue;
                String local = parts[1];
                // 双栈机器上 netstat 也可能把「任意地址监听」写成 [::]:端口 或 :::端口。
                // 这两种和 0.0.0.0 一样对外可达，必须一起认，否则会漏掉游戏端口。
                boolean wildcard = local.startsWith("0.0.0.0:")
                        || local.startsWith("[::]:")
                        || local.startsWith(":::");
                if (!wildcard) continue;
                int port;
                int procId;
                try {
                    port = Integer.parseInt(local.substring(local.lastIndexOf(':') + 1));
                    procId = Integer.parseInt(parts[parts.length - 1]);
                } catch (Exception e) {
                    continue;
                }
                if (procId <= 0) continue;
                if (isJavaProcess(procId)) out.add(port);
            }
        } catch (Exception e) {
            lastScanError = java.time.LocalTime.now() + " "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    private static boolean isJavaProcess(long procId) {
        if (procId == ProcessHandle.current().pid()) return false;
        try {
            return ProcessHandle.of(procId)
                    .flatMap(h -> h.info().command())
                    .map(c -> {
                        String n = c.replace('\\', '/');
                        n = n.substring(n.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                        return n.equals("java.exe") || n.equals("javaw.exe") || n.equals("java");
                    })
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 停止

    /** 断开：回待机（内核进程保留，下次同网络可复用，少一次 UAC）。 */
    public static void idle() {
        cancel = true;
        setState(State.of("offline"));
    }

    /** 彻底停止内核进程（需要管理员，会弹一次 UAC）。 */
    public static void shutdown() {
        cancel = true;
        int p = pid;
        if (p > 0) {
            try {
                Path ps1 = dataDir().resolve("stop-relay.ps1");
                StringBuilder s = new StringBuilder();
                s.append("$ErrorActionPreference='SilentlyContinue'\r\n");
                s.append("$adm=([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent())");
                s.append(".IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)\r\n");
                s.append("if(-not $adm){\r\n");
                s.append("  Start-Process -FilePath 'powershell.exe' -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','");
                s.append(ps1.toAbsolutePath().toString().replace("'", "''")).append("' -Verb RunAs -WindowStyle Hidden\r\n");
                s.append("  exit\r\n");
                s.append("}\r\n");
                s.append("Stop-Process -Id ").append(p).append(" -Force\r\n");
                Files.writeString(ps1, s.toString(), StandardCharsets.US_ASCII);
                ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", ps1.toAbsolutePath().toString());
                pb.redirectErrorStream(true);
                Process q = pb.start();
                q.waitFor(40, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        pid = -1;
        activeNetwork = "";
        hostPort = 0;
        setState(State.of("offline"));
    }

    public static int hostPort() {
        return hostPort;
    }

    /** 已启动内核时返回它的 pid，否则 -1。 */
    public static int pid() {
        return pid;
    }

    // ---------------------------------------------------------------- 工具

    private static String describe(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("Failed to create adapter")) return "建虚拟网卡失败，通常是没用管理员权限运行。";
        if (m.contains("Access is denied") || m.contains("拒绝访问")) return "提权被拒绝，需要点「是」。";
        return m.isBlank() ? "未知错误。" : m;
    }

    /** 当前平台是否支持（目前只随包分发 Windows 版内核）。 */
    public static boolean supported() {
        return com.jwh.launcher.util.OSUtil.currentOs() == com.jwh.launcher.util.OSUtil.OS.WINDOWS;
    }

    /** 把内核日志末尾若干行取出来（排错用）。 */
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
}
