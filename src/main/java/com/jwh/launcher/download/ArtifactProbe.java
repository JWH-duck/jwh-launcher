package com.jwh.launcher.download;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 下载产物的结构校验：所有"这个文件到底下完没有"的判断都收敛到这里。
 *
 * <p>历史教训：只看"文件存在且够大"会把镜像返回的 404 HTML 当成安装器；
 * 只看"ZipFile 能打开"会放过"总长度正确、首个 PK 头在偏移 524686"的拼接坏包。
 * 所以这里按类型给出可验证的证据，而不是体积近似。
 */
public final class ArtifactProbe {

    /** 产物类型。类型决定校验强度。 */
    public enum Kind {
        /** 普通 jar（mod、库、客户端 jar）：ZIP 结构 + 头在偏移 0。 */
        JAR,
        /** 可执行安装器 jar：在 JAR 基础上强制全条目 CRC。 */
        INSTALLER,
        /** zip 压缩包（整合包、更新包、地图包）。 */
        ZIP,
        /** json 元数据（版本 json、asset index）：要求能被解析。 */
        JSON,
        /** 纯二进制资源（图片、音频、材质包内的 png 等）。 */
        ASSET,
        /** 未分类二进制：只做非空 + 大小比对。 */
        BINARY;

        /** 按文件名与预期大小推断类型。 */
        public static Kind of(Path target, long expectedSize) {
            String n = target == null || target.getFileName() == null
                    ? "" : target.getFileName().toString().toLowerCase();
            if (n.endsWith(".jar")) return JAR;
            if (n.endsWith(".zip")) return ZIP;
            if (n.endsWith(".json")) return JSON;
            return expectedSize > 0 ? BINARY : ASSET;
        }
    }

    /** 校验结论。reason 为空表示通过；否则是可直接写入日志的失败原因。 */
    public record Verdict(boolean ok, String reason) {
        public static Verdict pass() {
            return new Verdict(true, "");
        }

        public static Verdict fail(String reason) {
            return new Verdict(false, reason);
        }
    }

    /** 超过这个体积的 jar 只验头部与中央目录，避免每次启动把 80MB 客户端读一遍。 */
    private static final long CRC_LIMIT = 32L * 1024 * 1024;
    /** 小于这个体积的文件视作可疑（除 json 外的正常产物都远大于此）。 */
    private static final long MIN_BYTES = 8;

    private ArtifactProbe() {
    }

    /** 校验文件是否可用。expectedSize <= 0 表示大小未知，只做类型级校验。 */
    public static Verdict check(Path target, Kind kind, long expectedSize) {
        if (target == null) return Verdict.fail("target-null");
        try {
            if (!Files.isRegularFile(target)) return Verdict.fail("missing");
            long size = Files.size(target);
            if (size <= 0) return Verdict.fail("empty");
            if (size < MIN_BYTES && kind != Kind.JSON) return Verdict.fail("too-small:" + size);
            if (expectedSize > 0 && size != expectedSize) {
                return Verdict.fail("size:" + size + "/" + expectedSize);
            }
            return switch (kind) {
                case JAR, INSTALLER, ZIP -> checkZip(target, size, kind == Kind.INSTALLER);
                case JSON -> checkJson(target);
                case ASSET, BINARY -> Verdict.pass();
            };
        } catch (IOException e) {
            return Verdict.fail("io:" + e.getClass().getSimpleName());
        }
    }

    /** ZIP 家族校验：首个 local file header 必须在偏移 0，中央目录必须可读，必要时全条目 CRC。 */
    private static Verdict checkZip(Path target, long size, boolean forceCrc) throws IOException {
        byte[] head = new byte[4];
        try (InputStream in = Files.newInputStream(target)) {
            int n = in.read(head);
            if (n != 4) return Verdict.fail("head-short");
        }
        // 合法的 jar/zip 必须以 "PK\x03\x04" 开头；偏移不为 0 说明是拼接错位的伪包
        if (!(head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4)) {
            return Verdict.fail(String.format("bad-head:%02x%02x%02x%02x", head[0], head[1], head[2], head[3]));
        }
        try (ZipFile zf = new ZipFile(target.toFile())) {
            int entries = zf.size();
            if (entries <= 0) return Verdict.fail("zip-empty");
            if (!forceCrc && size > CRC_LIMIT) return Verdict.pass();
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                try (InputStream in = zf.getInputStream(entry)) {
                    in.transferTo(java.io.OutputStream.nullOutputStream());
                }
            }
        } catch (IOException e) {
            // ZipException 是 IOException 子类：中央目录损坏、条目 CRC 不符都会走到这里
            return Verdict.fail("zip:" + e.getClass().getSimpleName());
        }
        return Verdict.pass();
    }

    /** JSON 校验：必须能被解析（截断的 json 会在下载后立刻失败，而不是等到启动游戏）。 */
    private static Verdict checkJson(Path target) throws IOException {
        String text = Files.readString(target, StandardCharsets.UTF_8);
        String t = text.strip();
        if (t.isEmpty()) return Verdict.fail("json-empty");
        if (!(t.startsWith("{") || t.startsWith("["))) return Verdict.fail("json-not-object");
        try {
            if (t.startsWith("{")) new org.json.JSONObject(t);
            else new org.json.JSONArray(t);
        } catch (Exception e) {
            return Verdict.fail("json-parse");
        }
        return Verdict.pass();
    }

    /** 删除文件及其所有下载残片（.part / .segN）。 */
    public static void purge(Path target) {
        if (target == null) return;
        try {
            Files.deleteIfExists(target);
        } catch (Exception ignored) {
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.deleteIfExists(part);
        } catch (Exception ignored) {
        }
        for (int i = 0; i < 8; i++) {
            try {
                Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".seg" + i));
            } catch (Exception ignored) {
            }
        }
    }

    /** 只删残片，保留目标文件（换源前清干净，但不影响下一源从零开始写）。 */
    public static void purgeResidue(Path target) {
        if (target == null) return;
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.deleteIfExists(part);
        } catch (Exception ignored) {
        }
        for (int i = 0; i < 8; i++) {
            try {
                Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".seg" + i));
            } catch (Exception ignored) {
            }
        }
    }
}
