package com.jwh.launcher.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 本地 Yggdrasil 皮肤服务：配合 authlib-injector 给离线账号注入皮肤。
 * 仅监听 127.0.0.1 随机端口，游戏退出时由调用方 stop()。
 */
public class SkinServer {

    private final HttpServer server;
    private final Path skinsDir;
    private final Function<String, String> nameLookup; // uuid(无横杠) -> 玩家名
    private final Function<String, Path> capeLookup; // uuid(无横杠) -> 披风文件路径，无则 null
    private final int port;

    public SkinServer(Path skinsDir, Function<String, String> nameLookup, Function<String, Path> capeLookup) throws IOException {
        this.skinsDir = skinsDir;
        this.nameLookup = nameLookup;
        this.capeLookup = capeLookup;
        Files.createDirectories(skinsDir);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
    }

    public int port() {
        return port;
    }

    /** authlib-injector 使用的 yggdrasil API 根地址。 */
    public String apiRoot() {
        return "http://127.0.0.1:" + port + "/api/yggdrasil";
    }

    public void stop() {
        try {
            server.stop(0);
        } catch (Exception ignored) {
        }
    }

    private void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("GET".equals(method) && path.startsWith("/skins/")) {
                serveSkin(ex, path);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/capes/")) {
                serveCape(ex, path);
                return;
            }
            if ("GET".equals(method) && (path.equals("/api/yggdrasil") || path.equals("/api/yggdrasil/"))) {
                respond(ex, 200, "application/json; charset=utf-8", metaJson().toString());
                return;
            }
            if (path.startsWith("/api/yggdrasil/sessionserver/session/minecraft/profile/")) {
                respondProfile(ex, lastSegment(path));
                return;
            }
            if (path.contains("/session/minecraft/hasJoined")) {
                String name = query(ex, "username");
                String uuid = name == null ? null : OSUtil.offlineUuid(name).replace("-", "");
                respondProfile(ex, uuid);
                return;
            }
            if (path.contains("/session/minecraft/join")) {
                respond(ex, 204, "text/plain", "");
                return;
            }
            if (path.contains("/authserver/")) {
                respond(ex, 200, "application/json", "{}");
                return;
            }
            respond(ex, 404, "text/plain", "not found");
        } catch (Exception e) {
            try {
                respond(ex, 500, "text/plain", "error");
            } catch (Exception ignored) {
            }
        } finally {
            try {
                ex.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** authlib-injector 拉取的 Yggdrasil meta（服务信息；不提供签名公钥，会话响应不校验）。 */
    private JSONObject metaJson() {
        JSONObject meta = new JSONObject();
        meta.put("serverName", "JWH Launcher");
        meta.put("implementationName", "JWH-SkinServer");
        meta.put("implementationVersion", "1.0.0");
        meta.put("feature.non_email_login", true);
        meta.put("feature.username_login", true);
        JSONObject root = new JSONObject();
        root.put("meta", meta);
        root.put("skinDomains", new JSONArray().put("127.0.0.1"));
        root.put("serverName", "JWH Launcher");
        return root;
    }

    private void respondProfile(HttpExchange ex, String uuidNoDash) throws IOException {
        if (uuidNoDash == null || uuidNoDash.isBlank()) {
            respond(ex, 404, "application/json", "{}");
            return;
        }
        String name = nameLookup == null ? null : nameLookup.apply(uuidNoDash);
        if (name == null || name.isBlank()) name = "Steve";
        boolean hasSkin = Files.isRegularFile(skinsDir.resolve(uuidNoDash + ".png"));
        Path cape = capeLookup == null ? null : capeLookup.apply(uuidNoDash);
        boolean hasCape = cape != null && Files.isRegularFile(cape);
        respond(ex, 200, "application/json; charset=utf-8", profileJson(uuidNoDash, name, hasSkin, hasCape).toString());
    }

    private JSONObject profileJson(String uuidNoDash, String name, boolean hasSkin, boolean hasCape) {
        JSONObject payload = new JSONObject();
        payload.put("timestamp", System.currentTimeMillis());
        payload.put("profileId", uuidNoDash);
        payload.put("profileName", name);
        JSONObject textures = new JSONObject();
        if (hasSkin) {
            JSONObject skin = new JSONObject();
            skin.put("url", "http://127.0.0.1:" + port + "/skins/" + uuidNoDash + ".png");
            textures.put("SKIN", skin);
        }
        if (hasCape) {
            JSONObject cape = new JSONObject();
            cape.put("url", "http://127.0.0.1:" + port + "/capes/" + uuidNoDash + ".png");
            textures.put("CAPE", cape);
        }
        if (!textures.isEmpty()) {
            payload.put("textures", textures);
        }
        String value = Base64.getEncoder().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject prop = new JSONObject();
        prop.put("name", "textures");
        prop.put("value", value);
        JSONObject out = new JSONObject();
        out.put("id", uuidNoDash);
        out.put("name", name);
        out.put("properties", new JSONArray().put(prop));
        return out;
    }

    private void serveSkin(HttpExchange ex, String path) throws IOException {
        String seg = lastSegment(path);
        if (seg.contains("..") || seg.contains("/") || seg.contains("\\")) {
            respond(ex, 404, "text/plain", "no skin");
            return;
        }
        Path f = skinsDir.resolve(seg);
        if (!Files.isRegularFile(f)) {
            respond(ex, 404, "text/plain", "no skin");
            return;
        }
        byte[] data = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    /** 披风纹理：按 uuid 经 capeLookup 取文件，不按 URL 文件名拼路径，杜绝目录穿越。 */
    private void serveCape(HttpExchange ex, String path) throws IOException {
        String seg = lastSegment(path);
        if (seg.contains("..") || seg.contains("/") || seg.contains("\\")) {
            respond(ex, 404, "text/plain", "no cape");
            return;
        }
        String uuid = seg.replaceFirst("(?i)\\.png$", "");
        Path f = capeLookup == null ? null : capeLookup.apply(uuid);
        if (f == null || !Files.isRegularFile(f)) {
            respond(ex, 404, "text/plain", "no cape");
            return;
        }
        byte[] data = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        String s = i >= 0 ? path.substring(i + 1) : path;
        int q = s.indexOf('?');
        return q >= 0 ? s.substring(0, q) : s;
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                return URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void respond(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        if (code == 204) {
            ex.sendResponseHeaders(204, -1);
        } else if (b.length == 0) {
            ex.sendResponseHeaders(code, -1);
        } else {
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    /** 把内置 authlib-injector.jar 导出到目标目录。总是覆盖，防止残留旧版导致新版 MC 进试玩。 */
    public static Path exportAgent(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path out = dir.resolve("authlib-injector.jar");
        try (InputStream in = com.jwh.launcher.util.Resources.open("authlib-injector.jar")) {
            if (in == null) throw new IOException("程序内置的 authlib-injector.jar 缺失，请使用完整版启动器压缩包，或等待资源层更新补齐");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }
}
