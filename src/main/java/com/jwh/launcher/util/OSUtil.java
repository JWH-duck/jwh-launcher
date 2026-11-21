package com.jwh.launcher.util;

/**
 * 操作系统 / 路径 / 进程 相关工具。
 */
public final class OSUtil {

    public enum OS { WINDOWS, LINUX, MACOS, OTHER }

    private OSUtil() {
    }

    public static OS currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return OS.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return OS.MACOS;
        if (os.contains("linux")) return OS.LINUX;
        return OS.OTHER;
    }

    public static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        if (a.contains("amd64") || a.contains("x86_64")) return "64";
        if (a.contains("aarch64") || a.contains("arm64")) return "arm64";
        return "32";
    }

    /** 判断 Minecraft 原生库规则中的 os.name 是否匹配当前系统。 */
    public static boolean osMatches(String osName) {
        if (osName == null) return true;
        return switch (currentOs()) {
            case WINDOWS -> osName.equals("windows");
            case LINUX -> osName.equals("linux");
            case MACOS -> osName.equals("osx");
            default -> false;
        };
    }

    /** 判断规则中 "arch" 是否匹配。 */
    public static boolean archMatches(String archName) {
        if (archName == null) return true;
        return arch().equals(archName);
    }

    public static boolean isWindows() {
        return currentOs() == OS.WINDOWS;
    }

    public static boolean isMac() {
        return currentOs() == OS.MACOS;
    }

    /** 读取环境变量（不存在返回默认值）。 */
    public static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    /** 生成离线模式的 UUID v3（基于名称的 MD5 哈希），与官方离线登录规则一致。 */
    public static String offlineUuid(String username) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x30); // version 3
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80); // variant
            StringBuilder sb = new StringBuilder(36);
            for (int i = 0; i < 16; i++) {
                if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return java.util.UUID.randomUUID().toString();
        }
    }
}
