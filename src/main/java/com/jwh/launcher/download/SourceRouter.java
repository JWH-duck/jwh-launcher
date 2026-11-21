package com.jwh.launcher.download;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载源排序：不再由调用点写死"镜像优先"或"官方优先"，而是按每个源的真实表现排序。
 *
 * <p>基线给官方源更高权重（实测本机官方 libraries.minecraft.net 1.2 秒，
 * BMCLAPI 对同一文件 39 秒且 302 不吐字节）。但某官方源连续失败时会被镜像反超，
 * 所以镜像仍能在官方不可达的环境里胜出——不需要玩家手动切换。
 */
public final class SourceRouter {

    private static final String OFFICIAL_HOSTS =
            "piston-meta.mojang.com launchermeta.mojang.com launcher.mojang.com piston-data.mojang.com "
                    + "resources.download.minecraft.net libraries.minecraft.net maven.minecraftforge.net "
                    + "maven.neoforged.net maven.fabricmc.net meta.fabricmc.net cdn.modrinth.com "
                    + "api.modrinth.com edge.forgecdn.net mediafilez.forgecdn.net github.com "
                    + "objects.githubusercontent.com minecraftforge.net neoforged.net fabricmc.net";
    private static final String MIRROR_MARK = "bangbang93";

    private record Stat(int success, int failStreak, long avgMs, long lastFailMs) {
    }

    private static final Map<String, Stat> STATS = new LinkedHashMap<>();
    private static volatile boolean loaded;
    private static volatile long lastFlush;
    private static volatile boolean dirty;

    private SourceRouter() {
    }

    /** 按历史表现给候选 URL 排序；同分保持调用方给的顺序。 */
    public static List<String> order(String... urls) {
        List<String> cands = new ArrayList<>();
        for (String u : urls) {
            if (u == null || u.isBlank()) continue;
            if (!cands.contains(u)) cands.add(u);
        }
        ensureLoaded();
        if (cands.size() <= 1) return cands;
        Map<String, Integer> score = new LinkedHashMap<>();
        for (String u : cands) score.put(u, score(u));
        List<String> out = new ArrayList<>(cands);
        out.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        return out;
    }

    private static int score(String url) {
        String host = hostOf(url);
        Stat s = STATS.get(host);
        int base = isOfficial(host) ? 60 : 45;
        // BMCLAPI 的 302 回源节点经常"连得上却不吐字节"，一个文件能白挂满整轮读超时；
        // Maven Central 镜像（aliyun / huaweicloud）稳定且极快，给更高基线让它先试
        if (host.contains("aliyun") || host.contains("huaweicloud")) base += 20;
        if (s == null) return base;
        int v = base + Math.min(9, s.success() * 3) - s.failStreak() * 15;
        if (s.avgMs() > 3000) v -= 10;
        else if (s.avgMs() > 0 && s.avgMs() < 900) v += 6;
        return v;
    }

    /** 记录一次成功：更新平均耗时、清零连续失败。 */
    public static void success(String url, long ms) {
        ensureLoaded();
        String host = hostOf(url);
        Stat old = STATS.getOrDefault(host, new Stat(0, 0, 0, 0));
        long avg = old.avgMs() <= 0 ? ms : (old.avgMs() * 3 + ms) / 4;
        STATS.put(host, new Stat(Math.min(50, old.success() + 1), 0, avg, old.lastFailMs()));
        dirty = true;
        flushIfDue();
    }

    /** 记录一次失败（含"下载完成但校验不通过"——这类源必须被降权，否则下次还从它拿坏包）。 */
    public static void fail(String url, long ms, String reason) {
        ensureLoaded();
        String host = hostOf(url);
        Stat old = STATS.getOrDefault(host, new Stat(0, 0, 0, 0));
        long avg = old.avgMs() <= 0 ? ms : (old.avgMs() * 3 + ms) / 4;
        STATS.put(host, new Stat(old.success(), Math.min(9, old.failStreak() + 1), avg, System.currentTimeMillis()));
        dirty = true;
        flushIfDue();
    }

    /** 供界面/日志展示的当前源表现快照。 */
    public static String describe() {
        ensureLoaded();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Stat> e : STATS.entrySet()) {
            sb.append(e.getKey()).append(": ok=").append(e.getValue().success())
                    .append(" streak=").append(e.getValue().failStreak())
                    .append(" avg=").append(e.getValue().avgMs()).append("ms\n");
        }
        return sb.toString();
    }

    public static String hostOf(String url) {
        if (url == null) return "";
        int i = url.indexOf("://");
        String rest = i >= 0 ? url.substring(i + 3) : url;
        int slash = rest.indexOf('/');
        String host = slash >= 0 ? rest.substring(0, slash) : rest;
        int colon = host.indexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    private static boolean isOfficial(String host) {
        if (host.contains(MIRROR_MARK)) return false;
        for (String h : OFFICIAL_HOSTS.split(" ")) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        // 未登记的第三方 CDN（BBSMC / 皮肤站等）按中性处理：既不算官方也不算镜像
        return false;
    }

    // ---------- 持久化：跨会话记住哪些源在本机可用 ----------

    private static Path statsPath() {
        try {
            Path cfg = com.jwh.launcher.config.ConfigManager.configPath();
            if (cfg != null && cfg.getParent() != null) return cfg.getParent().resolve("download-sources.json");
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path p = statsPath();
        if (p == null || !Files.isRegularFile(p)) return;
        try {
            JSONObject o = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
            JSONObject hosts = o.optJSONObject("hosts");
            if (hosts == null) return;
            for (String k : hosts.keySet()) {
                JSONObject v = hosts.optJSONObject(k);
                if (v == null) continue;
                STATS.put(k, new Stat(v.optInt("s", 0), v.optInt("f", 0), v.optLong("ms", 0), v.optLong("ls", 0)));
            }
        } catch (Exception ignored) {
            // 统计文件损坏不影响下载，按空表继续
        }
    }

    /** 最多每 2 秒落盘一次，避免并发下载时反复写小文件。 */
    public static synchronized void flushIfDue() {
        if (!dirty) return;
        long now = System.currentTimeMillis();
        if (now - lastFlush < 2000) return;
        lastFlush = now;
        dirty = false;
        Path p = statsPath();
        if (p == null) return;
        try {
            JSONObject hosts = new JSONObject();
            for (Map.Entry<String, Stat> e : STATS.entrySet()) {
                JSONObject v = new JSONObject();
                v.put("s", e.getValue().success());
                v.put("f", e.getValue().failStreak());
                v.put("ms", e.getValue().avgMs());
                v.put("ls", e.getValue().lastFailMs());
                hosts.put(e.getKey(), v);
            }
            JSONObject root = new JSONObject();
            root.put("hosts", hosts);
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Files.writeString(p, root.toString(2), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

}
