package com.jwh.launcher.game;

import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.download.DownloadManager;
import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import com.jwh.launcher.util.Mirror;
import com.jwh.launcher.util.OSUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 游戏安装器：下载版本 JSON、主 jar、依赖库（含原生库解压）与资源文件。
 * 所有文件与官方启动器布局一致，可与原版/其它启动器共用 .minecraft。
 */
public class GameInstaller implements com.jwh.launcher.util.Http.DownloadControl {

    public interface InstallListener {
        /** step 为当前阶段描述；done/total 为当前阶段任务进度。 */
        void onProgress(String step, int done, int total);

        /** 真实字节整体进度：bytesDone/bytesTotal 为当前批次已下载字节与总字节（总字节未知时不回调）。 */
        default void onBytes(String step, long bytesDone, long bytesTotal) {
        }
    }

    private static final String DEFAULT_LIBRARY_BASE = "https://libraries.minecraft.net/";
    private static final String ASSET_BASE = "https://resources.download.minecraft.net/";

    private final LauncherConfig config;
    private volatile DownloadManager activeDm;
    /** 当前阶段的监听者与字节回调标题：单个大文件（加载器主库）下载时用它把「已下多少 MB」推给界面。 */
    private volatile InstallListener activeListener;
    private volatile String activeByteStep = "";

    /** 每写入一块字节回调一次：大文件下载期间界面持续有「已下载 xx MB」的推进，不再是死住的进度条。 */
    @Override
    public void onBytes(long bytes) {
        InstallListener l = activeListener;
        if (l == null || bytes <= 0) return;
        String mb = String.format("%.1f MB", bytes / 1048576.0);
        l.onProgress(activeByteStep.isEmpty() ? mb : activeByteStep + " " + mb, 0, 0);
    }

    /** 取消当前正在进行的下载（版本安装/补全阶段）。 */
    public void cancelDownload() {
        DownloadManager d = activeDm;
        if (d != null) d.cancel();
    }

    /** 是否已请求取消。 */
    @Override
    public boolean isCancelled() {
        DownloadManager d = activeDm;
        return d != null && d.isCancelled();
    }

    /** UI 取消按钮调用入口。 */
    @Override
    public void cancel() {
        cancelDownload();
    }
    private final int threads;
    private final boolean useBmclapi;

    public GameInstaller(LauncherConfig config) {
        this.config = config;
        this.threads = Math.max(4, config.downloadThreads);
        this.useBmclapi = config.useBmclapi();
    }

    /** 官方 URL → BMCLAPI 镜像 URL（仅切换下载地址，文件路径/校验逻辑不变）。 */
    private String src(String url, String versionId) {
        return Mirror.rewrite(config, url, versionId);
    }

    /** 文本拉取（版本 JSON 等元数据）：镜像与官方两路并发抢答，先成功者胜出（PCL 同款思路，慢源不阻塞）。 */
    private String getTextAny(String url, String versionId) throws Exception {
        String mirrored = src(url, versionId);
        if (mirrored.equals(url)) return Http.getText(url);
        return Http.getTextRace(mirrored, url);
    }

    /** 文件下载：统一走 Downloader，源顺序由本机实测表现决定（不再写死"镜像优先"）。 */
    private boolean downloadAny(String url, String versionId, Path target) {
        String mirrored = src(url, versionId);
        String label = target.getFileName() == null ? "file" : target.getFileName().toString();
        return com.jwh.launcher.download.Downloader.fetch(target, -1,
                com.jwh.launcher.download.ArtifactProbe.Kind.of(target, -1),
                label, null, mirrored, url);
    }

    /** 安装一个版本（id 与 manifest 下载地址）。 */
    public void install(String id, String versionJsonUrl, InstallListener listener) throws Exception {
        // 1. 版本 JSON
        Path vd = config.versionsPath().resolve(id);
        Files.createDirectories(vd);
        // 未指定版本 JSON 地址（如整合包补装基础原版）→ 从版本清单解析该版本地址
        if (versionJsonUrl == null || versionJsonUrl.isBlank()) {
            VersionManifest manifest = VersionManifest.fetch(
                    config.minecraftPath().resolve("launcher_cache_manifest.json"), config.manifestUrl());
            String found = null;
            for (VersionManifest.Version vv : manifest.versions) {
                if (vv.id().equals(id)) {
                    found = vv.url();
                    break;
                }
            }
            if (found == null) throw new java.io.IOException("版本清单中未找到 " + id);
            versionJsonUrl = found;
        }
        Path jsonPath = vd.resolve(id + ".json");
        JSONObject v = null;
        if (Files.exists(jsonPath)) {
            try {
                v = new JSONObject(Files.readString(jsonPath, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        if (v == null) {
            if (listener != null) listener.onProgress("下载版本元数据 " + id, 0, 1);
            String text = getTextAny(versionJsonUrl, id);
            Files.writeString(jsonPath, text, StandardCharsets.UTF_8);
            v = new JSONObject(text);
        }

        // 2. 主 jar（慢速网络下最多尝试 8 次，Http.download 内部 60 秒读超时 + 断点续传，
        //    每次至少推进部分字节，不会长时间卡死）
        JSONObject client = Json.optObj(Json.optObj(v, "downloads"), "client");
        Path jarPath = vd.resolve(id + ".jar");
        if (client != null && !Http.fileOk(jarPath, client.optLong("size", 0))) {
            if (listener != null) listener.onProgress("下载游戏主程序", 0, 1);
            boolean ok = false;
            for (int attempt = 0; attempt < 8; attempt++) {
                ok = downloadAny(client.optString("url"), id, jarPath);
                if (ok && Http.fileOk(jarPath, client.optLong("size", 0))) break;
            }
            if (!ok) throw new IOException("游戏主程序下载失败（请检查网络后重试）");
        }

        // 3. libraries（含 natives 解压）
        List<LibraryResolved> libs = resolveLibraries(v);
        List<DownloadManager.Task> libTasks = new ArrayList<>();
        for (LibraryResolved l : libs) {
            if (l.artifact != null) {
                libTasks.add(new DownloadManager.Task(l.artifact.url, l.artifact.path,
                        l.artifact.size, l.label, l.artifact.officialUrl));
            }
            if (l.natives != null) {
                libTasks.add(new DownloadManager.Task(l.natives.url, l.natives.path,
                        l.natives.size, l.label + " (原生库)", l.natives.officialUrl));
            }
        }
        runTasks(libTasks, "下载依赖库", listener);

        // 4. assets 索引 + 对象
        downloadAssets(v, listener);
    }

    /** 仅补全缺失文件（已安装版本二次校验用）。 */
    public void repair(String id, InstallListener listener) throws Exception {
        Path jsonPath = config.versionsPath().resolve(id).resolve(id + ".json");
        // 版本 json 缺失/损坏时给出可执行的提示，而不是抛 NoSuchFileException 让玩家只看到路径
        if (!Files.isRegularFile(jsonPath)) {
            throw new IOException("版本 " + id + " 的清单文件缺失，请重新安装该版本："
                    + jsonPath.getFileName());
        }
        JSONObject v;
        try {
            v = new JSONObject(Files.readString(jsonPath, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("版本 " + id + " 的清单文件已损坏，请重新安装该版本："
                    + jsonPath.getFileName());
        }
        Path vd = config.versionsPath().resolve(id);
        JSONObject client = Json.optObj(Json.optObj(v, "downloads"), "client");
        Path jarPath = vd.resolve(id + ".jar");
        if (client != null && !Http.fileOk(jarPath, client.optLong("size", 0))) {
            boolean ok = downloadAny(client.optString("url"), id, jarPath);
            if (!ok) throw new IOException("游戏主程序下载失败");
        }
        List<LibraryResolved> libs = resolveLibraries(v);
        List<DownloadManager.Task> libTasks = new ArrayList<>();
        for (LibraryResolved l : libs) {
            if (l.artifact != null) libTasks.add(new DownloadManager.Task(l.artifact.url, l.artifact.path, l.artifact.size, l.label, l.artifact.officialUrl));
            if (l.natives != null) libTasks.add(new DownloadManager.Task(l.natives.url, l.natives.path, l.natives.size, l.label + " (原生库)", l.natives.officialUrl));
        }
        runTasks(libTasks, "修复依赖库", listener);
        downloadAssets(v, listener);
    }

    /**
     * NeoForge 1.21+ 免官方安装器安装（通用版，任何 21.x 版本可用）：
     * 1) 下载官方 installer.jar（BMCLAPI 镜像优先）并解压 data/win_args.txt，
     *    解析出该版本真实的模块路径 / legacyClassPath 库清单 / natives / fml 参数；
     * 2) 清单写入版本目录（GameLauncher 启动时按版本读取，不再依赖内置 21.1.264）；
     * 3) 全镜像下载依赖库；srg/extra（neoform 产物，仅由 MC 版本决定、不在任何镜像上）
     *    从启动器内置资源释放到 libraries/net/minecraft/client/&lt;mc-neoform&gt;/。
     * installer 下载失败时回退内置 21.1.264 清单（老安装仍可安装）。
     */
    public void installNeoForge(String mcVersion, String loaderVersion, Path installerJar,
                                InstallListener listener) throws Exception {
        String id = "neoforge-" + loaderVersion;
        Path vd = config.versionsPath().resolve(id);
        Files.createDirectories(vd);
        Path jsonPath = vd.resolve(id + ".json");
        // 内置最小 version.json：官方结构启动（GameLauncher.buildNeoForgeCommand）不依赖 json 的
        // arguments，json 仅用于版本列表显示与继承关系。
        if (!Files.exists(jsonPath)) {
            JSONObject min = new JSONObject();
            min.put("id", id);
            min.put("inheritsFrom", mcVersion);
            min.put("type", "release");
            min.put("assets", "17");
            min.put("mainClass", "cpw.mods.bootstraplauncher.BootstrapLauncher");
            Files.writeString(jsonPath, min.toString(2), StandardCharsets.UTF_8);
        }
        // 基础版本检查
        Path baseDir = config.versionsPath().resolve(mcVersion);
        if (!Files.exists(baseDir.resolve(mcVersion + ".json"))) {
            throw new IOException("缺少基础版本 " + mcVersion + "，请先在下载中心下载/安装该游戏版本");
        }

        // === 1. 获取官方 win_args（installer.jar 内 data/win_args.txt） ===
        String winArgs = null;
        Path inst = config.minecraftPath().resolve("jwh-cache")
                .resolve("neoforge-" + loaderVersion + "-installer.jar");
        if (!com.jwh.launcher.download.ArtifactProbe.check(inst,
                com.jwh.launcher.download.ArtifactProbe.Kind.INSTALLER, -1).ok()) {
            if (listener != null) listener.onProgress("下载 NeoForge 安装器", 0, 1);
            String rel = "net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";
            // 统一入口：BMCL 该路径对部分版本回 404 HTML，会被结构校验当场识破并自动切官方 maven
            boolean ok = com.jwh.launcher.download.Downloader.fetch(inst, -1,
                    com.jwh.launcher.download.ArtifactProbe.Kind.INSTALLER,
                    "neoforge-installer", null,
                    com.jwh.launcher.download.Downloader.Policy.of(180, 300),
                    "https://bmclapi2.bangbang93.com/maven/" + rel,
                    "https://maven.neoforged.net/releases/" + rel);
            if (ok) winArgs = readWinArgsFromJar(inst);
        } else {
            winArgs = readWinArgsFromJar(inst);
        }

        // === 2. 解析清单（win_args 优先，内置 21.1.264 兜底） ===
        List<String> lcpRels, moduleRels, nativesRels;
        List<String> jpms;
        Map<String, String> fml;
        String ignoreList;
        if (winArgs != null && !winArgs.isBlank()) {
            NeoArgs na = parseWinArgs(winArgs);
            lcpRels = na.lcp;
            moduleRels = na.modules;
            jpms = na.jpms;
            ignoreList = na.ignoreList;
            fml = na.fml;
            // natives 来自基础版本 json 的 classifiers；解析不到则用内置 21.1.264（lwjgl 3.3.3）
            nativesRels = nativesFromVersionJson(baseDir, mcVersion);
            if (nativesRels.isEmpty()) nativesRels = loadResourceLines("neoforge-21.1.264-natives.txt");
        } else {
            lcpRels = loadResourceLines("neoforge-21.1.264-lcp.txt");
            moduleRels = NEOFORGE_MODULES_FALLBACK;
            nativesRels = loadResourceLines("neoforge-21.1.264-natives.txt");
            jpms = NEOFORGE_JPMS_FALLBACK;
            ignoreList = NEOFORGE_IGNORE_FALLBACK;
            fml = new LinkedHashMap<>();
            if (listener != null) listener.onProgress("安装器不可用，使用内置清单（21.1.264）", 0, 1);
        }
        if (lcpRels.isEmpty()) {
            throw new IOException("NeoForge 库清单解析失败（win_args 与内置清单均不可用）");
        }
        // 官方 win_args 的 legacyClassPath 只含 NeoForge 自己的库；原版客户端库
        // （icu4j/patchy/blocklist/commons/httpclient/commons-compress 等）官方走 version.json
        // libraries 提供。固化方案用 lcp 直接做 classpath，必须一并并入原版 json 的库，
        // 否则 modlauncher 启动时报 NoSuchElementException。
        for (String rel : vanillaLibsFromVersionJson(baseDir, mcVersion)) {
            if (!lcpRels.contains(rel)) lcpRels.add(rel);
        }
        // 官方 win_args 的 legacyClassPath 不含 lwjgl（lwjgl 随原版继承）；固化方案用 lcp 直接
        // 做启动 classpath，必须并入原版 json 的 lwjgl 本体库（org/lwjgl/*，不含 natives classifier）。
        // 否则 FML earlydisplay 加载 GLFW 时 NoClassDefFoundError: org/lwjgl/system/Struct。
        for (String rel : lwjglFromVersionJson(baseDir, mcVersion)) {
            if (!lcpRels.contains(rel)) lcpRels.add(rel);
        }
        // NeoForge universal jar 与 client jar：fml 的 PathBasedLocator 按
        // neoforge-<ver>-universal.jar 文件名发现 neoforge 模块，ProductionClientProvider
        // 按 maven 坐标加载 neoforge-<ver>-client.jar 合成 minecraft 模块；两者都不进
        // legacyClassPath（否则与 mod discovery 产生同名/同包双模块冲突）。
        // 官方 maven 独立发布 -universal.jar；bmclapi 镜像曾出现主 jar（neoforge-<ver>.jar）
        // 损坏（内嵌 jarjar 数据损坏、coremods 版本错配，导致 FML JarInJar 解包失败秒退），
        // 因此 universal 一律直接下载 -universal.jar 并做完整性校验，不再复制主 jar。
        String nfMain = "net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + ".jar";
        String nfUniversal = "net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-universal.jar";
        String nfClient = "net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-client.jar";
        Path nfUniversalFile = config.librariesPath().resolve(
                nfUniversal.replace('/', java.io.File.separatorChar));
        if (!isNeoForgeUniversalValid(nfUniversalFile, loaderVersion)) {
            if (listener != null) listener.onProgress("下载 NeoForge 主库", 0, 1);
            deleteQuietly(nfUniversalFile); // 清掉已损坏文件，避免大小判断误跳过
            activeListener = listener;
            activeByteStep = "下载 NeoForge 主库";
            boolean dl = com.jwh.launcher.download.Downloader.fetch(nfUniversalFile, -1,
                    com.jwh.launcher.download.ArtifactProbe.Kind.JAR, "neoforge-universal", this,
                    com.jwh.launcher.download.Downloader.Policy.of(8, 60),
                    "https://bmclapi2.bangbang93.com/maven/" + nfUniversal,
                    "https://maven.neoforged.net/releases/" + nfUniversal);
            activeByteStep = "";
            if (!dl || !isNeoForgeUniversalValid(nfUniversalFile, loaderVersion)) {
                releaseNeoForgeFallback(nfUniversalFile, false, mcVersion, loaderVersion);
            }
        }
        Path nfClientFile = config.librariesPath().resolve(
                nfClient.replace('/', java.io.File.separatorChar));
        boolean mc214 = mcVersion != null && mcVersion.trim().equals("1.21.4");
        if (mc214) {
            // 1.21.4 的 client 补丁层公开 maven 不发布（含受版权 MC 类），BMCL/官方均 404 或不可达，
            // 直接用内置专属产物；老实例可能残留 21.1 错配兜底（有效 zip 但版本不符，isZipValid 仍通过），
            // 必须强制覆盖，不能因文件体积有效而跳过。
            releaseNeoForgeFallback(nfClientFile, true, mcVersion, loaderVersion);
        } else if (!isZipValid(nfClientFile)) {
            deleteQuietly(nfClientFile);
            // 这个文件在 bmclapi 镜像上是 404、官方 maven 在国内常连不上，所以预算收到 90 秒：
            // 拿不到就立刻用内置兜底（就是现在实际在跑的那份），不把界面挂住 5 分钟
            if (listener != null) listener.onProgress("下载 NeoForge 客户端库（镜像缺失时自动切官方源）", 0, 0);
            activeListener = listener;
            activeByteStep = "下载 NeoForge 客户端库";
            boolean dlc = com.jwh.launcher.download.Downloader.fetch(nfClientFile, -1,
                    com.jwh.launcher.download.ArtifactProbe.Kind.JAR, "neoforge-client", this,
                    com.jwh.launcher.download.Downloader.Policy.of(8, 45),
                    "https://bmclapi2.bangbang93.com/maven/" + nfClient,
                    "https://maven.neoforged.net/releases/" + nfClient);
            activeByteStep = "";
            if (!dlc || !isZipValid(nfClientFile)) {
                releaseNeoForgeFallback(nfClientFile, true, mcVersion, loaderVersion);
            }
        }

        // 引导 jar（bootstraplauncher/securejarhandler/asm 全套/JarJarFileSystems）只进模块路径
        // (-p)，不进 legacyClassPath，否则 BootstrapLauncher 2.0.2 校验模块/类路径重复加载直接崩溃。
        java.util.Set<String> moduleJars = new java.util.HashSet<>();
        for (String m : moduleRels) moduleJars.add(m.substring(m.lastIndexOf('/') + 1));
        if (ignoreList != null) {
            for (String ig : ignoreList.split(",")) {
                String t = ig.trim();
                if (!t.isEmpty()) moduleJars.add(t);
            }
        }
        lcpRels.removeIf(rel -> moduleJars.contains(rel.substring(rel.lastIndexOf('/') + 1))
                || rel.startsWith("org/ow2/asm/"));

        // === 3. 写版本目录清单（启动时 GameLauncher 按版本读取） ===
        Files.write(vd.resolve("neoforge-lcp.txt"), String.join("\n", lcpRels).getBytes(StandardCharsets.UTF_8));
        Files.write(vd.resolve("neoforge-modules.txt"), String.join("\n", moduleRels).getBytes(StandardCharsets.UTF_8));
        Files.write(vd.resolve("neoforge-natives.txt"), String.join("\n", nativesRels).getBytes(StandardCharsets.UTF_8));
        StringBuilder ab = new StringBuilder("ignoreList=").append(ignoreList == null ? "" : ignoreList).append('\n');
        int jidx = 0;
        for (String j : jpms) ab.append("jpms").append(jidx++).append('=').append(j).append('\n');
        for (Map.Entry<String, String> e : fml.entrySet()) {
            ab.append("fml.").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.write(vd.resolve("neoforge-args.txt"), ab.toString().getBytes(StandardCharsets.UTF_8));

        // === 4. 下载全部库（legacyClassPath + 模块路径去重 + natives） ===
        List<DownloadManager.Task> libTasks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rel : lcpRels) {
            if (!platformNeeded(rel)) continue;
            if (rel.equals(nfMain)) continue; // NeoForge 主库已在上方双源下载
            if (seen.add(rel)) libTasks.add(mavenTask(rel));
        }
        for (String rel : moduleRels) {
            if (!platformNeeded(rel)) continue;
            if (seen.add(rel)) libTasks.add(mavenTask(rel));
        }
        for (String rel : nativesRels) {
            if (!platformNeeded(rel)) continue;
            if (seen.add(rel)) libTasks.add(mavenTask(rel));
        }
        // NeoForge 库镜像回源节点带宽有限，用低并发下载避免把镜像打爆
        runTasks(libTasks, "下载 NeoForge 依赖库（国内镜像）", listener, 4);

        // === 4.5 固化客户端启动目标 ===
        // 从已下载的本地 loader jar 解析客户端 handler 注册名，写入实例缓存；
        // 启动期优先读取该缓存。解析不到则不写，启动时按 FML 版本规则兜底。
        String fmlVer = fml.get("fmlVersion");
        if (fmlVer == null || fmlVer.isBlank()) fmlVer = findInstalledFmlVersion(config.librariesPath());
        String cachedTarget = NeoForgeLaunchTarget.resolveFromLoaderJar(config.librariesPath(), fmlVer);
        if (cachedTarget != null) {
            Files.writeString(vd.resolve("neoforge-args.txt"),
                    "launchTarget=" + cachedTarget + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        // === 5. srg/extra（neoform 产物）从内置资源释放 ===
        releaseNeoFormArtifacts(fml, listener);

        // === 6. minecraft.jar（game layer 模块）：client jar 无 module-info，
        // securejarhandler 按文件名派生自动模块名，须以 minecraft.jar 命名提供，
        // 否则 runTarget 的 layer.findModule("minecraft") 报 NoSuchElementException。 ===
        String nfForm2 = fml.get("neoFormVersion");
        if (nfForm2 != null && !nfForm2.isBlank()) {
            Path mcModuleFile = config.librariesPath().resolve(
                    "net/minecraft/client/" + mcVersion + "-" + nfForm2 + "/minecraft.jar");
            if (!Files.exists(mcModuleFile)) {
                Path srgFile = config.librariesPath().resolve("net/minecraft/client/" + mcVersion + "-" + nfForm2
                        + "/client-" + mcVersion + "-" + nfForm2 + "-srg.jar");
                if (Files.exists(srgFile)) {
                    Files.copy(srgFile, mcModuleFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 内置 21.1.264 模块路径（win_args 不可用时的回退清单）。 */
    private static final List<String> NEOFORGE_MODULES_FALLBACK = List.of(
            "cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar",
            "cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
            "org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar",
            "org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar",
            "org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar",
            "org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar",
            "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
            "net/neoforged/JarJarFileSystems/0.4.1/JarJarFileSystems-0.4.1.jar");

    /** 内置 21.1.264 JPMS 参数（回退用）。 */
    private static final List<String> NEOFORGE_JPMS_FALLBACK = List.of(
            "--add-opens java.base/java.util.jar=cpw.mods.securejarhandler",
            "--add-opens java.base/java.lang.invoke=cpw.mods.securejarhandler",
            "--add-exports java.base/sun.security.util=cpw.mods.securejarhandler",
            "--add-exports jdk.naming.dns/com.sun.jndi.dns=java.naming");

    /** 内置 21.1.264 ignoreList（回退用）。 */
    private static final String NEOFORGE_IGNORE_FALLBACK =
            "bootstraplauncher-2.0.2.jar,securejarhandler-3.0.8.jar,"
                    + "asm-commons-9.10.1.jar,asm-util-9.10.1.jar,asm-analysis-9.10.1.jar,"
                    + "asm-tree-9.10.1.jar,asm-9.10.1.jar,JarJarFileSystems-0.4.1.jar";

    /** 解析结果容器。 */
    private record NeoArgs(List<String> modules, List<String> lcp, List<String> jpms,
                           String ignoreList, Map<String, String> fml) {
    }

    /** 安装期兜底：从 libraries/net/neoforged/fancymodloader/loader/* 目录名解析 fml 版本（取不到返回 null）。 */
    private static String findInstalledFmlVersion(Path libs) {
        Path dir = libs.resolve("net/neoforged/fancymodloader/loader");
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path vd : (Iterable<Path>) stream::iterator) {
                    if (Files.isDirectory(vd)) return vd.getFileName().toString();
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /** 从 installer.jar 中读取 data/win_args.txt（找不到返回 null）。 */
    private static String readWinArgsFromJar(Path installer) {
        try (ZipFile zf = new ZipFile(installer.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().endsWith("win_args.txt")) {
                    try (InputStream in = zf.getInputStream(e)) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 解析官方 win_args：模块路径、legacyClassPath、JPMS、ignoreList、fml 参数。 */
    private static NeoArgs parseWinArgs(String txt) {
        List<String> modules = new ArrayList<>();
        List<String> lcp = new ArrayList<>();
        List<String> jpms = new ArrayList<>();
        Map<String, String> fml = new LinkedHashMap<>();
        String ignoreList = null;
        String[] lines = txt.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if ("-p".equals(line) && i + 1 < lines.length) {
                String joined = lines[++i].trim();
                for (String rel : joined.split(";")) {
                    if (rel.startsWith("libraries/")) modules.add(rel.substring("libraries/".length()));
                }
            } else if (line.startsWith("-p ") || line.startsWith("-p\t")) {
                // win_args 中 -p 与模块路径同行（LF 行结构）
                String joined = line.substring(3).trim();
                for (String rel : joined.split(";")) {
                    if (rel.startsWith("libraries/")) modules.add(rel.substring("libraries/".length()));
                }
            } else if (line.startsWith("-DlegacyClassPath=")) {
                String v = line.substring("-DlegacyClassPath=".length());
                for (String rel : v.split(";")) {
                    if (rel.startsWith("libraries/")) lcp.add(rel.substring("libraries/".length()));
                }
            } else if (line.startsWith("-DignoreList=")) {
                ignoreList = line.substring("-DignoreList=".length());
            } else if (line.startsWith("--add-opens ") || line.startsWith("--add-exports ")) {
                jpms.add(line);
            } else if (line.startsWith("--fml.")) {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    fml.put(line.substring("--fml.".length(), sp).trim(), line.substring(sp + 1).trim());
                }
            }
        }
        return new NeoArgs(modules, lcp, jpms, ignoreList, fml);
    }

    /** 从基础版本 json 提取 lwjgl 本体库（org/lwjgl/*，不含 natives classifier，8 个）。 */
    private static List<String> lwjglFromVersionJson(Path baseDir, String mcVersion) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject v = new JSONObject(
                    Files.readString(baseDir.resolve(mcVersion + ".json"), StandardCharsets.UTF_8));
            JSONArray libs = Json.optArr(v, "libraries");
            if (libs != null) {
                for (int i = 0; i < libs.length(); i++) {
                    JSONObject l = libs.optJSONObject(i);
                    if (l == null) continue;
                    JSONObject dl = Json.optObj(l, "downloads");
                    if (dl == null) continue;
                    JSONObject art = Json.optObj(dl, "artifact");
                    if (art == null) continue;
                    String p = art.optString("path", "");
                    if (p.startsWith("libraries/org/lwjgl/")) {
                        if (!p.contains("-natives-")) out.add(p.substring("libraries/".length()));
                    } else if (p.startsWith("org/lwjgl/") && !p.contains("-natives-")) {
                        // 原版 json 的 artifact path 不带 libraries/ 前缀（classifiers 才带）
                        out.add(p);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 从基础版本 json 提取原版客户端库（排除 natives/lwjgl/client 本体，NeoForge 用 patched client）。 */
    private static List<String> vanillaLibsFromVersionJson(Path baseDir, String mcVersion) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject v = new JSONObject(
                    Files.readString(baseDir.resolve(mcVersion + ".json"), StandardCharsets.UTF_8));
            JSONArray libs = Json.optArr(v, "libraries");
            if (libs != null) {
                for (int i = 0; i < libs.length(); i++) {
                    JSONObject l = libs.optJSONObject(i);
                    if (l == null) continue;
                    JSONObject dl = Json.optObj(l, "downloads");
                    if (dl == null) continue;
                    JSONObject art = Json.optObj(dl, "artifact");
                    if (art == null) continue;
                    String p = art.optString("path", "");
                    if (p.contains("-natives-")) continue;
                    if (p.startsWith("libraries/")) p = p.substring("libraries/".length());
                    if (p.startsWith("net/minecraft/client/")) continue;
                    if (p.startsWith("org/lwjgl/")) continue;
                    out.add(p);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 从基础版本 json 的 libraries.downloads.classifiers 提取 natives-windows 库路径。 */
    private static List<String> nativesFromVersionJson(Path baseDir, String mcVersion) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject v = new JSONObject(
                    Files.readString(baseDir.resolve(mcVersion + ".json"), StandardCharsets.UTF_8));
            JSONArray libs = Json.optArr(v, "libraries");
            if (libs != null) {
                for (int i = 0; i < libs.length(); i++) {
                    JSONObject l = libs.optJSONObject(i);
                    if (l == null) continue;
                    JSONObject dl = Json.optObj(l, "downloads");
                    if (dl == null) continue;
                    JSONObject classifiers = Json.optObj(dl, "classifiers");
                    if (classifiers == null) continue;
                    JSONObject n = Json.optObj(classifiers, "natives-windows");
                    if (n == null) n = Json.optObj(classifiers, "natives-osx");
                    if (n == null) n = Json.optObj(classifiers, "natives-linux");
                    if (n == null) continue;
                    String p = n.optString("path", "");
                    if (!p.isBlank() && p.startsWith("libraries/")) {
                        out.add(p.substring("libraries/".length()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 释放内置 neoform 产物（srg/extra jar，1.21.1 的 20240808.144430 对所有 21.1.x 通用）。 */
    private void releaseNeoFormArtifacts(Map<String, String> fml, InstallListener listener) throws IOException {
        String mcVer = fml.get("mcVersion");
        String nfVer = fml.get("neoFormVersion");
        if (mcVer == null || nfVer == null || mcVer.isBlank() || nfVer.isBlank()) return;
        String key = mcVer.trim() + "-" + nfVer.trim();
        Path dir = config.librariesPath().resolve("net/minecraft/client/" + key);
        Files.createDirectories(dir);
        for (String suffix : new String[]{"srg", "extra"}) {
            Path target = dir.resolve("client-" + key + "-" + suffix + ".jar");
            if (Files.exists(target) && Files.size(target) > 100_000) continue;
            String res = "neoform-" + key + "-" + suffix + ".jar";
            try (InputStream in = com.jwh.launcher.util.Resources.open(res)) {
                if (in != null) {
                    Files.copy(in, target);
                    if (listener != null) listener.onProgress("释放 neoform " + suffix + " 库", 0, 1);
                }
            }
        }
    }

    /** zip 完整性：打开成功且全部条目可解压（含 CRC 校验）。用于识别镜像损坏的 NeoForge jar。 */
    private static boolean isZipValid(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        try {
            if (Files.size(jar) < 100_000) return false;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
                var en = zf.entries();
                while (en.hasMoreElements()) {
                    try (java.io.InputStream in = zf.getInputStream(en.nextElement())) {
                        byte[] buf = new byte[8192];
                        while (in.read(buf) >= 0) { /* 解压+CRC 由 ZipFile 流完成校验 */ }
                    }
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** NeoForge universal 校验：zip 全条目可解压，且内嵌 jarjar 含与版本匹配的 coremods。
     * 镜像损坏的主 jar 常表现为内嵌 jarjar 数据损坏、coremods 版本错配（如 265 装 264），
     * 通过此校验可拦截损坏文件并触发重下。 */
    private static boolean isNeoForgeUniversalValid(Path jar, String loaderVersion) {
        if (!isZipValid(jar)) return false;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
            String expect = "net.neoforged.neoforge-coremods-" + loaderVersion + ".jar";
            boolean hasMeta = false, hasCoremods = false;
            var en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.startsWith("META-INF/jarjar/")) continue;
                hasMeta = true;
                if (n.equals("META-INF/jarjar/" + expect)) hasCoremods = true;
            }
            return hasMeta && hasCoremods;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 静默删除（校验不通过时清掉损坏文件，避免大小判断误跳过）。 */
    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    /** 释放内置 NeoForge 兜底库。universal 用 21.1.264 通用产物；client 按 MC 版本选专属产物：
     *  1.21.4 → 本机处理器链生成的 21.4.161 补丁层（公开 maven 不发布含受版权 MC 类的 client 产物），
     *  其余 21.1.x → 21.1.264 产物。版本专属 client 强制覆盖现有错配文件。 */
    private void releaseNeoForgeFallback(Path target, boolean isClient, String mcVersion,
                                         String loaderVersion) throws IOException {
        boolean versionSpecificClient = isClient && mcVersion != null
                && mcVersion.trim().equals("1.21.4");
        String res;
        if (versionSpecificClient) {
            res = "/neoforge-21.4.161-client.jar";
        } else {
            res = isClient ? "/neoforge-fallback-client.jar" : "/neoforge-fallback-universal.jar";
        }
        // 通用兜底：已有有效文件则保留；版本专属 client：以内置本版本产物强制覆盖错配旧文件
        if (!versionSpecificClient && Files.exists(target) && Files.size(target) > 100_000) return;
        Files.createDirectories(target.getParent());
        try (InputStream in = com.jwh.launcher.util.Resources.open(res.substring(1))) {
            if (in == null) throw new IOException("内置 NeoForge 兜底库缺失（资源 " + res + "），请使用完整版启动器压缩包，或等待资源层更新补齐");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 该库是否当前平台需要（按文件名平台标记过滤：netty epoll=linux 专用、kqueue=mac 专用）。 */
    private static boolean platformNeeded(String rel) {
        String lower = rel.toLowerCase();
        var os = OSUtil.currentOs();
        if (lower.contains("linux")) return os == OSUtil.OS.LINUX;
        if (lower.contains("osx") || lower.contains("macos")) return os == OSUtil.OS.MACOS;
        if (lower.contains("windows")) return os == OSUtil.OS.WINDOWS;
        return true; // 无平台标记 = 通用库
    }

    /** 内置清单 → 库下载任务：Maven Central 镜像优先，BMCLAPI 兜底。
     *  BMCLAPI 的后端节点（如 mirrors.jcut.edu.cn）近期大面积 404，而 asm/log4j/lwjgl
     *  这些通用库在 Central 镜像上稳定且极快；Mojang/NeoForge 专有的在 Central 上没有，
     *  会立刻 404 再回落到 BMCLAPI（它的 302 节点是轮换的，Downloader 会整轮重试）。 */
    private DownloadManager.Task mavenTask(String rel) {
        Path target = config.librariesPath().resolve(rel.replace('/', java.io.File.separatorChar));
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        // Mojang 自家库（authlib / datafixerupper / text2speech / patchy / blocklist…）
        // 只有官方库站有，Central 镜像与 BMCLAPI 都没有；本机实测官方库站 0.2~0.7 秒返回 200
        if (rel.startsWith("com/mojang/")) {
            return new DownloadManager.Task("https://libraries.minecraft.net/" + rel, target, 0, name,
                    "https://bmclapi2.bangbang93.com/maven/" + rel);
        }
        // 第三兜底：Maven Central 直连。aliyun 同步延迟或路径差异、BMCL 节点轮换 404 时，
        // Central 直连对 asm/jna/fastutil/icu4j/guava/log4j 等通用库稳定可用；
        // 专有组（neoforged/fabricmc 等）在 Central 上 404，会被结构校验当场识破并换下一个源。
        // 第四兜底：NeoForged 官方 CDN（forgecdn）。events/JarJarFileSystems/loader/neoforgespi
        // 等仅发布在官方 maven、国内镜像未收录的旧版依赖，在此可按 maven 相对路径取到官方构建；
        // 非 neoforged 组在此 404，同样由结构校验识破后跳过。
        return new DownloadManager.Task("https://maven.aliyun.com/repository/public/" + rel,
                target, 0, name, "https://bmclapi2.bangbang93.com/maven/" + rel,
                List.of("https://repo1.maven.org/maven2/" + rel,
                        "https://neoforged.forgecdn.net/releases/" + rel));
    }

    /** 读取启动器内置清单资源（每行一个相对路径）。 */
    private static List<String> loadResourceLines(String name) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = GameInstaller.class.getResourceAsStream("/" + name)) {
            if (in == null) return out;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) out.add(line.trim());
                }
            }
        }
        return out;
    }

    private void runTasks(List<DownloadManager.Task> tasks, String step, InstallListener listener) throws Exception {
        // 依赖库几乎全是小文件（KB~MB 级），并发必须拉高：原来的 downloadThreads（通常 4-8 路）
        // 一旦碰上不吐字节的源，单个文件就占住一条线程直到预算耗尽，整批被拖成上百秒。
        // 这里与素材阶段保持一致，16~32 路并发。
        runTasks(tasks, step, listener, Math.max(16, Math.min(32, this.threads * 4)));
    }

    private void runTasks(List<DownloadManager.Task> tasks, String step, InstallListener listener,
                          int threads) throws Exception {
        if (tasks.isEmpty()) {
            if (listener != null) listener.onProgress(step + "：无缺失", 0, 0);
            return;
        }
        DownloadManager.ProgressListener pl = new DownloadManager.ProgressListener() {
            @Override
            public void onProgress(int done, int total, long bytes, String label) {
                if (listener != null) {
                    listener.onProgress(step + "（" + label + "）", done, total);
                    // 整体字节进度：本批次全部待下载文件的预计大小之和（未知大小的不计）
                    long totalBytes = 0;
                    for (DownloadManager.Task t : tasks) {
                        if (t.expectedSize() > 0) totalBytes += t.expectedSize();
                    }
                    if (totalBytes > 0) listener.onBytes(step, bytes, totalBytes);
                }
            }

            @Override
            public void onFinish(boolean success, int failed) {
            }
        };
        // 库阶段同样用「短读超时 + 短预算」：坏源 20 秒内被踢出，整批不会等某个库文件等满 90 秒以上
        DownloadManager dm = new DownloadManager(Math.max(1, threads), pl, 8, 40);
        // 挂当前下载管理器供取消按钮调用；下载结束清空
        activeDm = dm;
        try {
            List<DownloadManager.Task> failed = dm.run(tasks);
            // 服务端 srg 包（net/minecraft/server/*-extra.jar）只给 dedicated server 用，
            // 客户端进游戏不会加载它；镜像站上没有这个文件、官方 maven 又常连不上，
            // 所以把它列为可选项：缺了不拦整次安装，否则一定会卡在「安装失败」。
            List<DownloadManager.Task> hardFailed = new ArrayList<>();
            List<DownloadManager.Task> optionalFailed = new ArrayList<>();
            for (DownloadManager.Task t : failed) {
                if (isServerOnlyOptional(t)) optionalFailed.add(t);
                else hardFailed.add(t);
            }
            if (!optionalFailed.isEmpty() && listener != null && !dm.isCancelled()) {
                listener.onProgress(step + "：已跳过 " + optionalFailed.size()
                        + " 个服务端专用文件（不影响客户端启动）", 0, 0);
            }
            if (!hardFailed.isEmpty()) {
                StringBuilder sb = new StringBuilder(step + "：有 " + hardFailed.size() + " 个文件下载失败");
                int shown = 0;
                for (DownloadManager.Task t : hardFailed) {
                    if (shown++ >= 4) break;
                    String u = t.url();
                    int slash = u.lastIndexOf('/');
                    sb.append("\n· ").append(slash >= 0 ? u.substring(slash + 1) : u);
                }
                if (hardFailed.size() > 4) sb.append("\n· 等 ").append(hardFailed.size()).append(" 个文件");
                if (dm.isCancelled()) {
                    sb.append("\n（已取消，未完整的文件已删除）");
                } else {
                    sb.append("\n请检查网络后重试");
                }
                throw new IOException(sb.toString());
            }
        } finally {
            activeDm = null;
        }
    }

    /**
     * 预取一批 Maven 坐标库到本地 libraries（aliyun / BMCLAPI / Central 三源，复用 {@link #mavenTask}）。
     * 供老 Forge/NeoForge 官方安装器安装前预热：本地通过结构/校验的库，官方安装器会跳过联网，
     * 避免其内部直连官方 maven 超时。返回仍下载失败的 rel 列表；不抛异常、不阻断主流程。
     */
    public List<String> prefetchMavenArtifacts(List<String> rels, String step, InstallListener listener) {
        List<DownloadManager.Task> tasks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String rel : rels) {
            if (rel == null || rel.isBlank()) continue;
            if (!platformNeeded(rel)) continue;
            if (seen.add(rel)) tasks.add(mavenTask(rel));
        }
        if (tasks.isEmpty()) return List.of();
        DownloadManager dm = new DownloadManager(Math.min(8, tasks.size()), new DownloadManager.ProgressListener() {
            @Override
            public void onProgress(int done, int total, long bytes, String label) {
                if (listener != null) listener.onProgress(step + "（" + label + "）", done, total);
            }

            @Override
            public void onFinish(boolean success, int failed) {
            }
        }, 8, 40);
        activeDm = dm;
        List<DownloadManager.Task> failed;
        try {
            failed = dm.run(tasks);
        } finally {
            activeDm = null;
        }
        List<String> miss = new ArrayList<>();
        Path libs = config.librariesPath();
        for (DownloadManager.Task t : failed) {
            miss.add(libs.relativize(t.target()).toString().replace('\\', '/'));
        }
        return miss;
    }

    /** 服务端专用 srg 包：客户端启动不需要它，镜像与官方都拿不到时允许缺。 */
    private static boolean isServerOnlyOptional(DownloadManager.Task t) {
        String p = t.target() == null ? "" : t.target().toString().replace('\\', '/');
        return p.contains("/net/minecraft/server/") && p.endsWith("-extra.jar");
    }

    // ---------- libraries ----------

    private record LibraryResolved(String label, Artifact artifact, Artifact natives) {
    }

    private record Artifact(String url, Path path, long size, String officialUrl) {
    }

    private List<LibraryResolved> resolveLibraries(JSONObject versionJson) {
        List<LibraryResolved> out = new ArrayList<>();
        JSONArray arr = versionJson.optJSONArray("libraries");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject lib = arr.getJSONObject(i);
            if (!rulesAllow(lib.optJSONArray("rules"))) continue;
            String name = lib.optString("name");
            if (name.isBlank()) continue;
            String[] parts = name.split(":");
            String group = parts[0];
            String artifactName = parts.length > 1 ? parts[1] : "unknown";
            String version = parts.length > 2 ? parts[2] : "unknown";
            String classifier = parts.length > 3 ? parts[3] : null;

            String relPath = group.replace('.', '/') + "/" + artifactName + "/" + version
                    + "/" + artifactName + "-" + version
                    + (classifier == null ? "" : "-" + classifier) + ".jar";
            Path target = config.librariesPath().resolve(relPath);
            String label = artifactName + "-" + version;

            JSONObject downloads = Json.optObj(lib, "downloads");
            // 老格式加载器 profile（fabric-meta/quilt-meta 生成）：库顶层带 url 字段（如
            // https://maven.quiltmc.org/repository/release/），没有 downloads.artifact。
            // 必须用顶层 url 拼路径，否则回落默认 libraries.minecraft.net 必然 404。
            String topUrl = lib.optString("url", "");
            if (!topUrl.isBlank() && !topUrl.endsWith("/")) topUrl = topUrl + "/";
            Artifact artifact = null;
            if (downloads != null && downloads.has("artifact")) {
                JSONObject a = downloads.optJSONObject("artifact");
                String official = a.optString("url", DEFAULT_LIBRARY_BASE + relPath);
                artifact = new Artifact(src(official, null), target, a.optLong("size", 0), official);
            } else {
                String base = topUrl.isBlank() ? DEFAULT_LIBRARY_BASE : topUrl;
                artifact = new Artifact(src(base + relPath, null), target, 0, base + relPath);
            }

            Artifact natives = null;
            JSONObject classifiers = downloads == null ? null : Json.optObj(downloads, "classifiers");
            if (classifiers != null) {
                String osName = switch (OSUtil.currentOs()) {
                    case WINDOWS -> "natives-windows";
                    case MACOS -> "natives-osx";
                    default -> "natives-linux";
                };
                String nativeKey = osName + (OSUtil.arch().equals("64") ? "-64" : "");
                JSONObject n = Json.optObj(classifiers, nativeKey);
                if (n == null) n = Json.optObj(classifiers, osName);
                if (n != null) {
                    // 原生库解压目录：与 jar 同目录下的 natives-<os>-<arch>
                    String nRel = group.replace('.', '/') + "/" + artifactName + "/" + version + "/"
                            + artifactName + "-" + version + "-" + nativeKey;
                    Path nJar = config.librariesPath().resolve(nRel + ".jar");
                    Path nDir = config.librariesPath().resolve(nRel + "-natives");
                    String nOfficial = n.optString("url", DEFAULT_LIBRARY_BASE + nRel + ".jar");
                    natives = new Artifact(src(nOfficial, null), nJar, n.optLong("size", 0), nOfficial);
                    extractNative(nJar, nDir);
                }
            }
            out.add(new LibraryResolved(label, artifact, natives));
        }
        return out;
    }

    /** Minecraft rules 过滤：无 rules=允许；按顺序取第一个匹配的 action，都不匹配=拒绝。 */
    private boolean rulesAllow(JSONArray rules) {
        if (rules == null) return true;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            boolean match = true;
            JSONObject os = Json.optObj(rule, "os");
            if (os != null) {
                if (os.has("name") && !OSUtil.osMatches(os.optString("name"))) match = false;
                if (os.has("arch") && !OSUtil.archMatches(os.optString("arch"))) match = false;
                // os.version（罕见）简单放行，不阻断
            }
            JSONObject features = Json.optObj(rule, "features");
            if (features != null && match) {
                for (String key : features.keySet()) {
                    boolean expected = features.optBoolean(key);
                    if (launcherFeature(key) != expected) { match = false; break; }
                }
            }
            if (match) {
                return "allow".equals(rule.optString("action", "allow"));
            }
        }
        return false;
    }

    /** 启动器特性表：与 GameLauncher 保持一致，is_demo_user 必须为 false。 */
    private boolean launcherFeature(String key) {
        return switch (key) {
            case "is_demo_user" -> false;
            case "has_custom_resolution" -> true;
            case "has_quick_plays_support" -> false;
            case "is_quick_play_singleplayer" -> false;
            case "is_quick_play_multiplayer" -> false;
            case "is_quick_play_realms" -> false;
            default -> false;
        };
    }

    /** 解压原生库 jar（跳过 META-INF）。 */
    private void extractNative(Path nJar, Path nDir) {
        if (!Files.exists(nJar)) return;
        try {
            Files.createDirectories(nDir);
            try (ZipFile zf = new ZipFile(nJar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    if (n.startsWith("META-INF/")) continue;
                    Path out = nDir.resolve(n).normalize();
                    if (!out.startsWith(nDir)) continue;
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception e) {
            // 解压失败不影响主流程（启动时可能缺失原生库，启动前再校验）
        }
    }

    // ---------- assets ----------

    private void downloadAssets(JSONObject v, InstallListener listener) throws Exception {
        JSONObject assetIndex = Json.optObj(v, "assetIndex");
        if (assetIndex == null) return;
        String assetsName = assetIndex.optString("id", "legacy");
        Path indexFile = config.assetsPath().resolve("indexes").resolve(assetsName + ".json");

        // 下载索引。BMCLAPI 无 asset index 镜像端点（/assets/indexes 404），
        // 直接拉官方原始地址（不走 Mirror.rewrite，避免被 piston-data 规则误改写）；
        // 失败不阻断安装：缺少 index 时跳过资源下载，游戏仍可启动（资源缺失可后续修复）。
        if (!Files.exists(indexFile)) {
            if (listener != null) listener.onProgress("下载资源索引 " + assetsName, 0, 1);
            String idxUrl = assetIndex.optString("url");
            try {
                String text = Http.getText(idxUrl);
                Files.createDirectories(indexFile.getParent());
                Files.writeString(indexFile, text, StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.err.println("资源索引下载失败（跳过资源下载）: " + idxUrl + " -> " + e);
                return;
            }
        }

        // 收集 objects
        JSONObject index = new JSONObject(Files.readString(indexFile, StandardCharsets.UTF_8));
        JSONObject objects = index.optJSONObject("objects");
        if (objects == null) return;
        List<DownloadManager.Task> tasks = new ArrayList<>();
        long[] totalAssetsBytes = new long[]{0};
        objects.keys().forEachRemaining(key -> {
            JSONObject obj = objects.optJSONObject(key);
            if (obj == null) return;
            String hash = obj.optString("hash");
            long size = obj.optLong("size", 0);
            if (hash.isBlank()) return;
            Path target = config.assetsPath().resolve("objects")
                    .resolve(hash.substring(0, 2)).resolve(hash);
            if (Http.fileOk(target, size)) return; // 已存在且大小匹配：跳过，不计入总量
            totalAssetsBytes[0] += size;
            // assets 对象实测：BMCLAPI 的 /assets 端点返回 302 回源到不稳定节点（大量语言
            // 文件因此每个耗满 90 秒预算仍下不动，进度停在最后一截）；官方 CDN 实测稳定
            // 250KB/s 以上，故素材对象固定走官方地址，不经 Mirror 改写。
            String assetUrl = ASSET_BASE + hash.substring(0, 2) + "/" + hash;
            tasks.add(new DownloadManager.Task(
                    assetUrl, target, size, key, assetUrl));
        });

        if (tasks.isEmpty()) return;
        // assets 单文件短读超时(30s)+总预算(90s)：个别坏节点快速踢出不拖死整批；
        // 资源失败不阻断安装（游戏仍可启动，可后续修复），进度持续推进
        final long totalBytes = totalAssetsBytes[0];
        // assets 全是 KB 级小文件、属于纯 IO 等待：并发拉到 32 路，5147 个对象可从
        // 几十分钟压到一两分钟；单文件 20 秒读超时 + 40 秒总预算，坏节点快速踢掉
        int assetThreads = Math.max(16, Math.min(32, this.threads * 4));
        DownloadManager dm = new DownloadManager(assetThreads, new DownloadManager.ProgressListener() {
            @Override
            public void onProgress(int done, int total, long bytes, String label) {
                if (listener != null) {
                    listener.onProgress("下载资源文件 " + assetsName + "（" + label + "）", done, total);
                    if (totalBytes > 0) listener.onBytes("下载资源文件 " + assetsName, bytes, totalBytes);
                }
            }

            @Override
            public void onFinish(boolean success, int failed) {
            }
        }, 20, 40);
        activeDm = dm; // 挂当前下载管理器：取消按钮在资源阶段也生效
        try {
            List<DownloadManager.Task> failed = dm.run(tasks);
            if (!failed.isEmpty()) {
                // 个别资源失败通常不影响启动（会回退），不抛异常，仅记录
                System.err.println("assets 下载失败 " + failed.size() + " 个（已跳过，可后续修复）");
            }
        } finally {
            activeDm = null;
        }
    }
}
