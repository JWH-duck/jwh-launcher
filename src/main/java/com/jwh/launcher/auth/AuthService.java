package com.jwh.launcher.auth;

import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.util.Http;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 账号服务：离线登录、微软登录（授权码/刷新）、账号保存与选择。
 */
public class AuthService {

    private final LauncherConfig config;

    public AuthService(LauncherConfig config) {
        this.config = config;
    }

    /** 添加离线账号并选中。 */
    public LauncherConfig.Account addOfflineAccount(String username) {
        String name = username == null || username.isBlank() ? "Steve" : username.trim();
        LauncherConfig.Account a = LauncherConfig.Account.offline(name);
        config.accounts.add(a);
        config.selectedAccount = config.accounts.size() - 1;
        save();
        return a;
    }

    /** 微软 Device Code 登录：用轮询拿到的 (access_token, refresh_token) 完成登录并保存账号。 */
    public LauncherConfig.Account loginMicrosoftWithTokens(JSONObject msToken) throws Exception {
        return finishMicrosoft(msToken);
    }

    /**
     * LittleSkin 账号密码登录：认证 → 拉皮肤/披风 → 写入账号并选中。
     * 与旧弹窗共用同一套落库逻辑；{@code remember} 仅记住账号名，不保存密码。
     */
    public LauncherConfig.Account loginLittleSkin(String user, String pass, boolean remember) throws Exception {
        LittleSkinAuth.Result r = LittleSkinAuth.authenticate(LittleSkinAuth.DEFAULT_API, user, pass, "");

        LittleSkinAuth.Textures tex = null;
        String skinFile = "";
        String capeFile = "";
        Boolean slim = null;
        try {
            tex = LittleSkinAuth.fetchTextures(LittleSkinAuth.DEFAULT_API, r.profileId);
            Path dir = config.minecraftPath().resolve("skins").resolve("library");
            if (tex != null && tex.hasSkin()) {
                Files.createDirectories(dir);
                String name = "皮肤站-" + r.profileName + ".png";
                Path p = dir.resolve(name);
                if (Http.download(tex.skinUrl, p)) {
                    skinFile = name;
                    slim = tex.slim ? Boolean.TRUE : Boolean.FALSE;
                }
            }
            if (tex != null && tex.hasCape()) {
                Files.createDirectories(dir);
                String name = "皮肤站-" + r.profileName + "-cape.png";
                Path p = dir.resolve(name);
                if (Http.download(tex.capeUrl, p)) capeFile = name;
            }
        } catch (Exception ignored) {
            // 纹理拿不到不阻断登录，后续可在皮肤库页手动重同步
        }

        LauncherConfig.Account acc = new LauncherConfig.Account();
        acc.type = LauncherConfig.Account.Type.LITTLESKIN;
        acc.displayName = r.profileName;
        acc.userName = r.profileName;
        acc.uuid = LittleSkinAuth.withDashes(r.profileId);
        acc.accessToken = r.accessToken;
        acc.refreshToken = r.clientToken;
        acc.lsApiRoot = LittleSkinAuth.DEFAULT_API;
        acc.skinFile = skinFile;
        acc.capeFile = capeFile;
        acc.skinSlim = slim;

        // 复用已有的同名 LittleSkin 账号，避免重复添加
        LauncherConfig.Account existing = null;
        for (LauncherConfig.Account a : config.accounts) {
            if (a.type == LauncherConfig.Account.Type.LITTLESKIN && a.uuid.equals(acc.uuid)) {
                existing = a;
                break;
            }
        }
        if (existing != null) {
            existing.displayName = acc.displayName;
            existing.userName = acc.userName;
            existing.accessToken = acc.accessToken;
            existing.refreshToken = acc.refreshToken;
            existing.skinFile = skinFile;
            existing.capeFile = capeFile;
            existing.skinSlim = slim;
            config.selectedAccount = config.accounts.indexOf(existing);
        } else {
            config.accounts.add(acc);
            config.selectedAccount = config.accounts.size() - 1;
        }

        config.lsSavedUser = remember ? user : "";
        save();
        return existing != null ? existing : acc;
    }

    /** 使用保存的 refresh_token 静默刷新。返回刷新后的账号，失败抛异常。 */
    public LauncherConfig.Account refreshMicrosoft(LauncherConfig.Account acc) throws Exception {
        if (acc.refreshToken == null || acc.refreshToken.isBlank()) {
            throw new IllegalStateException("该账号没有可用的刷新令牌，请重新登录");
        }
        JSONObject ms = MicrosoftAuthenticator.refreshMicrosoftToken(acc.refreshToken);
        return finishMicrosoft(ms);
    }

    private LauncherConfig.Account finishMicrosoft(JSONObject msToken) throws Exception {
        String msAccessToken = msToken.optString("access_token");
        if (msAccessToken.isBlank()) {
            throw new IllegalStateException("Microsoft 授权失败：" + msToken.optString("error_description", "未知错误"));
        }
        JSONObject mc = MicrosoftAuthenticator.completeMinecraftLogin(msAccessToken);

        // 复用已有的同名正版账号，避免重复添加
        LauncherConfig.Account acc = null;
        for (LauncherConfig.Account a : config.accounts) {
            if (a.type == LauncherConfig.Account.Type.MICROSOFT && a.uuid.equals(mc.optString("uuid"))) {
                acc = a;
                break;
            }
        }
        if (acc == null) {
            acc = new LauncherConfig.Account();
            acc.type = LauncherConfig.Account.Type.MICROSOFT;
            acc.uuid = mc.optString("uuid");
            acc.userName = mc.optString("name");
            config.accounts.add(acc);
        }
        acc.displayName = mc.optString("name");
        acc.userName = mc.optString("name");
        acc.uuid = mc.optString("uuid");
        acc.accessToken = mc.optString("accessToken");
        acc.expiresAt = mc.optLong("expiresAt", 0);
        // refresh_token 可能轮换，非空才覆盖
        if (msToken.has("refresh_token")) {
            acc.refreshToken = msToken.optString("refresh_token");
        }
        config.selectedAccount = config.accounts.indexOf(acc);
        save();
        return acc;
    }

    /** 获取当前选中账号；自动尝试刷新正版 token。失败则抛出异常（含网络/令牌失效）。 */
    public LauncherConfig.Account currentAccount(boolean refreshIfNeeded) throws Exception {
        if (config.selectedAccount < 0 || config.selectedAccount >= config.accounts.size()) {
            return null;
        }
        LauncherConfig.Account acc = config.accounts.get(config.selectedAccount);
        if (acc.type == LauncherConfig.Account.Type.MICROSOFT && refreshIfNeeded && acc.isExpired()) {
            return refreshMicrosoft(acc);
        }
        return acc;
    }

    public void removeAccount(int index) {
        if (index >= 0 && index < config.accounts.size()) {
            config.accounts.remove(index);
            if (config.selectedAccount >= config.accounts.size()) {
                config.selectedAccount = config.accounts.size() - 1;
            }
            save();
        }
    }

    public void selectAccount(int index) {
        if (index >= 0 && index < config.accounts.size()) {
            config.selectedAccount = index;
            save();
        }
    }

    /** 设置离线账号的皮肤文件名（形如 <uuid>.png，存于 <minecraftDir>/skins/）。 */
    public void setOfflineSkin(LauncherConfig.Account acc, String skinFileName) {
        if (acc == null || acc.type != LauncherConfig.Account.Type.OFFLINE) return;
        acc.skinFile = skinFileName == null ? "" : skinFileName;
        save();
    }

    /** 移除离线账号的皮肤。 */
    public void removeOfflineSkin(LauncherConfig.Account acc) {
        if (acc == null) return;
        acc.skinFile = "";
        save();
    }

    private void save() {
        ConfigManager.save(config);
    }
}
