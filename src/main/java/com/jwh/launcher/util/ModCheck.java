package com.jwh.launcher.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mod 兼容性预检：扫描实例 mods 目录下所有 .jar（跳过 .disabled），解析
 * Forge/NeoForge 的 META-INF/mods.toml、Fabric 的 fabric.mod.json、Quilt 的
 * quilt.mod.json，检测四类问题：
 *   1) 缺少前置（必需依赖的 mod 未安装）
 *   2) 前置版本不匹配（已安装但版本不在要求范围内）
 *   3) Mod 冲突（conflicts/breaks 双方同时启用）
 *   4) 版本不对（Mod 要求的 Minecraft / 加载器版本与当前实例不符）
 * 每类问题附带中文解决办法，供启动前预检与启动失败弹窗使用。
 */
public final class ModCheck {

    public record Dep(String modId, String range, boolean mandatory) {
    }

    public record ModMeta(String fileName, String modId, String version, List<Dep> deps, List<Dep> conflicts) {
    }

    public record Issue(String modFile, String type, String message, String solution) {
    }

    private static final Set<String> LOADER_IDS = Set.of(
            "fabricloader", "quilt_loader", "quilt-loader", "forge", "neoforge", "javafml", "lowcodefml", "fml");

    private ModCheck() {
    }

    /** 检查一个实例的 mods 目录。mcVersion/loaderVersion 可为 null（跳过对应检查）。javaFeature 为启动该版本所用的 Java 主版本（0=跳过）。 */
    public static List<Issue> check(Path modsDir, String mcVersion, String loaderVersion, int javaFeature) {
        List<Issue> issues = new ArrayList<>();
        List<ModMeta> metas = new ArrayList<>();
        Map<String, String> installed = new HashMap<>();
        try {
            if (!Files.isDirectory(modsDir)) return issues;
            try (var s = Files.list(modsDir)) {
                s.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".jar")).forEach(p -> {
                    ModMeta meta = parseJar(p);
                    if (meta != null) {
                        metas.add(meta);
                        installed.putIfAbsent(meta.modId(), meta.version());
                    }
                });
            }
        } catch (Exception e) {
            return issues;
        }
        for (ModMeta m : metas) {
            for (Dep d : m.deps()) {
                if (isMc(d.modId())) {
                    if (mcVersion != null && !inRange(mcVersion, d.range())) {
                        issues.add(new Issue(m.fileName(), "版本不对",
                                "要求 Minecraft " + displayRange(d.range()) + "，当前实例为 " + mcVersion,
                                "请在「下载中心」安装与当前游戏版本匹配的该 Mod 版本，或更换游戏版本。"));
                    }
                    continue;
                }
                if (isLoader(d.modId())) {
                    if (loaderVersion != null && !inRange(loaderVersion, d.range())) {
                        issues.add(new Issue(m.fileName(), "版本不对",
                                "要求加载器版本 " + displayRange(d.range()) + "，当前加载器版本为 " + loaderVersion,
                                "请在「下载中心 → 加载器」安装兼容的加载器版本，或更换该 Mod 版本。"));
                    }
                    continue;
                }
                if ("java".equals(d.modId())) {
                    // Java 是运行时环境，不是 mod；用启动该版本的实际 Java 主版本判断
                    if (javaFeature > 0 && !inRange(String.valueOf(javaFeature), d.range())) {
                        issues.add(new Issue(m.fileName(), "缺少前置",
                                "需要 Java " + displayRange(d.range()) + "，当前 JVM 为 Java " + javaFeature,
                                "请在「设置」页把 Java 切换到 " + displayRange(d.range()) + " 或更高版本后重启。"));
                    }
                    continue;
                }
                String have = installed.get(d.modId());
                if (have == null && d.modId().startsWith("fabric-") && installed.containsKey("fabric-api")) {
                    // Fabric API 全家桶 jar 提供所有 fabric-* 模块（如 fabric-resource-loader-v0），视为已安装
                    have = installed.get("fabric-api");
                }
                if (have == null) {
                    if (d.mandatory()) {
                        issues.add(new Issue(m.fileName(), "缺少前置",
                                "缺少前置 Mod「" + d.modId() + "」" + displayRange(d.range()),
                                "请在「下载中心 → Mod」搜索并安装 " + d.modId() + "（下载时勾选「自动安装前置」），装好后再启动。"));
                    }
                } else if (!inRange(have, d.range())) {
                    issues.add(new Issue(m.fileName(), "前置版本不匹配",
                            "依赖「" + d.modId() + "」" + displayRange(d.range()) + "，已安装版本为 " + have,
                            "请在「实例管理 → Mod 模组」中把 " + d.modId() + " 更新到兼容版本，或更换本 Mod 的版本。"));
                }
            }
            for (Dep c : m.conflicts()) {
                if (isMc(c.modId()) || isLoader(c.modId())) continue;
                String have = installed.get(c.modId());
                if (have != null) {
                    issues.add(new Issue(m.fileName(), "Mod 冲突",
                            "与已启用的「" + c.modId() + " " + have + "」冲突（要求" + displayRange(c.range()) + "）",
                            "请在「实例管理 → Mod 模组」中禁用或删除 " + m.fileName() + " 与 " + c.modId()
                                    + " 中的其中一个，二者不能同时启用。"));
                }
            }
        }
        return issues;
    }

    // ---------- 解析 ----------

    private static ModMeta parseJar(Path p) {
        try (ZipFile zf = new ZipFile(p.toFile())) {
            ZipEntry toml = zf.getEntry("META-INF/mods.toml");
            if (toml != null) {
                String text = new String(zf.getInputStream(toml).readAllBytes(), StandardCharsets.UTF_8);
                ModMeta meta = parseModsToml(p.getFileName().toString(), text);
                if (meta != null) return meta;
            }
            ZipEntry fjson = zf.getEntry("fabric.mod.json");
            if (fjson != null) {
                String text = new String(zf.getInputStream(fjson).readAllBytes(), StandardCharsets.UTF_8);
                ModMeta meta = parseFabric(p.getFileName().toString(), text);
                if (meta != null) return meta;
            }
            ZipEntry qjson = zf.getEntry("quilt.mod.json");
            if (qjson != null) {
                String text = new String(zf.getInputStream(qjson).readAllBytes(), StandardCharsets.UTF_8);
                ModMeta meta = parseQuilt(p.getFileName().toString(), text);
                if (meta != null) return meta;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** mods.toml 简易解析：取第一个 [[mods]] 块为主 mod，[[dependencies.X]] 块（X=主 modId）为其依赖。 */
    private static ModMeta parseModsToml(String file, String text) {
        List<Map<String, String>> mods = new ArrayList<>();
        List<Map<String, String>> deps = new ArrayList<>();
        String section = null;
        Map<String, String> cur = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[[")) {
                String name = line.substring(2, line.length() - 2).trim();
                if (name.equals("mods")) {
                    section = "mods";
                    cur = new HashMap<>();
                    mods.add(cur);
                } else if (name.startsWith("dependencies.")) {
                    section = "deps";
                    cur = new HashMap<>();
                    cur.put("__target", name.substring("dependencies.".length()).trim());
                    deps.add(cur);
                } else {
                    section = "other";
                    cur = null;
                }
                continue;
            }
            if (line.startsWith("[")) {
                section = "other";
                cur = null;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0 || cur == null) continue;
            cur.put(line.substring(0, eq).trim(), unquote(line.substring(eq + 1).trim()));
        }
        if (mods.isEmpty()) return null;
        String modId = mods.get(0).get("modId");
        String version = mods.get(0).get("version");
        if (modId == null || modId.isBlank()) modId = stripExt(file);
        List<Dep> depList = new ArrayList<>();
        for (Map<String, String> d : deps) {
            String target = d.get("__target");
            if (target == null || target.isBlank()) continue;
            if (modId != null && !target.equals(modId)) continue; // 只取主 mod 自己的依赖块
            String did = d.get("modId");
            if (did == null || did.isBlank()) continue;
            boolean mandatory = Boolean.parseBoolean(d.getOrDefault("mandatory", "false"));
            String range = d.getOrDefault("versionRange", "*");
            depList.add(new Dep(did, range, mandatory));
        }
        return new ModMeta(file, modId, version == null ? "" : version, depList, List.of());
    }

    private static ModMeta parseFabric(String file, String text) {
        try {
            JSONObject o = new JSONObject(text);
            String id = o.optString("id");
            if (id.isBlank()) return null;
            String version = o.optString("version", "");
            List<Dep> deps = new ArrayList<>();
            JSONObject depends = o.optJSONObject("depends");
            if (depends != null) {
                for (String k : depends.keySet()) {
                    deps.add(new Dep(k, depends.optString(k, "*"), true));
                }
            }
            List<Dep> conflicts = new ArrayList<>();
            collectConflict(conflicts, o.optJSONObject("conflicts"));
            collectConflict(conflicts, o.optJSONObject("breaks"));
            return new ModMeta(file, id, version, deps, conflicts);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void collectConflict(List<Dep> out, JSONObject o) {
        if (o == null) return;
        for (String k : o.keySet()) {
            out.add(new Dep(k, o.optString(k, "*"), true));
        }
    }

    private static ModMeta parseQuilt(String file, String text) {
        try {
            JSONObject root = new JSONObject(text);
            JSONObject ql = root.optJSONObject("quilt_loader");
            if (ql == null) return null;
            String id = ql.optString("id");
            if (id.isBlank()) return null;
            String version = ql.optString("version", "");
            List<Dep> deps = new ArrayList<>();
            JSONArray arr = ql.optJSONArray("depends");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.optJSONObject(i);
                    if (d == null) continue;
                    String did = d.optString("id");
                    if (did.isBlank()) continue;
                    boolean optional = d.optBoolean("optional", false);
                    deps.add(new Dep(did, d.optString("versions", "*"), !optional));
                }
            }
            List<Dep> conflicts = new ArrayList<>();
            JSONArray carr = ql.optJSONArray("conflicts");
            if (carr != null) {
                for (int i = 0; i < carr.length(); i++) {
                    JSONObject d = carr.optJSONObject(i);
                    if (d == null) continue;
                    String did = d.optString("id");
                    if (did.isBlank()) continue;
                    conflicts.add(new Dep(did, d.optString("versions", "*"), true));
                }
            }
            return new ModMeta(file, id, version, deps, conflicts);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isMc(String id) {
        return "minecraft".equals(id) || "mc".equals(id);
    }

    private static boolean isLoader(String id) {
        return LOADER_IDS.contains(id);
    }

    // ---------- 版本范围 ----------

    private static final Pattern OP = Pattern.compile("^(>=|<=|>|<|=)?\\s*(.+)$");

    /** 版本是否满足范围表达式（Maven/Fabric 风格；逗号=或，空格=且）。 */
    static boolean inRange(String ver, String range) {
        if (range == null) return true;
        String r = range.trim();
        if (r.isEmpty() || r.equals("*")) return true;
        // 区间表达式整体处理（内部逗号是区间分隔，不是「或」）
        if (r.startsWith("[") || r.startsWith("(")) {
            return matchSingle(ver, r);
        }
        for (String part : r.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            if (matchAll(ver, part)) return true;
        }
        return false;
    }

    private static boolean matchAll(String ver, String expr) {
        // 支持 Maven/Fabric 的「或」表达式（A || B || C）：任一组满足即通过
        for (String orPart : expr.split("\\|\\|")) {
            orPart = orPart.trim();
            if (orPart.isEmpty()) continue;
            boolean ok = true;
            for (String cond : orPart.split("\\s+")) {
                if (cond.isEmpty()) continue;
                // 去掉 Fabric 约束里的引号（"~1.20.5"、">=21" 等写法）
                cond = cond.replace("\"", "").replace("'", "").trim();
                if (cond.isEmpty()) continue;
                if (!matchSingle(ver, cond)) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
        return false;
    }

    private static boolean matchSingle(String ver, String cond) {
        if (cond.equals("*")) return true;
        if (cond.startsWith("~")) {
            String base = cond.substring(1).trim();
            if (base.isEmpty()) return true;
            return compareVersions(ver, base) >= 0 && compareVersions(ver, nextMinor(base)) < 0;
        }
        if (cond.startsWith("[")) {
            return matchBracket(ver, cond, true);
        }
        if (cond.startsWith("(")) {
            return matchBracket(ver, cond, false);
        }
        Matcher m = OP.matcher(cond);
        if (!m.matches()) return compareVersions(ver, cond) == 0;
        String op = m.group(1);
        String v = m.group(2).trim();
        int c = compareVersions(ver, v);
        if (op == null) return c == 0;
        return switch (op) {
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case "<" -> c < 0;
            case "=" -> c == 0;
            default -> c == 0;
        };
    }

    /** [a] / [a,b] / [a,b) / [a,] / [,b] 及开区间变体。 */
    private static boolean matchBracket(String ver, String cond, boolean closedLeft) {
        boolean closedRight = cond.endsWith("]");
        String inner = cond.substring(1, cond.length() - 1).trim();
        if (inner.isEmpty()) return true;
        String[] parts = inner.split(",", -1);
        if (parts.length == 1) {
            String v = parts[0].trim();
            return v.isEmpty() || compareVersions(ver, v) == 0;
        }
        String lo = parts[0].trim();
        String hi = parts[1].trim();
        if (!lo.isEmpty()) {
            int c = compareVersions(ver, lo);
            if (c < 0 || (c == 0 && !closedLeft)) return false;
        }
        if (!hi.isEmpty()) {
            int c = compareVersions(ver, hi);
            if (c > 0 || (c == 0 && !closedRight)) return false;
        }
        return true;
    }

    /** ~1.20.1 → 1.21；~1.20 → 1.21；~1 → 2。 */
    private static String nextMinor(String base) {
        String[] parts = base.split("\\.");
        if (parts.length <= 1) {
            try {
                return String.valueOf(Long.parseLong(parts[0].trim()) + 1);
            } catch (Exception e) {
                return base + ".0";
            }
        }
        try {
            long major = Long.parseLong(parts[0].trim());
            long minor = Long.parseLong(parts[1].trim()) + 1;
            return major + "." + minor;
        } catch (Exception e) {
            return base;
        }
    }

    /** 分段版本比较：1.21.1 > 1.21；1.20.1 == 1.20.1。快照等非数字段按字符串比较（宽松）。 */
    static int compareVersions(String a, String b) {
        if (a == null || a.isBlank()) a = "0";
        if (b == null || b.isBlank()) b = "0";
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = i < pa.length ? pa[i].trim() : "0";
            String y = i < pb.length ? pb[i].trim() : "0";
            int c = cmpSeg(x, y);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int cmpSeg(String x, String y) {
        Long nx = tryLong(x), ny = tryLong(y);
        if (nx != null && ny != null) return Long.compare(nx, ny);
        // 版本段可能带预发布横杠尾巴（Fabric 范围如 "1.21.11-"）：先按 "-" 前的数字段比较
        String cx = x.replaceFirst("-.*$", "");
        String cy = y.replaceFirst("-.*$", "");
        Long nx2 = tryLong(cx), ny2 = tryLong(cy);
        if (nx2 != null && ny2 != null) {
            int c = Long.compare(nx2, ny2);
            if (c != 0) return c;
            // 数字部分相同：带 "-" 的是预发布版本，小于不带 "-" 的正式版
            boolean xPre = x.contains("-"), yPre = y.contains("-");
            if (xPre != yPre) return xPre ? -1 : 1;
            return x.compareTo(y);
        }
        return x.compareTo(y);
    }

    private static Long tryLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayRange(String r) {
        if (r == null || r.isBlank() || r.equals("*")) return "任意版本";
        return r;
    }
}
