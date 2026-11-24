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
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 离屏截 action=3（待机动）各相位帧，核对双臂交替上举 / 平展过渡姿态。
 * 环境变量：SKIN_PATH 皮肤 PNG；OUTDIR 输出目录；ACTION 动作编号(默认3)；
 * PHASES 逗号分隔的动作进度 0..1（默认覆盖右臂举→平展→左臂举→收回）。
 */
public class ProbeAction extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        String skin = System.getenv("SKIN_PATH");
        String outDir = System.getenv("OUTDIR");
        int action = Integer.parseInt(System.getenv().getOrDefault("ACTION", "3"));
        String phases = System.getenv().getOrDefault("PHASES", "0.06,0.18,0.34,0.40,0.55,0.70,0.86,0.95");

        SkinCanvas canvas = new SkinCanvas(302.0, 300.0);
        boolean ok = canvas.setSkin(Path.of(skin));
        System.out.println("SET_SKIN=" + ok);
        canvas.setYaw(22.5);
        canvas.setPitch(7);

        Field fAction = SkinCanvas.class.getDeclaredField("action");
        fAction.setAccessible(true);
        Field fTime = SkinCanvas.class.getDeclaredField("actionTime");
        fTime.setAccessible(true);
        Field fAnim = SkinCanvas.class.getDeclaredField("animTime");
        fAnim.setAccessible(true);

        List<Double> ps = new ArrayList<>();
        for (String s : phases.split(",")) ps.add(Double.parseDouble(s.trim()));

        BorderPane root = new BorderPane(canvas);
        root.setStyle("-fx-background-color:#e8eef5;");
        Scene scene = new Scene(root, 302, 300);
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            try {
                double dur = action == 2 ? 1.8 : (action == 3 ? 2.0 : 1.5);
                for (int i = 0; i < ps.size(); i++) {
                    double p = ps.get(i);
                    fAction.setInt(canvas, action);
                    fTime.setDouble(canvas, p * dur);
                    fAnim.setDouble(canvas, 0.5);
                    canvas.render();
                    WritableImage img = root.snapshot(null, null);
                    PixelReader pr = img.getPixelReader();
                    int w = (int) img.getWidth(), h = (int) img.getHeight();
                    BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < w; x++)
                            bi.setRGB(x, y, pr.getArgb(x, y));
                    String name = String.format("act%d_p%02d.png", action, (int) Math.round(p * 100));
                    ImageIO.write(bi, "png", new java.io.File(outDir, name));
                    System.out.println("FRAME_OK " + name + " p=" + p);
                }
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
