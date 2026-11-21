package com.jwh.launcher.util;

import com.jwh.launcher.config.LauncherConfig;

/**
 * 下载源镜像重写：官方 Mojang 域名 → BMCLAPI 国内镜像（PCL/HMCL 同款）。
 * 只切换下载地址，文件路径与校验逻辑不变。
 */
public final class Mirror {

    public static final String BMCLAPI = "https://bmclapi2.bangbang93.com";
    /** 官方版本清单端点（测速探针）。 */
    private static final String OFFICIAL_PROBE = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    /** BMCLAPI 版本清单端点（测速探针）。 */
    private static final String BMCLAPI_PROBE = BMCLAPI + "/mc/game/version_manifest_v2.json";

    private Mirror() {
    }
    /**
     * 自动测速选择下载源（PCL 同款）：分别探测 BMCLAPI 与官方 Mojang 的往返延迟，
     * 返回较快者（"bmclapi"/"official"）。两者都失败时默认 BMCLAPI（国内玩家主场景）。
     * 各测 2 次取平均值，减少抖动误判。
     */
    public static String autoSelectSource() {
        long b = averagePing(BMCLAPI_PROBE);
        long o = averagePing(OFFICIAL_PROBE);
        if (b < 0 && o < 0) return "bmclapi";
        if (b < 0) return "official";
        if (o < 0) return "bmclapi";
        return b <= o ? "bmclapi" : "official";
    }

    private static long averagePing(String url) {
        long a = Http.pingMs(url);
        if (a < 0) return -1;
        long s = a;
        long p = Http.pingMs(url);
        if (p < 0) return a;
        s += p;
        return s / 2;
    }

    /**
     * 官方 URL → 镜像 URL。未开启镜像或无需转换时原样返回。
     * piston-data 的 objects 端点未被镜像，主 jar 改用 /version/{id}/client。
     */
    public static String rewrite(LauncherConfig config, String url, String versionId) {
        if (!config.useBmclapi() || url == null) return url;
        if (url.startsWith("https://libraries.minecraft.net/")) {
            return BMCLAPI + "/maven/" + url.substring("https://libraries.minecraft.net/".length());
        }
        if (url.startsWith("https://maven.fabricmc.net/")) {
            // Fabric 官方 maven（fabric-loader 及 profile 依赖库）→ BMCLAPI 镜像
            return BMCLAPI + "/maven/" + url.substring("https://maven.fabricmc.net/".length());
        }
        if (url.startsWith("https://maven.neoforged.net/")) {
            // NeoForge/Forge 官方 maven → 国内镜像（PCL/HMCL 同款；官方源国内常超时）
            String p = url.substring("https://maven.neoforged.net/".length());
            if (p.startsWith("releases/")) p = p.substring("releases/".length());
            return BMCLAPI + "/maven/" + p;
        }
        if (url.startsWith("https://maven.minecraftforge.net/")) {
            // 老 Forge 官方 maven（forge-1.20.1 等旧版 profile 库、nashorn 等依赖）→ 国内镜像
            return BMCLAPI + "/maven/" + url.substring("https://maven.minecraftforge.net/".length());
        }
        if (url.startsWith("https://resources.download.minecraft.net/")) {
            return BMCLAPI + "/assets/" + url.substring("https://resources.download.minecraft.net/".length());
        }
        if (url.startsWith("https://piston-meta.mojang.com/") || url.startsWith("https://launchermeta.mojang.com/")
                || url.startsWith("https://launcher.mojang.com/")) {
            return BMCLAPI + "/" + url.substring(url.indexOf("mojang.com/") + "mojang.com/".length());
        }
        if (url.startsWith("https://piston-data.mojang.com/")) {
            // 版本 JSON 与主 jar 在 BMCLAPI 走不同端点（实测：/version/{id}/client.json 秒回，/version/{id}/client 302 官方 CDN）。
            // 无 versionId 的请求（如 asset index 等）不属于版本清单，保持官方原地址，不走此规则。
            if (versionId != null && !versionId.isBlank() && url.endsWith(".json")) {
                return BMCLAPI + "/version/" + versionId + "/client.json";
            }
            return BMCLAPI + "/version/" + (versionId == null ? "" : versionId) + "/client";
        }
        return url;
    }
}
