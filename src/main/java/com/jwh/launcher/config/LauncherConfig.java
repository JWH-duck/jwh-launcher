package com.jwh.launcher.config;

import com.jwh.launcher.util.OSUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动器全局配置。
 */
public class LauncherConfig {

    public static final String DEFAULT_MC_DIR;
    static {
        // 便携模式：默认游戏目录 = 启动器所在文件夹的 .minecraft（整个文件夹拷走即带走游戏）
        String portable = portableRoot();
        if (portable != null) {
            DEFAULT_MC_DIR = portable + "\\.minecraft";
        } else {
            // 兜底（拿不到程序位置时）：系统用户目录
            String dir;
            switch (OSUtil.currentOs()) {
                case WINDOWS -> dir = OSUtil.env("APPDATA", System.getProperty("user.home")) + "\\\\.minecraft";
                case MACOS -> dir = System.getProperty("user.home") + "/Library/Application Support/minecraft";
                default -> dir = System.getProperty("user.home") + "/.minecraft";
            }
            DEFAULT_MC_DIR = dir;
        }
    }

    /** 便携模式定位：程序 jar 所在目录的上一级（打包后 = JWH-Launcher 根目录）。 */
    private static String portableRoot() {
        try {
            java.nio.file.Path jar = java.nio.file.Path.of(
                    LauncherConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getParent().getParent().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** .minecraft 目录 */
    public String minecraftDir = DEFAULT_MC_DIR;
    /** 用户指定的 Java 路径，空 = 自动检测 */
    public String javaPath = "";
    /** 最大内存 MB */
    public int maxMemory = 4096;
    /** 最小内存 MB */
    public int minMemory = 1024;
    /** 内存分配模式：auto=每次打开自动按本机可用内存分配安全值 / custom=手动自定义（不自动检测） */
    public String memoryMode = "auto";
    /** 版本隔离：true = 每个版本独立 saves/mods/config（类似 PCL 的版本隔离） */
    public boolean versionIsolation = false;
    /** 下载并发线程数 */
    public int downloadThreads = 8;
    /** 内容下载源：auto=启动时自动测速选择最快的源（PCL 同款，默认）/ official=官方 Mojang 源 / bmclapi=BMCLAPI 国内镜像。 */
    public String contentSource = "auto";
    /** 内存态：auto 模式下最近一次测速选中的实际源（"bmclapi"/"official"），不写入配置文件。 */
    public String resolvedSource = "";
    /** 窗口参数 */
    public int windowWidth = 875;
    public int windowHeight = 554;
    /** 启动游戏时附加 JVM 参数，空格分隔 */
    public String jvmArgs = "";
    /** 是否显示游戏日志窗口 */
    public boolean showGameLog = true;
    /** LittleSkin 上次登录用的账号（只记账号，不存密码） */
    public String lsSavedUser = "";

    /** 背景模式：default=默认渐变 / image=自定义图片 / transparent=透明 */
    public String bgMode = "default";
    /** 自定义背景图片路径（选图时会复制到配置目录，原图移动/删除不影响） */
    public String bgImagePath = "";
    /** 主文字颜色（hex，#e8eaed） */
    public String textColor = "";
    /** 次要文字颜色（hex，#9aa0a6） */
    public String dimColor = "";

    /**
     * Microsoft OAuth 客户端 ID（可选）。
     * 留空使用内置默认（Minecraft 官方公共 client_id）；
     * 微软已弃用官方桌面回跳，正版登录建议在 Azure 注册自己的应用并在此填入。
     */
    public String microsoftClientId = "";
    /**
     * Microsoft OAuth 重定向地址（与 Azure 应用注册的 Redirect URI 一致）。
     * 留空默认 oauth20_desktop.srf；若填 http://localhost:端口 形式，
     * 登录时会自动启动本地服务接收回跳（无需手动复制网址）。
     */
    public String microsoftRedirectUri = "";

    /** 内置发号服务（Cloudflare Workers）：装好即用，玩家不用自己填地址。 */
    public static final String DEFAULT_ROOM_SERVER = "https://jwhl.top";
    /** 内置服务的访问口令。 */
    public static final String DEFAULT_ROOM_TOKEN = "jwh-room-2026";

    /**
     * 联机发号服务地址。
     * 留空 = 用内置的 {@link #DEFAULT_ROOM_SERVER}；填 off / none = 关闭 6 位房间号，
     * 只用陶瓦内核自己生成的 16 位随机码。
     */
    public String roomServer = DEFAULT_ROOM_SERVER;
    /** 发号服务的访问口令，留空 = 用内置口令。 */
    public String roomToken = DEFAULT_ROOM_TOKEN;

    /** 实际生效的发号地址：空值回落内置默认，off / none 表示关闭。 */
    public String effectiveRoomServer() {
        String s = roomServer == null ? "" : roomServer.trim();
        if (s.isEmpty()) return DEFAULT_ROOM_SERVER;
        if ("off".equalsIgnoreCase(s) || "none".equalsIgnoreCase(s)) return "";
        return s;
    }

    /** 实际生效的发号口令：空值回落内置默认。 */
    public String effectiveRoomToken() {
        String t = roomToken == null ? "" : roomToken.trim();
        return t.isEmpty() ? DEFAULT_ROOM_TOKEN : t;
    }

    /** 遗留字段（历史版本的自定义皮肤站配置，已停用；保留以便兼容读取旧配置）。 */
    public String skinSiteName = "";
    public String skinSiteRoot = "";

    /** 账号列表 */
    public List<Account> accounts = new ArrayList<>();
    /** 当前选中账号索引 */
    public int selectedAccount = -1;
    /** 主页上次选中的版本 id：切页/重启后按它恢复选中，不再总跳回列表第一项 */
    public String selectedVersionId = "";

    public Path minecraftPath() {
        return Path.of(minecraftDir);
    }

    public Path versionsPath() {
        return minecraftPath().resolve("versions");
    }

    public Path librariesPath() {
        return minecraftPath().resolve("libraries");
    }

    public Path assetsPath() {
        return minecraftPath().resolve("assets");
    }

    /** 是否开启版本隔离：开启时游戏数据目录 = versions/<id> */
    public Path gameDirFor(String versionId) {
        return versionIsolation ? versionsPath().resolve(versionId) : minecraftPath();
    }

    /** 是否使用 BMCLAPI 国内镜像源（PCL/HMCL 同款，国内下载更快更稳）。 */
    public boolean useBmclapi() {
        if ("official".equals(contentSource)) return false;
        if ("bmclapi".equals(contentSource)) return true;
        return "bmclapi".equals(resolvedSource);
    }

    /** 版本清单地址（随下载源切换）。 */
    public String manifestUrl() {
        return useBmclapi()
                ? "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
                : "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("minecraftDir", minecraftDir);
        o.put("javaPath", javaPath);
        o.put("maxMemory", maxMemory);
        o.put("minMemory", minMemory);
        o.put("memoryMode", memoryMode);
        o.put("versionIsolation", versionIsolation);
        o.put("downloadThreads", downloadThreads);
        o.put("contentSource", contentSource);
        o.put("windowWidth", windowWidth);
        o.put("windowHeight", windowHeight);
        o.put("jvmArgs", jvmArgs);
        o.put("showGameLog", showGameLog);
        o.put("bgMode", bgMode);
        o.put("bgImagePath", bgImagePath);
        o.put("textColor", textColor);
        o.put("dimColor", dimColor);
        o.put("microsoftClientId", microsoftClientId);
        o.put("microsoftRedirectUri", microsoftRedirectUri);
        o.put("roomServer", roomServer);
        o.put("roomToken", roomToken);
        o.put("skinSiteName", skinSiteName);
        o.put("skinSiteRoot", skinSiteRoot);
        o.put("lsSavedUser", lsSavedUser);
        JSONArray acc = new JSONArray();
        for (Account a : accounts) {
            acc.put(a.toJson());
        }
        o.put("accounts", acc);
        o.put("selectedAccount", selectedAccount);
        o.put("selectedVersionId", selectedVersionId);
        return o;
    }

    public static LauncherConfig fromJson(JSONObject o) {
        LauncherConfig c = new LauncherConfig();
        if (o.has("minecraftDir")) c.minecraftDir = o.optString("minecraftDir", DEFAULT_MC_DIR);
        if (o.has("javaPath")) c.javaPath = o.optString("javaPath", "");
        if (o.has("maxMemory")) c.maxMemory = o.optInt("maxMemory", 4096);
        if (o.has("minMemory")) c.minMemory = o.optInt("minMemory", 1024);
        if (o.has("memoryMode")) c.memoryMode = o.optString("memoryMode", "auto");
        if (o.has("versionIsolation")) c.versionIsolation = o.optBoolean("versionIsolation", false);
        if (o.has("downloadThreads")) c.downloadThreads = Math.max(1, o.optInt("downloadThreads", 8));
        if (o.has("contentSource")) c.contentSource = o.optString("contentSource", "auto");
        if (o.has("windowWidth")) c.windowWidth = o.optInt("windowWidth", 875);
        if (o.has("windowHeight")) c.windowHeight = o.optInt("windowHeight", 554);
        if (o.has("jvmArgs")) c.jvmArgs = o.optString("jvmArgs", "");
        if (o.has("showGameLog")) c.showGameLog = o.optBoolean("showGameLog", true);
        // 注意：旧配置里的 curseForgeKey 字段已不再读取——Key 改为环境变量注入
        // （JWH_CURSEFORGE_API_KEY / PCL_CURSEFORGE_API_KEY，见 util.CurseForgeKey），
        // 与 PCL 开源版同款机制：源码、UI、配置文件均不落 key。
        if (o.has("bgMode")) c.bgMode = o.optString("bgMode", "default");
        if (o.has("bgImagePath")) c.bgImagePath = o.optString("bgImagePath", "");
        if (o.has("textColor")) c.textColor = o.optString("textColor", "");
        if (o.has("dimColor")) c.dimColor = o.optString("dimColor", "");
        if (o.has("microsoftClientId")) c.microsoftClientId = o.optString("microsoftClientId", "");
        if (o.has("microsoftRedirectUri")) c.microsoftRedirectUri = o.optString("microsoftRedirectUri", "");
        // 旧配置里为空（= 没有发号服务）时自动升级成内置服务，老用户升级后也能直接拿 6 位短号。
        if (o.has("roomServer")) {
            String v = o.optString("roomServer", DEFAULT_ROOM_SERVER).trim();
            c.roomServer = v.isEmpty() ? DEFAULT_ROOM_SERVER : v;
        }
        if (o.has("roomToken")) {
            String v = o.optString("roomToken", DEFAULT_ROOM_TOKEN).trim();
            c.roomToken = v.isEmpty() ? DEFAULT_ROOM_TOKEN : v;
        }
        if (o.has("skinSiteName")) c.skinSiteName = o.optString("skinSiteName", "");
        if (o.has("skinSiteRoot")) c.skinSiteRoot = o.optString("skinSiteRoot", "");
        if (o.has("lsSavedUser")) c.lsSavedUser = o.optString("lsSavedUser", "");
        if (o.has("accounts")) {
            JSONArray arr = o.optJSONArray("accounts");
            for (int i = 0; i < arr.length(); i++) {
                c.accounts.add(Account.fromJson(arr.getJSONObject(i)));
            }
        }
        c.selectedAccount = o.optInt("selectedAccount", -1);
        if (c.selectedAccount >= c.accounts.size()) c.selectedAccount = c.accounts.size() - 1;
        if (o.has("selectedVersionId")) c.selectedVersionId = o.optString("selectedVersionId", "");
        return c;
    }

    /** 账号模型：离线或正版 */
    public static class Account {
        public enum Type { OFFLINE, MICROSOFT, LITTLESKIN }

        public Type type;
        /** 显示名 */
        public String displayName;
        /** 游戏内用户名（离线=玩家名，正版=profile name） */
        public String userName;
        /** 正版 UUID（带横线）；离线=离线 UUID */
        public String uuid;
        /** 正版：微软 access token（Minecraft services） */
        public String accessToken = "";
        /** 正版：微软 refresh token（用于刷新登录） */
        public String refreshToken = "";
        /** 正版：access token 过期时间戳（ms） */
        public long expiresAt = 0;
        /** 皮肤等附加信息 */
        public String avatarUrl = "";
        /** 离线皮肤文件名（存于 <minecraftDir>/skins/ 下）；空 = 未设置皮肤 */
        public String skinFile = "";
        /** 皮肤模型：true=细手臂(slim) / false=宽手臂(classic)。仅当用户手动指定时有效，空=按贴图自动判定 */
        public Boolean skinSlim = null;
        /** 披风文件名（存于 <minecraftDir>/skins/ 下）；空 = 无披风 */
        public String capeFile = "";
        /** LittleSkin 的 Yggdrasil 服务器地址（默认官方站点，换皮肤站时改这里） */
        public String lsApiRoot = "https://littleskin.cn/api/yggdrasil";

        public Account() {
        }

        public static Account offline(String name) {
            Account a = new Account();
            a.type = Type.OFFLINE;
            a.userName = name;
            a.displayName = name;
            a.uuid = OSUtil.offlineUuid(name);
            return a;
        }

        public boolean isExpired() {
            return type == Type.MICROSOFT && System.currentTimeMillis() > expiresAt - 300_000;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            o.put("type", type.name());
            o.put("displayName", displayName);
            o.put("userName", userName);
            o.put("uuid", uuid);
            o.put("accessToken", accessToken);
            o.put("refreshToken", refreshToken);
            o.put("expiresAt", expiresAt);
            o.put("avatarUrl", avatarUrl);
            o.put("skinFile", skinFile);
            if (skinSlim != null) o.put("skinSlim", skinSlim.booleanValue());
            o.put("capeFile", capeFile);
            o.put("lsApiRoot", lsApiRoot);
            return o;
        }

        public static Account fromJson(JSONObject o) {
            Account a = new Account();
            a.type = Type.valueOf(o.optString("type", "OFFLINE"));
            a.displayName = o.optString("displayName", "");
            a.userName = o.optString("userName", "");
            a.uuid = o.optString("uuid", "");
            a.accessToken = o.optString("accessToken", "");
            a.refreshToken = o.optString("refreshToken", "");
            a.expiresAt = o.optLong("expiresAt", 0);
            a.avatarUrl = o.optString("avatarUrl", "");
            a.skinFile = o.optString("skinFile", "");
            a.skinSlim = o.has("skinSlim") ? Boolean.valueOf(o.optBoolean("skinSlim")) : null;
            a.capeFile = o.optString("capeFile", "");
            a.lsApiRoot = o.optString("lsApiRoot", "https://littleskin.cn/api/yggdrasil");
            return a;
        }
    }
}
