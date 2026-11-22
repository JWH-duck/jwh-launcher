import com.jwh.launcher.ui.SkinCanvas;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * 离屏渲染单张皮肤，导出正面/背面帧，用于核对背面手臂、后脑勺贴图是否透明。
 * 环境变量：SKIN_PATH 皮肤 PNG；SLIM=true/false；YAW 角度；OUT 输出 PNG。
 */
public class ProbeSkin extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        String skin = System.getenv("SKIN_PATH");
        String slim = System.getenv("SLIM");
        String yaw = System.getenv("YAW");
        String out = System.getenv("OUT");

        SkinCanvas canvas = new SkinCanvas(302.0, 300.0);
        boolean ok = canvas.setSkin(Path.of(skin));
        System.out.println("SET_SKIN=" + ok);
        canvas.setSlimOverride(Boolean.parseBoolean(slim));
        canvas.setYaw(Double.parseDouble(yaw == null ? "22.5" : yaw));
        canvas.setPitch(7);
        canvas.render();

        BorderPane root = new BorderPane(canvas);
        root.setStyle("-fx-background-color:#e8eef5;");
        Scene scene = new Scene(root, 302, 300);
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            try {
                WritableImage img = root.snapshot(null, null);
                PixelReader pr = img.getPixelReader();
                int w = (int) img.getWidth(), h = (int) img.getHeight();
                BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int transparent = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int argb = pr.getArgb(x, y);
                        bi.setRGB(x, y, argb);
                        if (((argb >>> 24) & 0xFF) < 128) transparent++;
                    }
                }
                ImageIO.write(bi, "png", new java.io.File(out));
                System.out.println("SNAP_OK " + out + " transparentPx=" + transparent);
            } catch (Throwable t) {
                System.out.println("SNAP_FAIL " + t);
                t.printStackTrace();
            }
            Platform.exit();
        });
    }

    public static void main(String[] a) {
        launch(a);
    }
}
