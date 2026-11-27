import java.net.*;
import java.net.http.*;
import java.time.Duration;

public class Probe {
    public static void main(String[] a) throws Exception {
        String host = "api.modrinth.com";
        for (InetAddress ad : InetAddress.getAllByName(host)) {
            System.out.println("ADDR " + ad.getHostAddress());
        }
        System.out.println("preferIPv4Stack=" + System.getProperty("java.net.preferIPv4Stack"));
        System.out.println("preferIPv6Addresses=" + System.getProperty("java.net.preferIPv6Addresses"));
        HttpClient c = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest r = HttpRequest.newBuilder(URI.create("https://" + host + "/v2/search?limit=1"))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "JWH-Launcher/1.0")
                .GET().build();
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
            System.out.println("OK " + resp.statusCode() + " " + (System.nanoTime() - t0) / 1_000_000 + "ms len=" + resp.body().length());
        } catch (Exception e) {
            System.out.println("FAIL " + e.getClass().getName() + ": " + e.getMessage());
            Throwable x = e;
            while (x != null && x.getCause() != null && x.getCause() != x) {
                x = x.getCause();
                System.out.println("  cause: " + x.getClass().getName() + ": " + x.getMessage());
            }
        }
    }
}
