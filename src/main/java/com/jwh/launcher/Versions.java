package com.jwh.launcher;

import java.io.InputStream;
import java.util.Properties;

/**
 * 启动器版本号统一入口。
 *
 * <p>版本号单一来源为 {@code pom.xml} 的 {@code <version>}：
 * <ul>
 *   <li>classpath {@code version.properties}：编译期由 Maven resource filtering 写入主 jar
 *       （值来自 {@code ${project.version}}），jar 增量自更新后随新 jar 一起替换，是自更新场景下
 *       唯一能跟随新版本的来源，故优先级最高；</li>
 *   <li>jpackage 打包注入的系统属性 {@code jpackage.app-version}：打包快照，jar 增量更新不会
 *       重建 exe/cfg，仅在 classpath 清单缺失时兜底；</li>
 *   <li>两者都缺失时回退到内置兜底值（理论上仅在资源被误删时出现）。</li>
 * </ul>
 *
 * <p>发版时只需改 {@code pom.xml} 一处版本号，打包脚本、更新清单（manifest）与界面显示
 * 均从该版本号派生，不再需要在多处硬编码。
 */
public final class Versions {

    /** 兜底版本号：仅在系统属性与 classpath 资源都缺失时使用。 */
    public static final String FALLBACK = "1.0.0";

    private static final String CACHED = resolve();

    private Versions() {
    }

    /** 当前启动器版本号（已去空白；不可为空）。 */
    public static String current() {
        return CACHED;
    }

    private static String resolve() {
        // 1. classpath 内 version.properties：随主 jar 一起增量更新，自更新后唯一能跟随新版本
        try (InputStream in = Versions.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        } catch (Exception ignored) {
            // 资源读取失败则继续走兜底
        }

        // 2. jpackage 打包注入的 app-version 系统属性（打包快照；清单缺失时兜底）
        String sys = System.getProperty("jpackage.app-version");
        if (sys != null && !sys.isBlank()) {
            return sys.trim();
        }

        // 3. 兜底
        return FALLBACK;
    }
}
