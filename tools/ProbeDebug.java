import com.jwh.launcher.ui.SkinCanvas;
import javafx.application.Application;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

/** 离线反射打印头部各面的 UV / 法线 / 主色，核对侧面、后面是否采到前面肤色。 */
public class ProbeDebug extends Application {

    static int fInt(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        return f.getInt(o);
    }
    static boolean fBool(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        return f.getBoolean(o);
    }
    static double fDbl(Object o, String n) throws Exception {
        Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        return f.getDouble(o);
    }

    @Override
    public void start(Stage stage) throws Exception {
        String skin = System.getenv("SKIN_PATH");
        SkinCanvas c = new SkinCanvas(302, 300);
        System.out.println("SET_SKIN=" + c.setSkin(Path.of(skin)));

        Field facesF = SkinCanvas.class.getDeclaredField("faces");
        facesF.setAccessible(true);
        List<?> faces = (List<?>) facesF.get(c);

        Field px = SkinCanvas.class.getDeclaredField("pixels");
        px.setAccessible(true);
        int[] pixels = (int[]) px.get(c);

        int idx = 0;
        for (Object f : faces) {
            int part = fInt(f, "part");
            if (part != 1) continue;
            int ux = fInt(f, "ux"), uy = fInt(f, "uy"), uw = fInt(f, "uw"), uh = fInt(f, "uh");
            boolean outer = fBool(f, "outer");
            double nx = fDbl(f, "nx"), ny = fDbl(f, "ny"), nz = fDbl(f, "nz");

            int r = 0, g = 0, b = 0, n = 0;
            for (int y = uy; y < uy + uh; y++) {
                for (int x = ux; x < ux + uw; x++) {
                    if (x < 0 || y < 0 || x >= 64 || y >= 64) continue;
                    int argb = pixels[y * 64 + x];
                    if (((argb >>> 24) & 0xFF) >= 128) {
                        r += (argb >> 16) & 0xFF; g += (argb >> 8) & 0xFF; b += argb & 0xFF; n++;
                    }
                }
            }
            String rgb = n == 0 ? "透明" : String.format("#%02X%02X%02X", r / n, g / n, b / n);
            System.out.printf("face#%d outer=%b UV=(%d,%d,%d,%d) n=(%.1f,%.1f,%.1f) avg=%s opaque=%d%n",
                    idx, outer, ux, uy, uw, uh, nx, ny, nz, rgb, n);
            idx++;
        }
        System.exit(0);
    }

    public static void main(String[] a) { launch(a); }
}
