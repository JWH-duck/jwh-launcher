package com.jwh.launcher.util;

/**
 * CurseForge API Key 注入（PCL 同款机制：从环境变量读取，源码 / UI / 配置文件均不落 key）。
 *
 * 读取顺序：JWH_CURSEFORGE_API_KEY → PCL_CURSEFORGE_API_KEY（兼容已在 PCL 里设过同款变量的用户）。
 * 未配置时返回空串，调用方（下载中心）自动走 CurseForge 网页搜索兜底。
 */
public final class CurseForgeKey {

    /** JWH-Launcher 自己的注入变量名。 */
    public static final String ENV_JWH = "JWH_CURSEFORGE_API_KEY";
    /** PCL 开源版同款变量名（PCL2/Modules/ModSecret.vb：Environment.GetEnvironmentVariable("PCL_CURSEFORGE_API_KEY")）。 */
    public static final String ENV_PCL = "PCL_CURSEFORGE_API_KEY";

    private CurseForgeKey() {
    }

    /** 返回已注入的 Key；未配置返回空串。 */
    public static String get() {
        String v = System.getenv(ENV_JWH);
        if (v == null || v.isBlank()) v = System.getenv(ENV_PCL);
        return v == null ? "" : v.trim();
    }

    /** 是否已配置（供设置页只读状态展示）。 */
    public static boolean configured() {
        return !get().isEmpty();
    }
}
