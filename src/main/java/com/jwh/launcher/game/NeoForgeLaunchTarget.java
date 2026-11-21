package com.jwh.launcher.game;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * NeoForge 客户端启动目标（{@code --launchTarget}）解析。
 *
 * <p>服务名以该版本实际加载的 FML loader jar 内客户端 handler 注册名为准，
 * 不按我们对 FML 版本谱系的猜测决定；解析不到时再按 FML 主版本规则兜底。
 * 仅服务 {@code neoforge-} 前缀实例，老 Forge 走独立命令分支。</p>
 */
public final class NeoForgeLaunchTarget {

    /** 生产客户端 handler 在 loader jar 内的固定类路径（4.x 与 6.x 同名）。 */
    private static final String CLIENT_HANDLER =
            "net/neoforged/fml/loading/targets/NeoForgeClientLaunchHandler.class";

    /** 已知客户端服务名白名单；server / dev / userdev / data 等专用目标不在此列。 */
    private static final Set<String> CLIENT_TARGETS = Set.of("forgeclient", "neoforgeclient");

    private NeoForgeLaunchTarget() {
    }

    /** 白名单校验：缓存值必须是已知合法客户端目标才采用。 */
    public static boolean isValidTarget(String target) {
        return target != null && CLIENT_TARGETS.contains(target.trim());
    }

    /** 按 FML 主版本兜底：6 及以上用 neoforgeclient，其余（含解析失败）用 forgeclient。 */
    public static String fallbackByFmlVersion(String fmlVersion) {
        if (fmlVersion != null && !fmlVersion.isBlank()) {
            String major = fmlVersion.trim().split("\\.")[0];
            if (major.matches("[6-9]|1[0-9]")) {
                return "neoforgeclient";
            }
        }
        return "forgeclient";
    }

    /**
     * 从本地 FML loader jar 的客户端 handler 常量池解析注册服务名。
     *
     * @return 白名单内的客户端服务名；jar 缺失、类缺失或解析失败时返回 {@code null}
     */
    public static String resolveFromLoaderJar(Path libs, String fmlVersion) {
        if (libs == null || fmlVersion == null || fmlVersion.isBlank()) {
            return null;
        }
        Path jar = libs.resolve("net/neoforged/fancymodloader/loader/" + fmlVersion.trim()
                + "/loader-" + fmlVersion.trim() + ".jar");
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        try (JarFile zf = new JarFile(jar.toFile())) {
            JarEntry e = zf.getJarEntry(CLIENT_HANDLER);
            if (e == null) {
                return null;
            }
            byte[] cls;
            try (var in = zf.getInputStream(e)) {
                cls = in.readAllBytes();
            }
            for (String s : utf8Strings(cls)) {
                if (CLIENT_TARGETS.contains(s)) {
                    return s;
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    /**
     * 解析 class 常量池中的 Utf8 字符串（只取 tag=1）。
     * Long/Double 各占两个常量池槽位；未知 tag 直接放弃，避免越界误读。
     */
    private static List<String> utf8Strings(byte[] cls) {
        List<String> out = new ArrayList<>();
        try {
            int p = 8; // magic(4) + minor(2) + major(2)，随后是 constant_pool_count(2)
            int cpCount = (cls[p] << 8) | cls[p + 1];
            p += 2;
            for (int i = 1; i < cpCount; i++) {
                int tag = cls[p++] & 0xff;
                switch (tag) {
                    case 1: { // Utf8
                        int len = (cls[p] << 8) | cls[p + 1];
                        p += 2;
                        out.add(new String(cls, p, len, StandardCharsets.UTF_8));
                        p += len;
                        break;
                    }
                    case 3:
                    case 4:
                        p += 4;
                        break; // Integer / Float
                    case 5:
                    case 6:
                        p += 8;
                        i++;
                        break; // Long / Double 占两槽
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        p += 2;
                        break; // Class / String / MethodType / Module / Package
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        p += 4;
                        break; // Ref / NameAndType / Dynamic / InvokeDynamic
                    case 15:
                        p += 3;
                        break; // MethodHandle
                    default:
                        return out; // 未知 tag，停止解析
                }
            }
        } catch (Exception ignored) {
            // 字节码结构异常时返回已收集到的部分
        }
        return out;
    }
}
