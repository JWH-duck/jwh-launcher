package com.jwh.launcher.loader;

import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.game.GameInstaller;
import com.jwh.launcher.game.JavaFinder;
import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Forge / NeoForge 加载器安装。
 * 原理：官方 promotions 清单获取版本号 → 下载官方 installer jar。
 * 1.21+ NeoForge 走免安装器解析（installNeoForgeFast）；
 * 1.20.2+ NeoForge（20.2.x）安装器注册了 --installClient <目录>，无头直接装客户端并锁定实例目录；
 * 老 Forge（47.x）与 1.20.1 过渡包无此参数，客户端只能在官方 GUI 窗口里点 Install Client。
 */
public class ForgeInstaller {

    public enum Platform { FORGE, NEOFORGE }

    private final LauncherConfig config;

    public ForgeInstaller(LauncherConfig config) {
        this.config = config;
    }

    private String promotionsUrl(Platform p) {
        return p == Platform.FORGE
                ? "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
                : "https://maven.neoforged.net/releases/net/neoforged/forge/promotions_slim.json";
    }

    /** 安装器下载候选地址（BMCLAPI 国内镜像优先，官方 maven 兜底；路径与官方一致）。 */
    private String[] installerUrls(Platform p, String mc, String loader) {
        String bmclapi = "https://bmclapi2.bangbang93.com/maven/";
        if (p == Platform.NEOFORGE) {
            // 1.20.2 起为现代坐标 net/neoforged/neoforge/<loader>/neoforge-<loader>-installer.jar；
            // 1.20.1 及更早为过渡坐标 net/neoforged/forge/<mc>-<loader>/forge-<mc>-<loader>-installer.jar。
            // （旧逻辑仅按 1.21 前缀判断，会把 1.20.2 错归过渡坐标，两源均 404 后反复超时重试。）
            String rel = neoforgeSupported(mc)
                    ? "net/neoforged/neoforge/" + loader + "/neoforge-" + loader + "-installer.jar"
                    : "net/neoforged/forge/" + mc + "-" + loader + "/forge-" + mc + "-" + loader + "-installer.jar";
            return new String[]{bmclapi + rel, "https://maven.neoforged.net/releases/" + rel};
        }
        String rel = "net/minecraftforge/forge/" + mc + "-" + loader + "/forge-" + mc + "-" + loader + "-installer.jar";
        return new String[]{bmclapi + rel, "https://maven.minecraftforge.net/" + rel};
    }

    /**
     * 安装器文件有效性校验：必须是 ZIP（本地文件头 PK\x03\x04）且能打开、条目数 > 0。
     * 实测坏包特征：总长度正确（7,982,031 B）、中央目录能打开（373 条目），
     * 但开头 524KB 被别的源的内容覆盖，首个 PK 头部落在偏移 524,686 —— 这种包
     * zipfile 能读、java -jar 却直接报 "Invalid or corrupt jarfile"。所以必须查首部。
     */
    private static boolean isValidJar(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) < 100_000) return false;
            try (java.io.InputStream in = Files.newInputStream(p)) {
                byte[] h = new byte[4];
                if (in.read(h) != 4) return false;
                if (!(h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4)) return false;
            }
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(p.toFile())) {
                // 逐条目读完全文：触发 CRC 校验。中央目录能打开、首部也是 PK 的包，
                // 中间仍可能某段数据被改写（实测报 "ZipFile invalid LOC header"），只有全量读才能发现。
                java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                int n = 0;
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    try (java.io.InputStream in = zf.getInputStream(e)) {
                        in.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    n++;
                }
                return n > 0;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 安装成功的硬证据：加载器客户端 jar 真实存在且体积正常（仅有 version json 不算）。
     *  现代 NeoForge（1.20.2+）：net/neoforged/neoforge/<loader>/neoforge-<loader>-client.jar；
     *  1.20.1 及更早过渡包：net/neoforged/forge 或 net/minecraftforge/forge/<mc>-<loader>/forge-<mc>-<loader>-client.jar。 */
    private Path runtimeJar(Platform p, String mc, String loader) {
        String[][] groups;
        if (p == Platform.NEOFORGE && neoforgeSupported(mc)) {
            groups = new String[][]{
                    {"net/neoforged/neoforge/", loader, "neoforge-" + loader + "-client.jar"}};
        } else if (p == Platform.FORGE) {
            groups = new String[][]{
                    {"net/minecraftforge/forge/", mc + "-" + loader, "forge-" + mc + "-" + loader + "-client.jar"},
                    {"net/neoforged/forge/", mc + "-" + loader, "forge-" + mc + "-" + loader + "-client.jar"}};
        } else {
            groups = new String[][]{
                    {"net/neoforged/forge/", mc + "-" + loader, "forge-" + mc + "-" + loader + "-client.jar"},
                    {"net/minecraftforge/forge/", mc + "-" + loader, "forge-" + mc + "-" + loader + "-client.jar"}};
        }
        for (String[] g : groups) {
            Path jar = config.minecraftPath().resolve("libraries").resolve(g[0] + g[1]).resolve(g[2]);
            try {
                if (Files.isRegularFile(jar) && Files.size(jar) > 1_000_000L) return jar;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 清掉安装器本体与其分段/续传残片：残片来自旧源时会被续传拼进新文件，造成「长度对、内容错」。 */
    private static void purgeInstaller(Path target) {
        try {
            Files.deleteIfExists(target);
            String n = target.getFileName().toString();
            Files.deleteIfExists(target.resolveSibling(n + ".part"));
            for (int i = 0; i < 8; i++) {
                Files.deleteIfExists(target.resolveSibling(n + ".seg" + i));
            }
        } catch (Exception ignore) {
            // 清理失败不阻断：后续 isValidJar 校验仍能兜住坏包
        }
    }

    /** 查询某 MC 版本的最新（latest）与推荐（recommended）加载器版本。
     *  顺序：BMCLAPI /forge/promos（国内镜像稳定）→ 官方 promotions_slim.json →
     *  maven-metadata（BMCLAPI 镜像优先）取最新稳定 build。
     *  官方源（files.minecraftforge.net）国内直连经常超时，不能作为优先源。 */
    public String[] latestVersions(Platform p, String mc) throws Exception {
        if (p == Platform.NEOFORGE && !neoforgeSupported(mc)) {
            return new String[]{null, null};
        }
        if (p == Platform.NEOFORGE) {
            List<String> vs = listVersions(p, mc, false);
            if (!vs.isEmpty()) return new String[]{vs.get(0), null};
            return new String[]{null, null};
        }
        Exception firstErr = null;
        // 1+2) BMCLAPI forge/promos（数组结构：[{"name":"1.21.1-latest","build":{"version":"52.1.20"}}]）
        // 与官方 promotions_slim.json 双路并发抢答（通用思路：镜像/官方谁快用谁，慢源不阻塞、不卡 60s）
        try {
            String body = Http.getTextRace("https://bmclapi2.bangbang93.com/forge/promos", promotionsUrl(p));
            String latest = null, rec = null;
            // 先按 BMCLAPI 数组结构解析
            try {
                JSONArray arr = new JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", "");
                    JSONObject build = o.optJSONObject("build");
                    String ver = build == null ? "" : build.optString("version", "");
                    if (ver.isEmpty()) continue;
                    if (name.equals(mc + "-latest") && latest == null) latest = ver;
                    if (name.equals(mc + "-recommended") && rec == null) rec = ver;
                }
            } catch (Exception ignored) {
                // 不是数组 → 官方 JSON 结构
            }
            // 再按官方 promotions_slim.json 结构解析
            try {
                JSONObject o = new JSONObject(body);
                JSONObject promos = o.optJSONObject("promos");
                if (promos != null) {
                    if (latest == null) latest = promos.optString(mc + "-latest", null);
                    if (rec == null) rec = promos.optString(mc + "-recommended", null);
                }
            } catch (Exception ignored) {
            }
            if (latest != null && !latest.isEmpty()) return new String[]{latest, rec};
            firstErr = new Exception("promos 清单无 " + mc + " 条目");
        } catch (Exception e) {
            firstErr = e;
        }
        // 3) maven-metadata 版本列表取最新（Forge 每 MC 版本按 build 号排序，最新 ≈ latest）
        try {
            List<String> vs = listVersions(p, mc, false);
            if (!vs.isEmpty()) return new String[]{vs.get(0), vs.get(0)};
        } catch (Exception e3) {
            if (firstErr != null) firstErr.addSuppressed(e3);
            else firstErr = e3;
        }
        if (firstErr != null) throw firstErr;
        return new String[]{null, null};
    }

    /** 查询某 MC 版本的全部加载器版本（按版本号降序）。NeoForge 走 BMCLAPI 国内镜像；结果本地缓存 6 小时。 */
    public List<String> listVersions(Platform p, String mc, boolean force) throws Exception {
        if (p == Platform.NEOFORGE && !neoforgeSupported(mc)) {
            return new ArrayList<>();
        }
        if (p == Platform.NEOFORGE) {
            List<String> out = new ArrayList<>();
            try {
                String body = cachedGet("neoforge-list-" + mc,
                        "https://bmclapi2.bangbang93.com/neoforge/list/" + mc, force);
                org.json.JSONArray arr = new org.json.JSONArray(body);
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.getJSONObject(i).optString("version", "");
                    if (!v.isEmpty()) out.add(v);
                }
            } catch (Exception ignored) {
                out.addAll(listVersionsFromMeta(p, mc, force));
            }
            out.sort(ForgeInstaller::compareLoader);
            return out;
        }
        return listVersionsFromMeta(p, mc, force);
    }

    /** 从 Maven 元数据解析全部版本（Forge 老格式 mc- 前缀；NeoForge 老格式 mc- 前缀 + 新格式 21.x）。
     *  BMCLAPI 国内镜像优先，官方 maven 兜底（官方源国内直连不稳定）。 */
    private List<String> listVersionsFromMeta(Platform p, String mc, boolean force) throws Exception {
        String[][] candidates;
        if (p == Platform.FORGE) {
            candidates = new String[][]{
                    {"https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/maven-metadata.xml", "loader-meta-forge-" + mc},
                    {"https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml", "loader-meta-forge-official-" + mc}};
        } else {
            // 现代 NeoForge（1.20.2 起，含 20.2/20.3/20.4/21.1）构件都在 net/neoforged/neoforge；
            // net/neoforged/forge 是 1.20.1 及更早的 Forge 过渡仓库，不含任何 NeoForge 版本。
            candidates = new String[][]{
                    {"https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/maven-metadata.xml", "loader-meta-neoforge-" + mc},
                    {"https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml", "loader-meta-neoforge-official-" + mc}};
        }
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile("<version>(.*?)</version>");
        String prefix = mc + "-";
        String neoPrefix = p == Platform.NEOFORGE ? neoForgePrefix(mc) : null;
        Path dir = config.versionsPath().getParent().resolve("jwh-cache");
        // 1) 任一候选的本地缓存命中（6h）直接返回，秒开不联网
        for (String[] c : candidates) {
            try {
                Path f = dir.resolve(c[1] + ".txt");
                if (!force && Files.exists(f)) {
                    long age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis();
                    if (age < 6 * 3600 * 1000L) {
                        List<String> out = parseMetadata(pat, prefix, neoPrefix, Files.readString(f));
                        if (!out.isEmpty()) return out;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 2) 镜像与官方并发拉取全部 metadata；逐路解析，非空结果合并去重。
        //    空 / 残缺 body（镜像偶发 200 空响应）解析为空即跳过，不会被当成胜出结果。
        java.util.Map<String, String> bodies = fetchMetaBodies(candidates);
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        Exception last = null;
        try {
            for (String[] c : candidates) {
                String body = bodies.get(c[0]);
                if (body == null) continue;
                try {
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve(c[1] + ".txt"), body);
                } catch (Exception ignored) {
                }
                List<String> out = parseMetadata(pat, prefix, neoPrefix, body);
                if (!out.isEmpty()) merged.addAll(out);
            }
        } catch (Exception e) {
            last = e;
        }
        if (!merged.isEmpty()) {
            List<String> res = new ArrayList<>(merged);
            res.sort(ForgeInstaller::compareLoader);
            return res;
        }
        // 3) 双路均失败/解析为空：回退任意旧缓存（force 刷新失败也不丢旧数据）
        for (String[] c : candidates) {
            try {
                Path f = dir.resolve(c[1] + ".txt");
                if (Files.exists(f)) {
                    List<String> out = parseMetadata(pat, prefix, neoPrefix, Files.readString(f));
                    if (!out.isEmpty()) return out;
                }
            } catch (Exception ignored) {
            }
        }
        throw last != null ? last : new Exception("maven-metadata 无 " + mc + " 条目");
    }

    /** 并发拉取多路小文本 metadata，返回 url->body；失败/超时的 url 不入表。 */
    private static java.util.Map<String, String> fetchMetaBodies(String[][] candidates) {
        java.util.Map<String, String> res = new java.util.concurrent.ConcurrentHashMap<>();
        int n = Math.max(candidates.length, 1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(n);
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            for (String[] c : candidates) {
                ex.execute(() -> {
                    try {
                        res.put(c[0], Http.getText(c[0]));
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await(75, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ex.shutdownNow();
        }
        return res;
    }

    private static List<String> parseMetadata(java.util.regex.Pattern pat, String prefix,
                                              String neoPrefix, String body) {
        java.util.regex.Matcher matcher = pat.matcher(body);
        List<String> out = new ArrayList<>();
        while (matcher.find()) {
            String v = matcher.group(1).trim();
            if (v.startsWith(prefix)) {
                out.add(v.substring(prefix.length()));
            } else if (neoPrefix != null && v.startsWith(neoPrefix) && v.length() > neoPrefix.length()) {
                out.add(v); // NeoForge 新格式版本号（如 21.1.119）
            }
        }
        out.sort(ForgeInstaller::compareLoader);
        return out;
    }

    /** 带本地缓存的文本拉取：缓存目录 jwh-cache，6 小时有效，force=true 强制重拉。 */
    private String cachedGet(String key, String url, boolean force) throws Exception {
        Path dir = config.versionsPath().getParent().resolve("jwh-cache");
        Files.createDirectories(dir);
        Path f = dir.resolve(key + ".txt");
        if (!force && Files.exists(f)) {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis();
            if (age < 6 * 3600 * 1000L) return Files.readString(f);
        }
        String body = Http.getText(url);
        Files.writeString(f, body);
        return body;
    }

    /** MC 版本 -> NeoForge 新格式前缀（1.21.1 -> "21.1."，1.21 -> "21.0."）；老格式（1.20.1 等）返回 null。 */
    private static String neoForgePrefix(String mc) {
        String[] parts = mc.split("\\.");
        if (parts.length < 2 || !"1".equals(parts[0])) return null;
        if ("20".equals(parts[1])) return null; // 1.20.x 老格式
        String minor = parts.length >= 3 ? parts[2] : "0";
        return parts[1] + "." + minor + ".";
    }

    /** 解析 MC 版本号为三段数字（缺失段补 0，非数字段按 0 处理）。 */
    private static int[] mcParts(String mc) {
        int[] v = new int[]{0, 0, 0};
        if (mc == null) return v;
        String[] parts = mc.split("\\.");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            String d = parts[i].replaceAll("[^0-9].*$", "");
            if (!d.isEmpty()) {
                try { v[i] = Integer.parseInt(d); } catch (NumberFormatException ignored) { }
            }
        }
        return v;
    }

    /**
     * 该 MC 版本是否有 NeoForge 构建。NeoForge 从 1.20.2 起步；
     * 1.20.1 及更早只有 Forge（47.x 过渡包发布在 neoforged/forge 仓库，不代表它是 NeoForge）。
     */
    public static boolean neoforgeSupported(String mc) {
        int[] v = mcParts(mc);
        if (v[0] != 1) return v[0] > 1;
        if (v[1] != 20) return v[1] > 20;
        return v[2] >= 2;
    }

    /** 点分数字段逐段比较，返回降序（大版本在前）。 */
    private static int compareLoader(String a, String b) {
        String[] as = a.split("\\."), bs = b.split("\\.");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            int ai = i < as.length ? numPart(as[i]) : 0;
            int bi = i < bs.length ? numPart(bs[i]) : 0;
            if (ai != bi) return bi - ai;
        }
        return 0;
    }

    private static int numPart(String s) {
        try {
            String d = s.replaceAll("[^0-9].*$", "");
            return d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 安装 Forge/NeoForge。
     * @return 生成的版本 ID（如 1.21.1-forge-52.1.0）
     */
    public String install(Platform p, String mc, String loaderVersion,
                          Path javaExe, Consumer<String> log,
                          GameInstaller.InstallListener listener) throws Exception {
        if (loaderVersion == null || loaderVersion.isBlank()) {
            String[] v = latestVersions(p, mc);
            loaderVersion = v[1] != null ? v[1] : v[0];
            if (loaderVersion == null) {
                throw new IllegalStateException("未找到该 MC 版本对应的加载器版本");
            }
        }

        // NeoForge 1.21+（新格式 21.x）：免官方安装器，从安装器内提取 version.json +
        // 全镜像下载依赖库，根治 maven.neoforged.net 官方源国内超时问题
        if (p == Platform.NEOFORGE && mc.startsWith("1.21")) {
            return installNeoForgeFast(mc, loaderVersion, log, listener);
        }

        // 1. installer（已下载过则复用；但必须校验是真 JAR —— 镜像对部分版本直接回 404 页面，
        //    以及中断续传可能拼出长度正常、内容错乱的文件，旧逻辑只看大小 >100KB 会把坏文件
        //    一直复用下去，导致重试 3 次都报 "Invalid or corrupt jarfile"）
        Path installerJar = config.minecraftPath().resolve("jwh-cache").resolve(
                (p == Platform.FORGE ? "forge" : "neoforge") + "-" + mc + "-" + loaderVersion + "-installer.jar");
        boolean ok = isValidJar(installerJar);
        if (!ok && Files.exists(installerJar)) {
            purgeInstaller(installerJar); // 坏文件连同分段残片一起清掉，避免续传污染
        }
        if (!ok) {
            if (listener != null) listener.onProgress("下载官方安装器", 0, 1);
            // 统一入口：源顺序由路由按本机表现决定（BMCL 对 26.3 返回 404 HTML 时会被
            // 结构校验当场识破并自动降权到官方 maven），不再逐个源手工 purge 重试。
            purgeInstaller(installerJar);
            ok = com.jwh.launcher.download.Downloader.fetch(installerJar, -1,
                    com.jwh.launcher.download.ArtifactProbe.Kind.INSTALLER,
                    "forge-installer", null,
                    com.jwh.launcher.download.Downloader.Policy.of(180, 400),
                    installerUrls(p, mc, loaderVersion));
        }
        if (!ok) {
            throw new java.io.IOException("安装器下载失败（镜像与官方源均不可用），请检查网络后重试");
        }

        // 版本 ID 以安装器内嵌 install_profile.json 的 version 字段为准（如 1.20.1-forge-47.1.83），
        // 不再自己拼 neoforge- 前缀——否则安装器生成的目录名和启动器校验名对不上，会误判未安装。
        String fallbackId = mc + "-" + (p == Platform.FORGE ? "forge" : "neoforge") + "-" + loaderVersion;
        String versionId = readProfileVersion(installerJar, fallbackId);
        Path vd = config.versionsPath().resolve(versionId);
        // 只看 json 会被「安装中断留下的半成品」骗过：上次安装器写完了 version json、
        // 依赖库也下完，但解包 data 时崩了，目录里只有 json，随后每次都被判定"已安装"。
        if (Files.exists(vd.resolve(versionId + ".json")) && runtimeJar(p, mc, loaderVersion) != null) {
            return versionId; // 已安装
        }

        // Java 路径统一防御：上游可能传入 null / 空串（无可用 Java 时 JavaChoice 为 null），
        // 直接 toString 会让 ProcessBuilder 抛 "Cannot run program \"\""（error=87）。
        Path java = resolveJava(javaExe);

        // 官方安装器要求目标目录存在 launcher_profiles.json（官方启动器配置），
        // 否则报 "There is no minecraft launcher profile ... you need to run the launcher first!"
        ensureLauncherProfiles();

        // 前置：先把基础原版（版本 json / 主 jar / 依赖库 / 素材）装齐，再拉起官方安装器。
        // 与 Fabric/Quilt 链路一致：新机器若原版缺失，安装器窗口内合成时找不到 MC 类，
        // 即便窗口点完 Install Client，装出的版本也会 NoClassDefFoundError 起不来。
        // 已存在的文件由 install 内部校验后跳过，重复安装不会重下。
        if (listener != null) listener.onProgress("补齐基础原版（首次较慢）", 0, 1);
        new GameInstaller(config).install(mc, null, listener);

        // 预热：把安装器清单（version.json + install_profile.json）里的依赖库先用国内镜像
        // 预取到本地 libraries。官方安装器对本地校验通过的库会跳过联网，避免其内部直连
        // maven.neoforged.net 在国内超时。预取不阻断：缺的库仍由安装器自行再试。
        if (listener != null) listener.onProgress("预热安装器依赖库（国内镜像）", 0, 1);
        List<String> prefetchMiss = new GameInstaller(config)
                .prefetchMavenArtifacts(installerLibraryRels(installerJar), "预热依赖库", listener);
        if (!prefetchMiss.isEmpty()) {
            log.accept("[提示] 预热后仍有 " + prefetchMiss.size() + " 个库未就位："
                    + String.join("、", prefetchMiss));
        }

        // 1.20.2+ NeoForge 安装器支持无头 --installClient <目录>，直接装客户端并锁定实例目录，
        // 不弹 GUI、不会把默认目录指到 .minecraft；老 Forge 与 1.20.1 过渡包无此参数，保持 GUI。
        // 预热兼容补丁：老安装器在域名解析失败时 getIps 返回 null，预热日志未判空触发 NPE，
        // 窗口在弹出前崩溃。补丁副本把失败分支改为空列表；补丁不可用时回退原包。
        Path runnableJar = InstallerPatcher.patch(installerJar);
        if (!runnableJar.equals(installerJar)) {
            log.accept("已对官方安装器应用本地兼容补丁（规避预热解析空指针）");
        }

        // 预热域名 DNS 预检：仅作诊断提示，不阻断（补丁后空列表也能进入安装窗口）。
        List<String> unreachable = preflightDns();
        if (!unreachable.isEmpty()) {
            log.accept("[提示] 当前网络暂时无法解析：" + String.join("、", unreachable)
                    + "；若安装窗口内下载失败，请开启加速器或切换网络后重试。");
        }

        boolean headlessClient = p == Platform.NEOFORGE && neoforgeSupported(mc);
        log.accept("游戏目录：" + config.minecraftDir);
        if (headlessClient) {
            if (listener != null) listener.onProgress("无头安装 NeoForge 客户端到「" + config.minecraftDir + "」", 0, 1);
        } else if (listener != null) {
            listener.onProgress("已启动官方安装器窗口：请在弹窗中选择目录「" + config.minecraftDir + "」并点击 Install Client", 0, 1);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        cmd.add("-jar");
        cmd.add(runnableJar.toString());
        if (headlessClient) {
            cmd.add("--installClient");
            cmd.add(config.minecraftDir);
        }
        log.accept("> " + String.join(" ", cmd));

        java.util.List<String> installerTail = new java.util.ArrayList<>();
        // GUI 为交互式操作，不能像无头那样自动重试（重试会再次弹窗）；等待玩家在窗口内完成。
        int exit = runProcess(cmd, log, installerTail);

        // 安装完成的硬证据：版本 json 与客户端 jar 都落到真实版本目录（只看退出码不够，
        // 玩家在窗口里取消/关窗也可能以 0 退出但没有产物）。
        boolean jsonOk = Files.exists(vd.resolve(versionId + ".json"));
        Path rtJar = runtimeJar(p, mc, loaderVersion);
        boolean installed = jsonOk && rtJar != null;
        if (exit != 0 || !installed) {
            List<String> missing = new ArrayList<>();
            if (!jsonOk) missing.add("版本配置文件 " + versionId + ".json");
            if (rtJar == null) missing.add("加载器客户端 jar（forge-" + mc + "-" + loaderVersion + "-client.jar）");
            String miss = missing.isEmpty() ? "" : "；缺少：" + String.join("、", missing);
            String tail = installerTail.isEmpty() ? "" : " 安装器输出末尾：" + String.join(" | ", installerTail);
            throw new java.io.IOException("官方安装器未完成客户端安装（退出码 " + exit + "）" + miss + "。"
                    + "请在弹出的安装器窗口中选择本启动器游戏目录「" + config.minecraftDir + "」并点击 Install Client；"
                    + "若窗口未出现或持续失败，可手动运行：java -jar " + runnableJar + tail);
        }

        // 3. 补齐 libraries / assets（安装器可能没下全资源）
        try {
            new GameInstaller(config).repair(versionId, listener);
        } catch (Exception e) {
            log.accept("[警告] 资源补齐失败：" + e.getMessage() + "（启动前可点击\"修复\"重试）");
        }
        return versionId;
    }

    /** NeoForge 1.21+ 免官方安装器安装：内置库清单 → 全镜像下载依赖库。返回版本 ID。 */
    private String installNeoForgeFast(String mc, String loaderVersion, Consumer<String> log,
                                       GameInstaller.InstallListener listener) throws Exception {
        String versionId = "neoforge-" + loaderVersion;
        Path vd = config.versionsPath().resolve(versionId);
        if (Files.exists(vd.resolve(versionId + ".json"))) {
            return versionId; // 已安装
        }
        // 直接按内置库清单下载（官方 21.1.x 安装器内无 version.json，且 installer 下载常被墙；
        // 旧实现因下载 installer.jar 失败而卡在"下载安装器"阶段）
        new GameInstaller(config).installNeoForge(mc, loaderVersion, null, listener);
        return versionId;
    }

    /** 确保 .minecraft 目录存在官方启动器配置 launcher_profiles.json（Forge/NeoForge 安装器前置要求）。 */
    private void ensureLauncherProfiles() throws java.io.IOException {
        Path dir = config.minecraftPath();
        Files.createDirectories(dir);
        Path profiles = dir.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) {
            Files.writeString(profiles,
                    "{\"profiles\":{},\"selectedProfile\":\"(Default)\",\"clientToken\":\"0000000000000000\",\"authenticationDatabase\":{},\"version\":3}",
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * 规范化传给安装器的 Java 路径：null / 空白 / 失效路径时回退到本机扫描到的首个 Java；
     * 仍不可用则抛出明确错误，不再把空串交给 ProcessBuilder（否则报 Cannot run program ""，error=87）。
     */
    private Path resolveJava(Path given) throws java.io.IOException {
        if (given != null) {
            String g = given.toString().trim();
            if (!g.isEmpty() && (Files.exists(given) || "java".equals(g) || "javaw".equals(g))) {
                return given;
            }
        }
        try {
            List<JavaFinder.JavaRuntime> runtimes = JavaFinder.find();
            for (JavaFinder.JavaRuntime r : runtimes) {
                if (r.javaExe() != null) return r.javaExe();
            }
        } catch (Exception ignored) {
        }
        throw new java.io.IOException("未检测到可用的 Java 运行环境，请先在「设置」中选择 Java 后再安装加载器");
    }

    /** 从安装器内嵌 version.json + install_profile.json 收集全部依赖库的 maven 相对路径（去重保序）。 */
    private List<String> installerLibraryRels(Path installerJar) {
        List<String> rels = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(installerJar.toFile())) {
            collectRels(zf, "version.json", rels, seen);
            collectRels(zf, "install_profile.json", rels, seen);
        } catch (Exception ignored) {
        }
        return rels;
    }

    private void collectRels(java.util.zip.ZipFile zf, String entry, List<String> rels, java.util.Set<String> seen) {
        java.util.zip.ZipEntry e = zf.getEntry(entry);
        if (e == null) return;
        try (InputStream in = zf.getInputStream(e)) {
            JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JSONArray libs = o.optJSONArray("libraries");
            if (libs == null) return;
            for (int i = 0; i < libs.length(); i++) {
                JSONObject lib = libs.optJSONObject(i);
                if (lib == null) continue;
                JSONObject dl = Json.optObj(lib, "downloads");
                JSONObject art = dl == null ? null : Json.optObj(dl, "artifact");
                String path = art == null ? "" : art.optString("path", "");
                if (path.isBlank()) path = relFromName(lib.optString("name", ""));
                if (!path.isBlank() && seen.add(path)) rels.add(path);
            }
        } catch (Exception ignored) {
        }
    }

    /** 老格式坐标 name（group:artifact:version[@classifier]）转 maven 相对路径。 */
    private static String relFromName(String name) {
        if (name == null || name.isBlank()) return "";
        String[] c = name.split(":");
        if (c.length < 3) return "";
        String group = c[0].replace('.', '/');
        String art = c[1];
        String ver = c[2].replace("@jar", "");
        String classifier = c.length > 3 ? "-" + c[3].replace("@jar", "") : "";
        return group + "/" + art + "/" + ver + "/" + art + "-" + ver + classifier + ".jar";
    }

    /** 从安装器内嵌 install_profile.json（缺失时退回 version.json）读取真实版本 ID；读不到用兜底拼接值。 */
    private String readProfileVersion(Path installerJar, String fallback) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(installerJar.toFile())) {
            java.util.zip.ZipEntry e = zf.getEntry("install_profile.json");
            if (e == null) e = zf.getEntry("version.json");
            if (e == null) return fallback;
            try (java.io.InputStream in = zf.getInputStream(e)) {
                JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                String v = o.optString("version", o.optString("id", ""));
                return v == null || v.isBlank() ? fallback : v;
            }
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** 预热域名 DNS 预检，返回当前无法解析的域名列表（仅诊断，不抛异常、不阻断安装）。 */
    private List<String> preflightDns() {
        List<String> bad = new ArrayList<>();
        for (String host : InstallerPatcher.preflightHosts()) {
            try {
                java.net.InetAddress.getAllByName(host);
            } catch (Exception e) {
                bad.add(host);
            }
        }
        return bad;
    }

    /** 运行外部进程并转发输出，返回退出码；tailOut 收集输出末尾最多 30 行用于诊断。 */
    private int runProcess(List<String> cmd, Consumer<String> log, List<String> tailOut) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread t = new Thread(() -> {
            try (var in = p.getInputStream();
                 var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.accept(line);
                    if (tailOut != null) {
                        tailOut.add(line);
                        if (tailOut.size() > 30) tailOut.remove(0);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "installer-log");
        t.setDaemon(true);
        t.start();
        if (!p.waitFor(20, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new java.io.IOException("安装器运行超时（20 分钟）");
        }
        return p.exitValue();
    }
}
