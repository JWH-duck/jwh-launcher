import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.io.*;

public class Probe3 {
    static void p(PrintWriter w, String s) { w.println(s); w.flush(); }

    public static void main(String[] a) throws Exception {
        PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
                "C:/Users/33095/Doubao/chats/2026-10-21/new-chat/jwh-launcher/tools/probe3-out.txt"), "UTF-8"));
        String url = "https://api.modrinth.com/v2/search?limit=1";

        HttpClient def = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        test(w, "default(http2)", def, url);

        HttpClient v11 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        test(w, "http1.1", v11, url);

        w.close();
    }

    static void test(PrintWriter w, String tag, HttpClient c, String url) {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", "JWH-Launcher/1.0").GET().build();
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
            p(w, tag + " OK " + resp.statusCode() + " " + (System.nanoTime() - t0) / 1_000_000 + "ms");
        } catch (Exception e) {
            p(w, tag + " FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage() + " " + (System.nanoTime() - t0) / 1_000_000 + "ms");
        }
    }
}
