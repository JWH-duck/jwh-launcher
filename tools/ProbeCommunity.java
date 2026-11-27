import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.ui.CommunityView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

/**
 * 隔离构建社区页，打印白框 / 轮播层 / 卡片 / 圆点的真实布局尺寸，定位卡片不显示或尺寸异常。
 * 截图路径由环境变量 PREVIEW_OUT 指定。
 */
public class ProbeCommunity extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        LauncherApp app = new LauncherApp();
        Method m = LauncherApp.class.getDeclaredMethod("ensureCommunity");
        m.setAccessible(true);
        CommunityView cv = (CommunityView) m.invoke(app);
        Node root = cv.root();
        Scene scene = new Scene((Parent) root, 1000, 650);
        stage.setScene(scene);
        stage.show();

        javafx.animation.PauseTransition p = new javafx.animation.PauseTransition(Duration.millis(900));
        p.setOnFinished(e -> {
            dump(root, 0);
            try {
                WritableImage img = root.snapshot(null, null);
                BufferedImage bi = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < img.getHeight(); y++)
                    for (int x = 0; x < img.getWidth(); x++)
                        bi.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                String out = System.getenv("PREVIEW_OUT");
                if (out != null) ImageIO.write(bi, "png", new java.io.File(out));
                System.out.println("SNAP_OK");
            } catch (Throwable t) {
                t.printStackTrace();
            }
            Platform.exit();
        });
        p.play();
    }

    static void dump(Node n, int d) {
        double lw = n.getLayoutBounds().getWidth();
        double lh = n.getLayoutBounds().getHeight();
        double pw = n instanceof Region r ? r.getPrefWidth() : -1;
        double ph = n instanceof Region r ? r.getPrefHeight() : -1;
        System.out.println("  ".repeat(d) + n.getClass().getSimpleName()
                + " layout=" + Math.round(lw) + "x" + Math.round(lh)
                + " pref=" + Math.round(pw) + "," + Math.round(ph)
                + " vis=" + n.isVisible());
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) dump(c, d + 1);
        }
    }

    public static void main(String[] a) {
        launch(a);
    }
}
