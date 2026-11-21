import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 离线验证 Updater.copyTree 的占用重试逻辑：
 * 1) 目标文件被独占锁定时，重试耗尽后抛出带文件名的 IOException；
 * 2) 句柄释放后再次复制，覆盖成功且内容为新。
 * 不联网、不启动 FX。
 */
public class ProbeLock {
    public static void main(String[] args) throws Exception {
        Path base = Paths.get(System.getenv("TEMP"), "jwh-probe", "lock-probe");
        delete(base);
        Path src = base.resolve("src/music");
        Path dst = base.resolve("dst/music");
        Files.createDirectories(src);
        Files.createDirectories(dst);
        String name = "C418 - Haggstrom.mp3";
        Files.writeString(src.resolve(name), "NEW-CONTENT");
        Files.writeString(dst.resolve(name), "OLD-CONTENT");

        Method copyTree = Class.forName("com.jwh.launcher.update.Updater")
                .getDeclaredMethod("copyTree", Path.class, Path.class);
        copyTree.setAccessible(true);

        // 1) 独占锁定目标文件（模拟 gstreamer 解码器句柄）
        FileOutputStream lock = new FileOutputStream(dst.resolve(name).toFile());
        lock.write(0);
        boolean blocked;
        try {
            copyTree.invoke(null, src, dst);
            blocked = false;
            System.out.println("CASE1=UNEXPECTED-COPY-SUCCESS");
        } catch (Exception e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            blocked = true;
            System.out.println("CASE1=BLOCKED-AS-EXPECTED");
            System.out.println("CASE1-EX=" + root.getClass().getSimpleName() + " : " + root.getMessage());
        } finally {
            lock.close();
        }

        // 2) 释放句柄后再复制，应成功并覆盖为新内容
        try {
            copyTree.invoke(null, src, dst);
            String after = Files.readString(dst.resolve(name));
            System.out.println("CASE2=" + ("NEW-CONTENT".equals(after) ? "COPY-AFTER-RELEASE=OK" : "CONTENT-MISMATCH:" + after));
        } catch (Exception e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            System.out.println("CASE2=FAIL " + root.getClass().getSimpleName() + " : " + root.getMessage());
        }
        System.exit(0);
    }

    private static void delete(Path p) {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                try { Files.deleteIfExists(q); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
