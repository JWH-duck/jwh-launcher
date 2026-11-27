import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.game.GameLauncher;
import com.jwh.launcher.ui.Ui;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.scene.text.Font;
import javafx.scene.paint.Color;
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
        Ui.applyStylesheet(scene);
        if (System.getenv("NAV_EXPAND") != null) {
            Method ex = LauncherApp.class.getDeclaredMethod("setNavExpanded", boolean.class);
            ex.setAccessible(true);
            ex.invoke(app, true);
        }
        stage.setScene(scene);
        stage.show();

        String probePage = System.getenv("PROBE_PAGE");
        if ("community".equals(probePage)) {
            Method sc = LauncherApp.class.getDeclaredMethod("showCommunity");
            sc.setAccessible(true);
            sc.invoke(app);
        }

        PauseTransition p = new PauseTransition(Duration.millis(800));
        p.setOnFinished(e -> {
            try {
                Field nf = LauncherApp.class.getDeclaredField("navLayer");
                nf.setAccessible(true);
                javafx.scene.layout.AnchorPane nl = (javafx.scene.layout.AnchorPane) nf.get(app);
                for (javafx.scene.Node c : nl.getChildren()) {
                    System.out.println("NAV layoutX=" + c.getLayoutX() + " layoutY=" + c.getLayoutY());
                }
                Field cf = LauncherApp.class.getDeclaredField("content");
                cf.setAccessible(true);
                StackPane content = (StackPane) cf.get(app);
                Field rf = LauncherApp.class.getDeclaredField("root");
                rf.setAccessible(true);
                BorderPane root = (BorderPane) rf.get(app);
                Field sf = LauncherApp.class.getDeclaredField("shell");
                sf.setAccessible(true);
                StackPane shell = (StackPane) sf.get(app);
                diag("root", root);
                diag("content", content);
                diag("shell", shell);
                for (javafx.scene.Node c : content.getChildren()) {
                    dump(c, 0);
                }

                if ("community".equals(probePage) && "1".equals(System.getenv("FACTS_DIAG"))) {
                    ScrollPane sp = findScroll(content);
                    if (sp == null) {
                        System.out.println("FACTS_DIAG: scrollpane not found");
                        finishSnap(shell);
                        Platform.exit();
                    } else {
                        VBox facts = (VBox) sp.getContent();
                        VBox fact = (VBox) sp.getParent();
                        dumpFact("BASE", fact, sp, facts);
                        String longText = "……这是一条特别特别长的事实文本，用来测试面板在内容变成多行高文本时面板整体高度是否会被顶动、布局是否保持恒定不变，它应该只在内部滚动而不改变外面板的尺寸。".repeat(2);
                        facts.getChildren().clear();
                        for (int i = 0; i < 3; i++) facts.getChildren().add(makeFlow(longText));
                        scene.getRoot().requestLayout();
                        PauseTransition q = new PauseTransition(Duration.millis(300));
                        q.setOnFinished(ev -> {
                            dumpFact("LONG", fact, sp, facts);
                            finishSnap(shell);
                            Platform.exit();
                        });
                        q.play();
                        return;
                    }
                }

                finishSnap(shell);
            } catch (Throwable t) {
                System.out.println("SNAP_FAIL " + t);
                t.printStackTrace();
            }
            Platform.exit();
        });
        p.play();
    }

    private static void diag(String name, javafx.scene.layout.Region r) {
        System.out.println("[DIAG] " + name
                + " minW=" + Math.round(r.minWidth(-1)) + " minH=" + Math.round(r.minHeight(-1))
                + " prefW=" + Math.round(r.prefWidth(-1)) + " prefH=" + Math.round(r.prefHeight(-1))
                + " maxW=" + Math.round(r.getMaxWidth()) + " maxH=" + Math.round(r.getMaxHeight())
                + " layoutW=" + Math.round(r.getLayoutBounds().getWidth())
                + " layoutH=" + Math.round(r.getLayoutBounds().getHeight()));
    }

    private static void dump(javafx.scene.Node n, int d) {
        String cls = n.getClass().getSimpleName();
        System.out.println("  ".repeat(d) + cls + " x=" + Math.round(n.getLayoutX()) + " y=" + Math.round(n.getLayoutY())
                + " w=" + Math.round(n.getLayoutBounds().getWidth()) + " h=" + Math.round(n.getLayoutBounds().getHeight())
                + " vis=" + n.isVisible());
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) dump(c, d + 1);
        }
    }

    private static ScrollPane findScroll(javafx.scene.Node n) {
        if (n instanceof ScrollPane sp) return sp;
        if (n instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                ScrollPane r = findScroll(c);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static TextFlow makeFlow(String text) {
        Text t = new Text("• " + text);
        t.setFont(Font.font(12.5));
        t.setFill(Color.rgb(255, 255, 255, 0.72));
        TextFlow f = new TextFlow(t);
        f.setMinWidth(0);
        f.setMaxWidth(Double.MAX_VALUE);
        return f;
    }

    private static void dumpFact(String tag, VBox fact, ScrollPane sp, VBox facts) {
        System.out.println("[FACTS " + tag + "]"
                + " fact layout WxH=" + Math.round(fact.getLayoutBounds().getWidth()) + "x" + Math.round(fact.getLayoutBounds().getHeight())
                + " prefW=" + Math.round(fact.prefWidth(-1)) + " minW=" + Math.round(fact.minWidth(-1))
                + " prefH=" + Math.round(fact.prefHeight(-1)) + " minH=" + Math.round(fact.minHeight(-1))
                + " | scroller layoutW=" + Math.round(sp.getLayoutBounds().getWidth())
                + " layoutH=" + Math.round(sp.getLayoutBounds().getHeight())
                + " | facts layout WxH=" + Math.round(facts.getLayoutBounds().getWidth()) + "x" + Math.round(facts.getLayoutBounds().getHeight())
                + " facts prefW=" + Math.round(facts.prefWidth(-1)) + " facts prefH=" + Math.round(facts.prefHeight(-1)));
    }

    private static void finishSnap(StackPane shell) {
        try {
            WritableImage img = shell.snapshot(null, null);
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
        }
    }

    public static void main(String[] a) {
        launch(a);
    }
}
