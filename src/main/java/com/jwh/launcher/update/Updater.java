package com.jwh.launcher.update;

import com.jwh.launcher.util.Http;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 启动器自更新：检查「更新源 manifest」→ 发现新版本 → 下载 → 由更新脚本关闭本进程、
 * 覆盖文件、重启新版本。
 *
 * <p>支持两种更新形态（manifest 里二选一，jar 优先）：
 * <ul>
 *   <li><b>jar 增量更新（阶段 A，推荐）</b>：只替换 {@code app/JWH-Launcher.jar}，
 *       自带 JRE（runtime/）、exe、许可证等保持不动，玩家无需重下整包；</li>
 *   <li><b>全量 zip 覆盖（兼容兜底）</b>：下载完整压缩包解压覆盖整个目录，
 *       用于 jar 不兼容或首次大版本升级。</li>
 * </ul>
 *
 * 更新源配置（二选一，作者无需重新打包）：
 *  1) 启动器目录下放 update-url.txt，内容为 manifest 的完整直链（推荐）；
 *  2) 未找到时使用内置默认地址。
 *
 * <p>manifest.json 格式（jar 增量 + 全量兜底可同时给出；每个包可用 {@code urls} 数组给
 * 主源与兜底源，下载层按顺序自动换源；兼容旧的单 {@code url} 字段）：
 * <pre>
 * {
 *   "version": "1.0.0",
 *   "notes": "本次更新内容说明",
 *   "jar": {
 *     "urls": ["https://主源/.../JWH-Launcher-1.0.0.jar",
 *              "https://gitee.com/JWH-Minecraft/jwh-launcher/releases/download/latest/JWH-Launcher-1.0.0.jar"],
 *     "size": 182744928,
 *     "sha256": "ab12..."
 *   },
 *   "urls": ["https://主源/.../JWH-Launcher-v1.0.0.zip",
 *            "https://gitee.com/JWH-Minecraft/jwh-launcher/releases/download/latest/JWH-Launcher-v1.0.0.zip"],
 *   "size": 236749887
 * }
 * </pre>
 */
public class Updater {

    /** 内置默认更新源（占位；作者建好仓库后可在此固化，或玩家目录用 update-url.txt 覆盖）。 */
    public static final String DEFAULT_MANIFEST_URL =
            "https://gitee.com/JWH-Minecraft/jwh-launcher/releases/download/latest/manifest.json";

    /** jpackage app-image 中主程序 jar 的固定相对路径（相对 exe 所在目录）。 */
    public static final String MAIN_JAR_REL = "app/JWH-Launcher.jar";

    /** 外置资源目录（相对 exe 所在目录），与 {@link com.jwh.launcher.util.Resources#EXTERNAL_DIR} 一致。 */
    public static final String RESOURCES_DIR = com.jwh.launcher.util.Resources.EXTERNAL_DIR;

    /** 本地已装资源层记录文件（位于外置资源根下）。 */
    public static final String LAYERS_FILE = "layers.json";

    /** 每层关键文件（相对外置资源根）；任一缺失即判定该层不完整、需重下。music 单独按目录非空判定。 */
    private static final Map<String, String[]> LAYER_KEY_FILES = Map.ofEntries(
            Map.entry("relay", new String[]{"relay/easytier-core.exe", "relay/Packet.dll", "relay/WinDivert64.sys", "relay/wintun.dll"}),
            Map.entry("terracotta", new String[]{"terracotta/terracotta.exe", "terracotta/VCRUNTIME140.DLL"}),
            Map.entry("neoform", new String[]{
                    "neoform-1.21.1-20240808.144430-srg.jar",
                    "neoform-1.21.1-20240808.144430-extra.jar",
                    "neoform-1.21.4-20241203.161809-srg.jar",
                    "neoform-1.21.4-20241203.161809-extra.jar"}),
            Map.entry("neoforge", new String[]{
                    "neoforge-21.4.161-client.jar",
                    "neoforge-fallback-client.jar",
                    "neoforge-fallback-universal.jar"}),
            Map.entry("agent", new String[]{"authlib-injector.jar"})
    );

    /** 单个资源层描述。urls 为候选下载地址（主源在前，兜底源在后），按顺序由下载层自动换源。 */
    public record LayerInfo(String name, String version, List<String> urls, long size, String sha256) {
        public boolean hasUrl() {
            return urls != null && !urls.isEmpty();
        }

        /** 首条地址（兼容旧调用）。 */
        public String url() {
            return hasUrl() ? urls.get(0) : "";
        }
    }

    /** 全量包分卷描述。index 从 1 起，合并时按 index 升序拼接；urls 为候选源（主源在前）。 */
    public record PartInfo(int index, String name, List<String> urls, long size, String sha256) {
        public boolean hasUrl() {
            return urls != null && !urls.isEmpty();
        }

        public String url() {
            return hasUrl() ? urls.get(0) : "";
        }
    }

    /** 新版本信息。layers 为本轮需要补齐/更新的资源层（可为空）；parts 为全量包分卷（可为空，空则用单包）。 */
    public record UpdateInfo(String version, String notes, List<String> fullUrls, long fullSize,
                             String fullSha256, List<String> jarUrls, long jarSize,
                             String jarSha256, List<LayerInfo> layers, List<PartInfo> parts) {
        /** 是否携带 jar 增量包。 */
        public boolean hasJar() {
            return jarUrls != null && !jarUrls.isEmpty();
        }

        /** 是否有需要下载的资源层。 */
        public boolean hasLayers() {
            return layers != null && !layers.isEmpty();
        }

        /** 是否为分卷全量包。 */
        public boolean hasParts() {
            return parts != null && !parts.isEmpty();
        }

        /** 全量包首条地址（兼容旧调用）。 */
        public String url() {
            return fullUrls == null || fullUrls.isEmpty() ? "" : fullUrls.get(0);
        }

        /** jar 包首条地址（兼容旧调用）。 */
        public String jarUrl() {
            return jarUrls == null || jarUrls.isEmpty() ? "" : jarUrls.get(0);
        }

        /** 全量包大小（兼容旧调用，等同 fullSize）。 */
        public long size() {
            return fullSize;
        }
    }

    /**
     * 更新/补层全局串行锁。启动时的后台静默补层与用户确认后的主更新都落到本类，
     * 若并发执行会同时写 {@code .jwh-update} 临时目录、{@code layers.json} 与外置资源，
     * 造成临时文件互删、登记互相覆盖、同名文件占用而偶发失败。同一进程内只允许一个写流程。
     */
    private static final ReentrantLock UPDATE_LOCK = new ReentrantLock();

    private final Path appDir;
    private final String currentVersion;
    /** 进度文本回调（可空）；后台线程上报，由界面层切回 FX 线程刷新。 */
    private Consumer<String> progressSink;
    /** 进度数值回调（可空）：0..1 的确定进度，由界面层切回 FX 线程刷新进度条。 */
    private Consumer<Double> progressNumSink;
    /** 落地资源文件前回调（可空）：由界面层注入，用于暂停并释放背景音乐解码器，松开外置 mp3 句柄。 */
    private Runnable audioReleaseHook;

    /** 下载阶段占用的进度权重（解压/落地/校验占剩余部分）。 */
    private static final double DOWNLOAD_WEIGHT = 0.85;
    /** 本轮待下载字节总量（manifest 给出的各产物 size 之和）；<=0 表示大小未知，进度条回退不确定动画。 */
    private long totalBytes;
    /** 已下载完成的文件字节数（不含当前正在下载的文件）。 */
    private long doneBytes;
    /** 当前正在下载文件的起点累计字节（= 进入该文件时的 doneBytes）。 */
    private long fileBaseBytes;
    /** 资源层总数与已完成层数（用于落地阶段的细分进度）。 */
    private int layerTotal;
    private int layerDone;

    public Updater(Path appDir, String currentVersion) {
        this.appDir = appDir;
        this.currentVersion = currentVersion;
    }

    /** 注入进度文本回调，返回本对象便于链式调用。 */
    public Updater onProgress(Consumer<String> sink) {
        this.progressSink = sink;
        return this;
    }

    /** 注入确定进度数值回调（0..1），返回本对象便于链式调用。 */
    public Updater onProgressNumeric(Consumer<Double> sink) {
        this.progressNumSink = sink;
        return this;
    }

    /** 注入落地资源前的音频释放回调，返回本对象便于链式调用。 */
    public Updater onAudioRelease(Runnable hook) {
        this.audioReleaseHook = hook;
        return this;
    }

    /** 落地覆盖资源文件前调用界面层释放背景音乐句柄；回调异常静默，不打断更新。 */
    private void releaseAudio() {
        if (audioReleaseHook == null) return;
        try {
            audioReleaseHook.run();
        } catch (Exception ignored) {
        }
    }

    private void report(String msg) {
        if (progressSink == null || msg == null) return;
        try {
            progressSink.accept(msg);
        } catch (Exception ignored) {
        }
    }

    /** 上报确定进度值（可空回调；NaN 时界面回退不确定动画）。 */
    private void reportNum(double frac) {
        if (progressNumSink == null) return;
        try {
            progressNumSink.accept(frac);
        } catch (Exception ignored) {
        }
    }

    /** 同时上报进度值与状态文本。 */
    private void report(double frac, String msg) {
        reportNum(frac);
        report(msg);
    }

    /** 汇总本轮待下载字节总量：资源层 + jar；无 jar 时按全量包/分卷合计。 */
    private void initTotalBytes(UpdateInfo info) {
        long t = 0;
        if (info.layers() != null) {
            for (LayerInfo l : info.layers()) t += Math.max(0, l.size());
        }
        if (info.hasJar()) {
            t += Math.max(0, info.jarSize());
        } else if (info.hasParts()) {
            for (PartInfo p : info.parts()) t += Math.max(0, p.size());
        } else {
            t += Math.max(0, info.fullSize());
        }
        this.totalBytes = t;
        this.doneBytes = 0;
        this.layerDone = 0;
    }

    /** 进入一个文件下载：记录起点累计字节。 */
    private void beginFile(long size) {
        this.fileBaseBytes = this.doneBytes;
    }

    /** 文件下载成功后累加完成字节。 */
    private void endFile(long size) {
        this.doneBytes += Math.max(0, size);
    }

    /** 由当前全局已下字节换算下载段进度（0..DOWNLOAD_WEIGHT）；总量未知返回 NaN。 */
    private double downloadFrac(long globalBytes) {
        if (totalBytes <= 0) return Double.NaN;
        double f = DOWNLOAD_WEIGHT * Math.min(1.0, (double) globalBytes / totalBytes);
        return Math.max(0.0, Math.min(DOWNLOAD_WEIGHT, f));
    }

    /** 字节进度控制：把下载累计字节换算成 MB 文本与全局确定进度上报，不支持取消。 */
    private Http.DownloadControl byteControl(long expectedSize, String label) {
        return new Http.DownloadControl() {
            @Override
            public void onBytes(long bytes) {
                long global = fileBaseBytes + Math.max(0, bytes);
                report(downloadFrac(global),
                        label + " " + mb(bytes) + (expectedSize > 0 ? " / " + mb(expectedSize) : ""));
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /** 当前生效的更新源地址：update-url.txt 优先，其次内置默认。 */
    public String manifestUrl() {
        Path f = appDir.resolve("update-url.txt");
        if (Files.isRegularFile(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) return t;
                }
            } catch (IOException ignored) {
            }
        }
        return DEFAULT_MANIFEST_URL;
    }

    /** 检查更新。返回 null 表示已是最新（或更新源不可用/格式异常）。异常向上抛由调用方提示。 */
    public UpdateInfo check() throws Exception {
        String body = Http.getText(manifestUrl());
        JSONObject o = new JSONObject(body);
        String v = o.optString("version", "").trim();
        if (v.isEmpty() || compare(v, currentVersion) <= 0) return null;
        String notes = o.optString("notes", "");

        // 全量兜底包：优先 urls 数组（主源 + 兜底），兼容旧单 url 字段
        List<String> fullUrls = parseUrls(o, "url", "urls");
        long fullSize = o.optLong("size", 0);
        String fullSha256 = o.optString("sha256", "").trim();

        // jar 增量层（阶段 A）
        List<String> jarUrls = List.of();
        long jarSize = 0;
        String jarSha256 = "";
        JSONObject jar = o.optJSONObject("jar");
        if (jar != null) {
            jarUrls = parseUrls(jar, "url", "urls");
            jarSize = jar.optLong("size", 0);
            jarSha256 = jar.optString("sha256", "").trim();
        }

        List<LayerInfo> pending = filterPending(parseLayers(o));
        List<PartInfo> parts = parseParts(o);

        if (jarUrls.isEmpty() && fullUrls.isEmpty() && parts.isEmpty() && pending.isEmpty()) return null;
        return new UpdateInfo(v, notes, fullUrls, fullSize, fullSha256, jarUrls, jarSize,
                jarSha256, pending, parts);
    }

    /** 解析全量包分卷数组；按 index 升序返回。无该字段或格式异常时返回空表。 */
    private static List<PartInfo> parseParts(JSONObject o) {
        List<PartInfo> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("parts");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            List<String> urls = parseUrls(p, "url", "urls");
            if (urls.isEmpty()) continue;
            int index = p.optInt("index", i + 1);
            String name = p.optString("name", "").trim();
            if (name.isEmpty()) name = "part" + index;
            out.add(new PartInfo(index, name, urls, p.optLong("size", 0),
                    p.optString("sha256", "").trim()));
        }
        out.sort(Comparator.comparingInt(PartInfo::index));
        return out;
    }

    /**
     * 读取候选下载地址列表：优先 {@code arrayKey} 数组（按 manifest 给出的顺序，主源在前），
     * 其次兼容旧的 {@code singleKey} 单字符串字段；去空、去重、保序。
     */
    private static List<String> parseUrls(JSONObject o, String singleKey, String arrayKey) {
        List<String> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray(arrayKey);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (isUsableUrl(s) && !out.contains(s)) out.add(s);
            }
        }
        String single = o.optString(singleKey, "").trim();
        if (isUsableUrl(single) && !out.contains(single)) out.add(single);
        return out;
    }

    /**
     * 过滤占位与非法主机：manifest 里 {@code urls[0]} 仍是模板占位
     * {@code https://你的更新源/...}，该中文主机既无法解析也连不上，若当真源先试一次
     * 会白挂一轮连接超时、扩大失败面。这里在解析阶段直接剔除，只保留可解析的 ASCII 主机。
     */
    private static boolean isUsableUrl(String u) {
        if (u == null || u.isBlank()) return false;
        if (u.contains("你的更新源")) return false;
        try {
            URI uri = URI.create(u);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return false;
            return host.matches("[A-Za-z0-9._\\-:]+");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 仅比对资源层：返回 manifest 中本地缺失、版本/哈希不符或关键文件丢失的层。
     * 主程序已是最新时也可单独调用，后台补齐缺层（无需重启进程）。
     */
    public List<LayerInfo> pendingLayers() throws Exception {
        String body = Http.getText(manifestUrl());
        JSONObject o = new JSONObject(body);
        return filterPending(parseLayers(o));
    }

    /** 解析 manifest 的 {@code layers} 数组；无该字段或格式异常时返回空表。 */
    private static List<LayerInfo> parseLayers(JSONObject o) {
        List<LayerInfo> out = new ArrayList<>();
        JSONArray arr = o.optJSONArray("layers");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject l = arr.optJSONObject(i);
            if (l == null) continue;
            String name = l.optString("name", "").trim();
            List<String> urls = parseUrls(l, "url", "urls");
            if (name.isEmpty() || urls.isEmpty()) continue;
            out.add(new LayerInfo(name,
                    l.optString("version", "").trim(),
                    urls,
                    l.optLong("size", 0),
                    l.optString("sha256", "").trim()));
        }
        return out;
    }

    /** 与本地 layers.json 及外置目录关键文件比对，筛出需要下载的层。 */
    private List<LayerInfo> filterPending(List<LayerInfo> all) {
        Map<String, JSONObject> local = readLocalLayers();
        List<LayerInfo> out = new ArrayList<>();
        for (LayerInfo layer : all) {
            JSONObject saved = local.get(layer.name());
            boolean shaMatch = saved != null
                    && layer.sha256().equalsIgnoreCase(saved.optString("sha256", ""));
            boolean present = keyFilesPresent(layer);
            // 记录哈希一致且文件就位：跳过。
            if (shaMatch && present) continue;
            // 登记缺失（历史释放、layers.json 丢失）但关键文件已就位：不强制重下整层，
            // 避免资源内容没变却因 version 跟随启动器版本被判定过期而全量重下约百 MB。
            if (present && saved == null) continue;
            // 哈希不符或关键文件缺失：需要下载覆盖。
            out.add(layer);
        }
        return out;
    }

    /** 外置资源根目录（exe 同级 resources/）。 */
    private Path resourcesRoot() {
        return appDir.resolve(RESOURCES_DIR);
    }

    /** 读取本地 layers.json；损坏或不存在时返回空表。 */
    private Map<String, JSONObject> readLocalLayers() {
        Map<String, JSONObject> out = new HashMap<>();
        Path f = resourcesRoot().resolve(LAYERS_FILE);
        if (!Files.isRegularFile(f)) return out;
        try {
            JSONObject o = new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
            for (String k : o.keySet()) {
                JSONObject v = o.optJSONObject(k);
                if (v != null) out.put(k, v);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 把本地层记录写回 layers.json。 */
    private void writeLocalLayers(Map<String, JSONObject> local) {
        try {
            Path root = resourcesRoot();
            Files.createDirectories(root);
            Files.writeString(root.resolve(LAYERS_FILE), new JSONObject(local).toString(2), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** 该层关键文件是否都已在外置目录就位；无外置根（直跑/开发态）时不强制判定。 */
    private boolean keyFilesPresent(LayerInfo layer) {
        Path root = resourcesRoot();
        if (!Files.isDirectory(root)) return false;
        if ("music".equals(layer.name())) {
            Path dir = root.resolve("music");
            if (!Files.isDirectory(dir)) return false;
            try (var s = Files.list(dir)) {
                return s.anyMatch(p -> isAudio(p.getFileName().toString()));
            } catch (IOException e) {
                return false;
            }
        }
        String[] keys = LAYER_KEY_FILES.get(layer.name());
        if (keys == null) return true;
        for (String k : keys) {
            if (!Files.isRegularFile(root.resolve(k))) return false;
        }
        return true;
    }

    private static boolean isAudio(String name) {
        String l = name.toLowerCase();
        return l.endsWith(".mp3") || l.endsWith(".m4a") || l.endsWith(".aac") || l.endsWith(".wav");
    }

    /**
     * 下载并补齐资源层：逐层校验大小/sha256 → 解压到外置资源根 → 更新 layers.json。
     * 层资源在用到时才读取，补齐后无需重启进程；主版本更新流程会先调本方法再替换 jar。
     */
    public void applyLayers(List<LayerInfo> layers) throws Exception {
        if (layers == null || layers.isEmpty()) return;
        UPDATE_LOCK.lock();
        try {
            Path root = resourcesRoot();
            Files.createDirectories(root);
            Path tmp = appDir.resolve(".jwh-update");
            Files.createDirectories(tmp);
            cleanStaleLayers(tmp);
            Map<String, JSONObject> local = readLocalLayers();
            this.layerTotal = layers.size();
            report("正在检查并更新资源层…");
            for (LayerInfo layer : layers) {
                if (layer.hasUrl()) applyOneLayer(layer, root, tmp, local);
            }
        } finally {
            UPDATE_LOCK.unlock();
        }
    }

    /** 下载、校验、解压并登记单个资源层。 */
    private void applyOneLayer(LayerInfo layer, Path root, Path tmp, Map<String, JSONObject> local) throws Exception {
        Path zip = tmp.resolve("layer-" + layer.name() + "-" + layer.version() + ".zip");
        report("正在更新资源层 " + layer.name() + "…");
        beginFile(layer.size());
        boolean ok = com.jwh.launcher.download.Downloader.fetch(zip,
                layer.size() > 0 ? layer.size() : -1,
                com.jwh.launcher.download.ArtifactProbe.Kind.ZIP, "资源层 " + layer.name(),
                byteControl(layer.size(), "资源层 " + layer.name()),
                com.jwh.launcher.download.Downloader.Policy.of(60, 300),
                layer.urls().toArray(new String[0]));
        if (!ok) throw new IOException("资源层 " + layer.name() + " 下载失败，请检查网络后重试。");

        if (layer.size() > 0) {
            long actual = Files.size(zip);
            if (actual != layer.size()) {
                throw new IOException("资源层 " + layer.name() + " 大小不符（应为 " + layer.size()
                        + " 字节，实际 " + actual + "），下载可能不完整。");
            }
        }
        if (layer.sha256() != null && !layer.sha256().isBlank()) {
            String actual = sha256Hex(zip);
            if (!actual.equalsIgnoreCase(layer.sha256().trim())) {
                throw new IOException("资源层 " + layer.name() + " 校验值不符，文件可能已损坏，已取消更新。");
            }
        }
        endFile(layer.size());

        report("正在解压资源层 " + layer.name() + "…");
        Path extract = tmp.resolve("layer-" + layer.name() + "-" + layer.version());
        unzipSafe(zip, extract);
        // 覆盖落地前先暂停并释放背景音乐解码器，松开正被占用的外置 mp3；
        // gstreamer 句柄异步释放，留一小段等待窗口，随后的复制再带占用重试兜底。
        releaseAudio();
        sleepQuietly(250);
        copyTree(extract, root);

        JSONObject saved = new JSONObject();
        saved.put("version", layer.version());
        saved.put("sha256", layer.sha256() == null ? "" : layer.sha256());
        local.put(layer.name(), saved);
        writeLocalLayers(local);

        deleteQuietly(extract);
        try { Files.deleteIfExists(zip); } catch (IOException ignored) { }
        layerDone++;
        double layerFrac = DOWNLOAD_WEIGHT + 0.05 * (layerTotal > 0 ? (double) layerDone / layerTotal : 1.0);
        report(Math.min(0.90, layerFrac), "资源层 " + layer.name() + " 已就绪。");
    }

    /** 单个文件覆盖：占用重试次数与间隔（等待解码器异步释放句柄）。 */
    private static final int COPY_RETRIES = 6;
    private static final long COPY_RETRY_MS = 250;

    /** 递归复制解压目录到目标根（覆盖同名文件）。 */
    private static void copyTree(Path src, Path dst) throws IOException {
        try (var walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.equals(src)) continue;
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    copyWithRetry(p, target);
                }
            }
        }
    }

    /**
     * 先写到同目录临时文件再替换目标，避免半截内容覆盖旧文件；目标被占用（背景音乐解码器
     * 句柄尚未释放）时按占用类异常有限次重试等待，仍失败才抛出带目标文件名的清晰错误。
     */
    private static void copyWithRetry(Path src, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= COPY_RETRIES; attempt++) {
            Path tmp = target.resolveSibling(target.getFileName() + ".jwh-copy.tmp");
            try {
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                deleteQuietly(tmp);
                last = e;
                if (attempt < COPY_RETRIES && isLockLike(e)) {
                    sleepQuietly(COPY_RETRY_MS);
                } else {
                    break;
                }
            }
        }
        throw new IOException("无法写入文件 " + target + "：" + last.getMessage(), last);
    }

    /** 判断异常是否为 Windows 文件被占用 / 锁定（值得等待重试）。 */
    private static boolean isLockLike(IOException e) {
        String m = e.getMessage();
        if (m == null) return false;
        String low = m.toLowerCase();
        return m.contains("另一进程") || m.contains("另一个程序") || m.contains("另一个进程")
                || m.contains("being used") || low.contains("locked") || low.contains("sharing violation");
    }

    /** 静默睡眠；被中断时恢复中断标记并尽快返回。 */
    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 静默删除目录/文件（层临时目录清理用）。 */
    private static void deleteQuietly(Path p) {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                try { Files.deleteIfExists(q); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * 清理上次崩溃/中断残留的临时层包、解压目录与分卷目录。加锁后同一时刻只有一个写流程，
     * 流程开头删除旧残片是安全的，可避免半截 zip 被当成有效包、或旧解压目录混入新资源。
     */
    private static void cleanStaleLayers(Path tmp) {
        if (!Files.isDirectory(tmp)) return;
        try (var s = Files.list(tmp)) {
            s.forEach(q -> {
                String n = q.getFileName().toString();
                if (n.startsWith("layer-") || n.startsWith("parts-") || n.startsWith("extract-")) {
                    deleteQuietly(q);
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 解压层包到 out，条目路径必须落在 out 内（防压缩包越界）。 */
    private static void unzipSafe(Path zip, Path out) throws IOException {
        deleteQuietly(out);
        Files.createDirectories(out);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Path target = out.resolve(e.getName()).normalize();
                if (!target.startsWith(out.normalize())) {
                    throw new IOException("资源层包含越界路径条目（" + e.getName() + "），已拒绝解压。");
                }
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 下载并应用更新：优先 jar 增量替换主程序 jar；无 jar 层时回退全量 zip 覆盖。
     * 校验通过后写更新脚本并启动，脚本关闭本进程后完成覆盖、清理临时文件并重启新版本。
     */
    public void downloadAndApply(UpdateInfo info) throws Exception {
        UPDATE_LOCK.lock();
        try {
            Path tmp = appDir.resolve(".jwh-update");
            Files.createDirectories(tmp);
            cleanStaleLayers(tmp);
            initTotalBytes(info);
            report(downloadFrac(0), "正在准备更新…");

            // 先补齐/更新资源层（外置大文件），再替换程序 jar；层资源不被运行中的 jar 占用，可直接落盘。
            if (info.hasLayers()) {
                applyLayers(info.layers());
            }

            Path mainJar = appDir.resolve(MAIN_JAR_REL);
            boolean packaged = Files.isRegularFile(mainJar);

            if (info.hasJar()) {
                // 打包环境（存在 app/JWH-Launcher.jar）才走 jar 增量；开发/直跑环境退回全量兜底。
                if (packaged) {
                    applyJarUpdate(info, tmp, mainJar);
                    return;
                }
                // 直跑环境没有可覆盖的打包 jar：若 manifest 同时给了全量包（单包或分卷）则走全量，否则明确报错。
                if (info.url().isEmpty() && !info.hasParts()) {
                    throw new IOException("当前为开发/直跑环境，缺少打包目录 " + MAIN_JAR_REL
                            + "，无法执行 jar 增量更新；请发布全量包后再更新。");
                }
            }
            if (info.url().isEmpty() && !info.hasParts()) {
                throw new IOException("更新清单未提供可用的更新包地址。");
            }
            applyFullZipUpdate(info, tmp);
        } finally {
            UPDATE_LOCK.unlock();
        }
    }

    /** jar 增量：下载主程序 jar → 校验大小/sha256 → 启动替换脚本。 */
    private void applyJarUpdate(UpdateInfo info, Path tmp, Path mainJar) throws Exception {
        Path jar = tmp.resolve("update-" + info.version() + ".jar");
        report("正在下载启动器程序…");
        beginFile(info.jarSize());
        boolean ok = com.jwh.launcher.download.Downloader.fetch(jar,
                info.jarSize() > 0 ? info.jarSize() : -1,
                com.jwh.launcher.download.ArtifactProbe.Kind.JAR, "启动器程序",
                byteControl(info.jarSize(), "启动器程序"),
                com.jwh.launcher.download.Downloader.Policy.of(60, 300),
                info.jarUrls().toArray(new String[0]));
        if (!ok) throw new IOException("程序 jar 下载失败，请检查网络后重试，或手动下载新版本覆盖。");

        if (info.jarSize() > 0) {
            long actual = Files.size(jar);
            if (actual != info.jarSize()) {
                throw new IOException("程序 jar 大小不符（应为 " + info.jarSize()
                        + " 字节，实际 " + actual + "），下载可能不完整。");
            }
        }
        if (info.jarSha256() != null && !info.jarSha256().isBlank()) {
            String actual = sha256Hex(jar);
            if (!actual.equalsIgnoreCase(info.jarSha256().trim())) {
                throw new IOException("程序 jar 校验值不符，文件可能已损坏，已取消更新。");
            }
        }
        endFile(info.jarSize());
        // 同步刷新 jpackage 启动配置里记录的旧版本号，避免重启后 exe 仍按打包快照注入旧版本。
        refreshPackageVersion(info.version());
        report(0.95, "校验通过，正在准备替换程序…");
        reportNum(1.0);
        launchJarUpdateScript(info.version(), tmp, mainJar);
    }

    /**
     * jar 增量替换后，同步刷新 jpackage 启动配置里记录的旧版本号：
     * {@code app/*.cfg} 的 {@code -Djpackage.app-version} 与 {@code app/.jpackage.xml} 的
     * {@code <app-version>}。否则 exe 重启仍按打包快照注入旧版本系统属性，界面版本号与更新比对
     * 停留在旧版，反复弹更新。文件缺失或未匹配到旧版本时静默跳过，不阻断 jar 替换主流程。
     */
    private void refreshPackageVersion(String version) {
        if (version == null || version.isBlank()) return;
        try {
            Path app = appDir.resolve("app");
            if (!Files.isDirectory(app)) return;

            // 1. cfg：java-options=-Djpackage.app-version=1.0.4
            try (var stream = Files.list(app)) {
                for (Path cfg : (Iterable<Path>) stream::iterator) {
                    String name = cfg.getFileName().toString();
                    if (!Files.isRegularFile(cfg) || !name.endsWith(".cfg")) continue;
                    String text = Files.readString(cfg, StandardCharsets.UTF_8);
                    String updated = text.replaceAll("(-Djpackage\\.app-version=)\\S+", "$1" + version);
                    if (!updated.equals(text)) {
                        Files.writeString(cfg, updated, StandardCharsets.UTF_8);
                    }
                }
            }

            // 2. .jpackage.xml：<app-version>1.0.4</app-version>
            Path xml = app.resolve(".jpackage.xml");
            if (Files.isRegularFile(xml)) {
                String text = Files.readString(xml, StandardCharsets.UTF_8);
                String updated = text.replaceAll("(<app-version>)\\S+(</app-version>)",
                        "$1" + version + "$2");
                if (!updated.equals(text)) {
                    Files.writeString(xml, updated, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
            // 配置刷新失败不回滚 jar 替换；版本号仍可由主 jar 内 version.properties 兜底。
        }
    }

    /** 全量兜底：单包直接下载；分卷则逐卷下载校验后字节合并 → 结构/整体哈希校验 → 解压 → 覆盖脚本。 */
    private void applyFullZipUpdate(UpdateInfo info, Path tmp) throws Exception {
        Path zip;
        if (info.hasParts()) {
            zip = assembleParts(info, tmp);
        } else {
            zip = tmp.resolve("update-" + info.version() + ".zip");
            report("正在下载完整更新包…");
            beginFile(info.size());
            boolean ok = com.jwh.launcher.download.Downloader.fetch(zip,
                    info.size() > 0 ? info.size() : -1,
                    com.jwh.launcher.download.ArtifactProbe.Kind.ZIP, "完整更新包",
                    byteControl(info.size(), "完整更新包"),
                    com.jwh.launcher.download.Downloader.Policy.of(60, 300),
                    info.fullUrls().toArray(new String[0]));
            if (!ok) throw new IOException("更新包下载失败，请检查网络后重试，或手动下载新版本覆盖。");
            if (info.size() > 0) {
                long actual = Files.size(zip);
                if (actual != info.size()) {
                    throw new IOException("更新包大小不符（应为 " + info.size() + " 字节，实际 " + actual + "），下载可能不完整。");
                }
            }
            endFile(info.size());
        }

        // 合并产物（及单包）统一做 ZIP 结构校验，避免错位拼接的伪包进入解压步骤。
        com.jwh.launcher.download.ArtifactProbe.Verdict verdict =
                com.jwh.launcher.download.ArtifactProbe.check(zip,
                        com.jwh.launcher.download.ArtifactProbe.Kind.ZIP,
                        info.size() > 0 ? info.size() : -1);
        if (!verdict.ok()) {
            throw new IOException("更新包结构校验失败（" + verdict.reason() + "），文件可能已损坏，已取消更新。");
        }
        if (info.fullSha256() != null && !info.fullSha256().isBlank()) {
            if (!sha256Hex(zip).equalsIgnoreCase(info.fullSha256().trim())) {
                throw new IOException("更新包整体校验值不符，文件可能已损坏，已取消更新。");
            }
        }
        Path extract = tmp.resolve("extract-" + info.version());
        report(0.95, "正在解压更新包…");
        unzip(zip, extract);
        reportNum(1.0);
        launchFullUpdateScript(info.version(), extract);
    }

    /** 逐卷下载、校验分卷大小/哈希，按 index 升序字节合并为完整 zip；合并后删除分卷释放空间。 */
    private Path assembleParts(UpdateInfo info, Path tmp) throws Exception {
        List<PartInfo> parts = info.parts();
        Path partsDir = tmp.resolve("parts-" + info.version());
        Files.createDirectories(partsDir);
        Path merged = tmp.resolve("update-" + info.version() + ".zip");
        long total = 0;
        try (OutputStream out = Files.newOutputStream(merged,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int totalParts = parts.size();
            for (PartInfo p : parts) {
                Path partFile = partsDir.resolve(p.name());
                report("正在下载更新分卷 " + p.index() + "/" + totalParts + "…");
                beginFile(p.size());
                boolean ok = com.jwh.launcher.download.Downloader.fetch(partFile,
                        p.size() > 0 ? p.size() : -1,
                        com.jwh.launcher.download.ArtifactProbe.Kind.BINARY,
                        "分卷 " + p.index() + "/" + totalParts,
                        byteControl(p.size(), "分卷 " + p.index() + "/" + totalParts),
                        com.jwh.launcher.download.Downloader.Policy.of(60, 300),
                        p.urls().toArray(new String[0]));
                if (!ok) {
                    throw new IOException("更新分卷 " + p.index() + "（" + p.name()
                            + "）下载失败，请检查网络后重试。");
                }
                if (p.size() > 0 && Files.size(partFile) != p.size()) {
                    throw new IOException("分卷 " + p.index() + " 大小不符（应为 " + p.size()
                            + " 字节，实际 " + Files.size(partFile) + "），下载可能不完整。");
                }
                if (p.sha256() != null && !p.sha256().isBlank()) {
                    if (!sha256Hex(partFile).equalsIgnoreCase(p.sha256().trim())) {
                        throw new IOException("分卷 " + p.index() + " 校验值不符，文件可能已损坏，已取消更新。");
                    }
                }
                endFile(p.size());
                Files.copy(partFile, out);
                total += Files.size(partFile);
                try { Files.deleteIfExists(partFile); } catch (IOException ignored) { }
            }
        }
        if (info.size() > 0 && total != info.size()) {
            throw new IOException("合并后大小不符（应为 " + info.size() + " 字节，实际 " + total + "）。");
        }
        return merged;
    }

    /**
     * jar 替换脚本：关闭进程 → 备份旧 jar → 覆盖新 jar（失败自动回滚）→ 清理临时目录 → 重启。
     * 脚本只用 ASCII（路径均相对本脚本目录，规避中文/GBK 乱码）。
     */
    private void launchJarUpdateScript(String version, Path tmp, Path mainJar) throws IOException {
        Path script = appDir.resolve("jwh-update.bat");
        Files.writeString(script, buildJarBat(version), StandardCharsets.UTF_8);
        new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.toAbsolutePath().toString()).start();
    }

    /** 生成 jar 增量替换脚本文本（抽成静态方法便于离线审查路径与命令，不启动进程）。 */
    static String buildJarBat(String version) {
        String relJar = MAIN_JAR_REL.replace('/', '\\');
        String relNew = ".jwh-update\\update-" + version + ".jar";
        String relPrev = ".jwh-update\\prev.jar";
        return "@echo off\r\n"
                + "cd /d \"%~dp0\"\r\n"
                + "echo " + System.currentTimeMillis() + " jar-start > \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "timeout /t 2 /nobreak >nul\r\n"
                + "taskkill /f /im JWH-Launcher.exe >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "timeout /t 1 /nobreak >nul\r\n"
                + "copy /y /b \"" + relJar + "\" \"" + relPrev + "\" >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "copy /y /b \"" + relNew + "\" \"" + relJar + "\" >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "if errorlevel 1 (\r\n"
                + "  echo ROLLBACK >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "  copy /y /b \"" + relPrev + "\" \"" + relJar + "\" >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "  rd /s /q \".jwh-update\" >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "  start \"\" \"JWH-Launcher.exe\"\r\n"
                + "  del \"%~f0\"\r\n"
                + "  exit /b 1\r\n"
                + ")\r\n"
                + "rd /s /q \".jwh-update\" >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "start \"\" \"JWH-Launcher.exe\"\r\n"
                + "del \"%~f0\"\r\n";
    }

    /** 全量覆盖脚本：关闭进程 → 解压内容覆盖整目录 → 清理临时目录 → 重启。 */
    private void launchFullUpdateScript(String version, Path extract) throws IOException {
        Path script = appDir.resolve("jwh-update.bat");
        Files.writeString(script, buildFullBat(version), StandardCharsets.UTF_8);
        new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.toAbsolutePath().toString()).start();
    }

    /** 生成全量覆盖脚本文本（抽成静态方法便于离线审查）。 */
    static String buildFullBat(String version) {
        return "@echo off\r\n"
                + "cd /d \"%~dp0\"\r\n"
                + "echo " + System.currentTimeMillis() + " start > \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "timeout /t 2 /nobreak >nul\r\n"
                + "taskkill /f /im JWH-Launcher.exe >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "timeout /t 1 /nobreak >nul\r\n"
                + "xcopy /e /y /q /h \".jwh-update\\extract-" + version + "\\*\" \".\" >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "rd /s /q \".jwh-update\" >> \"%TEMP%\\jwh-update.log\" 2>&1\r\n"
                + "start \"\" \"JWH-Launcher.exe\"\r\n"
                + "del \"%~f0\"\r\n";
    }

    private static void unzip(Path zip, Path out) throws IOException {
        if (Files.exists(out)) {
            try (var walk = Files.walk(out)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
        Files.createDirectories(out);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Path target = out.resolve(e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 计算文件 SHA-256，返回小写十六进制串。 */
    private static String sha256Hex(Path p) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 摘要算法不可用", e);
        }
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 版本号比较：1.6.141 > 1.6.140；返回 a 与 b 的关系。 */
    static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = i < pa.length ? pa[i].trim() : "0";
            String y = i < pb.length ? pb[i].trim() : "0";
            long nx, ny;
            try {
                nx = Long.parseLong(x);
            } catch (Exception e) {
                nx = -1;
            }
            try {
                ny = Long.parseLong(y);
            } catch (Exception e) {
                ny = -1;
            }
            if (nx >= 0 && ny >= 0) {
                if (nx != ny) return nx < ny ? -1 : 1;
            } else {
                int c = x.compareTo(y);
                if (c != 0) return c < 0 ? -1 : 1;
            }
        }
        return 0;
    }
}
