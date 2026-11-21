package com.jwh.launcher.loader;

import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.game.GameInstaller;
import com.jwh.launcher.game.LocalVersions;
import com.jwh.launcher.game.VersionManifest;
import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fabric / Quilt 加载器安装。
 * 原理：从 fabric-meta / quilt-meta 拉取现成的 launcher profile JSON，
 * 下载原版主 jar 到加载器版本目录，再由 GameInstaller 补齐 libraries 与 assets。
 */
public class FabricQuiltInstaller {

    private final LauncherConfig config;

    public FabricQuiltInstaller(LauncherConfig config) {
        this.config = config;
    }

    /** 平台枚举。 */
    public enum Platform { FABRIC, QUILT }

    private String metaBase(Platform p) {
        return p == Platform.FABRIC
                ? "https://meta.fabricmc.net/v2"
                : "https://meta.quiltmc.org/v3";
    }

    /** 列表缓存目录（启动器根目录/jwh-cache）。 */
    private Path cacheDir() throws Exception {
        Path dir = config.versionsPath().getParent().resolve("jwh-cache");
        Files.createDirectories(dir);
        return dir;
    }

    /** meta 基地址。Fabric 官方源国内直连极不稳定（常 25s 连接超时、3 次重试 75s 后才失败），
     *  而 BMCLAPI fabric-meta 镜像国内秒开且数据与官方同步（实测 0.20.1 一致），
     *  故镜像优先、官方兜底。Quilt 无镜像，仅官方。 */
    private static java.util.List<String> metaBases(Platform p) {
        if (p == Platform.FABRIC) {
            return java.util.List.of(
                    "https://bmclapi2.bangbang93.com/fabric-meta/v2",
                    "https://meta.fabricmc.net/v2");
        }
        return java.util.List.of("https://meta.quiltmc.org/v3");
    }

    /** 读取加载器版本列表原始 JSON：优先读本地缓存（6 小时有效），force=true 时强制重新拉取。
     *  带游戏版本号时使用 /versions/loader/{mc}（只含该版本适配的加载器，嵌套结构，Fabric/Quilt 均如此），
     *  避免用户选中不适配的组合导致安装 404。
     *  官方 meta 超时/失败时（国内网络对 meta.fabricmc.net 直连极不稳定）自动切换 BMCLAPI 镜像；
     *  全部不可达返回 null，由 listLoaders 走 maven-metadata.xml 兜底。 */
    private String cachedLoaderList(Platform p, boolean force, String mcVersion) throws Exception {
        String tag = (p == Platform.FABRIC ? "fabric" : "quilt")
                + (mcVersion == null ? "" : "-" + mcVersion.replace('.', '_'));
        Path f = cacheDir().resolve("loader-list-" + tag + ".json");
        if (!force && Files.exists(f)) {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis();
            if (age < 6 * 3600 * 1000L) return Files.readString(f);
        }
        Exception last = null;
        for (String base : metaBases(p)) {
            String url = base + "/versions/loader" + (mcVersion == null ? "" : "/" + mcVersion);
            try {
                String body = Http.getText(url);
                Files.writeString(f, body);
                return body;
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null && p == Platform.QUILT) {
            // Quilt 无镜像：明确抛出，调用方会看到真实原因
            throw last;
        }
        return null;
    }

    /** 查询加载器版本列表（按版本号降序）。返回 [{version, stable}]。 */
    public java.util.List<String[]> listLoaders(Platform p, String mcVersion, boolean force) throws Exception {
        String body = cachedLoaderList(p, force, mcVersion);
        if (body == null) {
            // meta（官方+镜像）全不可达 → maven-metadata.xml 兜底（Fabric 走国内镜像优先）
            return listLoadersFromMaven(p);
        }
        JSONArray arr = new JSONArray(body);
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String ver = o.optString("version", "");
            boolean stable = o.optBoolean("stable", false);
            if (ver.isEmpty()) {
                // 兼容 /versions/loader/{mc} 的嵌套结构（Fabric/Quilt 均如此）
                JSONObject loader = o.optJSONObject("loader");
                ver = loader == null ? "" : loader.optString("version", "");
                stable = loader != null && loader.optBoolean("stable", false);
            }
            if (ver.isEmpty()) continue;
            if (p == Platform.QUILT) {
                // Quilt meta v3 不提供 stable 字段：版本号不含 beta/alpha/pre 视为稳定版
                stable = !ver.matches("(?i).*(beta|alpha|pre).*");
            }
            out.add(new String[]{ver, stable ? "稳定版" : "测试版"});
        }
        out.sort((a, b) -> compareLoader(b[0], a[0]));
        return out;
    }

    /** meta 全不可达时的最终兜底：从 maven-metadata.xml 解析版本列表（无 stable 标记，按版本名判断）。 */
    private java.util.List<String[]> listLoadersFromMaven(Platform p) throws Exception {
        String[] urls;
        if (p == Platform.FABRIC) {
            urls = new String[]{
                    "https://bmclapi2.bangbang93.com/maven/net/fabricmc/fabric-loader/maven-metadata.xml",
                    "https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml"};
        } else {
            urls = new String[]{
                    "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/maven-metadata.xml"};
        }
        IOException last = null;
        for (String u : urls) {
            try {
                String body = Http.getText(u);
                java.util.regex.Pattern pat = java.util.regex.Pattern.compile("<version>(.*?)</version>");
                java.util.regex.Matcher m = pat.matcher(body);
                java.util.List<String[]> out = new java.util.ArrayList<>();
                while (m.find()) {
                    String ver = m.group(1).trim();
                    if (ver.isEmpty()) continue;
                    boolean stable = !ver.matches("(?i).*(beta|alpha|pre|rc|snapshot).*");
                    out.add(new String[]{ver, stable ? "稳定版" : "测试版"});
                }
                out.sort((a, b) -> compareLoader(b[0], a[0]));
                return out;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    /** 适配过滤结果内存缓存（30 分钟），避免每次打开下拉都重测 profile。 */
    private static final java.util.Map<String, java.util.List<String[]>> ADAPT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> ADAPT_CACHE_TIME = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * UI 用：只返回「官方确认为该游戏版本生成 profile」的加载器版本（最新最多 40 个）。
     * Quilt meta 的 /versions/loader/{mc} 实际返回全部加载器（不过滤），
     * 若把不适配的版本展示给用户，下载时才会 404。这里并行探测 profile 存在性做过滤，
     * 保证下拉列表里每个版本都能直接下载安装。
     */
    public java.util.List<String[]> listLoadersAdapted(Platform p, String mcVersion, boolean force) throws Exception {
        String cacheKey = (p == Platform.FABRIC ? "fabric" : "quilt") + "-" + mcVersion;
        if (!force) {
            Long t = ADAPT_CACHE_TIME.get(cacheKey);
            if (t != null && System.currentTimeMillis() - t < 30 * 60 * 1000L) {
                java.util.List<String[]> hit = ADAPT_CACHE.get(cacheKey);
                if (hit != null) return hit;
            }
            // 磁盘缓存（6 小时）：重启后下载中心打开秒出，不用重新并行探测
            Path ac = cacheDir().resolve("loader-adapted-" + cacheKey + ".json");
            if (Files.exists(ac)) {
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(ac).toMillis();
                if (age < 6 * 3600 * 1000L) {
                    java.util.List<String[]> hit = parseAdapted(Files.readString(ac));
                    if (!hit.isEmpty()) {
                        ADAPT_CACHE.put(cacheKey, hit);
                        ADAPT_CACHE_TIME.put(cacheKey, System.currentTimeMillis());
                        return hit;
                    }
                }
            }
        }
        var all = listLoaders(p, mcVersion, force);
        int maxCheck = Math.min(all.size(), 90);
        var head = all.subList(0, maxCheck);
        boolean[] ok = new boolean[head.size()];
        String base = metaBase(p);
        int cores = Math.max(4, Runtime.getRuntime().availableProcessors());
        cores = Math.min(cores, 12);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(cores);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(head.size());
        for (int i = 0; i < head.size(); i++) {
            final int idx = i;
            final String ver = head.get(i)[0];
            pool.submit(() -> {
                try {
                    // 两次机会：meta 偶发抖动会误杀，重试一次
                    boolean exists = Http.exists(base + "/versions/loader/" + mcVersion + "/" + ver + "/profile/json");
                    if (!exists) {
                        try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                        exists = Http.exists(base + "/versions/loader/" + mcVersion + "/" + ver + "/profile/json");
                    }
                    if (exists) ok[idx] = true;
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(25, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdownNow();
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < head.size(); i++) {
            if (ok[i]) out.add(head.get(i));
        }
        if (out.size() > 40) out = out.subList(0, 40);
        try {
            StringBuilder sb = new StringBuilder();
            for (String[] s : out) sb.append(s[0]).append('\t').append(s[1]).append('\n');
            Files.writeString(cacheDir().resolve("loader-adapted-" + cacheKey + ".json"), sb.toString());
        } catch (Exception ignored) {
        }
        ADAPT_CACHE.put(cacheKey, out);
        ADAPT_CACHE_TIME.put(cacheKey, System.currentTimeMillis());
        return out;
    }

    /** 解析磁盘缓存的适配结果（每行 版本\t标记）。 */
    private static java.util.List<String[]> parseAdapted(String body) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String[] lines = body.split("\\r?\\n");
        for (String line : lines) {
            if (line.isBlank()) continue;
            int tab = line.indexOf('\t');
            String ver = tab < 0 ? line : line.substring(0, tab);
            String tag = tab < 0 ? "稳定版" : line.substring(tab + 1);
            if (!ver.isEmpty()) out.add(new String[]{ver, tag});
        }
        return out;
    }

    /** 加载器本体 jar 的下载地址候选（老格式 maven 仓库）：镜像优先，官方兜底。
     *  注意：Fabric 的 group 是 net.fabricmc（仓库路径 net/fabricmc），
     *  曾误写 org/fabricmc 导致镜像 404 + 官方直连超时，jar 探测永远失败，这里已修正。 */
    private java.util.List<String> loaderJarUrls(Platform p, String loaderVersion) {
        String artifact = p == Platform.FABRIC ? "fabric-loader" : "quilt-loader";
        String rel = (p == Platform.FABRIC ? "net/fabricmc/" : "org/quiltmc/") + artifact + "/"
                + loaderVersion + "/" + artifact + "-" + loaderVersion + ".jar";
        if (p == Platform.FABRIC) {
            return java.util.List.of(
                    "https://bmclapi2.bangbang93.com/maven/" + rel,
                    "https://maven.fabricmc.net/" + rel);
        }
        // Quilt 无国内镜像，仅官方源（maven.quiltmc.org/repository/release/）
        return java.util.List.of("https://maven.quiltmc.org/repository/release/" + rel);
    }

    /** 任一候选源存在即视为可用（镜像偶发 404 时回退官方）。 */
    private boolean loaderJarExists(Platform p, String loaderVersion) {
        for (String u : loaderJarUrls(p, loaderVersion)) {
            if (Http.exists(u)) return true;
        }
        return false;
    }

    /**
     * meta 对组合长期 404 时的最后兜底：
     * 自组装 Quilt profile（模板 = Quilt 0.31.0-beta.3 的库清单，替换 loader/mc 版本）。
     * Quilt 依赖库（asm/quilt-json5/quilt-config）在 0.30~0.31 系列保持稳定，
     * sponge-mixin 使用 0.17.x 系列；生成的 json 为启动器原生支持的老格式。
     */
    private String assembleQuiltProfile(String mcVersion, String loaderVersion) {
        JSONObject t = new JSONObject();
        t.put("id", "quilt-loader-" + loaderVersion + "-" + mcVersion);
        t.put("inheritsFrom", mcVersion);
        t.put("type", "release");
        t.put("mainClass", "org.quiltmc.loader.impl.launch.knot.KnotClient");
        t.put("arguments", new JSONObject().put("game", new JSONArray()));
        JSONArray libs = new JSONArray();
        String quiltRepo = "https://maven.quiltmc.org/repository/release/";
        String fabricRepo = "https://maven.fabricmc.net/";
        libs.put(new JSONObject().put("name", "net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7").put("url", fabricRepo));
        libs.put(new JSONObject().put("name", "org.quiltmc:quilt-json5:1.0.4+final").put("url", quiltRepo));
        libs.put(new JSONObject().put("name", "org.ow2.asm:asm:9.10.1").put("url", fabricRepo));
        libs.put(new JSONObject().put("name", "org.ow2.asm:asm-analysis:9.10.1").put("url", fabricRepo));
        libs.put(new JSONObject().put("name", "org.ow2.asm:asm-commons:9.10.1").put("url", fabricRepo));
        libs.put(new JSONObject().put("name", "org.ow2.asm:asm-tree:9.10.1").put("url", fabricRepo));
        libs.put(new JSONObject().put("name", "org.ow2.asm:asm-util:9.10.1").put("url", fabricRepo));
        libs.put(new JSONObject().put("name", "org.quiltmc:quilt-config:1.3.3").put("url", quiltRepo));
        libs.put(new JSONObject().put("name", "org.quiltmc:quilt-loader:" + loaderVersion).put("url", quiltRepo));
        libs.put(new JSONObject().put("name", "org.quiltmc:hashed:" + mcVersion).put("url", quiltRepo));
        libs.put(new JSONObject().put("name", "net.fabricmc:intermediary:" + mcVersion).put("url", fabricRepo));
        t.put("libraries", libs);
        return t.toString();
    }

    /** meta（官方+镜像）全不可达时的最后兜底：自组装 Fabric profile（结构同官方 meta 生成）。
     *  原版主 jar 由 install 单独下载到版本目录，profile 只声明加载器相关库。 */
    private String assembleFabricProfile(String mcVersion, String loaderVersion) {
        JSONObject t = new JSONObject();
        t.put("id", "fabric-loader-" + loaderVersion + "-" + mcVersion);
        t.put("inheritsFrom", mcVersion);
        t.put("type", "release");
        t.put("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient");
        t.put("arguments", new JSONObject().put("game", new JSONArray()));
        JSONArray libs = new JSONArray();
        String fabricRepo = "https://maven.fabricmc.net/";
        libs.put(new JSONObject().put("name", "net.fabricmc:sponge-mixin:" + mixinFor(loaderVersion)).put("url", fabricRepo));
        libs.put(new JSONObject().put("name", "net.fabricmc:fabric-loader:" + loaderVersion).put("url", fabricRepo));
        libs.put(new JSONObject().put("name", "net.fabricmc:intermediary:" + mcVersion).put("url", fabricRepo));
        t.put("libraries", libs);
        return t.toString();
    }

    /** Fabric loader 版本 → 配套 sponge-mixin 版本（loader 各主版本对应的 mixin 序列）。 */
    private static String mixinFor(String loaderVersion) {
        if (loaderVersion.startsWith("0.16")) return "0.15.4+mixin.0.8.7";
        if (loaderVersion.startsWith("0.15")) return "0.13.4+mixin.0.8.5";
        if (loaderVersion.startsWith("0.14")) return "0.13.4+mixin.0.8.5";
        return "0.11.4+mixin.0.8.5";
    }

    /** 点分版本号降序比较。 */
    private static int compareLoader(String a, String b) {
        String[] pa = a.split("[.-]"), pb = b.split("[.-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? numPart(pa[i]) : 0;
            int y = i < pb.length ? numPart(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int numPart(String s) {
        int v = 0;
        for (char c : s.toCharArray()) {
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /**
     * 安装加载器。
     * @param loaderVersion 加载器版本号（传 null 时自动选「最新稳定版且 maven jar 实际存在」，
     *                      meta 列表里的版本可能只有 profile 而没有 jar（如 Quilt 0.33.x 曾出现），
     *                      直接下载会 HTTP 404）
     */
    public String install(Platform p, String mcVersion, String loaderVersion,
                          GameInstaller.InstallListener listener) throws Exception {
        if (loaderVersion == null || loaderVersion.isBlank()) {
            var list = listLoaders(p, mcVersion, false);
            if (list.isEmpty()) throw new IllegalStateException("未找到可用的加载器版本");
            // 优先稳定版；meta 列表最新的 jar 可能缺失（404），逐个校验直到 jar 存在
            for (String[] s : list) {
                if (s[1].equals("稳定版") && loaderJarExists(p, s[0])) {
                    loaderVersion = s[0];
                    break;
                }
            }
            if (loaderVersion == null) {
                for (String[] s : list) {
                    if (loaderJarExists(p, s[0])) {
                        loaderVersion = s[0];
                        break;
                    }
                }
            }
            if (loaderVersion == null) {
                throw new IllegalStateException("加载器 jar 暂不可用（官方仓库缺失），请稍后再试或更换加载器");
            }
        }
        // 防御：传入的版本号若带「（测试版）」这类显示标签（UI 层漏剥离时），
        // 版本目录名与库 URL 都会带上中文括号导致下载 404、启动缺 jar 秒退，这里强制剥离。
        int brace = loaderVersion.indexOf('（');
        if (brace < 0) brace = loaderVersion.indexOf('(');
        if (brace > 0) loaderVersion = loaderVersion.substring(0, brace).trim();

        String prefix = p == Platform.FABRIC ? "fabric-loader" : "quilt-loader";
        String id = prefix + "-" + loaderVersion + "-" + mcVersion;
        Path vd = config.versionsPath().resolve(id);
        Files.createDirectories(vd);

        // 1. 拉取加载器 profile json
        //    官方 meta 偶发 CDN 波动可能 404：自动重试；Fabric 官方源国内直连不稳定，
        //    失败后自动切 BMCLAPI 镜像；两路均不可达时自组装 profile（模板替换版本，
        //    库文件全部验证存在，不依赖 meta profile，是最终可靠兜底）
        if (listener != null) listener.onProgress("获取加载器元数据", 0, 3);
        String profile = null;
        Exception lastErr = null;
        // 官方 meta 与 BMCLAPI 镜像并发抢答（PCL/HMCL 思路）：任一路先成功即用，慢源不再阻塞。
        // 旧实现是「串行 + 每个源 3 轮重试」：BMCL 的 fabric-meta 取不到 profile 时每次连接
        // 都要硬等超时，2 个源 × 3 轮 × 3 次重试实测拖到 158 秒，玩家看到的就是
        // 「加载器下载卡住」。竞速后正常 2-4 秒返回。
        String profilePath = "/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json";
        java.util.List<String> bases = metaBases(p);
        try {
            if (bases.size() >= 2) {
                profile = Http.getTextRace(bases.get(0) + profilePath, bases.get(1) + profilePath);
            } else {
                profile = Http.getText(bases.get(0) + profilePath);
            }
        } catch (Exception e) {
            lastErr = e;
        }
        if (profile == null) {
            if (listener != null) listener.onProgress("官方元数据暂不可用，自组装加载器配置", 0, 3);
            profile = p == Platform.QUILT
                    ? assembleQuiltProfile(mcVersion, loaderVersion)
                    : assembleFabricProfile(mcVersion, loaderVersion);
        }
        Path loaderJson = vd.resolve(id + ".json");
        Files.writeString(loaderJson, profile, StandardCharsets.UTF_8);

        // 2. 从官方清单下载原版主 jar 到加载器版本目录
        if (listener != null) listener.onProgress("下载原版主程序（" + mcVersion + "）", 0, 3);
        VersionManifest manifest = VersionManifest.fetch(config.minecraftPath().resolve("launcher_cache_manifest.json"),
                config.manifestUrl());
        VersionManifest.Version base = manifest.versions.stream()
                .filter(v -> v.id().equals(mcVersion))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("官方清单中没有版本 " + mcVersion));
        // 原版版本 JSON 优先读本地（补装加载器场景 versions/<mc>/<mc>.json 已存在，零网络请求，
        // 彻底避免每次补装都重新拉取并卡在镜像/官方回退）；不存在时才走镜像→官方回退。
        Path baseJsonPath = config.versionsPath().resolve(mcVersion).resolve(mcVersion + ".json");
        String baseJsonText = null;
        if (Files.exists(baseJsonPath)) {
            try {
                baseJsonText = Files.readString(baseJsonPath, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        if (baseJsonText == null) {
            baseJsonText = getTextAny(config, base.url(), mcVersion);
        }
        JSONObject baseJson = new JSONObject(baseJsonText);
        JSONObject client = Json.optObj(Json.optObj(baseJson, "downloads"), "client");
        if (client != null) {
            Path jar = vd.resolve(id + ".jar");
            if (!Files.exists(jar) || Files.size(jar) != client.optLong("size", -1)) {
                boolean ok = downloadAny(config, client.optString("url"), mcVersion, jar);
                if (!ok) throw new java.io.IOException("原版主程序下载失败");
            }
        }

        // 3. 补齐 libraries 与 assets
        //    先完整安装原版（下载原版 json 声明的全部依赖库，如 fastutil 等；
        //    否则加载器版本启动时 classpath 缺 MC 依赖，NoClassDefFoundError），
        //    已有文件会自动跳过；再补加载器自己的库。
        GameInstaller installer = new GameInstaller(config);
        // 步骤提示改成玩家能看懂的说法：这一步不是「卡住」，而是在补齐原版的库与素材
        if (listener != null) listener.onProgress("补齐原版依赖库与素材（首次较慢）", 0, 1);
        installer.install(mcVersion, base.url(), listener);
        // 兜底：install 完成后再次确认加载器版本描述文件仍在，缺失就重写。
        // 否则会出现「装完加载器却在版本列表里找不到、启动直接报缺少 json」。
        if (!Files.exists(loaderJson)) {
            Files.writeString(loaderJson, profile, StandardCharsets.UTF_8);
        }
        installer.repair(id, listener);
        if (!Files.exists(loaderJson)) {
            throw new java.io.IOException("加载器版本描述文件写入失败：" + loaderJson);
        }
        return id;
    }

    /** 文本拉取（加载器/版本元数据）：镜像与官方两路并发抢答，先成功者胜出（PCL 同款思路，慢源不阻塞）。 */
    private static String getTextAny(LauncherConfig cfg, String url, String versionId) throws Exception {
        String mirrored = com.jwh.launcher.util.Mirror.rewrite(cfg, url, versionId);
        if (mirrored.equals(url)) return Http.getText(url);
        return Http.getTextRace(mirrored, url);
    }

    /** 文件下载：统一走 Downloader，源顺序由本机实测表现决定（不再写死"镜像优先"）。 */
    private static boolean downloadAny(LauncherConfig cfg, String url, String versionId, Path target) {
        String mirrored = com.jwh.launcher.util.Mirror.rewrite(cfg, url, versionId);
        String label = target.getFileName() == null ? "file" : target.getFileName().toString();
        return com.jwh.launcher.download.Downloader.fetch(target, -1,
                com.jwh.launcher.download.ArtifactProbe.Kind.of(target, -1),
                label, null, mirrored, url);
    }
}
