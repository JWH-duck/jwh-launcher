import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.auth.AuthService;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.ui.SkinsView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 离屏复现「离线/无账号打开皮肤库」：构造 SkinsView 并 refresh，捕获异常并截帧。
 * 环境变量：MODE=none|offline；OUTDIR 临时输出目录（独立 mc 目录 + 临时 config）；SKIN_PATH 测试皮肤 PNG。
 * 只复现「打开皮肤库」路径；选肤落盘路径依赖完整启动的 homeView/statusLabel，不在此桩内触发。
 */
public class ProbeSkins extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        String mode = System.getenv().getOrDefault("MODE", "none");
        String outDir = System.getenv("OUTDIR");
        Path mc = Path.of(outDir, "mc");
        Files.createDirectories(mc.resolve("skins").resolve("library"));
        String skin = System.getenv("SKIN_PATH");
        if (skin != null && !skin.isBlank() && Files.exists(Path.of(skin))) {
            Files.copy(Path.of(skin), mc.resolve("skins").resolve("library").resolve("cow.png"),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        ConfigManager.setConfigFile(Path.of(outDir, "probe-config.json"));
        LauncherConfig cfg = new LauncherConfig();
        cfg.minecraftDir = mc.toString();

        LauncherApp app = new LauncherApp();
        app.config = cfg;
        app.auth = new AuthService(cfg);
        if ("offline".equals(mode) || "mixed".equals(mode)) {
            app.auth.addOfflineAccount("TestOffline");
        }

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#20242c;");
        Scene scene = new Scene(root, 900, 560);
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            try {
                SkinsView v = new SkinsView(app);
                root.setCenter(v.root());
                System.out.println("CONSTRUCT_OK mode=" + mode
                        + " accounts=" + cfg.accounts.size()
                        + " selected=" + cfg.selectedAccount);
                Platform.runLater(() -> {
                    try {
                        WritableImage img = root.snapshot(null, null);
                        PixelReader pr = img.getPixelReader();
                        int w = (int) img.getWidth(), h = (int) img.getHeight();
                        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < h; y++) {
                            for (int x = 0; x < w; x++) {
                                bi.setRGB(x, y, pr.getArgb(x, y));
                            }
                        }
                        ImageIO.write(bi, "png", new java.io.File(outDir, "skins_" + mode + ".png"));
                        System.out.println("SNAP_OK mode=" + mode);
                    } catch (Throwable t) {
                        System.out.println("SNAP_FAIL mode=" + mode);
                        t.printStackTrace();
                    }
                    Platform.exit();
                });
            } catch (Throwable t) {
                System.out.println("SKINS_FAIL mode=" + mode);
                t.printStackTrace();
                Platform.exit();
            }
        });
    }

    public static void main(String[] a) {
        launch(a);
    }
}
