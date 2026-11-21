package com.jwh.launcher.download;

import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.game.GameInstaller;
import com.jwh.launcher.loader.FabricQuiltInstaller;
import com.jwh.launcher.loader.ForgeInstaller;
import com.jwh.launcher.util.Http;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 整合包（Modrinth .mrpack）安装器。
 * 流程：下载 .mrpack → 解析 modrinth.index.json →
 * 先装好依赖加载器（或原版）作为基础版本 → 复制为独立实例 →
 * 按 index.files 下载 mods / 配置等文件 → 释放 overrides 覆盖。
 * 支持两种入口：在线（下载中心）与本地文件（拖入 .mrpack 导入）。
 */
public class ModpackInstaller {

    public interface Listener {
        /** step 为当前阶段描述；done/total 为当前阶段进度。 */
        void onProgress(String step, int done, int total);

        /** 当前阶段真实字节进度（下载大文件时优先用于进度条；总字节未知时不回调）。 */
        default void onBytes(String step, long bytesDone, long bytesTotal) {
        }
    }

    private final LauncherConfig config;

    public ModpackInstaller(LauncherConfig config) {
        this.config = config;
    }

    /**
     * 在线安装整合包（下载中心用）。
     * @return 安装后的独立版本 id（主页可直接启动）
     */
    /** 当前下载控制（取消标志）；null 表示不可取消。 */
    private com.jwh.launcher.util.Http.DownloadControl ctl;

    public void setDownloadControl(com.jwh.launcher.util.Http.DownloadControl c) {
        this.ctl = c;
    }

    public String install(String projectId, ModrinthApi.Version version, Listener listener) throws Exception {
        ModrinthApi api = new ModrinthApi();
        ModrinthApi.Project proj = api.project(projectId);
        ModrinthApi.File mrpack = version.primaryFile();
        if (mrpack == null) {
            throw new IllegalStateException("该整合包版本没有可下载的文件。");
        }
        String packId = sanitize(proj.slug());
        if (packId.isBlank()) {
            throw new IllegalStateException("整合包 slug 无效，无法创建版本目录。");
        }

        // 1. 下载 .mrpack（大整合包可达 100MB+；用户网络实测慢速波动 30-150KB/s，
        // 预算按大小放宽：30KB/s 悲观速率 + 300s 余量（失败不删半截、可续传，放宽只防误杀）
        Path tmp = Files.createTempFile("mrpack-", ".mrpack");
        try {
            if (listener != null) listener.onProgress("下载整合包文件 " + mrpack.filename(), 0, 1);
            if (ctl != null && ctl.isCancelled()) throw new IOException("整合包安装已取消");
            final long packSize = Math.max(1, mrpack.size());
            int budgetSec = (int) Math.min(7200, Math.max(900, packSize / 30_000L + 300));
            DownloadManager dm = new DownloadManager(1, new DownloadManager.ProgressListener() {
                @Override
                public void onProgress(int done, int total, long bytes, String label) {
                    if (listener != null) {
                        listener.onProgress("下载整合包文件 " + mrpack.filename(), done, total);
                        // 单文件按真实字节推进整体进度（0→100 连续，不虚报）
                        listener.onBytes("下载整合包文件 " + mrpack.filename(), bytes, packSize);
                    }
                }

                @Override
                public void onFinish(boolean success, int failed) {
                }
            }, 60, budgetSec, ctl);
            List<DownloadManager.Task> failed = dm.run(java.util.List.of(
                    new DownloadManager.Task(mrpack.url(), tmp, packSize, mrpack.filename(), null)));
            if (!failed.isEmpty()) {
                if (ctl != null && ctl.isCancelled()) throw new IOException("整合包安装已取消");
                StringBuilder sb = new StringBuilder("整合包文件下载失败：");
                int shown = 0;
                for (DownloadManager.Task t : failed) {
                    if (shown++ >= 4) break;
                    sb.append("\n· ").append(t.label());
                }
                if (failed.size() > 4) sb.append("\n· 等 ").append(failed.size()).append(" 个文件");
                throw new IOException(sb.toString());
            }
            return installFromLocalFile(tmp, packId, listener);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 从本地 .mrpack 文件安装整合包（拖入导入用）。
     * packId 由包内 modrinth.index.json 的 name 生成。
     * @return 安装后的独立版本 id
     */
    public String installLocal(Path mrpackFile, Listener listener) throws Exception {
        String packId = null;
        try (ZipFile zf = new ZipFile(mrpackFile.toFile())) {
            ZipEntry idx = zf.getEntry("modrinth.index.json");
            if (idx == null) {
                throw new IllegalStateException("整合包格式损坏（缺少 modrinth.index.json）。");
            }
            try (InputStream in = zf.getInputStream(idx)) {
                JSONObject index = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                String name = index.optString("name", "");
                if (name.isBlank()) {
                    throw new IllegalStateException("整合包未声明名称，无法安装。");
                }
                packId = sanitize(name);
            }
        }
        if (packId == null || packId.isBlank()) {
            throw new IllegalStateException("整合包名称无效，无法创建版本目录。");
        }
        return installFromLocalFile(mrpackFile, packId, listener);
    }

    /**
     * 安装核心：解析本地 .mrpack → 装依赖加载器 → 建独立实例 → 下载内容 → 释放 overrides。
     */
    private String installFromLocalFile(Path mrpack, String packId, Listener listener) throws Exception {
        Path vd = config.versionsPath().resolve(packId);
        if (Files.exists(vd.resolve(packId + ".json"))) {
            throw new IllegalStateException("整合包「" + packId + "」已安装（主页已有版本 " + packId + "）。");
        }

        // 2. 解析 mrpack（zip）：读 modrinth.index.json；overrides（地图/配置/资源包）暂存内存，
        //    在基础版本复制完成后才释放（此前版本先释放再被清空目录，导致整合包地图与配置丢失）
        JSONObject index = null;
        java.util.Map<String, byte[]> overrides = new java.util.LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(mrpack.toFile())) {
            for (java.util.Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if ("modrinth.index.json".equals(n)) {
                    try (InputStream in = zf.getInputStream(e)) {
                        index = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                } else if (n.startsWith("overrides/")) {
                    try (InputStream in = zf.getInputStream(e)) {
                        overrides.put(n, in.readAllBytes());
                    }
                }
            }
        }
        if (index == null) {
            throw new IllegalStateException("整合包格式损坏（缺少 modrinth.index.json）。");
        }
        // 3. 依赖解析：MC 版本 + 加载器
        JSONObject deps = index.optJSONObject("dependencies");
        String mc = deps == null ? "" : deps.optString("minecraft", "");
        if (mc.isBlank()) {
            throw new IllegalStateException("整合包未声明 Minecraft 版本，无法安装。");
        }
        // 依赖加载器阶段明确提示（首次安装 Forge/Fabric 联网较慢，避免看起来像卡死）
        if (listener != null) listener.onProgress("解析依赖（整合包要求 " + mc + "）", 0, 1);
        String fab = deps == null ? "" : deps.optString("fabric-loader", "");
        String quilt = deps == null ? "" : deps.optString("quilt-loader", "");
        String forge = deps == null ? "" : deps.optString("forge", "");
        String neo = deps == null ? "" : deps.optString("neoforge", "");
        String baseId;
        if (!fab.isBlank()) {
            baseId = installFabric(FabricQuiltInstaller.Platform.FABRIC, mc, stripVer(fab), listener);
        } else if (!quilt.isBlank()) {
            baseId = installFabric(FabricQuiltInstaller.Platform.QUILT, mc, stripVer(quilt), listener);
        } else if (!forge.isBlank()) {
            baseId = installForge(ForgeInstaller.Platform.FORGE, mc, stripVer(forge), listener);
        } else if (!neo.isBlank()) {
            baseId = installForge(ForgeInstaller.Platform.NEOFORGE, mc, stripVer(neo), listener);
        } else {
            baseId = installVanilla(mc, listener);
        }

        // 4. 复制基础版本 → 独立整合包实例（libraries / assets 共享，无需复制）
        Path baseDir = config.versionsPath().resolve(baseId);
        boolean baseExisted = Files.exists(baseDir.resolve(baseId + ".json"));
        if (!Files.exists(baseDir.resolve(baseId + ".json"))) {
            throw new IllegalStateException("基础版本 " + baseId + " 安装不完整，无法继续。");
        }
        // Forge/NeoForge 版本 json 采用 inheritsFrom 继承原版（版本目录本身不含自己的 jar）：
        // 完整性标准是父版本 json+jar 齐备；缺失则自动补装父版本（原版）。
        JSONObject baseJson = new JSONObject(
                Files.readString(baseDir.resolve(baseId + ".json"), StandardCharsets.UTF_8));
        String parent = baseJson.optString("inheritsFrom", "");
        if (!parent.isBlank()) {
            Path pdir = config.versionsPath().resolve(parent);
            if (!Files.exists(pdir.resolve(parent + ".json")) || !Files.exists(pdir.resolve(parent + ".jar"))) {
                if (listener != null) listener.onProgress("补装基础原版 " + parent, 0, 1);
                new GameInstaller(config).install(parent, null, (step, done, total) -> {
                    if (listener != null) listener.onProgress(step, done, total);
                });
            }
        } else if (!Files.exists(baseDir.resolve(baseId + ".jar"))) {
            // 独立版本（原版）：本目录 jar 缺失 → 补装
            if (listener != null) listener.onProgress("补全版本文件 " + baseId, 0, 1);
            new GameInstaller(config).install(baseId, null, (step, done, total) -> {
                if (listener != null) listener.onProgress(step, done, total);
            });
        }
        // 补齐基础版本缺失的依赖库（老 Forge 的 nashorn 等经镜像下载），保证实例启动不缺库
        if (listener != null) listener.onProgress("修复基础版本依赖 " + baseId, 0, 1);
        new GameInstaller(config).repair(baseId, (step, done, total) -> {
            if (listener != null) listener.onProgress(step, done, total);
        });
        if (Files.exists(vd)) {
            deleteDir(vd);
        }
        Files.createDirectories(vd);
        copyDir(baseDir, vd);
        Files.move(vd.resolve(baseId + ".json"), vd.resolve(packId + ".json"),
                StandardCopyOption.REPLACE_EXISTING);
        // 继承版本（Forge/NeoForge）目录不含自己的 jar：存在才复制，避免 move 抛 NoSuchFileException
        Path baseJar = vd.resolve(baseId + ".jar");
        if (Files.exists(baseJar)) {
            Files.move(baseJar, vd.resolve(packId + ".jar"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        // 版本 JSON 的 id 与实例名保持一致（启动参数 / 日志识别用）
        JSONObject vj = new JSONObject(Files.readString(vd.resolve(packId + ".json"), StandardCharsets.UTF_8));
        vj.put("id", packId);
        Files.writeString(vd.resolve(packId + ".json"), vj.toString(), StandardCharsets.UTF_8);

        // 5. 释放整合包 overrides（地图存档 saves / 配置 config / 资源包等）→ 实例目录
        int ovTotal = overrides.size();
        int ovDone = 0;
        for (java.util.Map.Entry<String, byte[]> e : overrides.entrySet()) {
            Path out = vd.resolve(e.getKey().substring("overrides/".length())).normalize();
            if (!out.startsWith(vd)) continue;
            Files.createDirectories(out.getParent());
            Files.write(out, e.getValue());
            ovDone++;
            if (listener != null && (ovDone % 20 == 0 || ovDone == Math.max(ovTotal, 1))) {
                listener.onProgress("释放整合包配置 " + ovDone + "/" + Math.max(ovTotal, 1), ovDone, Math.max(ovTotal, 1));
            }
        }

        // 5. 下载 index.files 里的内容（mods / config / 资源包等）：并发 + 单文件 90s 预算快速踢出
        JSONArray files = index.optJSONArray("files");
        int total = files == null ? 0 : files.length();
        List<DownloadManager.Task> contentTasks = new ArrayList<>();
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject fo = files.optJSONObject(i);
                if (fo == null) continue;
                String path = fo.optString("path", "");
                JSONArray dl = fo.optJSONArray("downloads");
                if (path.isBlank() || dl == null || dl.length() == 0) continue;
                Path dest = vd.resolve(path).normalize();
                if (!dest.startsWith(vd)) continue;
                if (ctl != null && ctl.isCancelled()) throw new IOException("整合包安装已取消");
                contentTasks.add(new DownloadManager.Task(dl.getString(0), dest, -1, path, null));
            }
        }
        if (!contentTasks.isEmpty()) {
            DownloadManager dm = new DownloadManager(Math.max(1, config.downloadThreads),
                    new DownloadManager.ProgressListener() {
                        @Override
                        public void onProgress(int d, int t, long bytes, String label) {
                            if (listener != null) {
                                // 大文件下载中界面持续显示当前字节，避免「计数停在 27/47 像卡死」
                                String extra = bytes > 0 ? " · " + fmtBytes(bytes) : "";
                                listener.onProgress("下载整合包内容 " + d + "/" + t + extra, d, t);
                            }
                        }

                        @Override
                        public void onFinish(boolean success, int failed) {
                        }
                    }, 150, 5400, ctl);
            List<DownloadManager.Task> failed = dm.run(contentTasks);
            if (!failed.isEmpty()) {
                if (ctl != null && ctl.isCancelled()) throw new IOException("整合包安装已取消");
                StringBuilder sb = new StringBuilder("整合包内容下载失败：");
                int shown = 0;
                for (DownloadManager.Task t : failed) {
                    if (shown++ >= 4) break;
                    sb.append("\n· ").append(t.label());
                }
                if (failed.size() > 4) sb.append("\n· 等 ").append(failed.size()).append(" 个文件");
                throw new IOException(sb.toString());
            }
        }
        // 7. 清理：本次安装临时新建的基础版本（无 mods 的空壳实例）不再保留，
        //    避免主页出现「一个装了 mod、一个没装」的两个重复版本
        if (!baseExisted) {
            deleteDir(baseDir);
        }
        if (listener != null) listener.onProgress("整合包安装完成", total, total);
        return packId;
    }

    // ---------- 基础版本安装 ----------

    private String installFabric(FabricQuiltInstaller.Platform p, String mc, String loaderVersion,
                                 Listener listener) throws Exception {
        if (listener != null) listener.onProgress("安装 " + (p == FabricQuiltInstaller.Platform.FABRIC ? "Fabric" : "Quilt") + " 加载器", 0, 1);
        FabricQuiltInstaller inst = new FabricQuiltInstaller(config);
        return inst.install(p, mc, loaderVersion.isBlank() ? null : loaderVersion,
                (step, done, total) -> {
                    if (listener != null) listener.onProgress(step, done, total);
                });
    }

    private String installForge(ForgeInstaller.Platform p, String mc, String loaderVersion,
                                Listener listener) throws Exception {
        if (listener != null) listener.onProgress("安装 " + (p == ForgeInstaller.Platform.FORGE ? "Forge" : "NeoForge") + " 加载器", 0, 1);
        ForgeInstaller inst = new ForgeInstaller(config);
        return inst.install(p, mc, loaderVersion.isBlank() ? null : loaderVersion,
                javaExe(), line -> {
                    if (listener != null) listener.onProgress(line, 0, 1);
                }, (step, done, total) -> {
                    if (listener != null) listener.onProgress(step, done, total);
                });
    }

    private String installVanilla(String mc, Listener listener) throws Exception {
        if (listener != null) listener.onProgress("安装原版 " + mc, 0, 1);
        new GameInstaller(config).install(mc, null, (step, done, total) -> {
            if (listener != null) listener.onProgress(step, done, total);
        });
        return mc;
    }

    private Path javaExe() {
        try {
            if (config.javaPath != null && !config.javaPath.isBlank() && Files.exists(Path.of(config.javaPath))) {
                return Path.of(config.javaPath);
            }
        } catch (Exception ignored) {
        }
        var runtimes = com.jwh.launcher.game.JavaFinder.find();
        if (!runtimes.isEmpty()) return runtimes.get(0).javaExe();
        return Path.of("java");
    }

    // ---------- 工具 ----------

    /** 依赖版本约束（如 >=0.14.23、0.16.10）→ 去掉符号前缀取纯版本号。 */
    private static String stripVer(String s) {
        if (s == null) return "";
        String t = s.trim();
        int i = 0;
        while (i < t.length() && !Character.isDigit(t.charAt(i))) i++;
        return t.substring(i).trim();
    }

    /** slug → 版本目录名（小写字母数字连字符，防路径注入）。 */
    private static String sanitize(String slug) {
        if (slug == null) return "";
        return slug.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        try (var s = Files.walk(src)) {
            s.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path out = dst.resolve(rel);
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    /** 字节 → 人类可读大小（下载中进度展示用）。 */
    private static String fmtBytes(long b) {
        if (b >= 1024L * 1024L) return String.format("%.1fMB", b / (1024.0 * 1024.0));
        if (b >= 1024L) return String.format("%.0fKB", b / 1024.0);
        return b + "B";
    }
}
