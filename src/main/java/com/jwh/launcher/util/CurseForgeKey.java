package com.jwh.launcher.util;

/**
 * CurseForge API Key 注入：从环境变量读取，源码 / UI / 配置文件均不落 key。
 *
 * 读取 JWH_CURSEFORGE_API_KEY；未配置时返回空串，调用方（下载中心）自动走 CurseForge 网页搜索兜底。
 */
public final class CurseForgeKey {

    /** 注入变量名。 */
    public static final String ENV_JWH = "JWH_CURSEFORGE_API_KEY";

    private CurseForgeKey() {
    }

    /** 返回已注入的 Key；未配置返回空串。 */
    public static String get() {
        String v = System.getenv(ENV_JWH);
        return v == null ? "" : v.trim();
    }

    /** 是否已配置（供设置页只读状态展示）。 */
    public static boolean configured() {
        return !get().isEmpty();
    }
}
