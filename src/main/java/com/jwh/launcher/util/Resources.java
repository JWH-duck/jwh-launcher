package com.jwh.launcher.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 统一资源解析器（阶段 B：大资源外置）。
 *
 * <p>内置大资源（联机内核、neoform/neoforge 产物、内置音乐、authlib 注入器）从 fat jar
 * 移到 exe 同级 {@code resources/} 目录，常规更新只需替换程序 jar；缺失的资源层再由
 * 分层更新按需下载补齐。
 *
 * <p>解析优先级：
 * <ol>
 *   <li>外置目录 {@code <exe 目录>/resources/<rel>}（打包态 / 带外置目录的开发态）；</li>
 *   <li>classpath {@code /<rel>}（IDE 直跑、或外置层尚未补齐时的兜底）。</li>
 * </ol>
 * 所有读取点统一走本类，避免各处各自判断外置/打包路径。
 */
public final class Resources {

    private Resources() {
    }

    /** 外置资源目录名（相对 exe 所在目录）。 */
    public static final String EXTERNAL_DIR = "resources";

    /** 是否打包态（jpackage 注入了 app-path）。 */
    public static boolean packaged() {
        String exe = System.getProperty("jpackage.app-path");
        return exe != null && !exe.isBlank() && Files.isRegularFile(Path.of(exe));
    }

    /**
     * 外置资源根目录；不存在或当前为直跑环境时返回 {@code null}。
     * 打包态取 exe 父目录下的 {@code resources/}；开发态若工作目录下存在 {@code resources/} 也采用，便于本地验证外置链路。
     */
    public static Path externalRoot() {
        String exe = System.getProperty("jpackage.app-path");
        Path base;
        if (exe != null && !exe.isBlank()) {
            Path p = Path.of(exe).toAbsolutePath();
            base = Files.isRegularFile(p) ? p.getParent() : Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        } else {
            base = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        }
        Path root = base.resolve(EXTERNAL_DIR);
        return Files.isDirectory(root) ? root : null;
    }

    /**
     * 外置文件路径；不存在或无外置根时返回 {@code null}。
     *
     * @param rel 资源相对路径，用 {@code /} 分隔（如 {@code relay/easytier-core.exe}）
     */
    public static Path external(String rel) {
        Path root = externalRoot();
        if (root == null) return null;
        Path p = root.resolve(rel.replace('/', java.io.File.separatorChar)).toAbsolutePath();
        // 防路径越界：解析后必须仍在外置根内
        if (!p.startsWith(root.toAbsolutePath())) return null;
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * 打开资源流：外置文件优先，回退 classpath；都不存在时返回 {@code null}。
     * 调用方负责关闭返回的流。
     */
    public static InputStream open(String rel) throws IOException {
        Path ext = external(rel);
        if (ext != null) return Files.newInputStream(ext);
        return Resources.class.getResourceAsStream("/" + rel);
    }

    /**
     * 取本地文件：外置文件直接返回；classpath 资源复制到 {@code cacheDir} 后返回副本。
     * 都不存在返回 {@code null}。
     */
    public static Path materialize(String rel, Path cacheDir) throws IOException {
        Path ext = external(rel);
        if (ext != null) return ext;
        try (InputStream in = Resources.class.getResourceAsStream("/" + rel)) {
            if (in == null) return null;
            Files.createDirectories(cacheDir);
            String simple = rel.substring(rel.lastIndexOf('/') + 1);
            Path out = cacheDir.resolve(simple);
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        }
    }

    /** 资源 URL：外置 file URL，否则 classpath URL；都不存在返回 {@code null}。 */
    public static URL url(String rel) {
        Path ext = external(rel);
        if (ext != null) {
            try {
                return ext.toUri().toURL();
            } catch (Exception ignored) {
            }
        }
        return Resources.class.getResource("/" + rel);
    }

    /**
     * 枚举目录前缀下的条目（返回 {@code rel} 形式名称，如 {@code music/xxx.mp3}）。
     * 外置目录优先；回退到 codeSource jar 条目或 classes 目录。
     *
     * @param prefix 目录前缀，需以 {@code /} 结尾（如 {@code music/}）
     * @param filter 条目名过滤（可空）
     */
    public static List<String> listDir(String prefix, Predicate<String> filter) throws IOException {
        List<String> out = new ArrayList<>();
        Path root = externalRoot();
        if (root != null) {
            Path dir = root.resolve(prefix.replace('/', java.io.File.separatorChar));
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.map(p -> prefix + p.getFileName())
                            .filter(filter == null ? n -> true : filter)
                            .sorted()
                            .forEach(out::add);
                }
                return out;
            }
        }

        URL loc = Resources.class.getProtectionDomain().getCodeSource().getLocation();
        if (loc == null) return out;
        Path base;
        try {
            base = Paths.get(loc.toURI());
        } catch (URISyntaxException e) {
            return out;
        }
        if (Files.isRegularFile(base)) {
            try (ZipFile zip = new ZipFile(base.toFile())) {
                zip.stream()
                        .filter(e -> !e.isDirectory())
                        .map(ZipEntry::getName)
                        .filter(n -> n.startsWith(prefix))
                        .filter(filter == null ? n -> true : filter)
                        .sorted()
                        .forEach(out::add);
            }
        } else {
            Path dir = base.resolve(prefix);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.map(p -> prefix + p.getFileName())
                            .filter(filter == null ? n -> true : filter)
                            .sorted()
                            .forEach(out::add);
                }
            }
        }
        return out;
    }
}
