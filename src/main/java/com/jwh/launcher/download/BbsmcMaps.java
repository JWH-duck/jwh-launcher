package com.jwh.launcher.download;

import com.jwh.launcher.util.Http;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * BBSMC（bbsmc.net）地图区的只读接口层。
 * <p>
 * 站点本身是 Nuxt 单页应用，列表与详情都要靠前端 JS 拼装；但它对外暴露了一套
 * 与 Modrinth 同构的 v2 JSON API（https://api.bbsmc.net/v2），字段含义基本一致，
 * 于是这里改用 API 取数，避免解析几百 KB 的 SSR HTML，也避免 WebView 被反爬拦住。
 * <p>
 * 已验证可用的端点：
 * <ul>
 *   <li>{@code GET /v2/search?query=&limit=&offset=&index=downloads&facets=[["project_type:map"]]}
 *       —— 地图列表（facets 不传会混进整合包/模组）</li>
 *   <li>{@code GET /v2/version/{版本ID}} —— 某个版本的下载文件信息</li>
 * </ul>
 * 下载形态有两种：主流是 cdn.bbsmc.net 直链（启动器内可直接下），
 * 少数作者只传了网盘（夸克/百度，disk_only=true，files[0].url 就是网盘分享页），
 * 这种无法在启动器内完成下载，只能把链接交给浏览器。
 */
public final class BbsmcMaps {

    /** API 根。 */
    public static final String API = "https://api.bbsmc.net/v2";
    /** 站点根（详情页 = SITE + "/map/" + slug）。 */
    public static final String SITE = "https://bbsmc.net";

    private BbsmcMaps() {
    }

    /** 列表里的一张地图卡。 */
    public static final class Item {
        public final String id;
        public final String slug;
        public final String title;
        public final String author;
        public final String description;
        /** 缩略图地址（gallery 首图优先，回退 icon_url），可能为空。 */
        public final String thumb;
        /** 项目小图标（站点卡片左上角那个方块图），可能为空。 */
        public final String icon;
        /** 最新版本 ID，下载前用它换文件地址。 */
        public final String latestVersion;
        public final int downloads;
        public final int follows;
        /** 最后更新时间的毫秒时间戳，0 表示未知。 */
        public final long modified;
        public final List<String> tags;

        Item(JSONObject o) {
            id = o.optString("project_id", "");
            slug = o.optString("slug", "");
            title = o.optString("title", "(无标题)");
            String a = o.optString("author", "");
            author = a;
            String d = o.optString("description", "");
            description = d == null ? "" : d.replace('\n', ' ').trim();
            latestVersion = o.optString("latest_version", "");
            downloads = o.optInt("downloads", 0);
            follows = o.optInt("follows", 0);
            modified = parseTime(o.optString("date_modified", ""));
            List<String> t = new ArrayList<>();
            JSONArray dc = o.optJSONArray("display_categories");
            if (dc == null) dc = o.optJSONArray("categories");
            if (dc != null) {
                for (int i = 0; i < dc.length() && t.size() < 4; i++) {
                    String s = dc.optString(i, "");
                    if (s == null || s.isBlank() || "map".equalsIgnoreCase(s)) continue;
                    t.add(s);
                }
            }
            tags = t;

            String icon = o.optString("icon_url", "");
            String g = "";
            JSONArray gallery = o.optJSONArray("gallery");
            if (gallery != null && gallery.length() > 0) g = gallery.optString(0, "");
            thumb = !g.isBlank() ? g : icon;
            this.icon = icon;
        }

        /** 站点详情页地址。 */
        public String pageUrl() {
            return SITE + "/map/" + slug;
        }
    }

    /** 某个版本的下载形态。 */
    public static final class Release {
        /** 真正可取的文件地址（直链时为 CDN 地址，网盘时是分享页）。 */
        public final String url;
        /** 网盘分享页（仅 diskOnly 时非空，用于「打开网盘」）。 */
        public final String diskUrl;
        /** 网盘平台标识：quark / baidu / ... 空串表示非网盘。 */
        public final String platform;
        public final String filename;
        public final long size;
        /** true = 作者只给了网盘，启动器拿不到直链。 */
        public final boolean diskOnly;
        /** 版本号（1.0 之类），用于展示。 */
        public final String versionName;

        Release(JSONObject v) {
            versionName = v.optString("version_number", "");
            diskOnly = v.optBoolean("disk_only", false);
            String u = "";
            String name = "";
            long sz = 0;
            JSONArray files = v.optJSONArray("files");
            if (files != null && files.length() > 0) {
                JSONObject f = files.optJSONObject(0);
                if (f != null) {
                    u = f.optString("url", "");
                    name = f.optString("filename", "");
                    sz = f.optLong("size", 0);
                }
            }
            String plat = "";
            String disk = "";
            JSONArray disks = v.optJSONArray("disk_urls");
            if (disks != null) {
                for (int i = 0; i < disks.length(); i++) {
                    JSONObject d = disks.optJSONObject(i);
                    if (d == null) continue;
                    String du = d.optString("url", "");
                    if (du != null && !du.isBlank()) {
                        disk = du;
                        plat = d.optString("platform", "");
                        break;
                    }
                }
            }
            if (disk.isBlank() && isNetDisk(u)) {
                disk = u;
                plat = platformOf(u);
            }
            url = u;
            diskUrl = disk;
            platform = plat;
            filename = name;
            size = sz;
        }

        /** 启动器能不能自己把文件拉下来。 */
        public boolean directDownloadable() {
            return !diskOnly && url != null && !url.isBlank() && !isNetDisk(url);
        }
    }

    /** 一次搜索结果：本页条目 + 站点给出的命中总数（用于「已显示 x / 共 y」）。 */
    public static final class Result {
        public final List<Item> items;
        public final int total;

        Result(List<Item> items, int total) {
            this.items = items;
            this.total = total;
        }
    }

    /**
     * 搜索地图。
     *
     * @param query  关键词，空串表示不过滤
     * @param index  排序：downloads（热门）/ newest（最新）/ relevance
     * @param offset 从第几条开始（分页用）
     * @param limit  每页条数
     */
    public static Result search(String query, String index, int offset, int limit) throws Exception {
        StringBuilder u = new StringBuilder(API)
                .append("/search?limit=").append(limit)
                .append("&offset=").append(offset)
                .append("&facets=").append(URLEncoder.encode("[[\"project_type:map\"]]", StandardCharsets.UTF_8));
        // relevance 是默认排序，带上无意义，省略可少一次参数校验失败的可能
        if (index != null && !index.isBlank() && !"relevance".equals(index)) {
            u.append("&index=").append(URLEncoder.encode(index, StandardCharsets.UTF_8));
        }
        if (query != null && !query.isBlank()) {
            u.append("&query=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        }
        String body = Http.getText(u.toString());
        JSONObject o = new JSONObject(body);
        JSONArray hits = o.optJSONArray("hits");
        List<Item> out = new ArrayList<>();
        if (hits != null) {
            for (int i = 0; i < hits.length(); i++) {
                JSONObject h = hits.optJSONObject(i);
                if (h == null) continue;
                if (!"map".equals(h.optString("project_type", ""))) continue;
                out.add(new Item(h));
            }
        }
        int total = o.optInt("total_hits", out.size());
        return new Result(out, total);
    }

    /** 取某个版本的下载信息。 */
    public static Release release(String versionId) throws Exception {
        if (versionId == null || versionId.isBlank()) throw new IllegalArgumentException("缺少版本 ID");
        String body = Http.getText(API + "/version/" + URLEncoder.encode(versionId, StandardCharsets.UTF_8));
        JSONArray arr = new JSONArray(body);
        if (arr.length() == 0) throw new IllegalStateException("该地图没有可用版本");
        return new Release(arr.getJSONObject(0));
    }

    /** 时间字符串 → 毫秒；失败返回 0。 */
    private static long parseTime(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli();
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    /** 判断是否为网盘分享链接。 */
    private static boolean isNetDisk(String url) {
        if (url == null) return false;
        String h = hostOf(url);
        return h.contains("quark.cn") || h.contains("baidu.com") || h.contains("aliyundrive")
                || h.contains("pan.") || h.contains("123pan") || h.contains("lanzou");
    }

    /** 网盘平台名（中文展示用）。 */
    private static String platformOf(String url) {
        String h = hostOf(url);
        if (h.contains("quark")) return "夸克网盘";
        if (h.contains("baidu")) return "百度网盘";
        if (h.contains("aliyundrive")) return "阿里云盘";
        if (h.contains("123pan")) return "123 网盘";
        if (h.contains("lanzou")) return "蓝奏云";
        return "网盘";
    }

    /** 网盘平台名（对外，UI 展示Release.platform 用）。 */
    public static String platformLabel(String platformOrUrl) {
        if (platformOrUrl == null || platformOrUrl.isBlank()) return "网盘";
        String p = platformOrUrl.toLowerCase();
        if (p.contains("quark")) return "夸克网盘";
        if (p.contains("baidu")) return "百度网盘";
        if (p.contains("aliyun")) return "阿里云盘";
        if (p.contains("123pan")) return "123 网盘";
        if (p.contains("lanzou")) return "蓝奏云";
        if (p.startsWith("http")) return platformOf(platformOrUrl);
        return "网盘";
    }

    private static String hostOf(String url) {
        try {
            String h = java.net.URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /** 下载数/关注数：2883 → 2,883。 */
    public static String num(int n) {
        return String.format("%,d", n);
    }

    /** 把时间戳转成「更新于 5 个月前」这种站点同款相对时间。 */
    public static String ago(long millis) {
        if (millis <= 0) return "未知";
        long diff = System.currentTimeMillis() - millis;
        if (diff < 0) diff = 0;
        long min = diff / 60000L;
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        long hour = min / 60;
        if (hour < 24) return hour + " 小时前";
        long day = hour / 24;
        if (day < 30) return day + " 天前";
        long month = day / 30;
        if (month < 12) return month + " 个月前";
        return (month / 12) + " 年前";
    }
}
