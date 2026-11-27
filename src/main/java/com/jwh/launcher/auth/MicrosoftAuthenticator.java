package com.jwh.launcher.auth;

import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Microsoft 正版账号认证（OAuth 2.0 → Xbox Live → XSTS → Minecraft Services）。
 *
 * 登录方式（对齐 v1.6.104 正版登录）：
 *   · 使用 Minecraft 官方应用 ID（00000000402b5328）时：走「授权码（Authorization Code）」
 *     网页登录 —— 内嵌 WebView 打开 login.live.com 官方授权页，用户登录后微软重定向回
 *     oauth20_desktop.srf 回调地址，启动器拦截回调取 code 换令牌（webFlowAvailable=true）。
 *   · 使用自己注册的 Entra 应用 ID 时：走「设备码（Device Code）」登录 —— 弹窗展示验证码 +
 *     系统浏览器授权，后台按 interval 轮询 token 端点（webFlowAvailable=false）。
 *
 * 两种方式共用后续链路：微软 access_token → Xbox Live（XBL）→ XSTS → Minecraft
 * login_with_xbox 三层令牌链 → 校验正版所有权（entitlements/mcstore）→ 获取玩家档案。
 * 令牌缓存与静默刷新（refreshMicrosoftToken）对两种方式通用。
 */
public class MicrosoftAuthenticator {

    /** Minecraft 官方 Launcher 的公共 client_id（仅授权码网页流可用；微软对此通道风控较严）。 */
    public static final String PUBLIC_CLIENT_ID = "00000000402b5328";
    /** 内置的微软登录应用 ID（公共客户端，免注册设备码流）。 */
    public static final String BUILTIN_CLIENT_ID = "fe72edc2-3a6f-4280-90e8-e2beb64ce7e1";
    public static final String DEFAULT_CLIENT_ID = BUILTIN_CLIENT_ID;
    public static String CLIENT_ID = DEFAULT_CLIENT_ID;
    /** 授权码流程的回调地址（微软桌面应用公共回调，固定值）。 */
    public static final String DEFAULT_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
    public static String REDIRECT_URI = DEFAULT_REDIRECT_URI;
    /** v1 桌面流（官方 ID + login.live.com 端点）的 Xbox Live 服务 scope（与 PojavLauncher 官方移植一致）。 */
    public static final String SCOPE_XBOX_V1 = "service::user.auth.xboxlive.com::MBI_SSL";
    /** v2 端点（自注册 Entra 应用 / 设备码，consumers 租户）的 scope。 */
    public static final String SCOPE_V2 = "XboxLive.signin offline_access";

    /** 网页授权端点（授权码流程，官方 client_id 使用）。 */
    public static final String AUTH_URL = "https://login.live.com/oauth20_authorize.srf";
    /** 官方 client_id 的换码/刷新端点（login.live.com v1）。 */
    public static final String LIVE_TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    /** 自注册 Entra 应用（个人账户）的换码/刷新端点（consumers v2）。 */
    public static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    /** 设备码请求端点（自注册 Entra 应用）。 */
    public static final String DEVICE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";

    private static final java.util.regex.Pattern CLIENT_PATTERN =
            java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 启动时调用（对齐 104）：配置有效则用之，否则读 jar 旁 microsoft-auth.json，再兜底官方 ID；回调地址恒为默认值。 */
    public static void init(com.jwh.launcher.config.LauncherConfig cfg) {
        String id = (cfg != null && cfg.microsoftClientId != null) ? cfg.microsoftClientId.trim() : "";
        if (isValidClientId(id)) {
            CLIENT_ID = id;
        } else {
            CLIENT_ID = distributionClientId();
        }
        REDIRECT_URI = DEFAULT_REDIRECT_URI;
    }

    /** 判断是否为合法的 client_id：UUID 格式，或等于 Minecraft 官方应用 ID（忽略大小写）。 */
    public static boolean isValidClientId(String id) {
        if (id == null) return false;
        String t = id.trim();
        return CLIENT_PATTERN.matcher(t).matches()
                || PUBLIC_CLIENT_ID.equalsIgnoreCase(t);
    }

    /** 是否使用自己注册的 Entra 应用（有效且 ≠ Minecraft 官方应用 ID）。 */
    public static boolean hasOwnClientId() {
        return isValidClientId(CLIENT_ID) && !PUBLIC_CLIENT_ID.equalsIgnoreCase(CLIENT_ID.trim());
    }

    /** 网页授权码登录是否可用：仅 Minecraft 官方应用 ID 支持内嵌官方页面。 */
    public static boolean webFlowAvailable() {
        return !hasOwnClientId();
    }

    /** 读取 jar 所在目录旁的 microsoft-auth.json 中的 clientId（分发专用），无效则兜底官方 ID。 */
    private static String distributionClientId() {
        try {
            java.net.URI loc = com.jwh.launcher.config.LauncherConfig.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            java.nio.file.Path jar = java.nio.file.Path.of(loc);
            if (!java.nio.file.Files.isRegularFile(jar)) return BUILTIN_CLIENT_ID;
            java.nio.file.Path json = jar.resolveSibling("microsoft-auth.json");
            if (!java.nio.file.Files.isRegularFile(json)) return BUILTIN_CLIENT_ID;
            String clientId = new org.json.JSONObject(
                    java.nio.file.Files.readString(json, java.nio.charset.StandardCharsets.UTF_8))
                    .optString("clientId").trim();
            return isValidClientId(clientId) ? clientId : BUILTIN_CLIENT_ID;
        } catch (Exception e) {
            return BUILTIN_CLIENT_ID;
        }
    }

    /** 保证 CLIENT_ID 有效：无效时重置为内置应用 ID 并返回。 */
    private static String requireClientId() {
        if (!isValidClientId(CLIENT_ID)) {
            CLIENT_ID = BUILTIN_CLIENT_ID;
            return BUILTIN_CLIENT_ID;
        }
        return CLIENT_ID;
    }

    // ---- 端点定义 ----
    // 租户说明：Minecraft 官方公共 client_id 注册在 common 租户（multi-tenant），
    // 自注册「仅个人账户」的 Azure 应用则需 consumers 租户。
    // 因此默认走 common，失败且提示「应用未找到（700016）」时自动回退 consumers。
    public static final String[] TENANTS = {"common", "consumers"};
    public static volatile String ACTIVE_TENANT = "common";
    public static final String XBOX_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    public static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    public static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    public static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";
    public static final String MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore";

    private MicrosoftAuthenticator() {
    }

    public static String deviceCodeUrl() {
        return "https://login.microsoftonline.com/" + ACTIVE_TENANT + "/oauth2/v2.0/devicecode";
    }

    public static String deviceTokenUrl() {
        return "https://login.microsoftonline.com/" + ACTIVE_TENANT + "/oauth2/v2.0/token";
    }

    /** 判断响应是否为「应用未找到 / 租户不匹配」（AADSTS700016 或 7000218），用于租户回退。 */
    static boolean isTenantMismatch(int status, String body) {
        if (body == null) return false;
        String lower = body.toLowerCase();
        return lower.contains("aadsts700016") || lower.contains("aadsts7000218")
                || lower.contains("unauthorized_client");
    }

    /**
     * 步骤 1：请求 Device Code（对齐 104：仅自己注册的 Entra 应用可用）。
     * 官方 Minecraft 应用 ID 直接抛错，引导使用网页授权码登录。默认 common 租户，700016 时自动回退 consumers 再试一次。
     * @return JSONObject，含 device_code / user_code / verification_uri / expires_in / interval / message
     */
    public static JSONObject requestDeviceCode() throws Exception {
        if (!hasOwnClientId()) {
            throw new IllegalStateException("设备码登录支持内置登录应用与自建 Entra 应用。当前使用的是 Minecraft 官方应用 ID，"
                    + "请使用默认内置登录应用后重试。");
        }
        String lastErr = null;
        // 内置应用固定 consumers 租户；其它自建应用先 common 再回退 consumers
        String[] tenants = BUILTIN_CLIENT_ID.equalsIgnoreCase(CLIENT_ID) ? new String[]{"consumers"} : TENANTS;
        for (String tenant : tenants) {
            Http.HttpResult r = Http.postFormRaw(
                    "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/devicecode", Map.of(
                            "client_id", CLIENT_ID,
                            "tenant", "/" + tenant,
                            "scope", SCOPE_V2));
            if (r.status == 200) {
                ACTIVE_TENANT = tenant;
                return new JSONObject(r.body);
            }
            lastErr = r.body;
            if (tenant.equals("common") && isTenantMismatch(r.status, r.body)) {
                // 官方公共 client_id 不在 consumers；自注册应用不在 common → 换租户重试
                continue;
            }
            break;
        }
        // 两个租户都失败：给出可读错误
        String hint = isTenantMismatch(0, lastErr)
                ? "\n（客户端 ID 与登录租户不匹配：如使用自己注册的 Azure 应用，请确认「账户类型」勾选了「个人 Microsoft 账户」）"
                : "";
        throw new IOException("获取登录验证码失败: HTTP 400 @ devicecode body=" + lastErr + hint);
    }

    /** 轮询回调：每轮 polling 都会调用（用于刷新 UI 状态）。返回 true 表示继续轮询，false 表示停止。 */
    public interface PollCallback {
        /** @param remainSeconds 剩余总时长（约） */
        boolean onPoll(int remainSeconds, String lastError);
    }

    /**
     * 步骤 1（续）：轮询 token 端点，等用户在浏览器中完成授权。
     * @param deviceInfo requestDeviceCode 的返回值
     * @param cancel 用户取消时为 true（主动终止轮询）
     * @return JSONObject，含 access_token / refresh_token
     */
    public static JSONObject pollDeviceToken(JSONObject deviceInfo, AtomicBoolean cancel, PollCallback cb) throws Exception {
        String deviceCode = deviceInfo.optString("device_code");
        int interval = Math.max(deviceInfo.optInt("interval", 5), 1);
        int expiresIn = deviceInfo.optInt("expires_in", 900);
        long deadline = System.currentTimeMillis() + expiresIn * 1000L;
        int failCount = 0;

        while (!cancel.get() && System.currentTimeMillis() < deadline) {
            int remain = (int) ((deadline - System.currentTimeMillis()) / 1000);
            String body;
            int status;
            try {
                Http.HttpResult r = Http.postFormRaw(deviceTokenUrl(), Map.of(
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id", CLIENT_ID,
                        "device_code", deviceCode,
                        "scope", SCOPE_V2));
                status = r.status;
                body = r.body;
            } catch (Exception e) {
                // 网络层临时错误：按 interval 重试，连续 5 次失败才报错
                failCount++;
                if (failCount >= 5) {
                    throw new IOException("登录轮询失败：" + e.getMessage());
                }
                if (cb != null && !cb.onPoll(remain, e.getMessage())) break;
                Thread.sleep(interval * 1000L);
                continue;
            }

            if (status == 200) {
                return new JSONObject(body);
            }
            // 400：按错误语义分支处理
            String lower = body == null ? "" : body.toLowerCase();
            if (lower.contains("authorization_pending")) {
                // 用户还没完成授权，继续轮询
                failCount = 0;
                if (cb != null && !cb.onPoll(remain, null)) break;
                Thread.sleep(interval * 1000L);
                continue;
            }
            if (lower.contains("authorization_declined")) {
                throw new IllegalStateException("你拒绝了登录授权。");
            }
            if (lower.contains("expired_token")) {
                throw new IllegalStateException("登录用时太长，验证码已过期，请重新登录。");
            }
            if (lower.contains("account security interrupt")) {
                throw new IllegalStateException("该账号因安全问题无法登录，请前往微软账户页获取更多信息。");
            }
            if (lower.contains("service abuse")) {
                throw new IllegalStateException("该账号已被微软封禁，无法登录。");
            }
            if (lower.contains("aadsts70000")) {
                throw new IllegalStateException("需要使用密码重新登录：请在浏览器登录时选择「其他登录方法 → 使用我的密码」。");
            }
            // 其他未知错误：累计失败次数，超限即放弃
            failCount++;
            if (failCount >= 5) {
                throw new IOException("登录轮询失败：" + (body == null ? "未知错误" : body));
            }
            if (cb != null && !cb.onPoll(remain, body)) break;
            Thread.sleep(interval * 1000L);
        }
        if (cancel.get()) {
            throw new IllegalStateException("登录已取消");
        }
        throw new IllegalStateException("登录超时，验证码已过期，请重新登录。");
    }

    /**
     * 换码/刷新 token 端点（对齐 104）：官方 client_id 走 login.live.com v1；自注册 Entra 应用走 consumers v2。
     */
    public static String tokenUrl() {
        // 内置应用与官方 ID 都用 login.live.com v1 端点刷新；
        // 仅「其它自建 Entra 应用」走 consumers v2 token 端点。
        return BUILTIN_CLIENT_ID.equalsIgnoreCase(CLIENT_ID) ? LIVE_TOKEN_URL
                : hasOwnClientId() ? TOKEN_URL : LIVE_TOKEN_URL;
    }

    /** 是否走 v1 桌面流（官方 ID → login.live.com 端点）。 */
    static boolean v1Flow() {
        return !hasOwnClientId();
    }

    /** 当前流程的 token scope：v1 桌面流用 MBI_SSL，v2（自建应用）用 XboxLive.signin offline_access。 */
    static String tokenScope() {
        return v1Flow() ? SCOPE_XBOX_V1 : SCOPE_V2;
    }

    /** 当前流程 token 端点的回调参数名：v1 端点（oauth20_token.srf）用 redirect_url，v2 端点用 redirect_uri。 */
    static String tokenRedirectParam() {
        return v1Flow() ? "redirect_url" : "redirect_uri";
    }

    /** 构建微软官方授权页地址（Authorization Code 流程）。state 用于防 CSRF 校验回调。 */
    public static String buildAuthorizeUrl(String state) {
        return buildLoginUrl(state);
    }

    /** 构建授权页地址：login.live.com/oauth20_authorize.srf + 查询参数（对齐 Pojav 官方：redirect_url + MBI_SSL，无 prompt）。 */
    public static String buildLoginUrl(String state) {
        return AUTH_URL + "?" + form(java.util.Map.of(
                "client_id", requireClientId(),
                "response_type", "code",
                "redirect_url", REDIRECT_URI,
                "scope", SCOPE_XBOX_V1,
                "state", state));
    }

    /** 是否为本启动器可信的回调地址：https + 无 userinfo + host=login.live.com + path=/oauth20_desktop.srf。 */
    public static boolean isTrustedRedirect(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            if (uri.getRawUserInfo() != null) return false;
            String host = uri.getHost();
            if (host == null || !"login.live.com".equalsIgnoreCase(host)) return false;
            String path = uri.getPath();
            return path != null && "/oauth20_desktop.srf".equalsIgnoreCase(path);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 从回调地址解析授权码：须含 code= 且为可信回调，正则 [A-Za-z0-9._~+/=-]{8,4096}。 */
    public static String authorizationCode(String url) {
        if (url == null || !url.contains("code=") || !isTrustedRedirect(url)) return null;
        String query = java.net.URI.create(url).getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!pair.substring(0, eq).equals("code")) continue;
            String v;
            try {
                v = java.net.URLDecoder.decode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                return null;
            }
            return v.matches("[A-Za-z0-9._~+/=-]{8,4096}") ? v : null;
        }
        return null;
    }

    /** 从回调地址解析授权错误（error=），返回用户可读中文提示；无可读错误返回 null。 */
    public static String redirectError(String url) {
        if (url == null || !isTrustedRedirect(url) || !url.contains("error=")) return null;
        String query = java.net.URI.create(url).getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!pair.substring(0, eq).equals("error")) continue;
            String e;
            try {
                e = java.net.URLDecoder.decode(pair.substring(eq + 1),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception ex) {
                return "微软返回了未知错误，请重新登录。";
            }
            if (e.equals("access_denied")) return "你拒绝了微软授权，请重新登录。";
            if (e.equals("invalid_request") || e.equals("unauthorized_client")) return "微软拒绝了此次授权，请稍后再试。";
            return "微软返回了错误：" + e + "，请重新登录。";
        }
        return "微软返回了未知错误，请重新登录。";
    }

    /**
     * 授权码换令牌（Authorization Code → access_token / refresh_token，对齐 104）。
     * 官方 client_id 走 login.live.com/oauth20_token.srf；自注册应用走 consumers v2 token 端点。
     */
    public static JSONObject exchangeCode(String code) throws Exception {
        if (code == null || code.isBlank()) {
            throw new IllegalStateException("没有收到微软授权码，请重新登录。");
        }
        Http.HttpResult r = Http.postFormRaw(tokenUrl(), java.util.Map.of(
                "client_id", requireClientId(),
                "code", code,
                "grant_type", "authorization_code",
                tokenRedirectParam(), REDIRECT_URI,
                "scope", tokenScope()));
        return checked(r, "微软授权");
    }

    /** 表单编码（URLEncoder UTF-8，& 分隔；与 104 的 form 同语义）。 */
    static String form(java.util.Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** OAuth 响应校验：2xx 且无 error 返回 JSON；否则抛出带可读中文的 IOException（对齐 104 的 checked）。 */
    static JSONObject checked(Http.HttpResult r, String ctx) throws IOException {
        JSONObject o;
        try {
            o = new JSONObject(r.body);
        } catch (Exception e) {
            o = new JSONObject();
        }
        if (r.status >= 200 && r.status < 300 && !o.has("error")) {
            return o;
        }
        String error = o.optString("error", "");
        String msg;
        if (error.equals("unauthorized_client") || error.equals("invalid_client")) {
            msg = "应用 Client ID 无效，或未配置给该账号和公共客户端类型的授权。请检查应用注册。";
        } else if (error.equals("invalid_grant") || error.equals("interaction_required")) {
            msg = "登录凭据已过期或失效，请重新开始微软登录。";
        } else if (error.equals("invalid_scope")) {
            msg = "该应用未获准使用所需的 Xbox 权限，请检查应用注册的权限与 Minecraft API 授权配置。";
        } else if (r.status == 429) {
            msg = "请求过于频繁，请稍后重试。";
        } else if (r.status == 403) {
            msg = "访问被拒绝，请检查应用的 Minecraft API 授权与账号状态。";
        } else if (r.status == 401) {
            msg = "登录凭据无效，请重新登录。";
        } else if (r.status >= 500) {
            msg = "微软或 Minecraft 服务暂时不可用，请稍后重试。";
        } else {
            msg = "请求未成功，请检查网络、应用配置及账号状态。";
        }
        throw new IOException(ctx + "失败: HTTP " + r.status + " " + msg);
    }

    /**
     * 刷新登录：用 refresh_token 换新 access_token（对齐 104：按 client_id 选择端点）。
     * 官方 client_id 走 login.live.com/oauth20_token.srf；自注册应用走 consumers v2 token 端点。
     * 刷新失败（需重新登录 / 账号异常）会抛出带明确中文提示的异常。
     * @return JSONObject，含 access_token / refresh_token（可能轮换）
     */
    public static JSONObject refreshMicrosoftToken(String refreshToken) throws Exception {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("该账号没有可用的刷新令牌，请重新登录");
        }
        String body;
        try {
            // 内置应用：LIVE_TOKEN_URL + refresh_token grant，不传 redirect
            java.util.LinkedHashMap<String, String> form = new java.util.LinkedHashMap<>();
            form.put("client_id", CLIENT_ID);
            form.put("refresh_token", refreshToken);
            form.put("grant_type", "refresh_token");
            if (!BUILTIN_CLIENT_ID.equalsIgnoreCase(CLIENT_ID)) {
                form.put(tokenRedirectParam(), REDIRECT_URI);
            }
            form.put("scope", tokenScope());
            body = Http.postForm(tokenUrl(), form);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String lower = msg.toLowerCase();
            if (lower.contains("must sign in again") || lower.contains("password expired")
                    || (lower.contains("refresh_token") && lower.contains("is not valid")) || lower.contains("expired")) {
                throw new IllegalStateException("登录状态已失效，请重新登录（微软要求重新验证账号）。");
            }
            if (lower.contains("account security interrupt")) {
                throw new IllegalStateException("该账号因安全问题无法登录，请前往微软账户页获取更多信息。");
            }
            if (lower.contains("service abuse")) {
                throw new IllegalStateException("该账号已被微软封禁，无法登录。");
            }
            throw e;
        }
        JSONObject o = new JSONObject(body);
        if (!o.has("access_token")) {
            throw new IllegalStateException("刷新登录失败：" + o.optString("error_description", o.optString("error", "未知错误")));
        }
        return o;
    }

    /**
     * 步骤 2-6：微软 access_token → Minecraft profile 完整链路。
     * @param msAccessToken 微软 access_token
     * @return 含 accessToken / uuid / name / expiresAt(ms) / profileJson 的 JSONObject
     */
    public static JSONObject completeMinecraftLogin(String msAccessToken) throws Exception {
        // 步骤 2：Xbox Live authenticate → XBL Token（JWT）
        String xblBody = Http.postJson(XBOX_AUTH_URL, new JSONObject()
                .put("Properties", new JSONObject()
                        .put("AuthMethod", "RPS")
                        .put("SiteName", "user.auth.xboxlive.com")
                        .put("RpsTicket", hasOwnClientId() ? "d=" + msAccessToken : msAccessToken))
                .put("RelyingParty", "http://auth.xboxlive.com")
                .put("TokenType", "JWT")
                .toString());
        JSONObject xbl = new JSONObject(xblBody);
        String xblToken = xbl.optString("Token");
        if (xblToken.isBlank()) {
            throw new IllegalStateException("Xbox Live 认证失败，请重试或检查账号");
        }

        // 步骤 3：XSTS authorize → XSTS Token + UHS（UserTokens 只放 XBL Token）
        JSONObject xstsReq = new JSONObject()
                .put("Properties", new JSONObject()
                        .put("SandboxId", "RETAIL")
                        .put("UserTokens", new org.json.JSONArray().put(xblToken)))
                .put("RelyingParty", "rp://api.minecraftservices.com/")
                .put("TokenType", "JWT");
        JSONObject xsts = null;
        try {
            String xstsBody = Http.postJson(XSTS_AUTH_URL, xstsReq.toString());
            xsts = new JSONObject(xstsBody);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.startsWith("HTTP 400")) {
                String body = msg.contains("body=") ? msg.substring(msg.indexOf("body=") + 5) : "";
                if (!body.isBlank() && body.startsWith("{")) {
                    try {
                        String d = describeXstsError(new JSONObject(body));
                        if (!d.equals("未知错误")) throw new IllegalStateException(d);
                    } catch (IllegalStateException ise) {
                        throw ise;
                    } catch (Exception ignored) {
                    }
                }
            }
            throw e;
        }
        String xstsToken = xsts.optString("Token");
        String uhs = xsts.optJSONObject("DisplayClaims") != null
                && xsts.optJSONObject("DisplayClaims").optJSONArray("xui") != null
                ? xsts.optJSONObject("DisplayClaims").optJSONArray("xui").getJSONObject(0).optString("uhs")
                : xbl.optJSONObject("DisplayClaims") != null
                        && xbl.optJSONObject("DisplayClaims").optJSONArray("xui") != null
                        ? xbl.optJSONObject("DisplayClaims").optJSONArray("xui").getJSONObject(0).optString("uhs")
                        : null;
        if (xstsToken.isBlank() || uhs == null || uhs.isBlank()) {
            throw new IllegalStateException("XSTS 授权失败，请重试或检查账号");
        }

        // 步骤 4：Minecraft login_with_xbox → MC access_token + expires_in
        String mcBody = Http.postJson(MC_LOGIN_URL, new JSONObject()
                .put("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken)
                .toString());
        JSONObject mc = new JSONObject(mcBody);
        String mcToken = mc.optString("access_token");
        long expiresIn = mc.optLong("expires_in", 86400);
        if (mcToken.isBlank()) {
            throw new IllegalStateException("Minecraft 令牌获取失败：" + mc.optString("error", "未知错误"));
        }

        // 步骤 5：所有权校验（未购买正版 → 抛提示）
        try {
            String entBody = Http.getText(MC_ENTITLEMENTS_URL, Map.of("Authorization", "Bearer " + mcToken));
            JSONObject ent = new JSONObject(entBody);
            if (!ent.has("items") || !ent.optJSONArray("items").iterator().hasNext()) {
                throw new IllegalStateException("你尚未购买正版 Minecraft，或 Xbox Game Pass 已到期。请购买后再登录。");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 所有权接口临时失败不阻断登录（仅在明确返回空 items 时判定未购买）
        }

        // 步骤 6：Minecraft profile → UUID + 用户名 + 完整档案 JSON
        String profileBody;
        try {
            profileBody = Http.getText(MC_PROFILE_URL, Map.of("Authorization", "Bearer " + mcToken));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                throw new IllegalStateException("该 Microsoft 账号尚未创建 Minecraft 玩家档案"
                        + "（请先在 minecraft.net 商店购买 Java 版并创建档案后重试）。");
            }
            throw e;
        }
        JSONObject profile = new JSONObject(profileBody);

        JSONObject out = new JSONObject();
        out.put("accessToken", mcToken);
        out.put("uuid", profile.optString("id"));
        out.put("name", profile.optString("name"));
        out.put("expiresAt", System.currentTimeMillis() + expiresIn * 1000L);
        out.put("profileJson", profileBody);
        return out;
    }

    /** 尝试将 XSTS 错误码转为用户可读信息（覆盖常见错误码）。 */
    public static String describeXstsError(JSONObject errBody) {
        if (errBody == null) return "未知错误";
        long err = errBody.optLong("XErr", 0);
        if (err == 2148916227L) return "该账号已被微软封禁，无法登录";
        if (err == 2148916233L) return "你尚未注册 Xbox 账户，请先注册 Xbox 账号后再登录";
        if (err == 2148916235L) return "你的网络所在的国家或地区无法登录微软账号，请使用加速器或 VPN 后再试";
        if (err == 2148916236L || err == 2148916237L) return "未成年账号需要家长在家庭安全设置中允许第三方应用登录";
        if (err == 2148916238L) return "该账号年龄不足，需要先在微软账户中修改出生日期（至少 13 岁）才能登录";
        if (errBody.has("message")) return errBody.optString("message");
        return "Xbox 登录失败（XErr=" + err + "）";
    }
}
