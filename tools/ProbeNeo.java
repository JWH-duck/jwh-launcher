import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.game.GameInstaller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** 在临时目录完整跑一次 NeoForge 安装：验证「镜像缺失/官方不可达」的文件不再拦住整次安装。 */
public class ProbeNeo {
    public static void main(String[] args) throws Exception {
        String testDir = System.getenv("TEMP") + "\\jwh-probe\\mc-test";
        Path mc = Paths.get(testDir);
        Path base = mc.resolve("versions").resolve("1.21.1");
        Files.createDirectories(base);
        Files.copy(Paths.get("F:\\jj\\versions\\1.21.1\\1.21.1.json"),
                base.resolve("1.21.1.json"), StandardCopyOption.REPLACE_EXISTING);

        LauncherConfig cfg = new LauncherConfig();
        cfg.minecraftDir = testDir;
        GameInstaller inst = new GameInstaller(cfg);

        long t0 = System.currentTimeMillis();
        final long[] last = {0L};
        inst.installNeoForge("1.21.1", "21.1.265", null, new GameInstaller.InstallListener() {
            @Override
            public void onProgress(String step, int done, int total) {
                long now = System.currentTimeMillis();
                if (now - last[0] >= 700 || done == total) {
                    last[0] = now;
                    System.out.println("[" + ((now - t0) / 1000) + "s] " + step + " " + done + "/" + total);
                }
            }

            @Override
            public void onBytes(String step, long b, long bt) {
            }
        });
        System.out.println("INSTALL_DONE ms=" + (System.currentTimeMillis() - t0));

        Path lib = mc.resolve("libraries");
        Path cj = lib.resolve("net/neoforged/neoforge/21.1.265/neoforge-21.1.265-client.jar");
        Path uj = lib.resolve("net/neoforged/neoforge/21.1.265/neoforge-21.1.265-universal.jar");
        Path vj = mc.resolve("versions/neoforge-21.1.265/neoforge-21.1.265.json");
        System.out.println("client=" + Files.exists(cj) + " size=" + (Files.exists(cj) ? Files.size(cj) : 0));
        System.out.println("universal=" + Files.exists(uj) + " size=" + (Files.exists(uj) ? Files.size(uj) : 0));
        System.out.println("versionJson=" + Files.exists(vj) + " size=" + (Files.exists(vj) ? Files.size(vj) : 0));
        System.exit(0);
    }
}
