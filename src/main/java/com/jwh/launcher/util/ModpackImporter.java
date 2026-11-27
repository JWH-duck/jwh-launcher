package com.jwh.launcher.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 整合包导入工具：把拖入的整合包压缩包（.zip / .jar / .mrpack）解压、
 * 识别目录结构、合并复制到游戏目录。压缩包拖到启动器即可导入。
 * 解压与合并都做了路径穿越防护（拒绝 ../ 与绝对路径条目）。
 */
public final class ModpackImporter {

    /** 解压 zip 到目标目录（防路径穿越：拒绝 ../ 与绝对路径条目）。 */
    public static void unzip(Path zip, Path target) throws Exception {
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry en;
            while ((en = zin.getNextEntry()) != null) {
                Path out = target.resolve(en.getName()).normalize();
                if (!out.startsWith(target)) {
                    throw new IOException("整合包内含非法路径条目：" + en.getName());
                }
                if (en.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zin.closeEntry();
            }
        }
    }

    /** 剥掉单层包裹目录（整合包常见：整个包套在一个文件夹里）。 */
    public static Path detectRoot(Path tmp) throws Exception {
        try (var s = Files.list(tmp)) {
            List<Path> all = s.toList();
            List<Path> dirs = all.stream().filter(Files::isDirectory).toList();
            List<Path> files = all.stream().filter(Files::isRegularFile).toList();
            if (dirs.size() == 1 && files.isEmpty()) {
                Path inner = dirs.get(0);
                try (var s2 = Files.list(inner)) {
                    if (s2.findAny().isPresent()) return inner;
                }
            }
        }
        return tmp;
    }

    /** 识别整合包结构类型（用于提示）。 */
    public static String detectType(Path root) {
        if (Files.exists(root.resolve("modrinth.index.json"))) return "Modrinth 整合包";
        if (Files.exists(root.resolve("manifest.json"))) return "CurseForge 整合包";
        if (Files.isDirectory(root.resolve("versions"))) return "版本目录结构整合包";
        if (Files.isDirectory(root.resolve("mods")) || Files.isDirectory(root.resolve("config"))) {
            return ".minecraft 结构整合包";
        }
        return "文件结构整合包";
    }

    /** 把整合包内容合并复制到游戏目录（覆盖同名文件）。 */
    public static void mergeInto(Path src, Path mcDir) throws Exception {
        try (var s = Files.walk(src)) {
            List<Path> all = s.toList();
            for (Path p : all) {
                if (Files.isDirectory(p)) continue;
                Path rel = src.relativize(p);
                Path out = mcDir.resolve(rel.toString()).normalize();
                if (!out.startsWith(mcDir)) {
                    throw new IOException("非法相对路径：" + rel);
                }
                Files.createDirectories(out.getParent());
                Files.copy(p, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 递归删除目录。 */
    public static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private ModpackImporter() {
    }
}
