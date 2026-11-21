import com.jwh.launcher.download.ArtifactProbe;
import com.jwh.launcher.download.DownloadLog;
import com.jwh.launcher.download.Downloader;
import com.jwh.launcher.util.Http;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 六类内容真下载冒烟：每次发版前跑一遍，替代"只验报障那一条路"的做法。
 *
 * <p>用法（工程根目录）：
 * <pre>
 *   javac -encoding UTF-8 -cp target/JWH-Launcher.jar -d %TEMP%\jwh-probe\probe-classes tools\SmokeSix.java
 *   java -cp "%TEMP%\jwh-probe\probe-classes;target\JWH-Launcher.jar" SmokeSix
 * </pre>
 * 输出每类一行 CATEGORY 结果 + 失败明细，退出码非 0 表示有类别未通过。
 */
public class SmokeSix {

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "jwh-probe", "smoke6");
        Files.createDirectories(dir);

        int failed = 0;

        // 1) 游戏版本元数据（JSON）
        failed += run(dir, "游戏版本.json", ArtifactProbe.Kind.JSON, "26.3.json",
                "https://piston-meta.mojang.com/v1/packages/80608ff6753705b6a82e2cb86a5937d4d233abd4/26.3.json",
                "https://bmclapi2.bangbang93.com/version/26.3/json");

        // 2) 加载器产物（JAR）：NeoForge universal
        failed += run(dir, "加载器.jar", ArtifactProbe.Kind.JAR, "neoforge-21.1.265-universal.jar",
                "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/21.1.265/neoforge-21.1.265-universal.jar",
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.265/neoforge-21.1.265-universal.jar");

        // 3~6) mod / 光影 / 材质 / 整合包：从 Modrinth 取当前真实文件，避免写死会过期的链接
        failed += modrinth(dir, "mod", "mod");
        failed += modrinth(dir, "光影", "shader");
        failed += modrinth(dir, "材质", "resourcepack");
        failed += modrinth(dir, "整合包", "modpack");

        System.out.println();
        System.out.println("SUMMARY failed=" + failed + "/6");
        System.out.println("LOG-TAIL:");
        for (String line : DownloadLog.recent(20)) System.out.println("  " + line);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static int run(Path dir, String label, ArtifactProbe.Kind kind, String filename, String... urls) {
        Path target = dir.resolve(filename);
        long t0 = System.currentTimeMillis();
        boolean ok = Downloader.fetch(target, -1, kind, label, null,
                Downloader.Policy.of(120, 300), urls);
        long ms = System.currentTimeMillis() - t0;
        long size = ok ? sizeOf(target) : 0;
        System.out.println(String.format("CATEGORY %-10s ok=%-5s size=%-10d ms=%d", label, ok, size, ms));
        return ok ? 0 : 1;
    }

    private static int modrinth(Path dir, String label, String projectType) {
        String pid = null;
        String url = null;
        String filename = null;
        long size = -1;
        try {
            String facets = URLEncoder.encode("[[\"project_type:" + projectType + "\"]]", StandardCharsets.UTF_8);
            String search = "https://api.modrinth.com/v2/search?limit=1&index=downloads&facets=" + facets;
            JSONObject s = new JSONObject(Http.getText(search));
            JSONArray hits = s.optJSONArray("hits");
            if (hits == null || hits.isEmpty()) throw new IllegalStateException("no-hit");
            pid = hits.getJSONObject(0).optString("project_id");

            // /project/{id}/version 返回的是数组（按发布时间倒序）
            JSONArray vers = new JSONArray(Http.getText("https://api.modrinth.com/v2/project/" + pid + "/version"));
            JSONObject first = vers.getJSONObject(0);
            // 优先取该版本的主文件（primary=true），否则取第一个
            JSONArray files = first.getJSONArray("files");
            JSONObject pick = files.getJSONObject(0);
            for (int i = 0; i < files.length(); i++) {
                if (files.getJSONObject(i).optBoolean("primary", false)) {
                    pick = files.getJSONObject(i);
                    break;
                }
            }
            url = pick.optString("url");
            filename = pick.optString("filename");
            size = pick.optLong("size", -1);
        } catch (Exception e) {
            System.out.println(String.format("CATEGORY %-10s ok=false  reason=api:%s", label, e.getMessage()));
            return 1;
        }
        if (url == null || url.isBlank() || filename == null || filename.isBlank()) {
            System.out.println(String.format("CATEGORY %-10s ok=false  reason=no-file", label));
            return 1;
        }
        Path target = dir.resolve(filename);
        ArtifactProbe.Kind kind = ArtifactProbe.Kind.of(target, size);
        // 整合包是 .mrpack（本质 ZIP），扩展名推断不出来，显式指定
        if ("modpack".equals(projectType)) kind = ArtifactProbe.Kind.ZIP;
        long t0 = System.currentTimeMillis();
        boolean ok = Downloader.fetch(target, size, kind, label + "/" + filename, null,
                Downloader.Policy.of(120, 600), url);
        long ms = System.currentTimeMillis() - t0;
        long got = ok ? sizeOf(target) : 0;
        System.out.println(String.format("CATEGORY %-10s ok=%-5s size=%-10d ms=%-7d file=%s",
                label, ok, got, ms, filename));
        return ok ? 0 : 1;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
