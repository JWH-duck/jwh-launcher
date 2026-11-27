import com.jwh.launcher.LauncherApp;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

/**
 * 只构建左侧导航（复用 LauncherApp.buildNav）并截图，用来核对四张透明导航图标在真实按钮里的效果。
 * 不触发任何页面切换回调；截图路径由环境变量 PREVIEW_OUT 指定。
 */
public class ProbeNav extends Application {

    @Override
    public void start(javafx.stage.Stage stage) throws Exception {
        LauncherApp app = new LauncherApp();
        Method m = LauncherApp.class.getDeclaredMethod("buildNav");
        m.setAccessible(true);
        VBox nav = (VBox) m.invoke(app);

        BorderPane root = new BorderPane();
        root.setLeft(nav);
        root.setStyle("-fx-background-color:#f3f4f6;");
        Scene scene = new Scene(root, 920, 600);
        scene.getStylesheets().add(
                LauncherApp.class.getResource("/style.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        // 强制展开导航，核对宽栏 + 文字标签显隐
        try {
            Method ex = LauncherApp.class.getDeclaredMethod("setNavExpanded", boolean.class);
            ex.setAccessible(true);
            ex.invoke(app, true);
        } catch (Throwable ignored) {
        }

        PauseTransition p = new PauseTransition(Duration.millis(700));
        p.setOnFinished(e -> {
            try {
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
                String out = System.getenv("PREVIEW_OUT");
                ImageIO.write(bi, "png", new java.io.File(out));
                System.out.println("SNAP_OK " + out);
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
