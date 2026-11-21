package com.jwh.launcher.download;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 下载失败埋点：每一次下载尝试（哪个文件、哪个源、耗时、结果、失败原因）都留痕。
 *
 * <p>玩家报"下载失败"时，直接看 downloads.log 就能判断是源 404、结构损坏还是超时，
 * 不用再靠猜测或反复让玩家重试。
 */
public final class DownloadLog {

    private static final int MEMORY_LIMIT = 200;
    private static final long FILE_LIMIT = 512L * 1024;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final java.util.ArrayDeque<String> RECENT = new java.util.ArrayDeque<>();
    private static final Object LOCK = new Object();

    private DownloadLog() {
    }

    /** 记录一行：result 固定为 OK / SKIP / BAD / IO / FAIL，extra 为失败原因。 */
    public static void record(String result, String label, Path target, String url, long ms, String extra) {
        String host = SourceRouter.hostOf(url);
        String line = String.format("%s | %-4s | %-42s | %-7dms | %-28s | %s",
                LocalDateTime.now().format(TS),
                result,
                shorten(label, 42),
                ms,
                shorten(host, 28),
                extra == null ? "" : extra);
        synchronized (LOCK) {
            RECENT.addLast(line);
            while (RECENT.size() > MEMORY_LIMIT) RECENT.removeFirst();
        }
        append(line);
    }

    public static void ok(String label, String url, long ms) {
        record("OK", label, null, url, ms, "");
    }

    public static void skip(String label) {
        record("SKIP", label, null, "", 0, "cache-hit");
    }

    public static void bad(String label, String url, long ms, String reason) {
        record("BAD", label, null, url, ms, reason);
    }

    public static void io(String label, String url, long ms) {
        record("IO", label, null, url, ms, "transfer");
    }

    public static void fail(String label, List<String> tried) {
        record("FAIL", label, null, "", 0, "all-sources:" + String.join(",", tried));
    }

    /** 最近 n 条，供"下载失败"弹窗或日志页展示。 */
    public static List<String> recent(int n) {
        synchronized (LOCK) {
            List<String> all = new ArrayList<>(RECENT);
            int from = Math.max(0, all.size() - Math.max(0, n));
            return Collections.unmodifiableList(all.subList(from, all.size()));
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('|', '/');
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static void append(String line) {
        Path p = logPath();
        if (p == null) return;
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            if (Files.isRegularFile(p) && Files.size(p) > FILE_LIMIT) {
                Path old = p.resolveSibling(p.getFileName() + ".1");
                Files.deleteIfExists(old);
                Files.move(p, old);
            }
            Files.writeString(p, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志写不进去不影响下载本身
        }
    }

    private static Path logPath() {
        try {
            Path cfg = com.jwh.launcher.config.ConfigManager.configPath();
            if (cfg != null && cfg.getParent() != null) return cfg.getParent().resolve("downloads.log");
        } catch (Exception ignored) {
        }
        return null;
    }
}
