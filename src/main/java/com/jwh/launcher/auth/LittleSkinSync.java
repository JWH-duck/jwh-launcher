package com.jwh.launcher.auth;

import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.util.Http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LittleSkin 皮肤 / 披风同步：把皮肤站上该角色当前的材质下载到本地皮肤库，
 * 文件名固定为「皮肤站-&lt;角色名&gt;.png」「皮肤站-&lt;角色名&gt;-cape.png」。
 * <p>启动加载页与皮肤库页共用这一份逻辑，靠 {@link #claim} 保证同一次运行里
 * 同一个账号只联网拉一次，避免两处各拉一遍。
 */
public final class LittleSkinSync {

    private LittleSkinSync() {
    }

    /** 本次运行里已经认领过的账号，key = uuid@apiRoot；顺便起到「正在同步」的锁作用。 */
    private static final Set<String> CLAIMED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 账号的同步唯一键：同一账号 + 同一站点只拉一次。 */
    public static String keyOf(LauncherConfig.Account acc) {
        if (acc == null || acc.uuid == null || acc.uuid.isBlank()) {
            return "";
        }
        String api = (acc.lsApiRoot == null || acc.lsApiRoot.isBlank())
                ? LittleSkinAuth.DEFAULT_API : acc.lsApiRoot;
        return acc.uuid.trim().toLowerCase(java.util.Locale.ROOT) + "@" + api;
    }

    /**
     * 认领同步任务：第一次调用返回 true（由调用方去联网），之后同一账号返回 false。
     * 线程安全，重复点击 / 两个入口同时触发也只会拉一次。
     */
    public static boolean claim(LauncherConfig.Account acc) {
        String key = keyOf(acc);
        if (key.isEmpty()) {
            return false;
        }
        return CLAIMED.add(key);
    }

    /**
     * 真正干活：拉 textures → 下载皮肤/披风 PNG → 写回账号字段（不落盘配置，由调用方决定）。
     * 阻塞方法，必须在后台线程调用。
     *
     * @return 账号字段是否有变化
     */
    public static boolean applyTo(LauncherConfig config, LauncherConfig.Account acc) throws Exception {
        if (config == null || acc == null || acc.uuid == null || acc.uuid.isBlank()) {
            return false;
        }
        String api = (acc.lsApiRoot == null || acc.lsApiRoot.isBlank())
                ? LittleSkinAuth.DEFAULT_API : acc.lsApiRoot;
        String name = (acc.displayName == null || acc.displayName.isBlank()) ? "player" : acc.displayName;
        Path lib = Path.of(config.minecraftDir).resolve("skins").resolve("library");
        Files.createDirectories(lib);

        LittleSkinAuth.Textures tex = LittleSkinAuth.fetchTextures(api, acc.uuid);
        if (tex == null) {
            return false;
        }
        boolean changed = false;
        if (tex.hasSkin()) {
            byte[] png = Http.getBytes(tex.skinUrl);
            if (png != null && png.length > 100) {
                Files.write(lib.resolve("皮肤站-" + name + ".png"), png);
                acc.skinFile = "皮肤站-" + name + ".png";
                acc.skinSlim = tex.slim;
                changed = true;
            }
        }
        if (tex.hasCape()) {
            byte[] png = Http.getBytes(tex.capeUrl);
            if (png != null && png.length > 100) {
                Files.write(lib.resolve("皮肤站-" + name + "-cape.png"), png);
                acc.capeFile = "皮肤站-" + name + "-cape.png";
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 启动加载页用：把所有 LittleSkin 账号的皮肤/披风同步一遍。
     * 阻塞方法，必须在后台线程调用；失败静默（站点连不上不该影响启动）。
     *
     * @return 是否有账号字段被更新
     */
    public static boolean prefetch(LauncherConfig config) {
        if (config == null || config.accounts == null) {
            return false;
        }
        boolean changed = false;
        for (LauncherConfig.Account acc : new ArrayList<>(config.accounts)) {
            if (acc == null || acc.type != LauncherConfig.Account.Type.LITTLESKIN) {
                continue;
            }
            if (!claim(acc)) {
                continue;
            }
            try {
                if (applyTo(config, acc)) {
                    changed = true;
                }
            } catch (Exception ignored) {
                // 站点超时 / 角色没设材质：静默跳过，进皮肤库页还能再拉
            }
        }
        return changed;
    }
}
