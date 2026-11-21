import com.jwh.launcher.ui.Splash;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.stage.Stage;
import javafx.util.Duration;

/** 只显示加载页并保持 6 秒，用来单独验证透明窗口能否正常出现。 */
public class ProbeSplash extends Application {
    @Override
    public void start(Stage s) {
        try {
            Splash.show();
            System.out.println("SHOW_OK");
        } catch (Throwable t) {
            System.out.println("SHOW_FAIL " + t);
            t.printStackTrace();
        }
        Splash.progress("探针：加载页测试", 0.45);
        PauseTransition p = new PauseTransition(Duration.seconds(6));
        p.setOnFinished(e -> Splash.finish());
        p.play();
    }

    public static void main(String[] a) {
        launch(a);
    }
}
