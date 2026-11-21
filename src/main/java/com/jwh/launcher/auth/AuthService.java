package com.jwh.launcher.auth;

import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.config.ConfigManager;
import org.json.JSONObject;

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
