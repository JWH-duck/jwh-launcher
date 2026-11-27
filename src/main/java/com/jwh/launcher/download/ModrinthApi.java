package com.jwh.launcher.download;

import com.jwh.launcher.util.Http;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Modrinth API 客户端（免费开放，无需 key）。
 * 用于 Mod / 光影包(shader) / 材质包(resourcepack) 的搜索与下载地址获取。
 */
public class ModrinthApi {

    public static final String BASE = "https://api.modrinth.com/v2";

    /** 内容类型。 */
    public enum Type {
        MOD("mod", "Mod"),
        SHADER("shader", "光影包"),
        RESOURCEPACK("resourcepack", "材质包"),
        MODPACK("modpack", "整合包");

        public final String facet;
        public final String label;

        Type(String facet, String label) {
            this.facet = facet;
            this.label = label;
        }
    }

    /** 搜索结果条目。 */
    public record Hit(String projectId, String slug, String title, String description,
                      String iconUrl, long downloads, List<String> categories,
                      String author, List<String> versions) {
    }

    /** 项目版本与可下载文件。 */
    public record File(String url, String filename, long size) {
    }

    /** 依赖项：dependencyType = required / optional / incompatible。 */
    public record Dependency(String projectId, String projectTitle, String dependencyType) {
    }

    /** Modrinth 项目基础信息（用于显示前置名称与检查本地是否已装）。 */
    public record Project(String id, String slug, String title, String iconUrl) {
    }

    public record Version(String id, String name, String versionNumber,
                          List<String> gameVersions, List<String> loaders, List<File> files,
                          List<Dependency> dependencies, String projectId, long datePublished) {
        public File primaryFile() {
            File best = files.isEmpty() ? null : files.get(0);
            for (File f : files) {
                // 优先主文件，且不是 sources jar
                if (f.filename.endsWith(".jar") || f.filename.endsWith(".zip") || f.filename.endsWith(".mrpack")) {
                    best = f;
                    break;
                }
            }
            return best;
        }
    }

    /** 获取项目信息（slug / title / iconUrl）。 */
    public Project project(String projectId) throws Exception {
        JSONObject o = new JSONObject(Http.getText(BASE + "/project/" + projectId));
        return new Project(o.optString("id"), o.optString("slug"), o.optString("title"), o.optString("icon_url"));
    }

    /** 搜索。mcVersion 与 loader 可为 null（不限）。 */
    public List<Hit> search(String query, Type type, String mcVersion, String loader, int limit) throws Exception {
        StringBuilder facets = new StringBuilder("[");
        facets.append("[\"project_type:").append(type.facet).append("\"]");
        if (mcVersion != null && !mcVersion.isBlank()) {
            facets.append(", [\"versions:").append(mcVersion).append("\"]");
        }
        if (loader != null && !loader.isBlank()) {
            facets.append(", [\"categories:").append(loader).append("\"]");
        }
        facets.append("]");

        String url = BASE + "/search?query=" + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8)
                + "&facets=" + URLEncoder.encode(facets.toString(), StandardCharsets.UTF_8)
                + "&limit=" + limit;
        JSONObject o = new JSONObject(Http.getText(url));
        JSONArray hits = o.optJSONArray("hits");
        List<Hit> out = new ArrayList<>();
        if (hits != null) {
            for (int i = 0; i < hits.length(); i++) {
                JSONObject h = hits.getJSONObject(i);
                List<String> cats = new ArrayList<>();
                JSONArray c = h.optJSONArray("categories");
                if (c != null) {
                    for (int j = 0; j < c.length(); j++) cats.add(c.getString(j));
                }
                List<String> vers = new ArrayList<>();
                JSONArray vv = h.optJSONArray("versions");
                if (vv != null) {
                    for (int j = 0; j < vv.length(); j++) vers.add(vv.getString(j));
                }
                out.add(new Hit(
                        h.optString("project_id"),
                        h.optString("slug"),
                        h.optString("title"),
                        h.optString("description"),
                        h.optString("icon_url"),
                        h.optLong("downloads", 0),
                        cats,
                        h.optString("author"),
                        vers));
            }
        }
        return out;
    }

    /** 获取某项目的全部版本（不带筛选），用于弹窗里列出所有可用游戏版本。 */
    public List<Version> allVersions(String projectId) throws Exception {
        return versions(projectId, null, null);
    }

    /** 获取某项目适配特定 MC 版本与加载器的版本列表（均可不限）。 */
    public List<Version> versions(String projectId, String mcVersion, String loader) throws Exception {
        StringBuilder url = new StringBuilder(BASE + "/project/" + projectId + "/version");
        boolean first = true;
        if (mcVersion != null && !mcVersion.isBlank()) {
            url.append(first ? "?" : "&");
            first = false;
            url.append("game_versions=").append(URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8));
        }
        if (loader != null && !loader.isBlank()) {
            url.append(first ? "?" : "&");
            url.append("loaders=").append(URLEncoder.encode("[\"" + loader + "\"]", StandardCharsets.UTF_8));
        }
        JSONArray arr = new JSONArray(Http.getText(url.toString()));
        List<Version> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            out.add(parseVersion(arr.getJSONObject(i)));
        }
        return out;
    }

    /**
     * 按文件哈希查所属版本（Modrinth version_file 接口，algorithm=sha1）。
     * 用于实例管理「检测更新」：本地文件 → 版本 → 项目 → 最新适配版本。
     */
    public Version versionByHash(String sha1) throws Exception {
        String url = BASE + "/version_file/" + sha1 + "?algorithm=sha1";
        JSONObject v = new JSONObject(Http.getText(url));
        return parseVersion(v);
    }

    /** 解析单个 Version JSON（project_id 与 date_published 用于更新检测比对）。 */
    private static Version parseVersion(JSONObject v) {
        List<String> gv = new ArrayList<>();
        JSONArray g = v.optJSONArray("game_versions");
        if (g != null) for (int j = 0; j < g.length(); j++) gv.add(g.getString(j));
        List<String> lds = new ArrayList<>();
        JSONArray l = v.optJSONArray("loaders");
        if (l != null) for (int j = 0; j < l.length(); j++) lds.add(l.getString(j));
        List<File> files = new ArrayList<>();
        JSONArray f = v.optJSONArray("files");
        if (f != null) {
            for (int j = 0; j < f.length(); j++) {
                JSONObject fo = f.getJSONObject(j);
                files.add(new File(fo.optString("url"), fo.optString("filename"), fo.optLong("size", 0)));
            }
        }
        List<Dependency> deps = new ArrayList<>();
        JSONArray d = v.optJSONArray("dependencies");
        if (d != null) {
            for (int j = 0; j < d.length(); j++) {
                JSONObject dj = d.getJSONObject(j);
                String dt = dj.optString("dependency_type", "");
                String pid = dj.optString("project_id", "");
                if (!pid.isBlank()) {
                    deps.add(new Dependency(pid, "", dt));
                }
            }
        }
        // date_published 为 ISO8601（如 2026-01-15T12:00:00.000Z），转为 epoch 毫秒便于比较
        long dp = 0;
        String ds = v.optString("date_published", "");
        if (!ds.isBlank()) {
            try {
                dp = java.time.Instant.parse(ds).toEpochMilli();
            } catch (Exception ignored) {
            }
        }
        return new Version(v.optString("id"), v.optString("name"), v.optString("version_number"),
                gv, lds, files, deps, v.optString("project_id"), dp);
    }
}
