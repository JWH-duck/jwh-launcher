package com.jwh.launcher.auth;

import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * LittleSkin 皮肤站认证（Yggdrasil 外置登录协议）。
 * <p>站点地址默认 https://littleskin.cn/api/yggdrasil，接口遵循 authlib-injector 规范：
 * <ul>
 *   <li>POST /authserver/authenticate —— 账号密码换 accessToken</li>
 *   <li>POST /authserver/refresh —— 用旧 token 续期</li>
 *   <li>POST /authserver/validate —— 校验 token 是否还有效</li>
 *   <li>GET  /sessionserver/session/minecraft/profile/{uuid} —— 取皮肤/披风纹理</li>
 * </ul>
 * 返回的 selectedProfile.id 是**不带横线**的 32 位 UUID，存入配置时补横线。
 */
public final class LittleSkinAuth {

    /** LittleSkin 官网首页 */
    public static final String HOME = "https://littleskin.cn";
    /** 注册页 */
    public static final String REGISTER = "https://littleskin.cn/auth/register";
    /** 默认 Yggdrasil 根地址 */
    public static final String DEFAULT_API = "https://littleskin.cn/api/yggdrasil";

    private LittleSkinAuth() {
    }

    /** 认证结果：令牌 + 角色信息。 */
    public static final class Result {
        public String accessToken = "";
        public String clientToken = "";
        /** 32 位无横线 UUID */
        public String profileId = "";
        public String profileName = "";
    }

    /** 账号 + 密码换取令牌。密码错误/账号不存在抛 {@link SkinError}（带站点原文中文提示）。 */
    public static Result authenticate(String apiRoot, String username, String password, String clientToken)
            throws Exception {
        String token = (clientToken == null || clientToken.isBlank())
                ? UUID.randomUUID().toString().replace("-", "") : clientToken;
        JSONObject agent = new JSONObject();
        agent.put("name", "Minecraft");
        agent.put("version", 1);
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        body.put("clientToken", token);
        body.put("agent", agent);
        body.put("requestUser", false);

        JSONObject resp = postObject(apiRoot + "/authserver/authenticate", body);
        Result r = new Result();
        r.accessToken = resp.optString("accessToken", "");
        r.clientToken = resp.optString("clientToken", token);
        JSONObject prof = Json.optObj(resp, "selectedProfile");
        // 账号下还没建角色时站点不返回 selectedProfile，用 availableProfiles 的第一个顶上
        if (prof == null) {
            JSONArray arr = Json.optArr(resp, "availableProfiles");
            if (arr != null && arr.length() > 0) prof = arr.optJSONObject(0);
        }
        if (prof != null) {
            r.profileId = prof.optString("id", "");
            r.profileName = prof.optString("name", "");
        }
        if (r.accessToken.isBlank()) throw new SkinError("皮肤站没有返回令牌，登录未完成。");
        if (r.profileId.isBlank()) throw new SkinError("该账号下还没有创建角色，请先到官网新建一个角色再登录。");
        return r;
    }

    /** 用已有令牌续期；令牌被吊销时抛异常（此时要求重新登录）。 */
    public static Result refresh(String apiRoot, String accessToken, String clientToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("accessToken", accessToken);
        if (clientToken != null && !clientToken.isBlank()) body.put("clientToken", clientToken);
        JSONObject resp = postObject(apiRoot + "/authserver/refresh", body);
        Result r = new Result();
        r.accessToken = resp.optString("accessToken", accessToken);
        r.clientToken = resp.optString("clientToken", clientToken == null ? "" : clientToken);
        JSONObject prof = Json.optObj(resp, "selectedProfile");
        if (prof != null) {
            r.profileId = prof.optString("id", "");
            r.profileName = prof.optString("name", "");
        }
        return r;
    }

    /** 令牌是否仍有效（预检用，失败只返回 false 不抛）。 */
    public static boolean validate(String apiRoot, String accessToken, String clientToken) {
        try {
            JSONObject body = new JSONObject();
            body.put("accessToken", accessToken);
            if (clientToken != null && !clientToken.isBlank()) body.put("clientToken", clientToken);
            postObject(apiRoot + "/authserver/validate", body);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 皮肤/披风纹理。 */
    public static final class Textures {
        public String skinUrl = "";
        public String capeUrl = "";
        /** true = 细手臂（slim），false = 宽手臂（classic） */
        public boolean slim = false;
        public boolean hasSkin() {
            return skinUrl != null && !skinUrl.isBlank();
        }
        public boolean hasCape() {
            return capeUrl != null && !capeUrl.isBlank();
        }
    }

    /**
     * 取某角色的纹理。textures 放在 properties 数组里，value 是 base64 的 JSON（与 Mojang 同格式）。
     * 站点未设置皮肤/披风时对应 URL 为空。
     */
    public static Textures fetchTextures(String apiRoot, String profileId) throws Exception {
        String uuid = profileId == null ? "" : profileId.replace("-", "");
        if (uuid.isBlank()) throw new IllegalArgumentException("缺少角色 UUID");
        String text = Http.getText(apiRoot + "/sessionserver/session/minecraft/profile/" + uuid);
        JSONObject root = Json.obj(text);
        Textures t = new Textures();
        JSONArray props = Json.optArr(root, "properties");
        if (props == null) return t;
        for (int i = 0; i < props.length(); i++) {
            JSONObject p = props.optJSONObject(i);
            if (p == null || !"textures".equals(p.optString("name"))) continue;
            String val = p.optString("value", "");
            if (val.isBlank()) continue;
            JSONObject tex = Json.obj(new String(Base64.getDecoder().decode(val), StandardCharsets.UTF_8));
            JSONObject block = Json.optObj(tex, "textures");
            if (block == null) break;
            JSONObject skin = Json.optObj(block, "SKIN");
            if (skin != null) {
                t.skinUrl = skin.optString("url", "");
                JSONObject meta = Json.optObj(skin, "metadata");
                t.slim = meta != null && "slim".equals(meta.optString("model", ""));
            }
            JSONObject cape = Json.optObj(block, "CAPE");
            if (cape != null) t.capeUrl = cape.optString("url", "");
            break;
        }
        return t;
    }

    /** 32 位无横线 UUID → 标准带横线格式（配置里统一存带横线）。 */
    public static String withDashes(String id) {
        String s = id == null ? "" : id.replace("-", "");
        if (s.length() != 32) return id == null ? "" : id;
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20);
    }

    /** POST JSON 并解析；4xx 时把站点返回的 errorMessage 直接抛出来（比 HTTP 状态码好懂）。 */
    private static JSONObject postObject(String url, JSONObject body) throws Exception {
        String resp;
        try {
            resp = Http.postJson(url, body.toString());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int at = msg.indexOf("body=");
            if (at >= 0) {
                try {
                    JSONObject err = Json.obj(msg.substring(at + 5));
                    String em = err.optString("errorMessage", "");
                    if (!em.isBlank()) throw new SkinError(em);
                } catch (SkinError se) {
                    throw se;
                } catch (Exception ignored) {
                    // 响应体不是 JSON，按原异常往外抛
                }
            }
            throw e;
        }
        if (resp == null || resp.isBlank()) throw new SkinError("皮肤站返回了空响应。");
        return Json.obj(resp);
    }

    /** 站点业务错误（已翻译成中文提示），界面直接把 getMessage() 显示给玩家。 */
    public static class SkinError extends RuntimeException {
        public SkinError(String msg) {
            super(msg);
        }
    }
}
