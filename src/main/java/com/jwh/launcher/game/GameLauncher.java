package com.jwh.launcher.game;

import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.util.Json;
import com.jwh.launcher.util.OSUtil;
import com.jwh.launcher.util.SkinServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 游戏启动核心：解析版本 JSON、组装 JVM 与游戏参数、启动进程并转发日志。
 * 支持正版（msa）与离线（legacy）两种身份。
 */
public class GameLauncher {

    private final LauncherConfig config;

    public GameLauncher(LauncherConfig config) {
        this.config = config;
    }

    /** NeoForge/Forge 启动必须的 JDK 模块开放参数（反射访问 IMPL_LOOKUP 等内部 API，HMCL/PCL 同款）。 */
    private static final String[] LOADER_ADD_OPENS = {
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens", "java.base/java.io=ALL-UNNAMED",
            "--add-opens", "java.base/java.net=ALL-UNNAMED",
            "--add-opens", "java.base/java.nio=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens", "java.base/java.util.zip=ALL-UNNAMED",
            "--add-opens", "java.base/java.text=ALL-UNNAMED",
            "--add-opens", "java.base/java.math=ALL-UNNAMED",
            "--add-opens", "java.base/java.time=ALL-UNNAMED",
            "--add-opens", "java.base/sun.security.util=ALL-UNNAMED",
            "--add-opens", "java.base/sun.reflect=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/javax.swing=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt.image=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.font=ALL-UNNAMED",
    };

    /**
     * NeoForge 1.21+ 官方启动结构（来自官方 installer 的 data/win_args.txt，已实测跑通）。
     * 官方结构必须走 bootstraplauncher：-p 模块路径 + 定向 JPMS 开放 + 官方 ignoreList
     * + legacyClassPath（84 个库）+ lwjgl natives 目录 + BootstrapLauncher 主类 + neoforgeclient 目标。
     * 依赖启动器内置清单资源（neoforge-21.1.264-lcp.txt / -natives.txt），不依赖网络元数据。
     */
    private static final List<String> NEOFORGE_MODULE_PATH = List.of(
            "cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar",
            "cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
            "org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar",
            "org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar",
            "org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar",
            "org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar",
            "org/ow2/asm/asm/9.10.1/asm-9.10.1.jar",
            "net/neoforged/JarJarFileSystems/0.4.1/JarJarFileSystems-0.4.1.jar");

    /** 官方 ignoreList：这些模块 jar 不进 legacyClassPath 的"游戏侧"，交给模块系统。 */
    private static final String NEOFORGE_IGNORE_LIST =
            "bootstraplauncher-2.0.2.jar,securejarhandler-3.0.8.jar,"
                    + "asm-commons-9.10.1.jar,asm-util-9.10.1.jar,asm-analysis-9.10.1.jar,"
                    + "asm-tree-9.10.1.jar,asm-9.10.1.jar,JarJarFileSystems-0.4.1.jar";

    /** 官方定向 JPMS 开放/导出（只对 securejarhandler 模块开放，勿改回 ALL-UNNAMED）。 */
    private static final String[][] NEOFORGE_JPMS = {
            {"--add-opens", "java.base/java.util.jar=cpw.mods.securejarhandler"},
            {"--add-opens", "java.base/java.lang.invoke=cpw.mods.securejarhandler"},
            {"--add-exports", "java.base/sun.security.util=cpw.mods.securejarhandler"},
            {"--add-exports", "jdk.naming.dns/com.sun.jndi.dns=java.naming"},
    };

    /** 已组装完成的启动命令。 */
    public record LaunchCommand(List<String> command, Path workingDir) {
    }

    /** 预检查缺失文件，返回缺失描述列表（空 = 就绪）。 */
    public List<String> checkMissing(String versionId) throws IOException {
        List<String> missing = new ArrayList<>();
        Path vd = config.versionsPath().resolve(versionId);
        if (!Files.exists(vd.resolve(versionId + ".json"))) {
            missing.add("缺少版本 JSON，请先安装该版本");
            return missing;
        }
        Path jar = vd.resolve(versionId + ".jar");
        if (!Files.exists(jar)) {
            // 加载器版本可能继承基础版本的主程序
            boolean inheritedOk = false;
            try {
                JSONObject v = new JSONObject(Files.readString(vd.resolve(versionId + ".json"), StandardCharsets.UTF_8));
                String inh = v.optString("inheritsFrom", "");
                if (!inh.isBlank()) {
                    Path baseJar = config.versionsPath().resolve(inh).resolve(inh + ".jar");
                    inheritedOk = Files.exists(baseJar);
                }
            } catch (Exception ignored) {
            }
            if (!inheritedOk) {
                missing.add("缺少游戏主程序 jar，请先安装或修复该版本");
            }
        }
        return missing;
    }

    /**
     * 该版本官方要求的 Java 主版本：
     * 优先读版本 JSON 的 javaVersion.majorVersion（官方标注，随新版本自动更新）；
     * 缺失时用推荐表兜底。
     */
    public int requiredJavaMajor(String versionId) {
        // 加载器实例（Forge/Fabric/Quilt…）自己的 json 往往没有 javaVersion，
        // 语义写在 inheritsFrom 指向的原版版本里，所以沿继承链往上找，最多三层。
        String cur = versionId;
        for (int depth = 0; depth < 3 && cur != null && !cur.isBlank(); depth++) {
            try {
                Path p = config.versionsPath().resolve(cur).resolve(cur + ".json");
                if (!Files.exists(p)) break;
                JSONObject v = new JSONObject(Files.readString(p, StandardCharsets.UTF_8));
                JSONObject jv = Json.optObj(v, "javaVersion");
                if (jv != null && jv.has("majorVersion")) {
                    int m = jv.optInt("majorVersion", -1);
                    if (m > 0) return m;
                }
                cur = v.optString("inheritsFrom", "");
            } catch (Exception ignored) {
                break;
            }
        }
        // 还没读到就用实例的基础版本号兜底（1.21.1-forge-52.1.21 → 1.21.1）
        try {
            for (LocalVersions.LocalVersion x : new LocalVersions(config).scan()) {
                if (x.id().equals(versionId)) {
                    int m = JavaFinder.recommendJavaMajor(x.mcBase());
                    if (m > 0) return m;
                }
            }
        } catch (Exception ignored) {
        }
        return JavaFinder.recommendJavaMajor(versionId);
    }

    /** 是否 1.21+ NeoForge：按继承的基础 MC 版本判定（1.21.0 起走内置清单 JPMS 分支）；解析失败保守返回 false。 */
    private static boolean isModernNeoForge(JSONObject v) {
        String inh = v == null ? "" : v.optString("inheritsFrom", "");
        if (inh.isBlank()) return false;
        String[] p = inh.split("[^0-9]+");
        try {
            int major = p.length > 0 ? Integer.parseInt(p[0]) : 0;
            int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return major > 1 || (major == 1 && minor >= 21);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 直达服务器（多人游戏）用：本线程本次组装是否带 quickPlay，影响 version.json 里 rules 的 features 判定。 */
    private final ThreadLocal<Boolean> quickPlayActive = ThreadLocal.withInitial(() -> false);

    /** 组装启动命令（普通启动：进游戏停在主菜单）。 */
    public LaunchCommand buildCommand(String versionId, LauncherConfig.Account account,
                                      Path javaExe, SkinServer skin) throws IOException {
        return buildCommand(versionId, account, javaExe, skin, null);
    }

    /**
     * 组装启动命令，可选直达服务器。
     *
     * @param quickPlay 服务器地址（{@code host} 或 {@code host:port}）；null/空 = 普通启动
     */
    public LaunchCommand buildCommand(String versionId, LauncherConfig.Account account,
                                      Path javaExe, SkinServer skin, String quickPlay) throws IOException {
        boolean qp = quickPlay != null && !quickPlay.isBlank();
        quickPlayActive.set(qp);
        try {
            return buildCommand0(versionId, account, javaExe, skin, qp ? quickPlay.trim() : "");
        } finally {
            quickPlayActive.set(false);
        }
    }

    /** 组装启动命令。account 可为 null（将尝试不传身份参数，一般不推荐）。skin 为离线皮肤注入服务（可 null）。 */
    private LaunchCommand buildCommand0(String versionId, LauncherConfig.Account account,
                                        Path javaExe, SkinServer skin, String quickPlay) throws IOException {
        JSONObject v = new JSONObject(Files.readString(
                config.versionsPath().resolve(versionId).resolve(versionId + ".json"),
                StandardCharsets.UTF_8));
        // NeoForge 1.21+（inheritsFrom 1.21+，version.json 无自带库/jvm）走官方 bootstraplauncher
        // 内置清单 JPMS 结构；1.20.2 及更早（version.json 自带依赖库与完整 jvm 模块参数）走下方
        // version.json 驱动的老模块模式，避免误用 21.x 内置库清单造成跨版本错配。
        if (versionId.startsWith("neoforge-") && isModernNeoForge(v)) {
            return buildNeoForgeCommand(versionId, account, javaExe, skin, quickPlay);
        }

        // 加载器版本（NeoForge/Fabric 等）通过 inheritsFrom 继承基础版本（如 1.21.1）：
        // 启动时合并基础版本的 jar、依赖库与 assets 索引
        JSONObject base = null;
        String inherited = v.optString("inheritsFrom", "");
        if (!inherited.isBlank()) {
            Path bp = config.versionsPath().resolve(inherited).resolve(inherited + ".json");
            if (Files.exists(bp)) {
                base = new JSONObject(Files.readString(bp, StandardCharsets.UTF_8));
            }
        }
        JSONArray mergedLibs = mergeLibraries(base, v);

        // Forge 老格式（1.20.x 及更早）走 bootstraplauncher 模块模式：version.json 的
        // arguments.jvm 含 -p + --add-modules ALL-MODULE-PATH。该模式下 MC 主程序由
        // MinecraftLocator 按 --fml.mcpVersion 从 libraries/net/minecraft/client/ 的
        // client-*-srg.jar 加载（官方安装器生成），classpath 若再带继承版本的原版 jar，
        // 模块解析会报 ResolutionException（minecraft 与 _1._20._1 重复导出 MC 包），
        // 因此模块模式下不追加继承版本的原版 jar。
        boolean moduleMode = false;
        JSONObject args0 = Json.optObj(v, "arguments");
        if (args0 != null && args0.has("jvm")) {
            JSONArray jvmArr = args0.optJSONArray("jvm");
            if (jvmArr != null) {
                for (int i = 0; i < jvmArr.length() && !moduleMode; i++) {
                    Object o = jvmArr.get(i);
                    if (o instanceof String s) {
                        if ("--add-modules".equals(s)) moduleMode = true;
                    } else if (o instanceof JSONObject obj) {
                        Object val = obj.opt("value");
                        if (val instanceof JSONArray arr) {
                            for (int j = 0; j < arr.length(); j++) {
                                if ("--add-modules".equals(arr.getString(j))) { moduleMode = true; break; }
                            }
                        } else if (val instanceof String sv && "--add-modules".equals(sv)) {
                            moduleMode = true;
                        }
                    }
                }
            }
        }

        // --- classpath：主 jar + 全部规则过滤后的库 ---
        List<String> classpath = new ArrayList<>();
        Path ownJar = config.versionsPath().resolve(versionId).resolve(versionId + ".jar");
        if (Files.exists(ownJar)) {
            classpath.add(ownJar.toString());
        }
        // 加载器版本的 ownJar 已是合并/补丁后的客户端（Forge/NeoForge 的 patched client、
        // Fabric/Quilt 直接就是原版 jar），完整包含原版类。若再追加继承版本的原版 jar，
        // 模块模式启动（Forge 1.20.x 的 -p + --add-modules ALL-MODULE-PATH）会出现两个
        // MC jar 同时参与模块解析，报 ResolutionException（minecraft 与 _1._20._1
        // 重复导出 net.minecraft.client.main）。模块模式下 MC 类由 MinecraftLocator
        // 提供（srg jar），也不回退原版 jar；仅非模块模式且本版本没有自己的 jar 时
        // 才追加继承版本的原版 jar。
        if (base != null && !Files.exists(ownJar) && !moduleMode) {
            Path baseJar = config.versionsPath().resolve(inherited).resolve(inherited + ".jar");
            if (Files.exists(baseJar)) {
                classpath.add(baseJar.toString());
            }
        }
        Set<String> nativeDirs = new LinkedHashSet<>();
        JSONArray libs = mergedLibs;
        if (libs != null) {
            for (int i = 0; i < libs.length(); i++) {
                JSONObject lib = libs.getJSONObject(i);
                if (!rulesAllow(lib.optJSONArray("rules"))) continue;
                String name = lib.optString("name");
                if (name.isBlank()) continue;
                String[] parts = name.split(":");
                // 优先用 downloads.artifact.path（老加载器 profile 的 name 带 @jar 后缀，
                // 按坐标拼路径会把 @jar 写进文件名导致漏库）；无 path 时按坐标兜底并去掉 @jar。
                JSONObject dl0 = Json.optObj(lib, "downloads");
                JSONObject art0 = dl0 == null ? null : Json.optObj(dl0, "artifact");
                String rel;
                String path0 = art0 == null ? "" : art0.optString("path", "");
                if (!path0.isBlank()) {
                    rel = path0;
                } else {
                    String ver = parts[2].replace("@jar", "");
                    String classifier = parts.length > 3 ? "-" + parts[3].replace("@jar", "") : "";
                    rel = parts[0].replace('.', '/') + "/" + parts[1] + "/" + ver
                            + "/" + parts[1] + "-" + ver + classifier + ".jar";
                }
                Path jar = config.librariesPath().resolve(rel);
                if (Files.exists(jar)) {
                    classpath.add(jar.toString());
                }
                // 原生库目录
                JSONObject downloads = Json.optObj(lib, "downloads");
                if (downloads != null && downloads.has("classifiers")) {
                    String osName = switch (OSUtil.currentOs()) {
                        case WINDOWS -> "natives-windows";
                        case MACOS -> "natives-osx";
                        default -> "natives-linux";
                    };
                    String nativeKey = osName + (OSUtil.arch().equals("64") ? "-64" : "");
                    if (!downloads.getJSONObject("classifiers").has(nativeKey)) {
                        nativeKey = osName;
                    }
                    if (downloads.getJSONObject("classifiers").has(nativeKey)) {
                        Path nDir = config.librariesPath()
                                .resolve(parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                                        + "/" + parts[1] + "-" + parts[2] + "-" + nativeKey + "-natives");
                        if (Files.isDirectory(nDir)) {
                            nativeDirs.add(nDir.toString());
                        }
                    }
                }
            }
        }

        // --- 命令 ---
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());

        // JVM 内存
        cmd.add("-Xms" + Math.max(512, config.minMemory) + "M");
        cmd.add("-Xmx" + Math.max(1024, config.maxMemory) + "M");
        // NeoForge/Forge 加载器版本：开放 JDK 模块，允许反射访问内部 API（否则 UnionFileSystem 初始化崩溃）
        if (versionId.startsWith("neoforge-") || versionId.startsWith("forge-")
                || versionId.contains("-forge-") || versionId.contains("-neoforge-")) {
            cmd.addAll(List.of(LOADER_ADD_OPENS));
        }
        // 补充命名模块开放（NeoForge 1.21+ 的 version.json 自带 --add-opens java.base/java.lang.invoke=
        // cpw.mods.securejarhandler 等，这里再补一层保险，覆盖更多 JDK 内部包）
        if (versionId.startsWith("neoforge-")) {
            String[] moduleTargets = {
                    "cpw.mods.securejarhandler", "cpw.mods.bootstraplauncher"
            };
            String[] moduleOpens = {
                    "java.base/java.lang", "java.base/java.lang.invoke",
                    "java.base/java.lang.reflect", "java.base/java.io",
                    "java.base/java.net", "java.base/java.nio", "java.base/java.util",
                    "java.base/java.util.concurrent", "java.base/java.util.zip"
            };
            for (String target : moduleTargets) {
                for (String open : moduleOpens) {
                    cmd.add("--add-opens");
                    cmd.add(open + "=" + target);
                }
            }
            // 关闭 NeoForge 早期显示窗口（fancy loading screen）：
            // 其在部分环境下崩溃（DisplayWindow.updateModuleReads → NoSuchElementException），
            // NeoForge 官方文档（neoforged.net/meta/displayerrors）建议 earlyWindowControl=false。
            cmd.add("-Dfml.earlyWindowControl=false");
        }
        // 原生库
        if (!nativeDirs.isEmpty()) {
            cmd.add("-Djava.library.path=" + String.join(java.io.File.pathSeparator, nativeDirs));
        }
        // 自定义 JVM 参数
        if (config.jvmArgs != null && !config.jvmArgs.isBlank()) {
            for (String s : config.jvmArgs.trim().split("\\s+")) {
                if (!s.isBlank()) cmd.add(s);
            }
        }
        // 离线一律注入 authlib-injector：新版 MC（1.21.2+ / 26.x）已不支持 legacy 离线会话，
        // 启动时游戏会用 token 调 Mojang 服务器验证，验证失败自动进入“试玩 Demo”模式。
        // 通过 -javaagent 把会话请求重定向到本地 SkinServer，模拟正版认证 → 完整版。
        // 无论是否设置了皮肤都注入（有皮肤时同时实现换肤）。
        // LittleSkin 也走同一条路：agent 指向站点 API 根，游戏发电到皮肤站验身份 + 拉皮肤披风。
        boolean injected = false;
        String injectRoot = "";
        if (account != null && account.type == LauncherConfig.Account.Type.LITTLESKIN) {
            try {
                Path agent = SkinServer.exportAgent(ConfigManager.configPath().getParent());
                injectRoot = (account.lsApiRoot == null || account.lsApiRoot.isBlank())
                        ? "https://littleskin.cn/api/yggdrasil" : account.lsApiRoot;
                cmd.add("-javaagent:" + agent + "=" + injectRoot);
                injected = true;
            } catch (IOException e) {
                System.err.println("[JWH] 导出 authlib-injector 失败，LittleSkin 账号将回退离线模式：" + e.getMessage());
            }
        } else if (skin != null && account != null && account.type == LauncherConfig.Account.Type.OFFLINE) {
            try {
                Path agent = SkinServer.exportAgent(ConfigManager.configPath().getParent());
                cmd.add("-javaagent:" + agent + "=" + skin.apiRoot());
                injectRoot = skin.apiRoot();
                injected = true;
            } catch (IOException e) {
                System.err.println("[JWH] 导出 authlib-injector 失败，回退 legacy 模式：" + e.getMessage());
            }
        }

        // 参数模板
        Path gameDir = config.gameDirFor(versionId);
        Files.createDirectories(gameDir);
        // 预写 fml.toml（关闭早期显示窗口兜底）：NeoForge 官方 FAQ 指定改 config/fml.toml 的
        // earlyWindowControl=true→false；若该键缺失，FML 启动时会自动补 true。
        if (versionId.startsWith("neoforge-") || versionId.contains("-neoforge-")
                || versionId.startsWith("forge-") || versionId.contains("-forge-")) {
            try {
                Path cfgDir = gameDir.resolve("config");
                Files.createDirectories(cfgDir);
                Path cfg = cfgDir.resolve("fml.toml");
                String content = Files.exists(cfg) ? Files.readString(cfg) : "";
                if (content.contains("earlyWindowControl")) {
                    content = content.replaceAll("(?m)^(\\s*earlyWindowControl\\s*=\\s*).*$", "$1false");
                } else {
                    content += (content.isBlank() ? "" : "\n") + "\nearlyWindowControl=false\n";
                }
                Files.writeString(cfg, content);
            } catch (IOException e) {
                System.err.println("[JWH] 写 fml.toml 失败：" + e.getMessage());
            }
        }
        Path assetsRoot = config.assetsPath();
        String assetsIndex = v.optString("assets",
                base != null ? base.optString("assets", "legacy") : "legacy");
        String uuid = account == null ? OSUtil.offlineUuid("Steve") : account.uuid;
        // 离线换肤/完整模式会话令牌：注入时给非 0 令牌触发本地 hasJoined/profile 流程
        String token = account == null ? "0"
                : (account.accessToken == null || account.accessToken.isBlank() ? "0" : account.accessToken);
        // 离线本地注入：令牌用 uuid 去横线（本地 SkinServer 按它识别玩家）
        // LittleSkin 注入：令牌保持站点发的 accessToken，由 authlib-injector 拿去验身份
        if (injected && account != null && account.type == LauncherConfig.Account.Type.OFFLINE) {
            token = uuid.replace("-", "");
        }
        // 会话类型：正版=msa；LittleSkin/本地注入=mojang；注入失败回退 legacy（仅旧版 MC 可用）
        String userType;
        if (account != null && account.type == LauncherConfig.Account.Type.MICROSOFT) {
            userType = "msa";
        } else if (account != null && account.type == LauncherConfig.Account.Type.LITTLESKIN) {
            userType = injected ? "mojang" : "legacy";
        } else {
            userType = injected ? "mojang" : "legacy";
        }
        String userName = account == null ? "Steve" : account.userName;
        String versionType = v.optString("type", "release");

        java.util.Map<String, String> vars = new java.util.HashMap<>();
        vars.put("auth_player_name", userName);
        vars.put("version_name", versionId);
        vars.put("game_directory", gameDir.toString());
        vars.put("assets_root", assetsRoot.toString());
        vars.put("assets_index_name", assetsIndex);
        vars.put("auth_uuid", uuid.replace("-", ""));
        vars.put("auth_access_token", token);
        vars.put("auth_session", token);
        vars.put("user_type", userType);
        vars.put("version_type", versionType);
        vars.put("launcher_name", "JWH-Launcher");
        vars.put("launcher_version", "1.0");
        // NeoForge 1.21+ 的 arguments.jvm 需要 ${library_directory} 与 ${classpath_separator}
        vars.put("library_directory", config.librariesPath().toString());
        vars.put("classpath_separator", java.io.File.pathSeparator);
        // 1.20.0+ 模板含 --clientId/--xuid：clientId 必须非空，否则 Yggdrasil 认证服务初始化异常
        vars.put("clientid", "00000000-402b-4cd3-a82b-c45ab2f1d3f7");
        vars.put("auth_xuid", "");
        vars.put("resolution_width", String.valueOf(config.windowWidth));
        vars.put("resolution_height", String.valueOf(config.windowHeight));
        vars.put("quickPlayPath", "");
        vars.put("quickPlaySingleplayer", "");
        vars.put("quickPlayMultiplayer", quickPlay);
        vars.put("quickPlayRealms", "");

        // --- 游戏参数 ---
        List<String> gameArgs = new ArrayList<>();
        JSONObject arguments = Json.optObj(v, "arguments");
        if (arguments != null && arguments.has("game")) {
            // 1.13+ 新格式
            JSONArray game = arguments.optJSONArray("game");
            for (int i = 0; i < game.length(); i++) {
                Object o = game.get(i);
                if (o instanceof String s) {
                    gameArgs.add(replaceVars(s, vars));
                } else if (o instanceof JSONObject obj) {
                    if (!rulesAllow(obj.optJSONArray("rules"))) continue;
                    Object val = obj.opt("value");
                    if (val instanceof String s) {
                        gameArgs.add(replaceVars(s, vars));
                    } else if (val instanceof JSONArray arr) {
                        for (int j = 0; j < arr.length(); j++) {
                            gameArgs.add(replaceVars(arr.getString(j), vars));
                        }
                    }
                }
            }
        } else if (v.has("minecraftArguments")) {
            // 1.12.2 及以前
            String legacy = v.optString("minecraftArguments");
            for (String s : legacy.trim().split("\\s+")) {
                if (!s.isBlank()) gameArgs.add(replaceVars(s, vars));
            }
        }
        // 极老版本兜底
        if (gameArgs.isEmpty()) {
            gameArgs.addAll(List.of(
                    "--username", vars.get("auth_player_name"),
                    "--version", vars.get("version_name"),
                    "--gameDir", vars.get("game_directory"),
                    "--assetsDir", vars.get("assets_root"),
                    "--assetIndex", vars.get("assets_index_name"),
                    "--uuid", vars.get("auth_uuid"),
                    "--accessToken", vars.get("auth_access_token"),
                    "--userType", vars.get("user_type"),
                    "--versionType", vars.get("version_type")));
        }

        // 身份参数兜底：部分加载器版本 json（如 Forge 1.20.5+ / 1.21.x）的 game arguments
        // 只带 --launchTarget，不含 --version/--accessToken 等，但主类启动必需
        // （joptsimple 校验 MissingRequiredOptionsException）。这里逐项检查缺失补上，
        // 已声明的不重复添加（避免 joptsimple 的 multiple arguments 报错）。
        String[][] identityArgs = {
                {"--username", "auth_player_name"},
                {"--version", "version_name"},
                {"--gameDir", "game_directory"},
                {"--assetsDir", "assets_root"},
                {"--assetIndex", "assets_index_name"},
                {"--uuid", "auth_uuid"},
                {"--accessToken", "auth_access_token"},
                {"--userType", "user_type"},
                {"--versionType", "version_type"},
        };
        for (String[] r : identityArgs) {
            if (!gameArgs.contains(r[0])) {
                gameArgs.add(r[0]);
                gameArgs.add(vars.get(r[1]));
            }
        }

        // --- 版本自带 JVM 参数（1.13+ arguments.jvm）---
        // NeoForge 1.21+ 的 version.json 自带完整 JPMS 启动参数：
        //   -DlibraryDirectory=${library_directory}、-p <module-path>、--add-modules ALL-MODULE-PATH、
        //   --add-opens/--add-exports（定向给 cpw.mods.securejarhandler）等。
        // 必须原样执行，否则报 "Missing libraryDirectory system property" 或缺少模块/开放参数。
        if (arguments != null && arguments.has("jvm")) {
            JSONArray jvm = arguments.optJSONArray("jvm");
            for (int i = 0; i < jvm.length(); i++) {
                Object o = jvm.get(i);
                if (o instanceof String s) {
                    String r = replaceVars(s, vars);
                    if (!r.isBlank()) cmd.add(r);
                } else if (o instanceof JSONObject obj) {
                    if (!rulesAllow(obj.optJSONArray("rules"))) continue;
                    Object val = obj.opt("value");
                    if (val instanceof String s) {
                        String r = replaceVars(s, vars);
                        if (!r.isBlank()) cmd.add(r);
                    } else if (val instanceof JSONArray arr) {
                        for (int j = 0; j < arr.length(); j++) {
                            String r = replaceVars(arr.getString(j), vars);
                            if (!r.isBlank()) cmd.add(r);
                        }
                    }
                }
            }
        }

        cmd.add("-cp");
        cmd.add(String.join(java.io.File.pathSeparator, classpath));
        cmd.add(v.optString("mainClass",
                base != null ? base.optString("mainClass", "net.minecraft.client.main.Main")
                        : "net.minecraft.client.main.Main"));
        // NeoForge 1.21+ 的 version.json 自带完整 fml 启动参数
        // （--fml.neoForgeVersion/--fml.fmlVersion/--fml.mcVersion/--fml.neoFormVersion/--launchTarget），
        // 这里不要再手动追加 --fml.*，否则 joptsimple 报 "multiple arguments for option fml.mcVersion"。
        cmd.addAll(gameArgs);

        verifyClasspathJars(classpath); // 启动前校验依赖库 ZIP 完整性（Fabric/Forge 扫描 classpath 时损坏 jar 直接崩溃）

        return new LaunchCommand(cmd, gameDir);
    }

    /**
     * NeoForge 1.21+ 官方结构启动命令：
     * -p 模块路径(8 引导 jar) + --add-modules ALL-MODULE-PATH + 定向 JPMS 开放
     * + 官方 ignoreList + -DlibraryDirectory/-DlegacyClassPath(84 库)
     * + -Dorg.lwjgl.librarypath(natives 解压目录) + BootstrapLauncher 主类 + neoforgeclient 目标
     * + --fml.*(从本地库文件名解析) + 标准游戏参数。全部参数来自官方 installer 的
     * data/win_args.txt（已实测跑通），依赖内置清单资源，不依赖网络元数据。
     */
    private LaunchCommand buildNeoForgeCommand(String versionId, LauncherConfig.Account account,
                                               Path javaExe, SkinServer skin, String quickPlay) throws IOException {
        Path libs = config.librariesPath();
        Path vd = config.versionsPath().resolve(versionId);
        String loaderVersion = versionId.substring("neoforge-".length());
        // 版本目录清单优先（安装时 GameInstaller 写入，任意 21.x 版本通用）；老安装回退内置 21.1.264
        List<String> lcpRels = readLinesIfExists(vd.resolve("neoforge-lcp.txt"),
                loadResourceLines("neoforge-21.1.264-lcp.txt"));
        List<String> nativesRels = readLinesIfExists(vd.resolve("neoforge-natives.txt"),
                loadResourceLines("neoforge-21.1.264-natives.txt"));
        List<String> moduleRels = readLinesIfExists(vd.resolve("neoforge-modules.txt"), NEOFORGE_MODULE_PATH);
        Map<String, String> nfArgs = readKeyValues(vd.resolve("neoforge-args.txt"));
        String ignoreList = nfArgs.getOrDefault("ignoreList", NEOFORGE_IGNORE_LIST);
        List<String[]> jpms = new ArrayList<>();
        for (int i = 0; ; i++) {
            String j = nfArgs.get("jpms" + i);
            if (j == null || j.isBlank()) break;
            int sp = j.indexOf(' ');
            if (sp > 0) jpms.add(new String[]{j.substring(0, sp), j.substring(sp + 1).trim()});
        }
        if (jpms.isEmpty()) {
            for (String[] p : NEOFORGE_JPMS) jpms.add(p);
        }
        // 官方 win_args 的 legacyClassPath 不含 lwjgl（lwjgl 随原版继承）；老安装清单可能缺失，
        // 启动时从基础版本 json 兜底补全 lwjgl 本体库，否则 FML earlydisplay 加载 GLFW 时
        // NoClassDefFoundError: org/lwjgl/system/Struct。
        String fmlMcForLwjgl = nfArgs.get("fml.mcVersion");
        String fmlNfFormForMc = nfArgs.get("fml.neoFormVersion");
        if (fmlMcForLwjgl != null && !fmlMcForLwjgl.isBlank()) {
            Path baseDir = config.versionsPath().resolve(fmlMcForLwjgl.trim());
            // 老安装/旧清单缺原版客户端库（icu4j/patchy/blocklist/commons 等）时兜底补全，
            // 否则 modlauncher 启动时报 NoSuchElementException。
            if (lcpRels.stream().noneMatch(r -> r.startsWith("com/mojang/blocklist/"))) {
                for (String rel : vanillaLibsFromVersionJson(baseDir, fmlMcForLwjgl.trim())) {
                    if (!lcpRels.contains(rel)) lcpRels.add(rel);
                }
            }
            // 老安装清单缺 lwjgl 本体库时兜底补全，否则 earlydisplay 加载 GLFW 时
            // NoClassDefFoundError: org/lwjgl/system/Struct。
            if (lcpRels.stream().noneMatch(r -> r.startsWith("org/lwjgl/lwjgl/"))) {
                for (String rel : lwjglFromVersionJson(baseDir, fmlMcForLwjgl.trim())) {
                    if (!lcpRels.contains(rel)) lcpRels.add(rel);
                }
            }
            // NeoForge 模块由 fml 的 PathBasedLocator 通过 neoforge-<ver>-universal.jar 发现；
            // legacyClassPath 若含 neoforge 主 jar（老安装）会与 mod discovery 产生两个同名模块
            // （ResolutionException: reads more than one module named neoforge），故启动时剔除；
            // universal jar 不进 legacyClassPath（避免双模块），由下方缺失检查单独校验。
            lcpRels.removeIf(r -> r.startsWith("net/neoforged/neoforge/" + loaderVersion + "/"));
        }
        if (lcpRels.isEmpty()) {
            throw new IOException("NeoForge 库清单缺失（版本目录清单与内置 21.1.264 均不可用），请重新安装");
        }
        // 校验依赖齐全（下载由安装流程负责，这里只报缺失；跳过非当前平台库如 netty epoll=linux 专用）
        List<String> missing = new ArrayList<>();
        for (String rel : lcpRels) {
            if (!platformNeeded(rel)) continue;
            if (!Files.exists(libs.resolve(rel.replace('/', java.io.File.separatorChar)))) missing.add(rel);
        }
        for (String rel : nativesRels) {
            if (!platformNeeded(rel)) continue;
            if (!Files.exists(libs.resolve(rel.replace('/', java.io.File.separatorChar)))) missing.add(rel);
        }
        // NeoForge universal jar（PathBasedLocator 发现目标）与 client jar（ProductionClientProvider
        // 合成 minecraft 模块的 additionalContent）必须存在（含 21.1.264 兜底物亦可）
        String nfUniversal = "net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-universal.jar";
        if (!Files.exists(libs.resolve(nfUniversal.replace('/', java.io.File.separatorChar)))) missing.add(nfUniversal);
        String nfClient = "net/neoforged/neoforge/" + loaderVersion + "/neoforge-" + loaderVersion + "-client.jar";
        if (!Files.exists(libs.resolve(nfClient.replace('/', java.io.File.separatorChar)))) missing.add(nfClient);
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder("NeoForge " + loaderVersion + " 缺少 " + missing.size() + " 个依赖库，请先安装（下载中心 → NeoForge）：\n");
            for (int i = 0; i < Math.min(4, missing.size()); i++) sb.append("· ").append(missing.get(i)).append('\n');
            throw new IOException(sb.toString());
        }
        // fml 参数：优先版本目录 win_args 缓存（安装时写入）；兜底从本地库文件名解析（含自动释放内置 srg）
        String srgVersion = null;
        String neoFormVersion = null;
        String fmlVersion = null;
        String mcVersion = null;
        String fmlMc = nfArgs.get("fml.mcVersion");
        String fmlNf = nfArgs.get("fml.neoFormVersion");
        if (fmlMc != null && fmlNf != null && !fmlMc.isBlank() && !fmlNf.isBlank()) {
            srgVersion = fmlMc.trim() + "-" + fmlNf.trim();
            neoFormVersion = fmlNf.trim();
            mcVersion = fmlMc.trim();
            fmlVersion = nfArgs.get("fml.fmlVersion");
            if (fmlVersion == null || fmlVersion.isBlank()) fmlVersion = findFmlVersion(libs);
        } else {
            ensureNeoFormArtifacts(libs); // 新环境缺 srg 时自动释放内置产物
            srgVersion = findNeoFormVersion(libs); // 如 1.21.1-20240808.144430
            int dashIdx = srgVersion.indexOf('-');
            neoFormVersion = dashIdx > 0 ? srgVersion.substring(dashIdx + 1) : srgVersion; // 官方只认纯构建号 20240808.144430
            fmlVersion = findFmlVersion(libs);
            mcVersion = dashIdx > 0 ? srgVersion.substring(0, dashIdx) : "1.21.1";
        }

        // 启动前补齐当前版本游戏层产物（srg/extra/minecraft.jar）与 21.4 补丁层 client。
        // 老实例可能只装过 loader 依赖、没跑完整安装流程：srg/extra 目录为空、client 是跨版本错配兜底，
        // 此时 FML 按 Maven 坐标找不到 srg/extra，minecraft 模块无类，报 CNFE LoadingOverlay。
        ensureGameLayerArtifacts(libs, mcVersion, neoFormVersion, loaderVersion);

        // 身份参数（与 buildCommand 同一套逻辑）
        boolean injected = false;
        String injectRoot = "";
        if (account != null && account.type == LauncherConfig.Account.Type.LITTLESKIN) {
            injectRoot = (account.lsApiRoot == null || account.lsApiRoot.isBlank())
                    ? "https://littleskin.cn/api/yggdrasil" : account.lsApiRoot;
            injected = true;
        } else if (skin != null && account != null && account.type == LauncherConfig.Account.Type.OFFLINE) {
            injectRoot = skin.apiRoot();
            injected = true;
        }
        String uuid = account == null ? OSUtil.offlineUuid("Steve") : account.uuid;
        String token = account == null ? "0"
                : (account.accessToken == null || account.accessToken.isBlank() ? "0" : account.accessToken);
        if (injected && account != null && account.type == LauncherConfig.Account.Type.OFFLINE) {
            token = uuid.replace("-", "");
        }
        String userType;
        if (account != null && account.type == LauncherConfig.Account.Type.MICROSOFT) {
            userType = "msa";
        } else {
            userType = injected ? "mojang" : "legacy";
        }
        String userName = account == null ? "Steve" : account.userName;

        // natives 合并解压（8 个 lwjgl natives jar → jwh-natives 统一目录）
        Path nativesDir = prepareNeoForgeNatives(libs, nativesRels);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.add("-Xms" + Math.max(512, config.minMemory) + "M");
        cmd.add("-Xmx" + Math.max(1024, config.maxMemory) + "M");
        // 官方 win_args 标准 JVM 参数
        cmd.add("-Djava.net.preferIPv6Addresses=system");
        // 模块路径（官方 8 个引导 jar）
        cmd.add("-p");
        cmd.add(String.join(java.io.File.pathSeparator, toAbsList(libs, moduleRels)));
        cmd.add("--add-modules");
        cmd.add("ALL-MODULE-PATH");
        for (String[] jp : jpms) {
            cmd.add(jp[0]);
            cmd.add(jp[1]);
        }
        cmd.add("-DignoreList=" + ignoreList);
        cmd.add("-Dfml.pluginLayerLibraries=");
        // minecraft 模块：官方 launcher 运行期填充 gameLayerLibraries；win_args 为空。
        // client jar 无 module-info，securejarhandler 按文件名派生自动模块名，因此必须用
        // minecraft.jar 这个名字（安装时复制生成），否则 game layer 无 minecraft 模块，
        // runTarget 的 layer.findModule("minecraft") 报 NoSuchElementException。
        String mcModuleRel = (fmlMcForLwjgl == null || fmlNfFormForMc == null) ? null
                : "net/minecraft/client/" + fmlMcForLwjgl.trim() + "-" + fmlNfFormForMc.trim() + "/minecraft.jar";
        cmd.add("-Dfml.gameLayerLibraries=" + (mcModuleRel == null ? ""
                : libs.resolve(mcModuleRel.replace('/', java.io.File.separatorChar)).toString()));
        cmd.add("-DlibraryDirectory=" + libs);
        // BootstrapLauncher 2.0.2 会校验：模块路径(-p)上已出现的自动模块，不得再出现在
        // legacyClassPath(JVM classpath)，否则抛 IllegalStateException: Module named
        // org.objectweb.asm already contains on the JVMs module path but class-path contains it。
        // 官方 win_args 的 legacyClassPath 可能重复列出引导 jar；这里把已进模块路径的 jar、
        // ignoreList 声明归模块系统的 jar，以及整套 ASM（模块路径提供，顺带清掉原版混入的
        // 旧版本 asm）从 legacyClassPath 剔除，只保留游戏侧库。
        java.util.Set<String> moduleJars = new java.util.HashSet<>();
        for (String m : moduleRels) moduleJars.add(basename(m));
        if (ignoreList != null) {
            for (String ig : ignoreList.split(",")) {
                String t = ig.trim();
                if (!t.isEmpty()) moduleJars.add(t);
            }
        }
        lcpRels.removeIf(rel -> moduleJars.contains(basename(rel))
                || rel.startsWith("org/ow2/asm/"));
        List<String> lcpAbs = toAbsList(libs, lcpRels);
        verifyClasspathJars(lcpAbs); // 启动前校验 84 库 ZIP 完整性，防止损坏 jar 让 FML 崩溃
        cmd.add("-DlegacyClassPath=" + String.join(java.io.File.pathSeparator, lcpAbs));
        cmd.add("-Dorg.lwjgl.librarypath=" + nativesDir);
        cmd.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativesDir);
        cmd.add("-Dio.netty.native.workdir=" + nativesDir);
        // 用户自定义 JVM 参数
        if (config.jvmArgs != null && !config.jvmArgs.isBlank()) {
            for (String s : config.jvmArgs.trim().split("\\s+")) {
                if (!s.isBlank()) cmd.add(s);
            }
        }
        // 注入（与 buildCommand 相同：离线走本地 SkinServer，LittleSkin 走站点 API 根）
        if (injected) {
            try {
                Path agent = SkinServer.exportAgent(ConfigManager.configPath().getParent());
                cmd.add("-javaagent:" + agent + "=" + injectRoot);
            } catch (IOException e) {
                System.err.println("[JWH] 导出 authlib-injector 失败，回退 legacy 模式：" + e.getMessage());
            }
        }

        // 客户端 LaunchHandler 服务名按三层解析，不按 FML 版本谱系猜测：
        //   1) 安装期从本地 loader jar 固化到实例缓存的 launchTarget；
        //   2) 缓存缺失/非法时实时解析本地 loader jar 客户端 handler 注册名；
        //   3) 仍取不到再按 FML 主版本规则兜底。
        // 传错服务名会报 Missing LaunchHandler <name>, cannot continue。
        String launchTarget;
        String cachedTarget = nfArgs.get("launchTarget");
        if (NeoForgeLaunchTarget.isValidTarget(cachedTarget)) {
            launchTarget = cachedTarget.trim();
            System.err.println("[JWH] NeoForge launchTarget=" + launchTarget + "（实例缓存，fml " + fmlVersion + "）");
        } else {
            String fromJar = NeoForgeLaunchTarget.resolveFromLoaderJar(libs, fmlVersion);
            if (fromJar != null) {
                launchTarget = fromJar;
                System.err.println("[JWH] NeoForge launchTarget=" + launchTarget + "（loader jar 实测，fml " + fmlVersion + "）");
            } else {
                launchTarget = NeoForgeLaunchTarget.fallbackByFmlVersion(fmlVersion);
                System.err.println("[JWH] NeoForge launchTarget=" + launchTarget + "（版本规则兜底，fml " + fmlVersion + "）");
            }
        }
        cmd.add("cpw.mods.bootstraplauncher.BootstrapLauncher");
        cmd.add("--launchTarget");
        cmd.add(launchTarget);
        cmd.add("--fml.neoForgeVersion");
        cmd.add(loaderVersion);
        cmd.add("--fml.fmlVersion");
        cmd.add(fmlVersion);
        cmd.add("--fml.mcVersion");
        cmd.add(mcVersion);
        cmd.add("--fml.neoFormVersion");
        cmd.add(neoFormVersion);

        // 游戏参数（官方结构必须显式传身份参数，否则 joptsimple 报 MissingRequiredOptionsException）
        Path gameDir = config.gameDirFor(versionId);
        Files.createDirectories(gameDir);
        Path assetsRoot = config.assetsPath();
        cmd.add("--username");
        cmd.add(userName);
        cmd.add("--version");
        cmd.add(mcVersion);
        cmd.add("--gameDir");
        cmd.add(gameDir.toString());
        cmd.add("--assetsDir");
        cmd.add(assetsRoot.toString());
        cmd.add("--assetIndex");
        cmd.add("17"); // MC 1.21.1 官方资产索引
        cmd.add("--uuid");
        cmd.add(uuid.replace("-", ""));
        cmd.add("--accessToken");
        cmd.add(token);
        cmd.add("--userType");
        cmd.add(userType);
        cmd.add("--versionType");
        cmd.add("release");
        // 多人游戏：直达指定服务器（官方 launcher 同款参数）
        if (quickPlay != null && !quickPlay.isBlank()) {
            cmd.add("--quickPlayMultiplayer");
            cmd.add(quickPlay.trim());
        }

        return new LaunchCommand(cmd, gameDir);
    }

    /** 读取启动器内置清单资源（每行一个相对路径）。 */
    private static List<String> loadResourceLines(String name) throws IOException {
        List<String> out = new ArrayList<>();
        try (java.io.InputStream in = GameLauncher.class.getResourceAsStream("/" + name)) {
            if (in == null) return out;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) out.add(line.trim());
                }
            }
        }
        return out;
    }

    /** 版本目录清单读取：文件存在且非空则读文件，否则返回 fallback（老安装兼容）。 */
    private static List<String> readLinesIfExists(Path f, List<String> fallback) throws IOException {
        if (Files.exists(f)) {
            List<String> out = new ArrayList<>();
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!l.isBlank()) out.add(l.trim());
            }
            if (!out.isEmpty()) return out;
        }
        return fallback;
    }

    /** 键值清单读取（每行 key=value），文件不存在返回空表。 */
    private static Map<String, String> readKeyValues(Path f) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (Files.exists(f)) {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int eq = l.indexOf('=');
                if (eq > 0) out.put(l.substring(0, eq).trim(), l.substring(eq + 1));
            }
        }
        return out;
    }

    /** 从基础版本 json 提取 lwjgl 本体库（org/lwjgl/*，不含 natives classifier，8 个）。 */
    private static List<String> lwjglFromVersionJson(Path baseDir, String mcVersion) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject v = new JSONObject(
                    Files.readString(baseDir.resolve(mcVersion + ".json"), StandardCharsets.UTF_8));
            JSONArray libs = Json.optArr(v, "libraries");
            if (libs != null) {
                for (int i = 0; i < libs.length(); i++) {
                    JSONObject l = libs.optJSONObject(i);
                    if (l == null) continue;
                    JSONObject dl = Json.optObj(l, "downloads");
                    if (dl == null) continue;
                    JSONObject art = Json.optObj(dl, "artifact");
                    if (art == null) continue;
                    String p = art.optString("path", "");
                    if (p.startsWith("libraries/org/lwjgl/")) {
                        if (!p.contains("-natives-")) out.add(p.substring("libraries/".length()));
                    } else if (p.startsWith("org/lwjgl/") && !p.contains("-natives-")) {
                        // 原版 json 的 artifact path 不带 libraries/ 前缀（classifiers 才带）
                        out.add(p);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 从基础版本 json 提取原版客户端库（排除 natives/lwjgl/client 本体，NeoForge 用 patched client）。 */
    private static List<String> vanillaLibsFromVersionJson(Path baseDir, String mcVersion) {
        List<String> out = new ArrayList<>();
        try {
            JSONObject v = new JSONObject(
                    Files.readString(baseDir.resolve(mcVersion + ".json"), StandardCharsets.UTF_8));
            JSONArray libs = Json.optArr(v, "libraries");
            if (libs != null) {
                for (int i = 0; i < libs.length(); i++) {
                    JSONObject l = libs.optJSONObject(i);
                    if (l == null) continue;
                    JSONObject dl = Json.optObj(l, "downloads");
                    if (dl == null) continue;
                    JSONObject art = Json.optObj(dl, "artifact");
                    if (art == null) continue;
                    String p = art.optString("path", "");
                    if (p.startsWith("libraries/org/lwjgl/")) {
                        if (!p.contains("-natives-")) out.add(p.substring("libraries/".length()));
                    } else if (p.startsWith("org/lwjgl/") && !p.contains("-natives-")) {
                        // 原版 json 的 artifact path 不带 libraries/ 前缀（classifiers 才带）
                        out.add(p);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 新环境缺 srg/extra 时自动释放内置 neoform 产物。内置两个构建：1.21.1-20240808.144430
     *  （21.1.x 通用）与 1.21.4-20241203.161809（21.4.161）；按各自 key 目录分别补齐，互不覆盖。 */
    private static void ensureNeoFormArtifacts(Path libs) {
        String[] keys = {
                "1.21.1-20240808.144430",
                "1.21.4-20241203.161809"
        };
        for (String key : keys) {
            Path dir = libs.resolve("net/minecraft/client/" + key);
            for (String suffix : new String[]{"srg", "extra"}) {
                Path target = dir.resolve("client-" + key + "-" + suffix + ".jar");
                try {
                    if (Files.exists(target) && Files.size(target) > 100_000) continue;
                    String res = "neoform-" + key + "-" + suffix + ".jar";
                    try (java.io.InputStream in = com.jwh.launcher.util.Resources.open(res)) {
                        if (in == null) continue;
                        Files.createDirectories(dir);
                        Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 启动前补齐当前版本的 MC 游戏层产物（srg/extra/minecraft.jar）与 21.4 补丁层 client.jar。
     * 老实例可能只装过 loader 依赖、没跑完整安装流程：srg/extra 目录为空、client 是跨版本错配兜底；
     * FML 按 Maven 坐标在 libraryDirectory 找 net.minecraft:client:&lt;key&gt;:srg/:extra，
     * 找不到则 minecraft 模块无类，报 CNFE LoadingOverlay。这里在真正拉起 JVM 前按版本自愈。
     */
    private static void ensureGameLayerArtifacts(Path libs, String mcVersion, String neoFormVersion,
                                                 String loaderVersion) throws IOException {
        if (mcVersion == null || mcVersion.isBlank() || neoFormVersion == null || neoFormVersion.isBlank()) {
            return;
        }
        String key = mcVersion.trim() + "-" + neoFormVersion.trim();
        Path mcDir = libs.resolve("net/minecraft/client/" + key);
        Files.createDirectories(mcDir);

        // 1. srg / extra：内置 neoform 产物，缺失或低于兜底大小才覆盖（避免反复重写）。
        for (String suffix : new String[]{"srg", "extra"}) {
            Path target = mcDir.resolve("client-" + key + "-" + suffix + ".jar");
            if (Files.exists(target) && Files.size(target) > 100_000) continue;
            String res = "/neoform-" + key + "-" + suffix + ".jar";
            try (java.io.InputStream in = com.jwh.launcher.util.Resources.open(res.substring(1))) {
                if (in == null) continue; // 当前版本无内置产物，保持现状（由下载/安装流程负责）
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // 2. minecraft.jar：game layer 自动模块占位，由 srg 复制；文件名固定为 minecraft.jar。
        Path srgFile = mcDir.resolve("client-" + key + "-srg.jar");
        Path mcModuleFile = mcDir.resolve("minecraft.jar");
        if (Files.exists(srgFile)) {
            boolean needCopy = !Files.exists(mcModuleFile)
                    || Files.size(mcModuleFile) != Files.size(srgFile);
            if (needCopy) {
                Files.copy(srgFile, mcModuleFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // 3. 21.4 补丁层 client：公开 maven 不发布含受版权 MC 类的 client 产物，老实例可能残留
        //    21.1 错配兜底（有效 zip 但版本不符），强制以内置 21.4 产物覆盖；其余版本不在此处理。
        if ("1.21.4".equals(mcVersion.trim())) {
            Path clientFile = libs.resolve("net/neoforged/neoforge/" + loaderVersion
                    + "/neoforge-" + loaderVersion + "-client.jar");
            Files.createDirectories(clientFile.getParent());
            try (java.io.InputStream in = com.jwh.launcher.util.Resources.open("neoforge-21.4.161-client.jar")) {
                if (in != null) {
                    Files.copy(in, clientFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 相对路径（/ 分隔）→ libraries 下绝对路径。 */
    private static List<String> toAbsList(Path libs, List<String> rels) {
        List<String> out = new ArrayList<>(rels.size());
        for (String rel : rels) {
            out.add(libs.resolve(rel.replace('/', java.io.File.separatorChar)).toString());
        }
        return out;
    }

    /** 取库相对路径的文件名部分（如 org/ow2/asm/asm/9.8/asm-9.8.jar → asm-9.8.jar）。 */
    private static String basename(String rel) {
        int sp = rel.lastIndexOf('/');
        return sp >= 0 ? rel.substring(sp + 1) : rel;
    }

    /** 启动前校验 classpath 上每个 jar 的 ZIP 完整性：下载中断/损坏的 jar（长度正确但
     *  CEN 头损坏）会让 Fabric/Forge/NeoForge 扫描 classpath 时直接崩溃，
     * 这里提前拦截并给出明确提示（用户点「修复文件」即可重下）。 */
    private static void verifyClasspathJars(List<String> classpath) throws IOException {
        for (String cp : classpath) {
            if (cp == null || !cp.endsWith(".jar")) continue;
            Path j = Path.of(cp);
            if (!Files.exists(j)) continue; // 缺失由缺库检查兜底，这里只拦损坏
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(j.toFile())) {
                zf.size();
            } catch (Exception e) {
                throw new IOException("依赖库文件损坏：" + j.getFileName()
                        + "，请在主页点击「修复文件」重新下载后再启动。");
            }
        }
    }

    /** 该库是否当前平台需要（按文件名平台标记过滤：netty epoll=linux 专用、kqueue=mac 专用）。 */
    private static boolean platformNeeded(String rel) {
        String lower = rel.toLowerCase();
        var os = OSUtil.currentOs();
        if (lower.contains("linux")) return os == OSUtil.OS.LINUX;
        if (lower.contains("osx") || lower.contains("macos")) return os == OSUtil.OS.MACOS;
        if (lower.contains("windows")) return os == OSUtil.OS.WINDOWS;
        return true; // 无平台标记 = 通用库
    }

    /** 从 libraries/net/minecraft/client 下的 client-*-srg.jar 文件名解析完整版本（如 1.21.1-20240808.144430）。 */
    private static String findNeoFormVersion(Path libs) throws IOException {
        Path dir = libs.resolve("net/minecraft/client");
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path vd : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(vd)) continue;
                    try (var jars = Files.list(vd)) {
                        for (Path j : (Iterable<Path>) jars::iterator) {
                            String n = j.getFileName().toString();
                            if (n.startsWith("client-") && n.endsWith("-srg.jar")) {
                                // 文件名如 client-1.21.1-20240808.144430-srg.jar → 1.21.1-20240808.144430
                                return n.substring("client-".length(), n.length() - "-srg.jar".length());
                            }
                        }
                    }
                }
            }
        }
        throw new IOException("未找到 neoform srg 库（net/minecraft/client 下的 client-*-srg.jar），请重新安装 NeoForge");
    }

    /** 从 libraries/net/neoforged/fancymodloader/loader/* 解析 fml 版本（取目录名，如 4.0.45）。 */
    private static String findFmlVersion(Path libs) throws IOException {
        Path dir = libs.resolve("net/neoforged/fancymodloader/loader");
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path vd : (Iterable<Path>) stream::iterator) {
                    if (Files.isDirectory(vd)) return vd.getFileName().toString();
                }
            }
        }
        throw new IOException("未找到 FML loader 库（net/neoforged/fancymodloader/loader/*），请重新安装 NeoForge");
    }

    /** 解压 8 个 lwjgl natives jar 的 dll 到 libraries/jwh-natives（展平到根目录，跳过 META-INF/非 dll）。 */
    private static Path prepareNeoForgeNatives(Path libs, List<String> nativesRels) throws IOException {
        Path outDir = libs.resolve("jwh-natives");
        Files.createDirectories(outDir);
        for (String rel : nativesRels) {
            Path jar = libs.resolve(rel.replace('/', java.io.File.separatorChar));
            if (!Files.exists(jar)) continue;
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    var e = entries.nextElement();
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    if (n.startsWith("META-INF/")) continue;
                    if (!n.endsWith(".dll")) continue;
                    // 展平：dll 在 jar 内的 windows/x64/org/lwjgl/... 子目录，只取文件名放到统一目录
                    String fileName = n.substring(n.lastIndexOf('/') + 1);
                    if (fileName.contains("/") || fileName.contains("\\")) continue;
                    Path out = outDir.resolve(fileName).normalize();
                    Files.createDirectories(out.getParent());
                    try (java.io.InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (java.util.zip.ZipException ignored) {
            }
        }
        return outDir;
    }

    /** 合并继承版本（基础版本在前）+ 当前版本的依赖库，按库 name 去重（当前版本优先）。 */
    /** 合并基础版本与加载器版本的依赖库：按 group:artifact:classifier 去重（忽略版本号），
     *  加载器 profile 声明的库优先（子覆盖父）——避免 classpath 上出现同库多版本
     * （如 Minecraft 1.21.3 的 asm 9.3 与 Fabric 的 asm 9.10.1 并存会触发
     *  Fabric Loader 的 duplicate ASM classes 校验而拒绝启动；
     *  保留加载器声明的高版本向后兼容，MC 的低版本用法也能运行）。 */
    private JSONArray mergeLibraries(JSONObject base, JSONObject v) {
        JSONArray out = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // 先加加载器 profile（子）声明的库——精确匹配加载器运行时需求
        JSONArray ownLibs = v.optJSONArray("libraries");
        if (ownLibs != null) {
            for (int i = 0; i < ownLibs.length(); i++) {
                JSONObject lib = ownLibs.getJSONObject(i);
                String n = lib.optString("name", "");
                if (!n.isBlank() && seen.add(libKey(n))) out.put(lib);
            }
        }
        // 再补基础版本（父）的库，同 artifact 已被子声明覆盖则跳过
        JSONArray baseLibs = base == null ? null : base.optJSONArray("libraries");
        if (baseLibs != null) {
            for (int i = 0; i < baseLibs.length(); i++) {
                JSONObject lib = baseLibs.getJSONObject(i);
                String n = lib.optString("name", "");
                if (!n.isBlank() && seen.add(libKey(n))) out.put(lib);
            }
        }
        return out;
    }

    /** 去重键：group:artifact:classifier（忽略版本号；classifier 区分 natives 等变体，不可丢）。 */
    private static String libKey(String name) {
        String[] p = name.split(":");
        String classifier = p.length > 3 ? p[3] : "";
        return p[0] + ":" + p[1] + ":" + classifier;
    }

    /** 在合并后的依赖库中按 group:artifact 前缀查找 jar 路径（用于 module-path 加载），找不到返回 null。 */
    private Path findLibrary(JSONArray libs, String prefix) {
        if (libs == null) return null;
        for (int i = 0; i < libs.length(); i++) {
            JSONObject lib = libs.getJSONObject(i);
            String name = lib.optString("name", "");
            if (!name.startsWith(prefix)) continue;
            String[] parts = name.split(":");
            String rel = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                    + "/" + parts[1] + "-" + parts[2]
                    + (parts.length > 3 ? "-" + parts[3] : "") + ".jar";
            Path jar = config.librariesPath().resolve(rel);
            if (Files.exists(jar)) return jar;
        }
        return null;
    }

    /** 按 group:artifact 前缀收集全部匹配库的 jar 路径（前缀匹配整个 asm 系列等）。 */
    private List<Path> findAllLibraries(JSONArray libs, String prefix) {
        List<Path> out = new ArrayList<>();
        if (libs == null) return out;
        for (int i = 0; i < libs.length(); i++) {
            JSONObject lib = libs.getJSONObject(i);
            String name = lib.optString("name", "");
            if (!name.startsWith(prefix)) continue;
            String[] parts = name.split(":");
            String rel = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2]
                    + "/" + parts[1] + "-" + parts[2]
                    + (parts.length > 3 ? "-" + parts[3] : "") + ".jar";
            Path jar = config.librariesPath().resolve(rel);
            if (Files.exists(jar)) out.add(jar);
        }
        return out;
    }

    /** 启动游戏进程，日志逐行回调 consumer。返回进程。skin 为离线皮肤注入服务（可 null）。 */
    public Process launch(String versionId, LauncherConfig.Account account, Path javaExe,
                          SkinServer skin,
                          Consumer<String> logLine, Consumer<Integer> onExit) throws IOException {
        return launch(versionId, account, javaExe, skin, null, logLine, onExit);
    }

    /**
     * 启动游戏进程，可指定服务器地址直达（多人游戏）。
     *
     * @param quickPlay 服务器地址（{@code host} 或 {@code host:port}）；null/空 = 普通启动
     */
    public Process launch(String versionId, LauncherConfig.Account account, Path javaExe,
                          SkinServer skin, String quickPlay,
                          Consumer<String> logLine, Consumer<Integer> onExit) throws IOException {
        LaunchCommand lc = buildCommand(versionId, account, javaExe, skin, quickPlay);
        // 首行输出完整启动命令，便于排查（含 javaagent、全部游戏参数）
        if (logLine != null) {
            logLine.accept("[JWH] 启动命令: " + String.join(" ", lc.command()));
        }
        // 同时落盘日志，方便事后排查
        java.io.PrintWriter logFile = null;
        try {
            Path logDir = java.nio.file.Files.createDirectories(
                    ConfigManager.configPath().getParent().resolve("logs"));
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            logFile = new java.io.PrintWriter(
                    new java.io.OutputStreamWriter(
                            java.nio.file.Files.newOutputStream(logDir.resolve("game-" + versionId + "-" + ts + ".log")),
                            StandardCharsets.UTF_8), true);
        } catch (Exception ignored) {
        }
        final java.io.PrintWriter logFile0 = logFile;
        if (logFile0 != null) {
            logFile0.println("[JWH] 启动命令: " + String.join(" ", lc.command()));
        }
        ProcessBuilder pb = new ProcessBuilder(lc.command());
        pb.directory(lc.workingDir().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();

        Thread t = new Thread(() -> {
            try (var in = p.getInputStream();
                 var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (logLine != null) logLine.accept(line);
                    if (logFile0 != null) logFile0.println(line);
                }
            } catch (Exception ignored) {
            }
            try {
                int code = p.waitFor();
                if (logFile0 != null) logFile0.println("===== 进程退出码: " + code + " =====");
                if (onExit != null) onExit.accept(code);
            } catch (InterruptedException ignored) {
            } finally {
                if (logFile0 != null) logFile0.close();
            }
        }, "game-log-reader");
        t.setDaemon(true);
        t.start();
        return p;
    }

    private String replaceVars(String s, java.util.Map<String, String> vars) {
        for (java.util.Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }

    /** 与 GameInstaller 相同的 rules 过滤逻辑（保持独立实现避免耦合）。 */
    private boolean rulesAllow(JSONArray rules) {
        if (rules == null) return true;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            boolean match = true;
            JSONObject os = Json.optObj(rule, "os");
            if (os != null) {
                if (os.has("name") && !OSUtil.osMatches(os.optString("name"))) match = false;
                if (os.has("arch") && !OSUtil.archMatches(os.optString("arch"))) match = false;
            }
            JSONObject features = Json.optObj(rule, "features");
            if (features != null && match) {
                for (String key : features.keySet()) {
                    boolean expected = features.optBoolean(key);
                    if (launcherFeature(key) != expected) { match = false; break; }
                }
            }
            if (match) {
                return "allow".equals(rule.optString("action", "allow"));
            }
        }
        return false;
    }

    /** 启动器特性表：决定版本 JSON rules 中 features 的匹配结果。is_demo_user 必须为 false，否则会被加上 --demo 进入试玩。 */
    private boolean launcherFeature(String key) {
        return switch (key) {
            case "is_demo_user" -> false;
            case "has_custom_resolution" -> true;
            // 只有本次确实要直达服务器时才声称支持 quickPlay，
            // 否则 1.21.x 的 arguments 会把 --quickPlaySingleplayer 等空参数也塞进命令行。
            case "has_quick_plays_support" -> quickPlayActive.get();
            case "is_quick_play_singleplayer" -> false;
            case "is_quick_play_multiplayer" -> quickPlayActive.get();
            case "is_quick_play_realms" -> false;
            default -> false;
        };
    }
}
