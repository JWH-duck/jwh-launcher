import java.net.*;
import java.nio.charset.StandardCharsets;

public class Probe2 {
    static void p(String s) { System.out.println(s); System.out.flush(); }

    public static void main(String[] a) throws Exception {
        String host = "api.modrinth.com";
        InetAddress[] addrs = InetAddress.getAllByName(host);
        p("DNS order:");
        for (InetAddress ad : addrs) p("  " + ad.getHostAddress() + "  v6=" + (ad instanceof Inet6Address));
        p("proxy host=" + System.getProperty("http.proxyHost") + " port=" + System.getProperty("http.proxyPort"));

        for (InetAddress ad : addrs) {
            long t0 = System.nanoTime();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ad, 443), 8000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                p("TCP OK  " + ad.getHostAddress() + " " + ms + "ms");
            } catch (Exception e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                p("TCP FAIL " + ad.getHostAddress() + " " + ms + "ms " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        // 原生 HttpsURLConnection 对比 JDK HttpClient
        p("--- HttpsURLConnection ---");
        long t0 = System.nanoTime();
        try {
            HttpURLConnection uc = (HttpURLConnection) new URL("https://" + host + "/v2/search?limit=1").openConnection();
            uc.setConnectTimeout(8000);
            uc.setReadTimeout(15000);
            uc.setRequestProperty("User-Agent", "JWH-Launcher/1.0");
            int sc = uc.getResponseCode();
            p("URLConn OK " + sc + " " + (System.nanoTime() - t0) / 1_000_000 + "ms");
        } catch (Exception e) {
            p("URLConn FAIL " + e.getClass().getName() + ": " + e.getMessage() + " " + (System.nanoTime() - t0) / 1_000_000 + "ms");
        }
    }
}
