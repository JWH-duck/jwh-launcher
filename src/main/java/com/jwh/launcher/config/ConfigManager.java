package com.jwh.launcher.config;

import com.jwh.launcher.util.Http;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置持久化：读写固定用户目录下的 config.json（%APPDATA%\JWH-Launcher\config.json）。
 * 固定位置保证 jar / exe / 不同启动方式下配置始终一致，不会随启动器文件位置变化而丢配置。
 */
public class ConfigManager {

    private static Path configFile;

    /** 定位配置文件：优先 %APPDATA%\JWH-Launcher，其次 ~/.jwh-launcher。 */
    public static Path configPath() {
        if (configFile != null) return configFile;
        Path dir = null;
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            dir = Path.of(appData).resolve("JWH-Launcher");
        } else {
            dir = Path.of(System.getProperty("user.home", ".")).resolve(".jwh-launcher");
        }
        configFile = dir.resolve("config.json");
        return configFile;
    }

    public static LauncherConfig load() {
        Path p = configPath();
        if (Files.exists(p)) {
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                return LauncherConfig.fromJson(new JSONObject(text));
            } catch (Exception e) {
                // 配置损坏则回退默认
            }
        }
        return new LauncherConfig();
    }

    public static void save(LauncherConfig c) {
        Path p = configPath();
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, c.toJson().toString(2), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 供测试/开发时指定配置路径。 */
    public static void setConfigFile(Path p) {
        configFile = p;
    }
}
