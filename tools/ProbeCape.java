import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.auth.AuthService;
import com.jwh.launcher.config.ConfigManager;
import com.jwh.launcher.config.LauncherConfig;
import com.jwh.launcher.ui.SkinsView;
import com.jwh.launcher.util.SkinServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 离屏验证披风链路（A+B）：
 *  1. UI 层：离线账号绑定本地披风后，预览 hasCape、勾选框/按钮文案与移除按钮可见性回显正确。
 *  2. 服务层：本地 SkinServer profile 下发 textures.CAPE，/capes/ 路由可取到披风 PNG；无披风账号不带 CAPE。
 * 环境变量：OUTDIR 临时输出目录（独立 mc 目录 + 临时 config）。
 */
public class ProbeCape extends Application {

    static Object field(Object o, String n) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        return f.get(o);
    }

    static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static int httpCode(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    static BufferedImage makePng(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, rgb);
        return img;
    }

    @Override
    public void start(Stage stage) throws Exception {
        String outDir = System.getenv("OUTDIR");
        Path mc = Path.of(outDir, "mc");
        Path skins = mc.resolve("skins");
        Path lib = skins.resolve("library");
        Files.createDirectories(lib);

        // 测试皮肤 64×64、披风 64×32
        ImageIO.write(makePng(64, 64, 0xFF88AA44), "png", lib.resolve("cow.png").toFile());
        ImageIO.write(makePng(64, 32, 0xFF3355CC), "png", lib.resolve("CapeHero-cape.png").toFile());

        ConfigManager.setConfigFile(Path.of(outDir, "probe-config.json"));
        LauncherConfig cfg = new LauncherConfig();
        cfg.minecraftDir = mc.toString();

        LauncherApp app = new LauncherApp();
        app.config = cfg;
        app.auth = new AuthService(cfg);
        app.auth.addOfflineAccount("CapeHero");
        LauncherConfig.Account acc = cfg.accounts.get(0);
        acc.capeFile = "CapeHero-cape.png";
        acc.skinFile = "cow.png";

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color:#20242c;");
        Scene scene = new Scene(root, 900, 560);
        stage.setScene(scene);
        stage.show();

        Platform.runLater(() -> {
            try {
                SkinsView v = new SkinsView(app);
                root.setCenter(v.root());

                Object preview = field(v, "preview");
                boolean hasCape = (Boolean) preview.getClass().getMethod("hasCape").invoke(preview);
                CheckBox capeBox = (CheckBox) field(v, "capeBox");
                Button pick = (Button) field(v, "capePickBtn");
                Button clear = (Button) field(v, "capeClearBtn");

                System.out.println("UI hasCape=" + hasCape
                        + " boxText=" + capeBox.getText()
                        + " boxSelected=" + capeBox.isSelected()
                        + " pickText=" + pick.getText()
                        + " clearVisible=" + clear.isVisible());

                WritableImage img = root.snapshot(null, null);
                PixelReader pr = img.getPixelReader();
                int w = (int) img.getWidth(), h = (int) img.getHeight();
                BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++)
                        bi.setRGB(x, y, pr.getArgb(x, y));
                ImageIO.write(bi, "png", new java.io.File(outDir, "cape_ui.png"));

                // —— 服务层：本地 SkinServer 披风下发 ——
                String uuidNo = acc.uuid.replace("-", "");
                SkinServer ss = new SkinServer(skins,
                        u -> acc.userName,
                        u -> {
                            if (u.equals(uuidNo)) {
                                Path p = lib.resolve(acc.capeFile);
                                return Files.isRegularFile(p) ? p : null;
                            }
                            return null;
                        });
                String profile = httpGet(ss.apiRoot() + "/sessionserver/session/minecraft/profile/" + uuidNo);
                JSONObject rootj = new JSONObject(profile);
                JSONArray props = rootj.getJSONArray("properties");
                String value = props.getJSONObject(0).getString("value");
                JSONObject tex = new JSONObject(new String(Base64.getDecoder().decode(value),
                        java.nio.charset.StandardCharsets.UTF_8));
                JSONObject textures = tex.getJSONObject("textures");
                boolean hasCapeTex = textures.has("CAPE");
                String capeUrl = hasCapeTex ? textures.getJSONObject("CAPE").getString("url") : "";
                int capeCode = hasCapeTex ? httpCode(capeUrl) : -1;

                // 第二个无披风账号：profile 不应带 CAPE
                app.auth.addOfflineAccount("NoCape");
                LauncherConfig.Account no = cfg.accounts.get(1);
                String noUuid = no.uuid.replace("-", "");
                SkinServer ss2 = new SkinServer(skins, u -> no.userName, u -> null);
                String prof2 = httpGet(ss2.apiRoot() + "/sessionserver/session/minecraft/profile/" + noUuid);
                JSONObject r2 = new JSONObject(prof2);
                String v2 = r2.getJSONArray("properties").getJSONObject(0).getString("value");
                JSONObject t2 = new JSONObject(new String(Base64.getDecoder().decode(v2),
                        java.nio.charset.StandardCharsets.UTF_8));
                boolean noCapeTex = t2.has("textures") && t2.getJSONObject("textures").has("CAPE");

                System.out.println("SERVER capeTex=" + hasCapeTex + " capeUrl=" + capeUrl + " capeCode=" + capeCode);
                System.out.println("SERVER noCapeAccountHasCape=" + noCapeTex);
                ss.stop();
                ss2.stop();

                boolean ok = hasCape && hasCapeTex && capeCode == 200 && !noCapeTex;
                System.out.println(ok ? "PROBE_OK" : "PROBE_FAIL");
            } catch (Throwable t) {
                System.out.println("PROBE_EXCEPTION");
                t.printStackTrace();
            }
            Platform.exit();
        });
    }

    public static void main(String[] a) {
        launch(a);
    }
}
