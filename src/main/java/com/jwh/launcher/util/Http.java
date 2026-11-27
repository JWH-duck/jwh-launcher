package com.jwh.launcher.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * HTTP 请求工具（基于 JDK 内置 HttpClient，无第三方依赖）。
 * 所有网络请求统一走这里，便于设置超时 / 代理 / User-Agent。
 */
public final class Http {

    public static final String UA = "JWH-Launcher/1.0";

    /**
     * JDK HttpClient 在 HTTP/1.1 下对同一主机的连接池默认只有 2 条并发连接；
     * 下载时 8 线程并行 + 大文件 4 段分段会全部排队在这 2 条连接上，慢速网络下
     * 进度肉眼可见地卡（实测国内镜像 bmclapi 不支持 HTTP/2，只能走 1.1）。
     * 这里建 8 个实例：每个实例有独立连接池，按 URL 轮流分发，同主机总并发
     * 提到 ~16 条，海量小文件（assets）和分段下载不再互相排队。
     */
    private static final int CONNECTION_POOLS = 8;
    private static final HttpClient[] CLIENTS = new HttpClient[CONNECTION_POOLS];

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(25))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    static {
        for (int i = 0; i < CONNECTION_POOLS; i++) {
            CLIENTS[i] = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(25))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
    }

    /**
     * 大文件下载专用客户端：强制 HTTP/1.1。
     * JDK HttpClient 的 HTTP/2 单流吞吐受内部流窗口限制（实测真实 CDN ~33KB/s），
     * HTTP/1.1 每连接独立窗口，配合 4 段分段并行才能把用户网络的多连接带宽用起来
     * （与 curl 的 HTTP/1.1 行为一致）。
     */
    private static final HttpClient DL_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            // 建连只给 10 秒：maven.neoforged.net 在国内经常 TCP 都连不上，
            // 25 秒 × 每源 2 次 × 2 源意味着单个拿不到的文件要白挂 100 秒以上
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 下载请求按 URL 分布到不同连接池实例，避免挤在单实例的 2 条连接上。 */
    private static HttpClient clientFor(String url) {
        return CLIENTS[Math.floorMod(url.hashCode(), CONNECTION_POOLS)];
    }

    /**
     * 大文件下载的响应体处理器：边收边写文件（断点续传 append / 服务器不支持断点时从头写）。
     * 关键点：BodyHandler 在 HttpClient 内部线程消费 body，future 直到「响应体全部写入文件」才完成，
     * 因此外层 future.get(超时) + cancel(true) 能真正中止「响应头已到、body 吐一点就 stall」的下载
     * （HttpClient 的 .timeout() 只管到响应头，旧实现 body 读取无限阻塞——v1.6.168 实测真实 CDN
     * 100s 只下 15KB、界面卡 2%）。数据每块到达即回调 onBytes，进度实时。
     */
    private static HttpResponse.BodyHandler<Long> appendFileHandler(Path tmp, DownloadControl ctl, boolean allowFull) {
        return info -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<Long> cf = new CompletableFuture<>();
            private java.io.OutputStream out;
            private long written;
            private int mode = -1; // -1 未定 0 从头 1 追加

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription sub) {
                sub.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.util.List<ByteBuffer> items) {
                try {
                    if (mode == -1) {
                        int sc = info.statusCode();
                        LAST_STATUS.set(sc);
                        if (sc == 206) {
                            mode = 1;
                        } else if (sc == 200 && allowFull) {
                            mode = 0;
                            Files.deleteIfExists(tmp); // 服务器不支持断点：从头写
                        } else {
                            cf.completeExceptionally(new IOException("HTTP " + sc));
                            return;
                        }
                    }
                    if (cancelled(ctl)) {
                        cf.completeExceptionally(new IOException("下载已取消"));
                        return;
                    }
                    if (out == null) {
                        Files.createDirectories(tmp.getParent());
                        out = Files.newOutputStream(tmp,
                                java.nio.file.StandardOpenOption.CREATE,
                                mode == 1
                                        ? java.nio.file.StandardOpenOption.APPEND
                                        : java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                    }
                    for (ByteBuffer bb : items) {
                        byte[] chunk = new byte[bb.remaining()];
                        bb.get(chunk);
                        out.write(chunk);
                        written += chunk.length;
                        if (ctl != null) ctl.onBytes(written);
                    }
                } catch (Exception e) {
                    cf.completeExceptionally(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                closeOut();
                cf.completeExceptionally(t);
            }

            @Override
            public void onComplete() {
                closeOut();
                long expect = info.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (expect > 0 && written != expect) {
                    cf.completeExceptionally(new IOException("长度不符 " + written + "/" + expect));
                    return;
                }
                cf.complete(written);
            }

            private void closeOut() {
                try {
                    if (out != null) out.close();
                } catch (Exception ignored) {
                }
                out = null;
            }

            @Override
            public CompletionStage<Long> getBody() {
                return cf;
            }
        };
    }

    /**
     * 丢弃分段残片。
     * 旧实现把 .segN 按序号无条件追加进 .part，但各段的下载进度互不连续：
     * seg1 只下了半段时，seg2 的字节会被拼到 seg1 的中途位置，再以这个长度作为
     * 续传起点发出 Range 请求，最终得到「总长度正确、开头与中间错位」的文件
     * （实测 Forge 安装器：7,982,031 B、中央目录可读、首个 PK 头落在偏移 524,686，
     * java -jar 直接报 Invalid or corrupt jarfile）。分段残片无法安全复用，一律丢弃。
     */
    private static void discardSegmentParts(Path target) {
        String n = target.getFileName().toString();
        for (int i = 0; i < 8; i++) {
            try {
                Files.deleteIfExists(target.resolveSibling(n + ".seg" + i));
            } catch (Exception ignore) {
                // 清理失败不阻断下载
            }
        }
    }

    /** 是否存在分段残片（有残片说明 .part 可能被污染，不能当续传前缀用）。 */
    private static boolean hasSegmentResidue(Path target) {
        String n = target.getFileName().toString();
        for (int i = 0; i < 8; i++) {
            if (Files.exists(target.resolveSibling(n + ".seg" + i))) return true;
        }
        return false;
    }

    private Http() {
    }

    /** GET 文本（默认 UTF-8）。 */
    public static String getText(String url) throws IOException, InterruptedException {
        return getText(url, null);
    }

    /** HEAD 探测资源是否存在（404/网络失败返回 false，不抛异常）。 */
    public static boolean exists(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", UA)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }


    /** 测量到目标 URL 的往返延迟（毫秒），请求失败返回 -1。用于下载源自动测速选路。 */
    public static long pingMs(String url) {
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", UA)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            clientFor(url).send(req, HttpResponse.BodyHandlers.discarding());
            return (System.nanoTime() - t0) / 1_000_000;
        } catch (Exception e) {
            return -1;
        }
    }
    public static String getText(String url, Map<String, String> headers) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return getTextOnce(url, headers);
            } catch (IOException e) {
                last = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        throw last;
    }

    /** GET 字节数组（下载皮肤图等小资源；带 UA / 超时，失败自动重试 3 次）。 */
    public static byte[] getBytes(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(40))
                        .header("User-Agent", UA)
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() >= 400) {
                    throw new IOException("HTTP " + resp.statusCode() + " @ " + url);
                }
                return resp.body();
            } catch (IOException e) {
                last = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(800);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        throw last;
    }

    /** GET 文本单次请求（默认 UTF-8）。 */
    private static String getTextOnce(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", UA)
                .GET();
        if (headers != null) {
            headers.forEach(b::header);
        }
        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " @ " + url);
        }
        return resp.body();
    }

    /**
     * 两路并发抢答：镜像与官方同时拉取同一小文本（版本 JSON / 版本清单等元数据），
     * 先成功返回者胜出并立即取消另一路（通用思路：慢源不再阻塞进度）。
     * 单路失败自动用另一路结果；两路都失败抛最后异常。仅用于小体积元数据，不用于大文件。
     */
    public static String getTextRace(String urlA, String urlB) throws IOException, InterruptedException {
        if (urlA == null || urlB == null || urlA.equals(urlB)) {
            return getText(urlA == null ? urlB : urlA);
        }
        java.util.concurrent.atomic.AtomicReference<String> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<IOException> lastErr =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (String u : new String[]{urlA, urlB}) {
                ex.execute(() -> {
                    try {
                        String t = getTextOnce(u, null);
                        if (result.compareAndSet(null, t)) done.countDown();
                    } catch (IOException e) {
                        lastErr.set(e);
                    } catch (InterruptedException e) {
                        lastErr.set(new IOException("竞速中断 @ " + u, e));
                    } catch (RuntimeException e) {
                        lastErr.set(new IOException("竞速异常 @ " + u, e));
                    }
                });
            }
            done.await(75, java.util.concurrent.TimeUnit.SECONDS);
            String r = result.get();
            if (r != null) return r;
            IOException e = lastErr.get();
            throw e != null ? e : new IOException("两路元数据拉取均失败/超时");
        } finally {
            ex.shutdownNow();
        }
    }
    /** POST JSON 并返回 JSON 文本。 */
    public static String postJson(String url, String jsonBody) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " @ " + url + " body=" + resp.body());
        }
        return resp.body();
    }

    /** POST 表单（application/x-www-form-urlencoded）。 */
    public static String postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(buildForm(form)))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IOException("HTTP " + resp.statusCode() + " @ " + url + " body=" + resp.body());
        }
        return resp.body();
    }

    /** POST 表单并返回原始状态码 + 响应体（不抛 4xx 异常，供 OAuth 轮询按错误语义分支处理）。 */
    public static HttpResult postFormRaw(String url, Map<String, String> form) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(buildForm(form)))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(resp.statusCode(), resp.body());
    }

    private static String buildForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** HTTP 结果：状态码 + 响应体。 */
    public static final class HttpResult {
        public final int status;
        public final String body;

        public HttpResult(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }


    /**
     * 下载文件到目标路径（支持断点续传：已有部分字节则从断点继续）。
     * 大文件（>1MB）且服务端支持 Range 时自动 4 段并行分段下载（海外慢源显著提速）；
     * 分段失败自动回退单连接。连接阶段失败（超时/瞬断）自动重试 3 轮。
     * 返回是否完整成功。
     */
    /** 下载控制（进度/取消）：下载过程中周期性回调字节数与取消标志。可为 null 表示不控制。 */
    public interface DownloadControl {
        /** 每写入一块字节后回调（bytes 为本次下载已累计字节数）。 */
        default void onBytes(long bytes) {
        }

        /** 是否已请求取消。为 true 时下载在下个检查点中止并清理未完整文件。 */
        boolean isCancelled();

        /** 请求取消（UI 取消按钮调用；Http 层只读 isCancelled）。默认空实现，子类按需覆盖。 */
        default void cancel() {
        }
    }

    /** 简单可取消标志（UI 取消按钮用）。 */
    public static class CancelFlag implements DownloadControl {
        private final java.util.concurrent.atomic.AtomicBoolean b = new java.util.concurrent.atomic.AtomicBoolean();

        @Override
        public void cancel() {
            b.set(true);
        }

        @Override
        public boolean isCancelled() {
            return b.get();
        }
    }

    public static boolean download(String url, Path target) {
        return download(url, target, null, 150);
    }

    public static boolean download(String url, Path target, DownloadControl ctl) {
        return download(url, target, ctl, 150);
    }

    /** 便捷重载：无取消控制的单文件下载，指定读超时（秒）。 */
    public static boolean download(String url, Path target, int readTimeoutSec) {
        return download(url, target, null, readTimeoutSec);
    }

    /** 带读超时（秒）的下载：小文件（assets 等）用短超时快速踢出坏节点，大文件用长超时+断点续传。 */
    public static boolean download(String url, Path target, DownloadControl ctl, int readTimeoutSec) {
        if (cancelled(ctl)) return false;
        // 单次镜像尝试总时长硬上限 60s：坏节点「无响应」或「持续慢速吐数据」时，
        // 分段/单连接/重试全部共享此预算，调用在 60s 内必然返回，绝不无限占死线程；
        // 大文件每轮至少推进部分字节（断点续传），由 DownloadManager 预算控制总轮数。
        // 单次镜像尝试总时长上限：小文件（assets/库，读超时 ≤30s）按 2 倍读超时封顶，
        // 坏节点 15-40 秒内必然返回，不再让每个小文件各占 60 秒；大文件仍共享 60 秒下限预算。
        long deadline = System.currentTimeMillis()
                + (readTimeoutSec <= 30
                        ? Math.max(15_000L, readTimeoutSec * 2000L)
                        : Math.max(60_000L, readTimeoutSec * 2000L));
        if (trySegmented(url, target, ctl, readTimeoutSec, deadline)) return true;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (System.currentTimeMillis() > deadline) return false;
            LAST_STATUS.set(0);
            if (downloadOnce(url, target, ctl, readTimeoutSec, deadline)) return true;
            if (cancelled(ctl)) return false;
            // 404/403/410 是「这个源根本没有这个文件」，同源重试只会再拿到同一份 404；
            // 直接放弃该源交给调用方换源（416 例外：那表示 Range 越界、文件其实已完整）
            int sc = LAST_STATUS.get();
            if (sc >= 400 && sc < 500 && sc != 416) return false;
            if (attempt < 2) {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean cancelled(DownloadControl ctl) {
        return ctl != null && ctl.isCancelled();
    }

    /** HEAD 探测资源总字节数（Content-Length）；失败/不支持返回 -1，供未知大小的下载换算真实进度。 */
    public static long contentLength(String url) {
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", UA)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> hr = clientFor(url).send(head, HttpResponse.BodyHandlers.discarding());
            if (hr.statusCode() != 200) return -1;
            return hr.headers().firstValueAsLong("Content-Length").orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 最近一次响应状态码（按线程隔离）：供 download() 区分「这个源没这个文件」与「传输被打断」。 */
    private static final ThreadLocal<Integer> LAST_STATUS = ThreadLocal.withInitial(() -> 0);
    private static final int SEGMENTS = 4;
    private static final long SEGMENT_MIN = 1_000_000L; // 1MB 以上才分段

    /** 4 段并行分段下载；返回 false 表示不可行/失败，调用方回退单连接。 */
    private static boolean trySegmented(String url, Path target, DownloadControl ctl, int readTimeoutSec, long deadline) {
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(25))
                    .header("User-Agent", UA)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> hr = clientFor(url).send(head, HttpResponse.BodyHandlers.discarding());
            if (hr.statusCode() != 200) return false;
            if (!"bytes".equalsIgnoreCase(hr.headers().firstValue("Accept-Ranges").orElse(""))) return false;
            long total = hr.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (total < SEGMENT_MIN) return false;
            Path dir = target.getParent();
            if (dir != null) Files.createDirectories(dir);
            // 不再「大小相同即视为已完成」：坏包总长度也是对的（拼接错位的产物），
            // 旧短路会让这类文件永远跳过校验、永远被复用。
            long seg = (total + SEGMENTS - 1) / SEGMENTS;
            List<Path> parts = new java.util.ArrayList<>();
            List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(SEGMENTS);
            for (int i = 0; i < SEGMENTS; i++) {
                final long start = i * seg;
                final long end = Math.min(total - 1, start + seg - 1);
                final Path part = target.resolveSibling(target.getFileName() + ".seg" + i);
                parts.add(part);
                futures.add(pool.submit(() -> downloadSegment(url, part, start, end, ctl, readTimeoutSec, deadline)));
            }
            boolean allOk = true;
            for (var f : futures) {
                try {
                    if (!f.get()) allOk = false;
                } catch (Exception e) {
                    allOk = false;
                }
            }
            pool.shutdown();
            if (!allOk) {
                // 各段进度不连续，顺序拼接必然错位（会产出「长度对、内容错」的文件），
                // 因此不能保留残片，直接丢弃后由单流从头下载。
                for (Path p : parts) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignore) {
                    }
                }
                return false;
            }
            // 合并前校验每段完整性：未下完的段保留续传，不合并
            for (int i = 0; i < SEGMENTS; i++) {
                long expect = Math.min(total, (i + 1) * seg) - i * seg;
                if (Files.size(parts.get(i)) != expect) return false;
            }
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.deleteIfExists(tmp);
            try (var out = Files.newOutputStream(tmp)) {
                for (Path p : parts) {
                    Files.copy(p, out);
                }
            }
            for (Path p : parts) Files.deleteIfExists(p);
            if (Files.size(tmp) != total) {
                Files.deleteIfExists(tmp);
                return false;
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 下载单个分段（Range bytes=from-end），支持断点续传：已有部分字节则从该位置继续，
     * 失败保留已下部分（外层重试时继续推进，不再每次从头下导致大文件永远卡在开头）。
     */
    private static boolean downloadSegment(String url, Path part, long start, long end, DownloadControl ctl,
                                           int readTimeoutSec, long deadline) {
        try {
            long have = Files.exists(part) ? Files.size(part) : 0;
            if (have > end - start + 1) {
                Files.deleteIfExists(part);
                have = 0;
            }
            if (have == end - start + 1) return true; // 本段已完成
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(20, readTimeoutSec)))
                    .header("User-Agent", UA)
                    .header("Range", "bytes=" + (start + have) + "-" + end)
                    .GET()
                    .build();
            CompletableFuture<HttpResponse<Long>> fut = DL_CLIENT.sendAsync(req,
                    appendFileHandler(part, ctl, false)); // 分段必须 206，200 全量视为失败
            long remain = Math.min(
                    readTimeoutSec <= 30 ? Math.max(10_000L, readTimeoutSec * 1000L) : 60_000L,
                    Math.max(5_000, deadline - System.currentTimeMillis()));
            try {
                fut.get(remain, java.util.concurrent.TimeUnit.MILLISECONDS);
                return true; // handler 已完成长度校验；完整段校验由合并时统一做
            } catch (java.util.concurrent.TimeoutException te) {
                fut.cancel(true);
                return false; // 已写部分保留，供续传
            } catch (Exception e) {
                return false;
            }
        } catch (Exception e) {
            return false; // 已写部分保留，供续传
        }
    }

    private static boolean downloadOnce(String url, Path target, DownloadControl ctl, int readTimeoutSec, long deadline) {
        return downloadOnceInner(url, target, ctl, readTimeoutSec, deadline);
    }

    private static boolean downloadOnceInner(String url, Path target, DownloadControl ctl, int readTimeoutSec, long deadline) {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        long from = 0;
        try {
            // 分段残片一律丢弃；若存在残片，.part 可能是拼错的内容，不能作为续传前缀
            if (hasSegmentResidue(target)) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignore) {
                }
            }
            discardSegmentParts(target);
            Path dir = target.getParent();
            if (dir != null) Files.createDirectories(dir);
            from = Files.exists(tmp) ? Files.size(tmp) : 0L;

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(20, readTimeoutSec)))
                    .header("User-Agent", UA);
            if (from > 0) {
                b.header("Range", "bytes=" + from + "-");
            }
            HttpRequest req = b.GET().build();
            CompletableFuture<HttpResponse<Long>> fut = DL_CLIENT.sendAsync(req,
                    appendFileHandler(tmp, ctl, true));
            // 超时与传输上限对齐（最多 60s/轮，慢速节点每轮至少推进部分字节，累计下完）
            long remain = Math.min(
                    readTimeoutSec <= 30 ? Math.max(10_000L, readTimeoutSec * 1000L) : 60_000L,
                    Math.max(5_000, deadline - System.currentTimeMillis()));
            try {
                fut.get(remain, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                // body 卡死/慢速：中止本轮，断点（.part）保留，下一轮续传
                fut.cancel(true);
                long cur = Files.exists(tmp) ? Files.size(tmp) : 0L;
                if (cur <= from || cancelled(ctl)) {
                    Files.deleteIfExists(tmp);
                }
                return false;
            } catch (Exception e) {
                long cur = Files.exists(tmp) ? Files.size(tmp) : 0L;
                if (cur <= from || cancelled(ctl)) {
                    Files.deleteIfExists(tmp);
                }
                return false;
            }
            if (cancelled(ctl)) {
                Files.deleteIfExists(tmp);
                return false;
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Exception e) {
            try {
                long cur = Files.exists(tmp) ? Files.size(tmp) : 0L;
                if (cur <= from || cancelled(ctl)) {
                    Files.deleteIfExists(tmp);
                }
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    /**
     * 流式写入本地文件。用 256KB 大缓冲块写（InputStream.transferTo 内部固定 8KB，
     * 高延迟 / 跨运营商线路上吞吐被锁在 ~8KB/RTT，256KB 显著提升大文件下载速度）。
     */
    private static void transfer(java.io.InputStream in, java.io.OutputStream out,
                                 DownloadControl ctl, long maxMillis) throws IOException {
        long deadline = System.currentTimeMillis() + maxMillis;
        byte[] buf = new byte[256 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            if (cancelled(ctl)) throw new IOException("下载已取消");
            out.write(buf, 0, n);
            total += n;
            if (ctl != null) ctl.onBytes(total);
            // 传输级总时长上限：坏节点「持续慢速吐数据但连接不断」时，请求超时不会触发，
            // 这里强制中止本轮，交由外层重试/预算逻辑换镜像或放弃，杜绝单文件无限下载占死线程
            if (System.currentTimeMillis() > deadline) throw new IOException("传输超时");
        }
    }

    /** 校验文件是否存在且长度匹配；jar 额外校验 ZIP 结构完整性
     * （下载中断的 jar 长度可能正确但 CEN 头损坏，会让 Fabric/Forge 等
     * 加载器扫描 classpath 时崩溃，这里一并拦截以便重新下载）。 */
    public static boolean fileOk(Path p, long expectedSize) {
        boolean sizeOk;
        if (expectedSize <= 0) {
            try {
                sizeOk = Files.exists(p) && Files.size(p) > 0;
            } catch (IOException e) {
                return false;
            }
        } else {
            try {
                sizeOk = Files.exists(p) && Files.size(p) == expectedSize;
            } catch (IOException e) {
                return false;
            }
        }
        if (!sizeOk) return false;
        String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
        if (name.endsWith(".jar") || name.endsWith(".zip")) {
            // 首 4 字节的本地文件头必须落在偏移 0。历史坏包总长度完全正确、中央目录也能打开，
            // 但前 524KB 被另一个源的内容覆盖，首个 PK 头漂到了偏移 524,686 ——
            // 只用 ZipFile.size() 判断会一路放行，等到启动时才报 "Invalid or corrupt jarfile"。
            try (java.io.InputStream in = Files.newInputStream(p)) {
                byte[] h = new byte[4];
                if (in.read(h) != 4) return false;
                if (!(h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4)) return false;
            } catch (Exception e) {
                return false;
            }
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(p.toFile())) {
                if (zf.size() <= 0) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }
}
