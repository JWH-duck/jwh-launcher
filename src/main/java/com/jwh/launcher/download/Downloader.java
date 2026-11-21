package com.jwh.launcher.download;

import com.jwh.launcher.util.Http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 唯一的单文件下载入口。所有下载都必须经过这里，调用点不再自行决定源顺序与完成判定。
 *
 * <p>这里只做四件事：选源（{@link SourceRouter}）、传输（{@link Http}）、
 * 校验（{@link ArtifactProbe}）、留痕（{@link DownloadLog}）。
 * 之前同一套问题在五个实现里轮流发作，根因就是这四件事分散在调用点各自实现。
 */
public final class Downloader {

    /** 单文件下载策略。readTimeoutSec<=0 表示按产物大小自动分级；budgetSec 为该文件的总时间预算。 */
    public record Policy(int readTimeoutSec, int budgetSec) {
        public static final Policy DEFAULT = new Policy(0, 300);

        public static Policy of(int readTimeoutSec, int budgetSec) {
            return new Policy(readTimeoutSec, budgetSec);
        }
    }

    private Downloader() {
    }

    /** 下载到 target，多个候选源由路由排序。返回 true 表示文件已通过结构校验。 */
    public static boolean fetch(Path target, long expectedSize, ArtifactProbe.Kind kind,
                                String label, Http.DownloadControl ctl, String... urls) {
        return fetch(target, expectedSize, kind, label, ctl, Policy.DEFAULT, urls);
    }

    public static boolean fetch(Path target, long expectedSize, ArtifactProbe.Kind kind,
                                String label, Http.DownloadControl ctl, Policy policy, String... urls) {
        if (target == null || urls == null || urls.length == 0) return false;
        String tag = label == null ? target.getFileName().toString() : label;
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
        } catch (Exception ignored) {
        }

        // 1) 缓存命中：只有通过结构校验才算"已下载"。坏包在这里被清掉并重下，
        //    不再出现"文件在、大小对、启动才报 corrupt"。
        if (Files.isRegularFile(target)) {
            ArtifactProbe.Verdict cached = ArtifactProbe.check(target, kind, expectedSize);
            if (cached.ok()) {
                DownloadLog.skip(tag);
                return true;
            }
            DownloadLog.bad(tag, "", 0, "stale:" + cached.reason());
            ArtifactProbe.purge(target);
        }

        // 2) 源排序：官方源基线更高，但连续失败会被镜像反超（按本机真实表现）
        List<String> ordered = SourceRouter.order(urls);
        if (ordered.isEmpty()) return false;
        int sources = ordered.size();
        long budgetMs = Math.max(10_000L, (long) (policy.budgetSec() > 0 ? policy.budgetSec() : 300) * 1000L);
        long perSource = Math.max(10_000L, budgetMs / sources);
        long deadline = System.currentTimeMillis() + budgetMs;

        boolean hasMirror = false;
        for (String u : ordered) {
            if (u.contains("bangbang93.com")) hasMirror = true;
        }
        // BMCLAPI 的 302 后端节点是轮换的（实测同一个文件一次 404、一次 200），
        // 所以所有源都失败后再整轮重试一次，命中可用节点的概率明显提高
        int rounds = hasMirror ? 2 : 1;
        for (int round = 0; round < rounds; round++) {
        if (round > 0) ArtifactProbe.purgeResidue(target);
        for (int i = 0; i < sources; i++) {
            if (isCancelled(ctl)) {
                ArtifactProbe.purge(target);
                return false;
            }
            if (System.currentTimeMillis() > deadline) break;
            String url = ordered.get(i);
            // 换源前清残片：不同源的字节不能拼在一起（这正是"长度对、头错位"坏包的来源）
            if (i > 0) ArtifactProbe.purgeResidue(target);
            long sourceDeadline = Math.min(deadline, System.currentTimeMillis() + perSource);
            for (int attempt = 0; attempt < 2; attempt++) {
                if (isCancelled(ctl)) {
                    ArtifactProbe.purge(target);
                    return false;
                }
                if (System.currentTimeMillis() > sourceDeadline) break;
                long t0 = System.currentTimeMillis();
                boolean transferred = Http.download(url, target, ctl,
                        readTimeout(kind, expectedSize, policy));
                long ms = System.currentTimeMillis() - t0;
                if (isCancelled(ctl)) {
                    ArtifactProbe.purge(target);
                    return false;
                }
                ArtifactProbe.Verdict v = ArtifactProbe.check(target, kind, expectedSize);
                if (v.ok()) {
                    SourceRouter.success(url, ms);
                    DownloadLog.ok(tag, url, ms);
                    return true;
                }
                if (transferred) {
                    // 传输跑完但产物不成立：这是坏包（如镜像 404 返回的 HTML），
                    // 同一源重试只会再拿到同一个坏包，直接删掉换源。
                    SourceRouter.fail(url, ms, v.reason());
                    DownloadLog.bad(tag, url, ms, v.reason());
                    ArtifactProbe.purge(target);
                    break;
                }
                SourceRouter.fail(url, ms, "io");
                DownloadLog.io(tag, url, ms);
                // 12 秒内就失败的源基本都是 404/403/TCP 连不上这类硬失败
                // （真慢速网络至少要拖几十秒），同一源再试一次只是白等，直接换源
                if (ms < 12000L) break;
            }
        }
        }
        ArtifactProbe.purge(target);
        DownloadLog.fail(tag, ordered);
        return false;
    }

    /** 读超时按产物类型与大小分级：小素材快速踢出坏节点，大文件留给断点续传。 */
    private static int readTimeout(ArtifactProbe.Kind kind, long expectedSize, Policy policy) {
        int rt = policy.readTimeoutSec() > 0 ? policy.readTimeoutSec() : 150;
        if (kind == ArtifactProbe.Kind.JSON) return Math.max(10, Math.min(rt, 40));
        if (expectedSize > 0) {
            if (expectedSize < 1024 * 1024) {
                rt = Math.min(rt, 20);
            } else if (expectedSize < 10L * 1024 * 1024) {
                rt = Math.min(rt, 60);
            }
        }
        return Math.max(10, rt);
    }

    private static boolean isCancelled(Http.DownloadControl ctl) {
        return ctl != null && ctl.isCancelled();
    }
}
