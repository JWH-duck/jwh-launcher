package com.jwh.launcher.download;

import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CurseForge 官方 API 接入（需开发者 Key，可在 https://console.curseforge.com 免费申请）。
 * 端点：api.curseforge.com/v1；classId：6=Mods，12=Resourcepacks。
 */
public class CurseForgeApi {

    /** 搜索结果条目。 */
    public record Hit(String projectId, String title, String description, long downloads,
                      String logoUrl, String websiteUrl) {
    }

    /** 可直接下载的文件。 */
    public record File(String filename, String url) {
    }

    private static final String BASE = "https://api.curseforge.com/v1";

    private final String key;

    public CurseForgeApi(String key) {
        this.key = key == null ? "" : key;
    }

    public boolean hasKey() {
        return !key.isBlank();
    }

    private JSONObject get(String path) throws Exception {
        return new JSONObject(Http.getText(BASE + path, Map.of("x-api-key", key)));
    }

    /** 搜索。classId：6=Mods，12=Resourcepacks。 */
    public List<Hit> search(String query, String mcVersion, int classId, String loader, int pageSize) throws Exception {
        StringBuilder u = new StringBuilder("/mods/search?gameId=432&sortField=2&sortOrder=desc&pageSize=" + pageSize + "&index=0");
        if (classId > 0) u.append("&classId=").append(classId);
        if (query != null && !query.isBlank()) {
            u.append("&searchFilter=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        }
        if (mcVersion != null && !mcVersion.isBlank()) {
            u.append("&gameVersion=").append(URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
        }
        if (loader != null && !loader.isBlank()) {
            u.append("&modLoaderType=").append(loaderType(loader));
        }
        JSONObject body = get(u.toString());
        JSONArray data = body.optJSONArray("data");
        List<Hit> out = new ArrayList<>();
        if (data == null) return out;
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.getJSONObject(i);
            JSONObject logo = Json.optObj(m, "logo");
            JSONObject links = m.optJSONObject("links");
            out.add(new Hit(String.valueOf(m.optLong("id")),
                    m.optString("name"), m.optString("summary"),
                    m.optLong("downloadCount"),
                    logo == null ? "" : logo.optString("url"),
                    links == null ? "" : links.optString("websiteUrl")));
        }
        return out;
    }

    /** 取适配 MC 版本与加载器的文件，并解析出直接下载地址。 */
    public File pickFile(String projectId, String mc, String loader) throws Exception {
        StringBuilder u = new StringBuilder("/mods/" + projectId + "/files?pageSize=50&index=0");
        if (mc != null && !mc.isBlank()) {
            u.append("&gameVersion=").append(URLEncoder.encode(mc, StandardCharsets.UTF_8));
        }
        if (loader != null && !loader.isBlank()) {
            u.append("&modLoaderType=").append(loaderType(loader));
        }
        JSONObject body = get(u.toString());
        JSONArray data = body.optJSONArray("data");
        if (data == null || data.isEmpty()) return null;
        JSONObject f = data.getJSONObject(0);
        long fileId = f.optLong("id");
        String filename = f.optString("fileName");
        JSONObject dl = get("/mods/" + projectId + "/files/" + fileId + "/download-url");
        String url = dl.optString("data");
        if (filename.isBlank() || url.isBlank()) return null;
        return new File(filename, url);
    }

    /** CurseForge 加载器类型：1=Forge 2=Cauldron 3=LiteLoader 4=NeoForge 5=Fabric 6=Quilt。 */
    private static int loaderType(String loader) {
        if (loader == null) return -1;
        return switch (loader.toLowerCase()) {
            case "fabric" -> 5;
            case "quilt" -> 6;
            case "neoforge" -> 4;
            default -> 1;
        };
    }
}
