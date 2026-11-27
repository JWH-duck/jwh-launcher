package com.jwh.launcher.game;

import com.jwh.launcher.config.LauncherConfig;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地已安装版本扫描（versions/<id>/<id>.json 存在即视为已安装）。
 * 同时提供版本 JSON 的读写入口。
 */
public class LocalVersions {

    private final LauncherConfig config;

    public LocalVersions(LauncherConfig config) {
        this.config = config;
    }

    public record LocalVersion(String id, String mainClass, boolean hasJar, long sizeBytes,
                               String mcBase, String loaderName, String loaderVer) {
        /** 粗略判断：json 中 mainClass 含 "forge"/"neoforge"/"fabric"/"quilt" 时为加载器版本。 */
        public String kind() {
            String m = (mainClass == null ? "" : mainClass.toLowerCase()) + " " + id.toLowerCase();
            if (m.contains("neoforge")) return "NeoForge";
            if (m.contains("forge")) return "Forge";
            if (m.contains("fabric") || m.contains("quilt")) return "Fabric/Quilt";
            return "原版";
        }

        /** 通用显示名：加载器版本显示为 "1.21.1 · NeoForge 21.1.264"，原版直接显示 id。 */
        public String displayName() {
            if (loaderName != null && !loaderName.isBlank()) {
                return mcBase + " · " + loaderName + (loaderVer == null || loaderVer.isBlank() ? "" : " " + loaderVer);
            }
            return id;
        }

        /** 简名（不含加载器版本号）："1.21.1 · NeoForge"，原版直接显示 id。用于实例下拉等窄列表。 */
        public String shortName() {
            if (loaderName != null && !loaderName.isBlank()) {
                return mcBase + " · " + loaderName;
            }
            return id;
        }
    }

    public List<LocalVersion> scan() {
        List<LocalVersion> list = new ArrayList<>();
        Path dir = config.versionsPath();
        if (!Files.isDirectory(dir)) return list;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(vd -> {
                String id = vd.getFileName().toString();
                // 历史 bug 产生的坏版本：id 带「（测试版）」等中文括号/空格的目录，
                // 库 URL 全部 404、启动必秒退，视为无效安装直接隐藏（重新下载即可得到正确版本）
                if (id.indexOf('（') >= 0 || id.indexOf('）') >= 0 || id.indexOf('(') >= 0 || id.indexOf(')') >= 0) return;
                Path json = vd.resolve(id + ".json");
                Path jar = vd.resolve(id + ".jar");
                if (!Files.exists(json)) return;
                try {
                    JSONObject o = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
                    long size = 0;
                    if (Files.exists(jar)) {
                        try {
                            size = Files.size(jar);
                        } catch (IOException ignored) {
                        }
                    }
                    boolean hasJar = Files.exists(jar);
                    // 加载器版本通常没有自己的主程序，继承基础版本（inheritsFrom）的 jar
                    if (!hasJar) {
                        String base = parseMcBase(o, id);
                        if (!base.equals(id)) {
                            Path baseJar = config.versionsPath().resolve(base).resolve(base + ".jar");
                            hasJar = Files.exists(baseJar);
                        }
                    }
                    list.add(new LocalVersion(id,
                            o.optString("mainClass", ""),
                            hasJar,
                            size,
                            parseMcBase(o, id),
                            parseLoaderName(o, id),
                            parseLoaderVer(id)));
                } catch (Exception ignored) {
                    // json 损坏则忽略
                }
            });
        } catch (IOException ignored) {
        }
        list.sort(Comparator.comparing((LocalVersion v) -> v.id).reversed());
        return list;
    }

    /** 通用可见列表：隐藏被加载器版本继承的基础版本（如原版 1.21.1 已被 NeoForge 使用）。
     *  空壳加载器模板（只有 json/jar、没有 mods 也没有存档）**仅当存在同显示名的另一已装版本时**隐藏：
     *  那是整合包安装时把基础加载器版本复制为独立实例后遗留的基础版副本，若不隐藏会与整合包
     *  实例显示名完全一样（如都是「1.21.11 · Fabric」）。用户主动下载安装的加载器版本
     *  即使还没玩过（无 mods/saves）也应正常显示。 */
    public List<LocalVersion> scanVisible() {
        List<LocalVersion> all = scan();
        java.util.Set<String> inherited = new java.util.HashSet<>();
        for (LocalVersion v : all) {
            if (v.mcBase() != null && !v.mcBase().isEmpty() && !v.mcBase().equals(v.id())) {
                inherited.add(v.mcBase());
            }
        }
        java.util.Map<String, String> nameOwner = new java.util.HashMap<>();
        for (LocalVersion v : all) {
            nameOwner.putIfAbsent(v.displayName(), v.id());
        }
        List<LocalVersion> out = new ArrayList<>();
        for (LocalVersion v : all) {
            if (inherited.contains(v.id())) continue;
            if (isLoaderTemplate(v.id()) && isEmptyShell(v.id())) {
                String owner = nameOwner.get(v.displayName());
                if (owner != null && !owner.equals(v.id())) continue; // 整合包遗留的基础版副本
            }
            out.add(v);
        }
        return out;
    }

    /** 是否为加载器基础模板版本（fabric-loader、quilt-loader、neoforge、forge 前缀或含 -forge- / -neoforge-）。 */
    private boolean isLoaderTemplate(String id) {
        String lower = id.toLowerCase();
        return lower.startsWith("fabric-loader-") || lower.startsWith("quilt-loader-")
                || lower.startsWith("neoforge-") || lower.startsWith("forge-")
                || lower.contains("-forge-") || lower.contains("-neoforge-");
    }

    /** 空壳模板：目录里既没有 mods 也没有存档（未装过整合包内容、未游玩过），可安全隐藏。 */
    private boolean isEmptyShell(String id) {
        Path vd = config.versionsPath().resolve(id);
        return !Files.exists(vd.resolve("mods")) && !Files.exists(vd.resolve("saves"));
    }

    /**
     * 给定纯游戏版本 mc，返回本地内容应落到的实例版本 id：
     * 若本地有加载器实例继承该 mc（如 neoforge-21.1.265 继承 1.21.1），
     * 返回该实例 id（Mod 等落入实例目录）；否则返回 mc 自身（原版目录）。
     * 多个实例继承同一 mc 时取第一个。
     */
    public String resolveInstanceFor(String mc) {
        if (mc == null || mc.isBlank()) return mc;
        for (LocalVersion v : scan()) {
            if (v.mcBase() != null && v.mcBase().equals(mc) && !v.mcBase().equals(v.id())) {
                return v.id();
            }
        }
        return mc;
    }

    /** 基础版本：优先取 json 的 inheritsFrom；fabric/quilt 从 id 尾部取（fabric-loader-0.19.5-1.21.1）。 */
    private static String parseMcBase(JSONObject o, String id) {
        String inh = o.optString("inheritsFrom", "").trim();
        if (!inh.isEmpty()) return inh;
        String lower = id.toLowerCase();
        if (lower.startsWith("fabric-loader-") || lower.startsWith("quilt-loader-")) {
            int i = lower.lastIndexOf('-');
            if (i > 0) return id.substring(i + 1);
        }
        return id;
    }

    /** 加载器类型：结合 mainClass 与 id 判断（NeoForge 的 mainClass 是 net.neoforged.devlauncher.Main）。 */
    private static String parseLoaderName(JSONObject o, String id) {
        String m = ((o.optString("mainClass", "") + " " + id).toLowerCase());
        if (m.contains("neoforge")) return "NeoForge";
        if (m.contains("forge")) return "Forge";
        if (m.contains("fabric")) return "Fabric";
        if (m.contains("quilt")) return "Quilt";
        return null;
    }

    /** 加载器版本号：从 id 中提取（neoforge-21.1.264 → 21.1.264；fabric-loader-0.19.5-1.21.1 → 0.19.5）。 */
    private static String parseLoaderVer(String id) {
        String lower = id.toLowerCase();
        if (lower.startsWith("neoforge-")) return id.substring("neoforge-".length());
        if (lower.startsWith("forge-")) return id.substring("forge-".length());
        if (lower.startsWith("fabric-loader-") || lower.startsWith("quilt-loader-")) {
            int i = lower.lastIndexOf('-');
            return i > 0 ? id.substring(id.indexOf('-', id.indexOf('-') + 1) + 1, i) : null;
        }
        int f = lower.indexOf("-forge-");
        if (f >= 0) return id.substring(lower.lastIndexOf('-') + 1);
        int n = lower.indexOf("-neoforge-");
        if (n >= 0) return id.substring(lower.lastIndexOf('-') + 1);
        return null;
    }

    public Path versionDir(String id) {
        return config.versionsPath().resolve(id);
    }

    /**
     * 是否为纯 Minecraft 游戏版本（排除 fabric/forge 等加载器版本）。
     * 加载器版本 id 形如 fabric-loader-0.19.5-1.21.1、1.21.1-forge-52.0.1、
     * neoforge-21.1.265（前缀式，NeoForge 1.21+ 安装名）、forge-1.20.1-47.1.0
     * （前缀式，NeoForge 1.20.x 时代的安装名），Modrinth 等平台不认。
     */
    public static boolean isGameVersion(String id) {
        String lower = id.toLowerCase();
        if (lower.contains("loader")) return false;
        if (lower.contains("-forge-") || lower.contains("-neoforge-")) return false;
        if (lower.startsWith("fabric-") || lower.startsWith("quilt-")) return false;
        if (lower.startsWith("neoforge-") || lower.startsWith("forge-")) return false;
        return true;
    }

    public Path versionJson(String id) {
        return versionDir(id).resolve(id + ".json");
    }

    public Path versionJar(String id) {
        return versionDir(id).resolve(id + ".jar");
    }

    public boolean exists(String id) {
        return Files.exists(versionJson(id));
    }

    /** 读取版本 JSON。 */
    public JSONObject readJson(String id) throws IOException {
        return new JSONObject(Files.readString(versionJson(id), StandardCharsets.UTF_8));
    }
}
