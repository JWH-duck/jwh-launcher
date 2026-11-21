import com.jwh.launcher.download.ArtifactProbe;
import com.jwh.launcher.download.Downloader;
import com.jwh.launcher.update.Updater;
import com.jwh.launcher.update.Updater.LayerInfo;
import com.jwh.launcher.update.Updater.UpdateInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 复现完整层补齐 + jar 下载校验，逐层打印真实结果/异常（不启动替换脚本）。 */
public class ProbeUpd {
    public static void main(String[] args) throws Exception {
        Path appDir = Paths.get(System.getenv("TEMP"), "jwh-probe", "upd-appdir2");
        Files.createDirectories(appDir.resolve("app"));
        Files.writeString(appDir.resolve("app").resolve("JWH-Launcher.jar"), "stub");

        Updater u = new Updater(appDir, "1.0.0");
        UpdateInfo info = u.check();
        if (info == null) { System.out.println("CHECK null"); System.exit(0); }
        System.out.println("version=" + info.version() + " layers=" + info.layers().size()
                + " parts=" + info.parts().size());

        // 1) 完整跑层补齐（含解压落盘到临时 resources），复现真实异常
        try {
            u.applyLayers(info.layers());
            System.out.println("APPLY-LAYERS=OK");
        } catch (Exception e) {
            System.out.println("APPLY-LAYERS=FAIL " + e.getClass().getName() + " : " + e.getMessage());
            e.printStackTrace(System.out);
        }

        // 2) jar 增量包下载 + 结构/大小/哈希校验（不启动脚本）
        Path jar = appDir.resolve(".jwh-update").resolve("update-" + info.version() + ".jar");
        Files.deleteIfExists(jar);
        long t0 = System.currentTimeMillis();
        boolean ok = Downloader.fetch(jar, info.jarSize(), ArtifactProbe.Kind.JAR,
                "launcher-update-jar", null, Downloader.Policy.of(60, 300),
                info.jarUrls().toArray(new String[0]));
        long ms = System.currentTimeMillis() - t0;
        long sz = Files.exists(jar) ? Files.size(jar) : -1;
        ArtifactProbe.Verdict v = Files.exists(jar)
                ? ArtifactProbe.check(jar, ArtifactProbe.Kind.JAR, info.jarSize()) : null;
        System.out.println("JAR ok=" + ok + " ms=" + ms + " size=" + sz + " expect=" + info.jarSize()
                + (v == null ? "" : (" probe=" + (v.ok() ? "PASS" : "FAIL:" + v.reason()))));
        System.exit(0);
    }
}
