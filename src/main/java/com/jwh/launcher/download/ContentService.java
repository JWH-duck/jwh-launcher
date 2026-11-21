package com.jwh.launcher.download;

import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.util.Http;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 内容下载服务：把 Mod / 光影包 / 材质包 下载到目标版本的对应目录。
 * 同时提供 OptiFine 下载（官方无开放 API，采用社区通用的 BMCLAPI 镜像，见 README 说明）。
 */
public class ContentService {

    private final LauncherConfig config;

    public ContentService(LauncherConfig config) {
        this.config = config;
    }

    /** 内容类型 → 版本目录下的子目录。 */
    public String subdirFor(ModrinthApi.Type type) {
        return switch (type) {
            case MOD -> "mods";
            case SHADER -> "shaderpacks";
            case RESOURCEPACK -> "resourcepacks";
            case MODPACK -> "mods"; // 整合包由 ModpackInstaller 独立处理，此处仅兜底
        };
    }

    /** 目标版本的游戏数据目录（含版本隔离逻辑）。 */
    public Path gameDir(String versionId) {
        return config.gameDirFor(versionId);
    }

    /** 下载一个文件到目标版本对应目录。返回落盘路径。 */
    public Path downloadTo(ModrinthApi.Type type, String versionId, String url, String filename) throws Exception {
        return downloadTo(type, versionId, url, filename, null);
    }

    /** 下载一个文件到目标版本对应目录（支持进度/取消控制）。取消时删除未完整文件。 */
    public Path downloadTo(ModrinthApi.Type type, String versionId, String url, String filename,
                           com.jwh.launcher.util.Http.DownloadControl ctl) throws Exception {
        Path dir = gameDir(versionId).resolve(subdirFor(type));
        Files.createDirectories(dir);
        Path target = dir.resolve(filename);
        // mod / 光影 / 材质 过去是"单源 + 大小未知 + 只验非空"，所以经常"下完了但用不了"。
        // 现在统一走 Downloader：jar 会做 ZIP 头 + CRC 校验，失败自动换源重试，并写进 downloads.log。
        ArtifactProbe.Kind kind = ArtifactProbe.Kind.of(target, -1);
        boolean ok = Downloader.fetch(target, -1, kind, filename, ctl,
                Downloader.Policy.of(60, 300), url);
        if (ctl != null && ctl.isCancelled()) {
            throw new java.io.IOException("下载已取消");
        }
        if (!ok) throw new java.io.IOException("下载失败：" + filename);
        return target;
    }

    // ---------- OptiFine（官方下载页含 JS 校验，社区统一走 BMCLAPI 镜像） ----------

    public record OptifineVersion(String filename, String mcversion, String type, String patch,
                                  String downloadUrl, boolean latest) {
    }

    /** 查询某 MC 版本的 OptiFine 版本列表。 */
    public List<OptifineVersion> listOptifine(String mcVersion) throws Exception {
        String body = Http.getText("https://bmclapi2.bangbang93.com/optifine/" + mcVersion);
        JSONArray arr = new JSONArray(body);
        List<OptifineVersion> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new OptifineVersion(
                    o.optString("filename"),
                    o.optString("mcversion"),
                    o.optString("type"),
                    o.optString("patch"),
                    o.optString("downloadMirror", o.optString("url")),
                    o.optBoolean("latest", false)));
        }
        return out;
    }

    /** 下载 OptiFine jar 到缓存目录（OptiFine 为安装器形式，需运行 jar 安装，见界面提示）。 */
    public Path downloadOptifine(String downloadUrl, String filename) throws Exception {
        return downloadOptifine(downloadUrl, filename, null);
    }

    public Path downloadOptifine(String downloadUrl, String filename,
                                 com.jwh.launcher.util.Http.DownloadControl ctl) throws Exception {
        Path dir = config.minecraftPath().resolve("jwh-cache");
        Files.createDirectories(dir);
        Path target = dir.resolve(filename);
        // OptiFine 是安装器 jar：走 INSTALLER 校验（PK 头在偏移 0 + 全条目 CRC），
        // 镜像返回 HTML 错误页时会被立刻识别并换源，而不是留个坏包等运行报 corrupt。
        boolean ok = Downloader.fetch(target, -1, ArtifactProbe.Kind.INSTALLER, filename, ctl,
                Downloader.Policy.of(150, 300), downloadUrl);
        if (ctl != null && ctl.isCancelled()) throw new java.io.IOException("OptiFine 下载已取消");
        if (!ok) throw new java.io.IOException("OptiFine 下载失败");
        return target;
    }
}
