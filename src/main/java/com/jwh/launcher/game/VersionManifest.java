package com.jwh.launcher.game;

import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.Json;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft 官方版本清单（piston-meta）。失败时回退使用本地缓存。
 */
public class VersionManifest {

    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    public record Version(String id, String type, String url, String releaseTime, String sha1) {
        public boolean isRelease() {
            return "release".equals(type);
        }

        public boolean isSnapshot() {
            return "snapshot".equals(type);
        }

        /** 愚人节版本：4 月 1 日发布的快照（26w14a / 25w14craftmine / 3D Shareware v1.34 等）。
         *  注意排除 4 月 1 日发布的正式版（如 26.1.1），规则为「快照 且 发布日为 04-01」。 */
        public boolean isAprilFools() {
            if (!"snapshot".equals(type) || releaseTime == null) return false;
            try {
                return releaseTime.length() >= 10
                        && releaseTime.substring(5, 7).equals("04")
                        && releaseTime.substring(8, 10).equals("01");
            } catch (Exception e) {
                return false;
            }
        }

        /** 远古版：官方清单中的 old_beta（Beta 1.8.1 及更早）与 old_alpha。 */
        public boolean isOld() {
            return "old_beta".equals(type) || "old_alpha".equals(type);
        }

        public String typeLabel() {
            if (isAprilFools()) return "愚人节";
            return switch (type) {
                case "release" -> "正式版";
                case "snapshot" -> "快照";
                case "old_beta", "old_alpha" -> "远古版";
                default -> type;
            };
        }
    }

    public List<Version> versions = new ArrayList<>();
    public String latestRelease;
    public String latestSnapshot;
    public long fetchedAt;

    /** 拉取版本清单，失败时尝试本地缓存；都失败则抛异常。 */
    public static VersionManifest fetch(Path cacheFile) throws Exception {
        return fetch(cacheFile, MANIFEST_URL);
    }

    /** 使用指定清单地址（支持官方 / BMCLAPI 镜像）。
     *  缓存优先：本地缓存 6 小时内直接使用（每次打开下载中心秒开，不再等网络）；过期则网络刷新并回写缓存；网络失败回退缓存。 */
    public static VersionManifest fetch(Path cacheFile, String manifestUrl) throws Exception {
        if (cacheFile != null && Files.exists(cacheFile)) {
            try {
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile).toMillis();
                if (age < 6 * 3600 * 1000L) {
                    return parse(Json.obj(Files.readString(cacheFile, StandardCharsets.UTF_8)));
                }
            } catch (Exception ignored) {
                // 缓存损坏则忽略，走网络刷新
            }
        }
        try {
            // 当前源与另一源两路并发抢答（通用思路：镜像/官方谁快用谁，慢源不阻塞清单刷新）
            String alt = manifestUrl.contains("bmclapi2")
                    ? MANIFEST_URL
                    : "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json";
            JSONObject o = Json.obj(Http.getTextRace(manifestUrl, alt));
            if (cacheFile != null) {
                Files.createDirectories(cacheFile.getParent());
                Files.writeString(cacheFile, o.toString(2), StandardCharsets.UTF_8);
            }
            return parse(o);
        } catch (Exception netEx) {
            if (cacheFile != null && Files.exists(cacheFile)) {
                try {
                    return parse(Json.obj(Files.readString(cacheFile, StandardCharsets.UTF_8)));
                } catch (Exception cacheEx) {
                    throw netEx;
                }
            }
            throw netEx;
        }
    }

    private static VersionManifest parse(JSONObject o) {
        VersionManifest m = new VersionManifest();
        JSONObject latest = Json.optObj(o, "latest");
        if (latest != null) {
            m.latestRelease = latest.optString("release", "");
            m.latestSnapshot = latest.optString("snapshot", "");
        }
        JSONArray arr = o.optJSONArray("versions");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject v = arr.getJSONObject(i);
                m.versions.add(new Version(
                        v.optString("id"),
                        v.optString("type"),
                        v.optString("url"),
                        v.optString("releaseTime"),
                        v.optString("sha1")));
            }
        }
        m.fetchedAt = System.currentTimeMillis();
        return m;
    }
}
