import com.jwh.launcher.download.ArtifactProbe;
import com.jwh.launcher.download.Downloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 验证「源上没有这个文件」时的耗时：改动前单个 404 文件要白挂 40~300 秒，改动后应数秒内出结论。 */
public class ProbeDl {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(System.getenv("TEMP"), "jwh-probe", "dltest");
        Files.createDirectories(dir);

        String serverRel = "net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-extra.jar";
        Path t1 = dir.resolve("server-extra.jar");
        Files.deleteIfExists(t1);
        long t0 = System.currentTimeMillis();
        boolean ok1 = Downloader.fetch(t1, -1, ArtifactProbe.Kind.JAR, "server-extra", null,
                Downloader.Policy.of(20, 40),
                "https://bmclapi2.bangbang93.com/maven/" + serverRel,
                "https://maven.neoforged.net/releases/" + serverRel);
        System.out.println("SRV ok=" + ok1 + " ms=" + (System.currentTimeMillis() - t0)
                + " size=" + (Files.exists(t1) ? Files.size(t1) : 0));

        String clientRel = "net/neoforged/neoforge/21.1.265/neoforge-21.1.265-client.jar";
        Path t2 = dir.resolve("neoforge-client.jar");
        Files.deleteIfExists(t2);
        t0 = System.currentTimeMillis();
        boolean ok2 = Downloader.fetch(t2, -1, ArtifactProbe.Kind.JAR, "neoforge-client", null,
                Downloader.Policy.of(30, 90),
                "https://bmclapi2.bangbang93.com/maven/" + clientRel,
                "https://maven.neoforged.net/releases/" + clientRel);
        System.out.println("CLI ok=" + ok2 + " ms=" + (System.currentTimeMillis() - t0)
                + " size=" + (Files.exists(t2) ? Files.size(t2) : 0));

        System.exit(0);
    }
}
