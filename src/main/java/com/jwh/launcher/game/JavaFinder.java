package com.jwh.launcher.game;

import com.jwh.launcher.util.OSUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Java 运行时自动检测：扫描常见安装位置与 PATH，解析版本号。
 */
public class JavaFinder {

    public record JavaRuntime(Path javaExe, int major, String rawVersion) {
        /** 自动选择哨兵项：javaExe 为 null，启动时按游戏版本自动匹配。 */
        public static final JavaRuntime AUTO = new JavaRuntime(null, -1, "auto");

        public boolean isAuto() {
            return javaExe == null;
        }

        public String display() {
            if (javaExe == null) return "自动选择（推荐，按游戏版本自动匹配）";
            return "Java " + major + "  (" + javaExe.getParent().getParent().toString() + ")";
        }
    }

    /** 扫描系统，返回按版本降序的 Java 列表。 */
    public static List<JavaRuntime> find() {
        List<Path> candidates = new ArrayList<>();
        // JAVA_HOME
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) candidates.add(Path.of(jh, "bin", javaExeName()));
        // 当前运行的 JVM
        candidates.add(Path.of(System.getProperty("java.home"), "bin", javaExeName()));
        // Windows 常见目录
        if (OSUtil.isWindows()) {
            String pf = System.getenv("ProgramFiles");
            String pfX86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LOCALAPPDATA");
            for (String base : new String[]{pf, pfX86, local + "\\Programs", pf + "\\Microsoft"}) {
                if (base == null) continue;
                Path b = Path.of(base);
                if (!Files.isDirectory(b)) continue;
                try (Stream<Path> s = Files.list(b)) {
                    s.filter(Files::isDirectory).forEach(d -> {
                        Path j = d.resolve("bin").resolve(javaExeName());
                        if (Files.exists(j)) candidates.add(j);
                        // Eclipse Adoptium 等二级结构
                        Path j2 = d.resolve("jre").resolve("bin").resolve(javaExeName());
                        if (Files.exists(j2)) candidates.add(j2);
                    });
                } catch (IOException ignored) {
                }
            }
            // Windows 注册表登记的所有 Java（含不在标准目录的新装版本）
            collectRegistryJava(candidates);
            // PATH 里的 java（scoop / chocolatey / 自定义安装）
            collectPathJava(candidates);
        }
        // mac / linux
        Path[] extras = {
                Path.of("/Library/Java/JavaVirtualMachines"),
                Path.of("/usr/lib/jvm"),
                Path.of("/opt/java")
        };
        for (Path base : extras) {
            if (!Files.isDirectory(base)) continue;
            try (Stream<Path> s = Files.list(base)) {
                s.filter(Files::isDirectory).forEach(d -> {
                    Path j = d.resolve("Contents").resolve("Home").resolve("bin").resolve(javaExeName());
                    if (!Files.exists(j)) j = d.resolve("bin").resolve(javaExeName());
                    if (Files.exists(j)) candidates.add(j);
                });
            } catch (IOException ignored) {
            }
        }

        List<JavaRuntime> runtimes = new ArrayList<>();
        for (Path p : candidates) {
            try {
                Path real = p.toRealPath();
                if (runtimes.stream().anyMatch(r -> r.javaExe.equals(real))) continue;
                String version = readVersion(real);
                if (version == null) continue;
                int major = parseMajor(version);
                runtimes.add(new JavaRuntime(real, major, version));
            } catch (Exception ignored) {
            }
        }
        runtimes.sort(Comparator.comparingInt((JavaRuntime r) -> r.major).reversed());
        return runtimes;
    }

    /** 用 reg.exe 读取 JavaSoft 注册表键下的 JavaHome，覆盖所有新安装/非标准目录的 Java。 */
    private static void collectRegistryJava(List<Path> candidates) {
        String[] roots = {
                "HKLM\\SOFTWARE\\JavaSoft",
                "HKCU\\SOFTWARE\\JavaSoft",
                "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft"
        };
        for (String root : roots) {
            try {
                Process p = new ProcessBuilder("reg.exe", "query", root, "/s", "/v", "JavaHome")
                        .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
                for (String line : out.split("\\r?\\n")) {
                    int idx = line.indexOf("JavaHome");
                    if (idx < 0) continue;
                    String rest = line.substring(idx + "JavaHome".length());
                    int vIdx = rest.lastIndexOf("REG_SZ");
                    if (vIdx < 0) continue;
                    String path = rest.substring(vIdx + "REG_SZ".length()).trim();
                    if (!path.isEmpty()) {
                        candidates.add(Path.of(path, "bin", javaExeName()));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 扫描 PATH 中所有能解析到 java 的目录（scoop shims / chocolatey / 自定义安装）。 */
    private static void collectPathJava(List<Path> candidates) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return;
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) continue;
            try {
                Path dir = Path.of(entry.trim());
                if (!Files.isDirectory(dir)) continue;
                Path direct = dir.resolve(javaExeName());
                if (Files.exists(direct)) candidates.add(direct);
                Path underBin = dir.resolve("bin").resolve(javaExeName());
                if (Files.exists(underBin)) candidates.add(underBin);
            } catch (Exception ignored) {
            }
        }
    }

    private static String javaExeName() {
        return OSUtil.isWindows() ? "java.exe" : "java";
    }

    /** 运行 java -version 读取版本字符串。 */
    public static String readVersion(Path javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe.toString(), "-version")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            for (String line : out.split("\\r?\\n")) {
                int idx = line.indexOf("version");
                if (idx >= 0) {
                    String v = line.substring(idx + "version".length()).trim();
                    v = v.replace("\"", "");
                    return v;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析主版本号：1.8.0_402 → 8；21.0.1 → 21。 */
    public static int parseMajor(String version) {
        try {
            if (version.startsWith("1.")) {
                return Integer.parseInt(version.substring(2, version.indexOf('.', 2)));
            }
            int dot = version.indexOf('.');
            return Integer.parseInt(dot > 0 ? version.substring(0, dot) : version);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 根据 MC 版本推荐的 Java 主版本（兜底表；有版本 JSON 时以 JSON 的 javaVersion 为准）：
     * 1.8 ~ 1.16.5 → 8；1.17 → 16；1.18 ~ 1.20.4 → 17；1.20.5 ~ 1.21.x → 21；1.22+ → 25。
     */
    public static int recommendJavaMajor(String mcVersion) {
        int major = mcMajor(mcVersion);
        if (major < 0) return 21;
        if (major <= 16) return 8;                  // 1.8 ~ 1.16.5
        if (major == 17) return 16;                 // 1.17
        if (major == 18 || major == 19) return 17;  // 1.18 / 1.19
        int minor = minorOf(mcVersion);
        if (major == 20) return minor >= 5 ? 21 : 17;   // 1.20.5+ → 21，1.20 ~ 1.20.4 → 17
        if (major == 21) return 21;                 // 1.21.x
        if (major >= 22) return 25;                 // 1.22+ 需要 Java 25
        return 21;
    }

    /**
     * 取「1.x.y」里的 x 当作 MC 大版本（1.21.1 → 21，1.16.5 → 16）。
     *
     * <p>加载器版本 id 形如 {@code 1.21.1-forge-52.1.21}、{@code fabric-loader-0.20.1-1.21.1}，
     * 直接取第一个数字段只会拿到 1，会把 1.21.1 判成「需要 Java 8」，
     * 所以这里按「1.」前缀取出紧跟的版本号，取不到再退回第一段。
     */
    private static int mcMajor(String v) {
        int[] p = numbersOf(v);
        if (p.length == 0) return -1;
        if (p[0] == 1 && p.length >= 2) return p[1];
        return p[0];
    }

    private static int minorOf(String v) {
        int[] p = numbersOf(v);
        try {
            if (p[0] == 1 && p.length >= 3) return p[2];
            if (p.length >= 2) return p[1];
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 把字符串里连续的阿拉伯数字段拆出来（"1.21.1-forge-52.1.21" → 1,21,1,52,1,21）。 */
    private static int[] numbersOf(String v) {
        if (v == null || v.isBlank()) return new int[0];
        java.util.List<Integer> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < v.length()) {
            if (!Character.isDigit(v.charAt(i))) { i++; continue; }
            int j = i;
            while (j < v.length() && Character.isDigit(v.charAt(j))) j++;
            try {
                out.add(Integer.parseInt(v.substring(i, j)));
            } catch (Exception ignored) {
            }
            i = j;
        }
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(k);
        return r;
    }
}
