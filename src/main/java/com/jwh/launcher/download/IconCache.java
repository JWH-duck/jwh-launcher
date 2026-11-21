package com.jwh.launcher.download;

import javafx.application.Platform;
import javafx.scene.image.Image;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Modrinth 项目图标本地缓存。
 * 用 JDK HttpClient 下载图标到本地目录（.jwh-launcher/icons），再用 Image(本地文件) 显示，
 * 避免 JavaFX Image 直连 HTTPS 在部分网络环境下加载失败导致列表全是占位图。
 * 多线程下载 + body 超时保护（不会因单个慢 URL 卡死队列）+ 失败自动重试（最多 3 次，刷新可重置）。
 *
 * 关键：Modrinth 部分项目图标以 WebP 内容返回（URL 后缀虽为 .webp），而 JavaFX 21 无法解码 WebP
 * （实测 Image.isError()=true "No loader for image data"）。因此下载后检测 WebP 头并自动转存为 PNG，
 * 保证 JavaFX 一定能显示；已缓存但解码失败的文件会被自动删除并在下次访问时重新下载转换。
 */
public final class IconCache {

    private static final Path DIR = Path.of(System.getProperty("user.home"), ".jwh-launcher", "icons");
    private static final int MAX_RETRY = 3;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService DOWNLOAD = Executors.newFixedThreadPool(3);
    /** 正在下载的 url 集合，防止并发重复下载。 */
    private static final Map<String, Boolean> INFLIGHT = new ConcurrentHashMap<>();
    /** 下载失败计数，超过 MAX_RETRY 后本会话不再尝试（点刷新可重置）。 */
    private static final Map<String, Integer> FAILED = new ConcurrentHashMap<>();

    private IconCache() {
    }

    /** 刷新/重试时调用：清除失败计数，让所有图标重新尝试下载。 */
    public static void resetFailures() {
        FAILED.clear();
    }

    /** 缓存命中直接返回 Image；未命中返回 null（若缓存文件损坏/不可解码则删除后返回 null）。 */
    public static Image cached(String url) {
        return cached(url, 36);
    }

    /** 按指定显示尺寸取缓存（同 URL 不同尺寸各存一份，避免大图被压成 36px 再放大变糊）。 */
    public static Image cached(String url, int size) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Path f = fileFor(url, size);
        if (Files.exists(f)) {
            Image img = loadQuiet(f, size);
            if (img != null) {
                return img;
            }
            // loadQuiet 已删除损坏文件，返回 null 让调用方重新下载
        }
        return null;
    }

    /** 异步确保图标可用；下载完成后在 FX 线程回调 Image（失败则不回调，保持占位图）。 */
    public static void ensure(String url, Consumer<Image> done) {
        ensure(url, 36, done);
    }

    /** 异步确保图标可用（指定显示尺寸，默认 36）。 */
    public static void ensure(String url, int size, Consumer<Image> done) {
        if (url == null || url.isBlank()) {
            return;
        }
        Path f = fileFor(url, size);
        if (Files.exists(f)) {
            Image img = loadQuiet(f, size);
            if (img != null) {
                done.accept(img);
                return;
            }
            // 损坏文件已删除，继续走下载
        }
        Integer fails = FAILED.get(url);
        if (fails != null && fails >= MAX_RETRY) {
            return; // 重试次数用尽，等待下次刷新
        }
        if (INFLIGHT.putIfAbsent(url, Boolean.TRUE) != null) {
            return; // 已在下载中
        }
        DOWNLOAD.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "JWH-Launcher/1.6")
                        .GET().build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200 && resp.body() != null && resp.body().length > 0) {
                    Files.createDirectories(DIR);
                    Path tmp = DIR.resolve("tmp_" + System.nanoTime() + ".dat");
                    Files.write(tmp, resp.body());
                    try {
                        if (isWebp(tmp)) {
                            // WebP → PNG 转换（JavaFX 21 不支持 WebP）
                            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(tmp.toFile());
                            if (bi == null) {
                                throw new java.io.IOException("no image reader for WebP");
                            }
                            javax.imageio.ImageIO.write(bi, "png", f.toFile());
                            Files.deleteIfExists(tmp);
                        } else {
                            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                        }
                        Image img = new Image(f.toUri().toString(), size, size, true, true);
                        Platform.runLater(() -> done.accept(img));
                    } catch (Exception e) {
                        Files.deleteIfExists(tmp);
                        FAILED.merge(url, 1, Integer::sum);
                    }
                } else {
                    FAILED.merge(url, 1, Integer::sum);
                }
            } catch (Exception e) {
                FAILED.merge(url, 1, Integer::sum);
            } finally {
                INFLIGHT.remove(url);
            }
        });
    }

    /** 加载缓存文件；JavaFX 无法解码（如残留 WebP 文件）时删除并返回 null。 */
    private static Image loadQuiet(Path f, int size) {
        try {
            Image img = new Image(f.toUri().toString(), size, size, true, true);
            if (img.isError()) {
                Files.deleteIfExists(f);
                return null;
            }
            return img;
        } catch (Exception e) {
            try {
                Files.deleteIfExists(f);
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    /** 检测文件是否为 WebP（RIFF....WEBP 头）。 */
    private static boolean isWebp(Path p) throws java.io.IOException {
        try (var in = Files.newInputStream(p)) {
            byte[] h = in.readNBytes(12);
            return h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
        }
    }

    private static Path fileFor(String url, int size) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            // 默认尺寸沿用历史文件名（避免把已有缓存当成另一张图重复下载）
            return DIR.resolve(sb + (size == 36 ? "" : "_" + size) + ".png");
        } catch (Exception e) {
            return DIR.resolve(Integer.toHexString(url.hashCode()) + (size == 36 ? "" : "_" + size) + ".png");
        }
    }
}
