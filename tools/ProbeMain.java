import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.game.GameLauncher;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 复用 LauncherApp.buildScene 构建完整主界面（顶栏 + 导航 + 主页 + 状态栏）并截图。
 * 跳过启动联网流程：仅本地读配置、构造 GameLauncher，不触发 LittleSkin 同步 / 更新检查。
 * 截图路径由环境变量 PREVIEW_OUT 指定。
 */
public class ProbeMain extends Application {

    @Override
    public void start(javafx.stage.Stage stage) throws Exception {
        LauncherApp app = new LauncherApp();
        app.config = ConfigManager.load();
        app.gameLauncher = new GameLauncher(app.config);

        Method m = LauncherApp.class.getDeclaredMethod("buildScene", javafx.stage.Stage.class);
        m.setAccessible(true);
        Scene scene = (Scene) m.invoke(app, stage);
        stage.setScene(scene);
        stage.show();

        PauseTransition p = new PauseTransition(Duration.millis(800));
        p.setOnFinished(e -> {
            try {
                Field rf = LauncherApp.class.getDeclaredField("root");
                rf.setAccessible(true);
                BorderPane root = (BorderPane) rf.get(app);
                WritableImage img = root.snapshot(null, null);
                PixelReader pr = img.getPixelReader();
                int w = (int) img.getWidth();
                int h = (int) img.getHeight();
                BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        bi.setRGB(x, y, pr.getArgb(x, y));
                    }
                }
                ImageIO.write(bi, "png", new java.io.File(System.getenv("PREVIEW_OUT")));
                System.out.println("SNAP_OK");
            } catch (Throwable t) {
                System.out.println("SNAP_FAIL " + t);
                t.printStackTrace();
            }
            Platform.exit();
        });
        p.play();
    }

    public static void main(String[] a) {
        launch(a);
    }
}
