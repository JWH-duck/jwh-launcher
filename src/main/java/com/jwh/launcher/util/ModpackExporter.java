package com.jwh.launcher.util;

import com.jwh.launcher.game.LocalVersions;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 整合包导出：把一个本地实例打包成通用整合包 zip。
 *
 * <p>输出结构（整合包通用约定，任何启动器都能识别）：
 * <pre>
 *   manifest.json          CurseForge 清单（minecraft.version + modLoaders + overrides）
 *   jwh-pack.json          本启动器的完整元数据（mod 清单、sha1、大小、加载器、时间）
 *   overrides/mods/...     实例里真实的 mods / config / 资源包 / 光影
 *   overrides/config/...
 *   overrides/options.txt  （存在才带）
 *   overrides/resourcepacks/...
 *   overrides/shaderpacks/...
 * </pre>
 *
 * <p>导出两种格式，任选其一：
 * <ul>
 *   <li>{@link Format#CURSEFORGE}：manifest.json + overrides/，常见启动器与 CurseForge 认</li>
 *   <li>{@link Format#MODRINTH}：modrinth.index.json + overrides/，Modrinth App / Prism 认（.mrpack）</li>
 * </ul>
 * 两种格式都额外带一份 jwh-pack.json（本启动器自己再导入时用）。
 *
 * <p>默认不带存档 saves（体积大且属于个人进度）；需要时传 includeSaves=true，
 * 存档会放进 overrides/saves。日志、崩溃报告、CustomSkinLoader 皮肤模组残留 jar 永远不带。
 */
public final class ModpackExporter {

    /** 整合包格式。 */
    public enum Format {
        /** CurseForge 通用结构：manifest.json + overrides/。 */
        CURSEFORGE,
        /** Modrinth 结构：modrinth.index.json + overrides/（扩展名 .mrpack）。 */
        MODRINTH
    }

    /** 打包结果：文件数、原始字节数、mod 数量、最终文件大小。 */
    public record Result(int files, long bytes, int mods, long zipSize) {
    }

    /** 不打包的目录（实例根下一级）；saves 由 includeSaves 单独控制，不在这里。 */
    private static final List<String> SKIP_DIRS = List.of(
            "logs", "crash-reports", "backups", "screenshots",
            "CustomSkinLoader", ".cache", "downloads", "jwh-cache");

    /** 不打包的文件（实例根下一级）。 */
    private static final List<String> SKIP_FILES = List.of(
            "authlib-injector.log", "latest.log", "debug.log", "usercache.json", "realms_pending_invites.txt");

    private ModpackExporter() {
    }

    /** CurseForge 的加载器 id：forge-52.1.21 / neoforge-21.1.265 / fabric-0.20.1 / quilt-0.31.0-beta.4。 */
    public static String loaderId(LocalVersions.LocalVersion v) {
        if (v == null) return null;
        return loaderId(v.loaderName(), v.loaderVer());
    }

    /** 同上，按加载器类型与版本号构造；版本号缺失时返回 null（宁可不写，也不写 "fabric-" 这种坏 id）。 */
    public static String loaderId(String loaderName, String loaderVer) {
        if (loaderName == null || loaderName.isBlank()) return null;
        if (loaderVer == null || loaderVer.isBlank()) return null;
        String n = loaderName.toLowerCase(Locale.ROOT);
        String prefix;
        if (n.contains("neoforge")) prefix = "neoforge-";
        else if (n.contains("forge")) prefix = "forge-";
        else if (n.contains("quilt")) prefix = "quilt-";
        else if (n.contains("fabric")) prefix = "fabric-";
        else return null;
        return prefix + loaderVer.trim();
    }

    /**
     * 从实例版本 json 的 libraries 里推断加载器版本号。
     * 整合包实例（从 .mrpack 安装出来的，如 skyblock-plus）id 里只有整合包名，
     * 但它的 json 里必然列着加载器库，例如 net.fabricmc:fabric-loader:0.16.5。
     */
    static String detectLoaderVersion(Path instanceDir, String versionId, String loaderName) {
        Path json = instanceDir.resolve(versionId + ".json");
        if (!Files.isRegularFile(json)) return null;
        String n = loaderName.toLowerCase(Locale.ROOT);
        String key = n.contains("neoforge") ? "neoforge"
                : n.contains("forge") ? "forge"
                : n.contains("quilt") ? "quilt-loader" : "fabric-loader";
        try {
            JSONObject o = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
            JSONArray libs = o.optJSONArray("libraries");
            if (libs == null) return null;
            for (int i = 0; i < libs.length(); i++) {
                String name = libs.getJSONObject(i).optString("name", "");
                String[] parts = name.split(":");
                if (parts.length < 3) continue;
                if (!parts[1].equalsIgnoreCase(key)) continue;
                String ver = parts[2];
                if ("forge".equals(key) && ver.contains("-")) {
                    // net.minecraftforge:forge:1.21.1-52.1.21 → 取后段
                    ver = ver.substring(ver.lastIndexOf('-') + 1);
                }
                return ver;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 导出实例为整合包（CurseForge 结构、不含存档）。
     *
     * @param instanceDir 实例目录（versions/&lt;id&gt;）
     * @param outZip      输出文件
     * @param info        该实例的版本信息（可为 null：此时按目录名兜底）
     * @param author      作者名（一般填当前账号名）
     */
    public static Result export(Path instanceDir, Path outZip, LocalVersions.LocalVersion info,
                               String author) throws Exception {
        return export(instanceDir, outZip, info, author, Format.CURSEFORGE, false);
    }

    /**
     * 导出实例为整合包。
     *
     * @param fmt          包格式（CurseForge / Modrinth）
     * @param includeSaves 是否把 saves 存档一起打包（放进 overrides/saves）
     */
    public static Result export(Path instanceDir, Path outZip, LocalVersions.LocalVersion info,
                               String author, Format fmt, boolean includeSaves) throws Exception {
        if (!Files.isDirectory(instanceDir)) throw new IOException("实例目录不存在：" + instanceDir);

        String versionId = info != null ? info.id() : instanceDir.getFileName().toString();
        // 整合包实例（skyblock-plus 这类自命名 id）用 id 当包名，才认得出是哪个包；
        // 标准加载器/原版实例用显示名（1.21.1 · Fabric），id 里那串 loader 版本号不适合当包名
        String packName = LocalVersions.isGameVersion(versionId)
                ? versionId
                : (info != null ? info.displayName() : versionId);
        String mc = info != null && info.mcBase() != null ? info.mcBase() : versionId;
        String loaderName = info != null ? info.loaderName() : null;
        String loaderVer = info != null ? info.loaderVer() : null;
        // 整合包实例（如从 .mrpack 装出来的 skyblock-plus）id 里不带加载器版本，
        // 这里从版本 json 的 libraries 里把真实加载器版本捞出来，清单才不会写成 "fabric-"
        if ((loaderVer == null || loaderVer.isBlank()) && loaderName != null && !loaderName.isBlank()) {
            loaderVer = detectLoaderVersion(instanceDir, versionId, loaderName);
        }
        String lid = loaderId(loaderName, loaderVer);
        if (author == null || author.isBlank()) author = "JWH-Launcher";

        // 先扫一遍要打包的内容，用于清单与提示（也避免边写边统计出错）
        List<Path> mods = new ArrayList<>();
        Path modsDir = instanceDir.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> s = Files.list(modsDir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> !isSkinModJar(p.getFileName().toString()))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .forEach(mods::add);
            }
        }

        JSONObject jwh = new JSONObject();
        jwh.put("format", "jwh-modpack/1");
        jwh.put("name", packName);
        jwh.put("version", "1.0.0");
        jwh.put("author", author);
        jwh.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        JSONObject mcObj = new JSONObject();
        mcObj.put("version", mc);
        if (loaderName != null && !loaderName.isBlank()) mcObj.put("loader", loaderName);
        if (loaderVer != null && !loaderVer.isBlank()) mcObj.put("loaderVersion", loaderVer);
        mcObj.put("versionId", versionId);
        if (lid != null) mcObj.put("loaderId", lid);
        jwh.put("minecraft", mcObj);

        JSONObject manifest = new JSONObject();
        manifest.put("manifestType", "minecraftModpack");
        manifest.put("manifestVersion", 1);
        manifest.put("name", packName);
        manifest.put("version", "1.0.0");
        manifest.put("author", author);
        JSONObject mMc = new JSONObject();
        mMc.put("version", mc);
        JSONArray loaders = new JSONArray();
        if (lid != null) loaders.put(new JSONObject().put("id", lid).put("primary", true));
        mMc.put("modLoaders", loaders);
        manifest.put("minecraft", mMc);
        manifest.put("files", new JSONArray());
        manifest.put("overrides", "overrides");

        // Modrinth 格式的清单（modrinth.index.json）。files 留空、内容全放 overrides：
        // 离线导出拿不到官方下载地址，Modrinth 规范允许 overrides 承载未被 files 覆盖的文件。
        JSONObject index = new JSONObject();
        index.put("formatVersion", 1);
        index.put("game", "minecraft");
        index.put("versionId", "1.0.0");
        index.put("name", packName);
        StringBuilder summary = new StringBuilder("由 JWH-Launcher 导出的整合包：").append(mc);
        if (loaderName != null && !loaderName.isBlank()) {
            summary.append(" · ").append(loaderName);
            if (loaderVer != null && !loaderVer.isBlank()) summary.append(' ').append(loaderVer);
        }
        index.put("summary", summary.toString());
        index.put("files", new JSONArray());
        JSONObject deps = new JSONObject();
        deps.put("minecraft", mc);
        putLoaderDependency(deps, loaderName, loaderVer);
        index.put("dependencies", deps);
        jwh.put("packFormat", fmt == Format.MODRINTH ? "modrinth" : "curseforge");
        jwh.put("includeSaves", includeSaves);

        int[] counter = {0};
        long[] bytes = {0};
        JSONArray modList = new JSONArray();

        Files.createDirectories(outZip.toAbsolutePath().getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outZip))) {
            zos.setLevel(9);
            if (fmt == Format.MODRINTH) {
                writeText(zos, "modrinth.index.json", index.toString(2));
            } else {
                writeText(zos, "manifest.json", manifest.toString(2));
            }
            // jwh-pack.json 里的 mod 清单需要 sha1，先收集再写（放在最后写入）
            for (Path m : mods) {
                String sha1 = sha1(m);
                JSONObject mo = new JSONObject();
                mo.put("file", m.getFileName().toString());
                mo.put("sha1", sha1);
                mo.put("size", Files.size(m));
                mo.put("enabled", !m.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".disabled"));
                modList.put(mo);
            }

            // overrides：实例里真实的内容
            for (Path m : mods) {
                addFile(zos, m, "overrides/mods/" + m.getFileName(), counter, bytes);
            }
            copyTree(zos, instanceDir.resolve("config"), "overrides/config", counter, bytes);
            copyTree(zos, instanceDir.resolve("resourcepacks"), "overrides/resourcepacks", counter, bytes);
            copyTree(zos, instanceDir.resolve("shaderpacks"), "overrides/shaderpacks", counter, bytes);

            for (String f : List.of("options.txt", "servers.dat", "servers.dat_old")) {
                Path p = instanceDir.resolve(f);
                if (Files.isRegularFile(p)) addFile(zos, p, "overrides/" + f, counter, bytes);
            }
            // 存档按用户选择：进 overrides/saves，导入端会原样还原到 saves/
            if (includeSaves) copySaves(zos, instanceDir.resolve("saves"), counter, bytes);

            // 元数据最后写：此时 mod sha1 已算好
            jwh.put("mods", modList);
            jwh.put("modCount", modList.length());
            writeText(zos, "jwh-pack.json", jwh.toString(2));
        }
        return new Result(counter[0], bytes[0], modList.length(), Files.size(outZip));
    }

    /** Modrinth 清单的 dependencies 里加载器字段：forge / neoforge / fabric-loader / quilt-loader。 */
    private static void putLoaderDependency(JSONObject deps, String loaderName, String loaderVer) {
        if (loaderName == null || loaderName.isBlank()) return;
        if (loaderVer == null || loaderVer.isBlank()) return;
        String n = loaderName.toLowerCase(Locale.ROOT);
        String key = n.contains("neoforge") ? "neoforge"
                : n.contains("forge") ? "forge"
                : n.contains("quilt") ? "quilt-loader"
                : n.contains("fabric") ? "fabric-loader" : null;
        if (key != null) deps.put(key, loaderVer.trim());
    }

    /** 存档目录：逐个世界写进 overrides/saves（跳过 session.lock，游戏运行时它可能被占用）。 */
    private static void copySaves(ZipOutputStream zos, Path dir, int[] counter, long[] bytes) throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> all = s.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase("session.lock"))
                    .sorted(Comparator.comparing(p -> p.toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (Path p : all) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                addFile(zos, p, "overrides/saves/" + rel, counter, bytes);
            }
        }
    }

    /** 遍历目录并写入 zip（相对路径统一用 /；跳过不打包的目录）。 */
    private static void copyTree(ZipOutputStream zos, Path dir, String entryRoot, int[] counter, long[] bytes)
            throws Exception {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            List<Path> all = s.sorted(Comparator.comparingInt(Path::getNameCount))
                    .filter(Files::isRegularFile).toList();
            for (Path p : all) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                addFile(zos, p, entryRoot + "/" + rel, counter, bytes);
            }
        }
    }

    private static void addFile(ZipOutputStream zos, Path file, String entry, int[] counter, long[] bytes)
            throws IOException {
        for (String skip : SKIP_DIRS) {
            if (entry.contains("/" + skip + "/") || entry.equals("overrides/" + skip)) return;
        }
        for (String skip : SKIP_FILES) {
            if (entry.equals("overrides/" + skip)) return;
        }
        zos.putNextEntry(new ZipEntry(entry));
        try (InputStream in = Files.newInputStream(file)) {
            in.transferTo(new CountingStream(zos, bytes));
        }
        zos.closeEntry();
        counter[0]++;
    }

    private static void writeText(ZipOutputStream zos, String entry, String text) throws IOException {
        zos.putNextEntry(new ZipEntry(entry));
        zos.write(text.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /** CustomSkinLoader 皮肤模组残留不属于玩家内容，导出时排除。 */
    private static boolean isSkinModJar(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("customskinloader") || n.contains("customskinloader");
    }

    public static String sha1(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** 统计写出字节数（顺带给出导出过程的真实进度依据）。 */
    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        private final long[] total;

        CountingStream(OutputStream out, long[] total) {
            this.out = out;
            this.total = total;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            total[0]++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            total[0] += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }
    }
}
