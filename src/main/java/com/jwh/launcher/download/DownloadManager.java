package com.jwh.launcher.download;

import com.jwh.launcher.util.Http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 并发下载管理器：断点续传 + 已存在文件跳过 + 进度回调 + 失败重试。
 */
public class DownloadManager implements com.jwh.launcher.util.Http.DownloadControl {

    /** 单个下载任务。 */
    /** 下载任务。fallbackUrl 为镜像下载失败后的官方源兜底（可为 null）；
     *  extraUrls 为第三及以后的兜底源（如 Maven Central 直连），可为空。 */
    public record Task(String url, Path target, long expectedSize, String label, String fallbackUrl,
                       List<String> extraUrls) {
        public Task(String url, Path target, long expectedSize, String label, String fallbackUrl) {
            this(url, target, expectedSize, label, fallbackUrl, java.util.List.of());
        }

        public Task {
            if (extraUrls == null) extraUrls = java.util.List.of();
        }
    }

    public interface ProgressListener {
        /** done/total 为任务数；label 为当前正在下载的文件描述。 */
        void onProgress(int done, int total, long bytesDone, String label);

        void onFinish(boolean success, int failed);
    }

    private final int threads;
    private final ProgressListener listener;
    private final int readTimeoutSec;
    private final int budgetSec;
    private final com.jwh.launcher.util.Http.DownloadControl externalCtl;
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    // 队列级状态（run 内复用；并发时 currentLabel/currentTarget 为最近开始任务，仅用于进度展示）
    private final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong completedBytes = new java.util.concurrent.atomic.AtomicLong();
    private volatile int total;
    private volatile String currentLabel = "";
    private volatile Path currentTarget;

    public DownloadManager(int threads, ProgressListener listener) {
        this(threads, listener, 150, 300, null);
    }

    /** @param readTimeoutSec 单次读超时（秒），小文件用短值快速踢出坏节点
     *  @param budgetSec      单文件总时间预算（秒）：超过即放弃该文件继续下一个，
     *                        避免个别坏文件（节点挂起/镜像 404）占死全部下载线程、整批卡死 */
    public DownloadManager(int threads, ProgressListener listener, int readTimeoutSec, int budgetSec) {
        this(threads, listener, readTimeoutSec, budgetSec, null);
    }

    /** @param externalCtl 可选外部取消源（UI 取消按钮的控制对象）：其取消状态与本队列合并生效 */
    public DownloadManager(int threads, ProgressListener listener, int readTimeoutSec, int budgetSec,
                           com.jwh.launcher.util.Http.DownloadControl externalCtl) {
        this.threads = Math.max(1, threads);
        this.listener = listener;
        this.readTimeoutSec = Math.max(5, readTimeoutSec);
        this.budgetSec = Math.max(10, budgetSec);
        this.externalCtl = externalCtl;
    }

    /** 请求取消整个下载队列：正在下载的文件在下个检查点中止并删除未完整文件。 */
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get() || (externalCtl != null && externalCtl.isCancelled());
    }

    /** Http.transfer 每写一块调用：转发「已完成字节 + 当前文件磁盘实际大小」给进度监听，
     *  让大文件下载过程中界面持续有真实推进（不再卡在同一个文件计数上）。 */
    @Override
    public void onBytes(long ignored) {
        if (listener == null) return;
        long disk = currentDiskBytes();
        listener.onProgress(done.get(), total, completedBytes.get() + disk, currentLabel);
    }

    private long currentDiskBytes() {
        Path t = currentTarget;
        if (t == null) return 0;
        long n = sizeOf(t);
        n += sizeOf(t.resolveSibling(t.getFileName() + ".part"));
        for (int i = 0; i < 8; i++) {
            n += sizeOf(t.resolveSibling(t.getFileName() + ".seg" + i));
        }
        return n;
    }

    /** 阻塞执行下载队列，返回失败的任务列表。 */
    public List<Task> run(List<Task> tasks) {
        done.set(0);
        completedBytes.set(0);
        this.total = tasks.size();
        List<Task> failed = java.util.Collections.synchronizedList(new ArrayList<>());

        if (total == 0) {
            if (listener != null) listener.onFinish(true, 0);
            return failed;
        }

        // 海量小文件（assets 等动辄上千）时固定 8 线程每文件串行太慢：
        // 文件数远超线程数则自动扩容并发（小文件轻量，扩容安全；Http 侧已按 URL
        // 分布到多个连接池实例，不会挤在 JDK 1.1 默认的每主机 2 连接上排队）。
        int poolSize = Math.min(threads, total);
        if (total > threads * 4) {
            poolSize = Math.min(32, threads * 2);
        }
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<?>> futures = new ArrayList<>();
        for (Task t : tasks) {
            if (cancelled.get()) break;
            futures.add(pool.submit(() -> {
                boolean ok = downloadOne(t);
                if (!ok) failed.add(t);
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                // 单任务异常已在内部捕获
            }
        }
        pool.shutdown();
        if (listener != null) listener.onFinish(failed.isEmpty(), failed.size());
        return failed;
    }

    private boolean downloadOne(Task t) {
        currentLabel = t.label();
        currentTarget = t.target();
        if (cancelled.get()) {
            cleanupPartial(t.target());
            return false;
        }
        if (listener != null) listener.onProgress(done.get(), total, completedBytes.get(), t.label());
        long before = sizeOf(t.target());
        // 源顺序、超时分级、重试次数、完成判定统一交给 Downloader。
        // 这里只保留并发、进度与批量时间预算，不再自己实现第二套策略
        // （历史上就是这一套与 GameInstaller.downloadAny 各写一套、结论相反，
        //   才导致同一个坏源在不同入口反复发作）。
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (t.url() != null) urls.add(t.url());
        if (t.fallbackUrl() != null && !t.fallbackUrl().equals(t.url())) urls.add(t.fallbackUrl());
        for (String x : t.extraUrls()) {
            if (x != null && !x.isBlank() && !urls.contains(x)) urls.add(x);
        }
        ArtifactProbe.Kind kind = ArtifactProbe.Kind.of(t.target(), t.expectedSize());
        boolean ok = Downloader.fetch(t.target(), t.expectedSize(), kind, t.label(), this,
                Downloader.Policy.of(readTimeoutSec, budgetSec), urls.toArray(new String[0]));
        if (ok) {
            completedBytes.addAndGet(Math.max(0, sizeOf(t.target()) - before));
            int d = done.incrementAndGet();
            if (listener != null) listener.onProgress(d, total, completedBytes.get(), t.label());
            return true;
        }
        if (listener != null) listener.onProgress(done.get(), total, completedBytes.get(), t.label() + " ✗");
        return false;
    }

    /** 删除未下载完整的文件（.part 断点残留、.segN 分段残留、半截 target），取消时调用。 */
    private void cleanupPartial(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (Exception ignored) {
        }
        try {
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".part"));
        } catch (Exception ignored) {
        }
        for (int i = 0; i < 8; i++) {
            try {
                Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".seg" + i));
            } catch (Exception ignored) {
            }
        }
    }

    private long sizeOf(Path p) {
        try {
            return java.nio.file.Files.exists(p) ? java.nio.file.Files.size(p) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
