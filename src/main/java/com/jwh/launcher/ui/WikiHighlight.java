package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;

/**
 * minecraft.wiki 首页「精选更新」卡的数据层与渲染层（主页、社区页白框轮播共用）。
 * <p>
 * 站点是 Nuxt 单页应用且带 Cloudflare，WebView 直连会被拦；这里沿用主页已验证的
 * 方案：Java HttpClient 抓首页 HTML → 正则解析两张卡 → 图片落盘缓存 → 原生控件拼卡。
 * 启动先读上次成功抓到的缓存，再后台拉官网刷新；官网挂了也至少显示上一次真实内容，
 * 缓存与网络都失败时退回内置兜底，保证任何情况下卡片都不是空白。
 */
public final class WikiHighlight {

    private WikiHighlight() {
    }

    /** 一张精选卡。 */
    public static final class Card {
        public String imageUrl = "";
        public String title = "";
        public String link = "";
        public String tagText = "";
        public boolean tagUpcoming;
        public final List<String> linkIcons = new ArrayList<>();
        public final List<String> linkTexts = new ArrayList<>();
    }

    private static final String WIKI_HOME = "https://minecraft.wiki/";

    /** 卡片文案汉化表：官网英文，本地显示中文；未命中保留英文。 */
    private static final Map<String, String> WIKI_CN = new HashMap<>();

    static {
        WIKI_CN.put("Wilderness Bound", "荒野之旅");
        WIKI_CN.put("Latest", "最新");
        WIKI_CN.put("Fourth Drop 2026", "2026年第四次更新");
        WIKI_CN.put("Upcoming", "即将推出");
        WIKI_CN.put("Dappled Forest", "斑驳森林");
        WIKI_CN.put("Abandoned Camp", "废弃营地");
        WIKI_CN.put("Straw Bed", "稻草床");
        WIKI_CN.put("Cushion", "坐垫");
        WIKI_CN.put("Ice Caves", "冰洞");
        WIKI_CN.put("Frostbite", "冻伤");
        WIKI_CN.put("Ice Ball", "冰球");
        WIKI_CN.put("Icicle", "冰柱");
        WIKI_CN.put("Ice Crystal", "冰晶");
    }

    private static String wikiCn(String en) {
        if (en == null) return "";
        String v = WIKI_CN.get(en.trim());
        return v != null ? v : en.trim();
    }

    // ---------- 磁盘缓存（APPDATA，断网可出图，重启仍在） ----------

    private static Path cacheDir() {
        String appdata = System.getenv("APPDATA");
        Path base = (appdata != null && !appdata.isEmpty())
                ? Path.of(appdata, "JWH-Launcher", "cache", "wiki")
                : Path.of(System.getProperty("java.io.tmpdir"), "jwh-wiki-cache");
        try { Files.createDirectories(base); } catch (Exception ignored) { }
        return base;
    }

    private static Path cardsFile() {
        return cacheDir().resolve("cards.properties");
    }

    private static String cacheName(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            String clean = url;
            int q = clean.indexOf('?');
            if (q >= 0) clean = clean.substring(0, q);
            int dot = clean.lastIndexOf('.');
            String ext = (dot > clean.lastIndexOf('/')) ? clean.substring(dot) : ".png";
            if (ext.length() > 5) ext = ".png";
            return sb + ext;
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode()) + ".png";
        }
    }

    private static void saveCards(List<Card> cards) {
        if (cards == null || cards.size() < 2) return;
        try {
            Properties p = new Properties();
            for (int i = 0; i < 2; i++) {
                Card d = cards.get(i);
                String k = "card" + i + ".";
                p.setProperty(k + "title", d.title == null ? "" : d.title);
                p.setProperty(k + "image", d.imageUrl == null ? "" : d.imageUrl);
                p.setProperty(k + "link", d.link == null ? "" : d.link);
                p.setProperty(k + "tag", d.tagText == null ? "" : d.tagText);
                p.setProperty(k + "upcoming", d.tagUpcoming ? "1" : "0");
                p.setProperty(k + "icons", String.join("|", d.linkIcons));
                p.setProperty(k + "texts", String.join("|", d.linkTexts));
            }
            p.setProperty("savedAt", String.valueOf(System.currentTimeMillis()));
            try (var w = Files.newBufferedWriter(cardsFile(), StandardCharsets.UTF_8)) {
                p.store(w, "minecraft.wiki 首页精选卡（上次成功抓取的结果）");
            }
        } catch (Exception e) {
            System.err.println("[wiki] 缓存写入失败: " + e);
        }
    }

    private static List<Card> loadCached() {
        Path f = cardsFile();
        if (!Files.isRegularFile(f)) return null;
        try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(r);
            List<Card> out = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String k = "card" + i + ".";
                String title = p.getProperty(k + "title", "").trim();
                String image = p.getProperty(k + "image", "").trim();
                if (title.isEmpty() || image.isEmpty()) return null;
                Card d = new Card();
                d.title = title;
                d.imageUrl = image;
                d.link = p.getProperty(k + "link", "").trim();
                d.tagText = p.getProperty(k + "tag", "").trim();
                d.tagUpcoming = "1".equals(p.getProperty(k + "upcoming", "0"));
                for (String s : splitList(p.getProperty(k + "icons", ""))) d.linkIcons.add(s);
                for (String s : splitList(p.getProperty(k + "texts", ""))) d.linkTexts.add(s);
                out.add(d);
            }
            System.out.println("[wiki] 使用上次缓存 (" + p.getProperty("savedAt", "?") + ")");
            return out.size() >= 2 ? out : null;
        } catch (Exception e) {
            System.err.println("[wiki] 缓存读取失败: " + e);
            return null;
        }
    }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split("\\|")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    // ---------- 官网抓取与解析 ----------

    private static byte[] httpGetBytes(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();
        HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String fetchHtml() throws IOException, InterruptedException {
        byte[] bytes = httpGetBytes(WIKI_HOME);
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (body.length() < 5000) throw new IOException("返回内容异常（长度 " + body.length() + "）");
        return body;
    }

    private static String abs(String path) {
        if (path.startsWith("http")) return path;
        return "https://minecraft.wiki" + path;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#039;", "'").replace("&nbsp;", " ");
    }

    /** 从官网首页 HTML 解析出两张卡：大图、标题、跳转链接、角标、底部词条。 */
    private static List<Card> parseCards(String html) {
        List<Card> out = new ArrayList<>();
        if (html == null) return out;
        String[] chunks = html.split("mp-section mp-highlight");
        for (int i = 1; i < chunks.length && out.size() < 2; i++) {
            String s = chunks[i];
            // 最后一张卡之后紧跟「平台版本」区块，不截断会把里面的 sprite-text 当词条解析进来
            int cut = s.indexOf("mp-platform-wrapper");
            if (cut > 0) s = s.substring(0, cut);
            Card d = new Card();

            Matcher mImg = Pattern.compile("src=\"(/images/thumb/[^\"]+)\"").matcher(s);
            if (mImg.find()) d.imageUrl = abs(mImg.group(1));

            Matcher mTitle = Pattern.compile(
                    "class=\"mp-highlight-title\"><a href=\"([^\"]+)\"[^>]*>([^<]+)</a>").matcher(s);
            if (mTitle.find()) {
                d.link = abs(mTitle.group(1));
                d.title = unescape(mTitle.group(2)).trim();
            }

            Matcher mTag = Pattern.compile("class=\"mp-highlight-tag([^\"]*)\">([^<]+)<").matcher(s);
            if (mTag.find()) {
                d.tagUpcoming = mTag.group(1).contains("upcoming");
                d.tagText = unescape(mTag.group(2)).trim();
            }

            Matcher mLink = Pattern.compile(
                    "src=\"(/images/[^\"]+?)\"[^>]*width=\"16\"[^>]*>.*?<span class=\"sprite-text\">([^<]+)</span>").matcher(s);
            while (mLink.find() && d.linkTexts.size() < 8) {
                d.linkIcons.add(abs(mLink.group(1)));
                d.linkTexts.add(unescape(mLink.group(2)).trim());
            }
            if (d.linkTexts.isEmpty()) {
                Matcher mTxt = Pattern.compile("<span class=\"sprite-text\">([^<]+)</span>").matcher(s);
                while (mTxt.find() && d.linkTexts.size() < 8) {
                    d.linkTexts.add(unescape(mTxt.group(1)).trim());
                }
            }
            if (!d.title.isEmpty() && !d.imageUrl.isEmpty()) out.add(d);
        }
        return out;
    }

    /** 官网与缓存都拿不到时的内置兜底（与当前官网一致），保证卡片任何时候不空白。 */
    private static List<Card> defaultCards() {
        List<Card> list = new ArrayList<>();

        Card a = new Card();
        a.imageUrl = "https://minecraft.wiki/images/thumb/Wilderness_Bound_main_page_light.png/640px-Wilderness_Bound_main_page_light.png";
        a.title = "Wilderness Bound";
        a.link = "https://minecraft.wiki/w/Wilderness_Bound";
        a.tagText = "Latest";
        a.linkTexts.add("Dappled Forest");
        a.linkTexts.add("Abandoned Camp");
        a.linkTexts.add("Straw Bed");
        a.linkTexts.add("Cushion");
        a.linkIcons.add("https://minecraft.wiki/images/BiomeSprite_dappled-forest.png");
        a.linkIcons.add("https://minecraft.wiki/images/EnvSprite_abandoned-camp.png");
        a.linkIcons.add("https://minecraft.wiki/images/BlockSprite_straw-bed.png");
        a.linkIcons.add("https://minecraft.wiki/images/thumb/ItemSprite_white-cushion.png/16px-ItemSprite_white-cushion.png");
        list.add(a);

        Card b = new Card();
        b.imageUrl = "https://minecraft.wiki/images/thumb/Minwi_Mainpage_Highlight_-_Fourth_Drop_2026_Light.png/640px-Minwi_Mainpage_Highlight_-_Fourth_Drop_2026_Light.png";
        b.title = "Fourth Drop 2026";
        b.link = "https://minecraft.wiki/w/Fourth_Drop_2026";
        b.tagText = "Upcoming";
        b.tagUpcoming = true;
        b.linkTexts.add("Ice Caves");
        b.linkTexts.add("Frostbite");
        b.linkTexts.add("Ice Ball");
        b.linkTexts.add("Icicle");
        b.linkTexts.add("Ice Crystal");
        b.linkIcons.add("https://minecraft.wiki/images/BiomeSprite_ice-caves.png");
        b.linkIcons.add("https://minecraft.wiki/images/EntitySprite_frostbite.png");
        b.linkIcons.add("https://minecraft.wiki/images/ItemSprite_ice-ball.png");
        b.linkIcons.add("https://minecraft.wiki/images/Invicon_Icicle.png");
        b.linkIcons.add("https://minecraft.wiki/images/BlockSprite_ice-crystal.png");
        list.add(b);

        return list;
    }

    /** 启动即用：缓存优先，缺失回内置兜底。 */
    public static List<Card> initialCards() {
        List<Card> cached = loadCached();
        return (cached != null && cached.size() >= 2) ? cached : defaultCards();
    }

    /**
     * 后台拉官网刷新。成功（≥2 张）落盘缓存并通过回调在 UI 线程返回新列表；
     * 失败不回调，调用方保留现有内容。
     */
    public static CompletableFuture<List<Card>> refreshAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<Card> list = parseCards(fetchHtml());
                if (list != null && list.size() >= 2) {
                    saveCards(list);
                    System.out.println("[wiki] 官网卡片已更新: " + list.get(0).title + " / " + list.get(1).title);
                    return list;
                }
                return null;
            } catch (Exception e) {
                System.err.println("[wiki] 首页抓取失败: " + e);
                return null;
            }
        });
    }

    // ---------- 图片加载 ----------

    private static Image cachedCardImage(String url, int index) {
        try {
            Path f = cacheDir().resolve(cacheName(url));
            if (Files.isRegularFile(f)) {
                Image img = new Image(f.toUri().toString(), false);
                if (!img.isError() && img.getWidth() > 0) return img;
            }
        } catch (Exception ignored) { }
        try (var in = WikiHighlight.class.getResourceAsStream("/wiki_card" + (index + 1) + ".png")) {
            if (in != null) {
                Image img = new Image(in);
                if (!img.isError() && img.getWidth() > 0) return img;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static Image cachedSmallImage(String url) {
        try {
            Path f = cacheDir().resolve(cacheName(url));
            if (Files.isRegularFile(f)) {
                Image img = new Image(f.toUri().toString(), false);
                if (!img.isError() && img.getWidth() > 0) return img;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 把 16:9 原图居中裁成 2:1，别把主体切歪。 */
    private static void cropImageTo2by1(ImageView iv, Image img) {
        if (img == null || img.getWidth() < 1 || img.getHeight() < 1) return;
        double w = img.getWidth();
        double h = img.getHeight();
        double want = w / 2.0;
        if (h > want) {
            iv.setViewport(new Rectangle2D(0, (h - want) / 2.0, w, want));
        } else {
            iv.setViewport(null);
        }
    }

    /** 后台下载图片写缓存，成功后 UI 线程替换；失败保持现有图。 */
    private static void refreshImageAsync(ImageView target, String url) {
        if (url == null || url.isEmpty()) return;
        CompletableFuture.supplyAsync(() -> {
            try { return httpGetBytes(url); } catch (Exception e) { return null; }
        }).thenAccept(bytes -> {
            if (bytes == null || bytes.length < 200) return;
            try {
                Files.write(cacheDir().resolve(cacheName(url)), bytes,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception ignored) { }
            Platform.runLater(() -> {
                try {
                    Image img = new Image(new java.io.ByteArrayInputStream(bytes));
                    if (!img.isError() && img.getWidth() > 0) {
                        target.setImage(img);
                        cropImageTo2by1(target, img);
                    }
                } catch (Exception ignored) { }
            });
        });
    }

    // ---------- 卡片渲染 ----------

    /**
     * 渲染一张精选卡：背景图铺底 + 左上标题 + 右上角标 + 底部词条条；点卡片跳官网详情。
     *
     * @param card  卡片数据
     * @param index 卡片序号（兜底资源图 / 图片缓存用）
     * @param app   启动器实例，用于打开外链
     */
    public static StackPane buildCard(Card card, int index, LauncherApp app) {
        StackPane pane = new StackPane();
        // 别让封面图/词条条的原生 pref 尺寸反推卡片宽高；尺寸完全由外层容器分配。
        pane.setPrefWidth(0);
        pane.setMinWidth(0);
        pane.setMinHeight(0);
        pane.setMaxWidth(Double.MAX_VALUE);
        pane.setMaxHeight(Double.MAX_VALUE);
        pane.setStyle("-fx-background-color:" + Ui.PANEL + ";"
                + " -fx-border-color:" + Ui.BORDER + "; -fx-border-width:1;"
                + " -fx-background-radius:10; -fx-border-radius:10;");

        // 内容再宽也裁在本卡内
        Rectangle paneClip = new Rectangle();
        paneClip.widthProperty().bind(pane.widthProperty());
        paneClip.heightProperty().bind(pane.heightProperty());
        pane.setClip(paneClip);

        ImageView bg = new ImageView();
        bg.setPreserveRatio(true);
        bg.setSmooth(false);
        bg.fitWidthProperty().bind(pane.widthProperty());
        Image cached = cachedCardImage(card.imageUrl, index);
        if (cached != null) {
            bg.setImage(cached);
            cropImageTo2by1(bg, cached);
        }
        refreshImageAsync(bg, card.imageUrl);
        pane.getChildren().add(bg);

        Label title = new Label(wikiCn(card.title));
        title.setStyle("-fx-text-fill:white; -fx-font-size:19px; -fx-font-weight:bold;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.85), 7, 0.6, 0, 1);");
        StackPane.setAlignment(title, Pos.TOP_LEFT);
        StackPane.setMargin(title, new Insets(10, 0, 0, 16));
        pane.getChildren().add(title);

        if (!card.tagText.isEmpty()) {
            Label tag = new Label(wikiCn(card.tagText));
            tag.setStyle("-fx-background-color:" + (card.tagUpcoming ? "#8a63d2" : "#3fa34d") + ";"
                    + " -fx-text-fill:white; -fx-font-size:11px; -fx-font-weight:bold;"
                    + " -fx-padding:3 9 3 9; -fx-background-radius:3;");
            StackPane.setAlignment(tag, Pos.TOP_RIGHT);
            StackPane.setMargin(tag, new Insets(10, 12, 0, 0));
            pane.getChildren().add(tag);
        }

        if (!card.linkTexts.isEmpty()) {
            HBox links = new HBox(6);
            links.setAlignment(Pos.CENTER_LEFT);
            links.setPadding(new Insets(4, 12, 4, 12));
            links.setMaxWidth(Double.MAX_VALUE);
            links.setMinWidth(0);
            links.setStyle("-fx-background-color:rgba(0,0,0,0.42);");
            for (int i = 0; i < card.linkTexts.size(); i++) {
                HBox item = new HBox(4);
                item.setAlignment(Pos.CENTER_LEFT);
                ImageView icon = new ImageView();
                icon.setFitWidth(14);
                icon.setFitHeight(14);
                icon.setSmooth(false);
                String iconUrl = (i < card.linkIcons.size()) ? card.linkIcons.get(i) : null;
                if (iconUrl != null) {
                    Image ic = cachedSmallImage(iconUrl);
                    if (ic != null) icon.setImage(ic);
                    else refreshImageAsync(icon, iconUrl);
                }
                Label txt = new Label(wikiCn(card.linkTexts.get(i)));
                txt.setStyle("-fx-text-fill:white; -fx-font-size:10px;");
                item.getChildren().addAll(icon, txt);
                links.getChildren().add(item);
            }
            StackPane.setAlignment(links, Pos.BOTTOM_CENTER);
            // 保持自然高度，否则 HBox 会被 StackPane 拉成整卡高度、背景色糊满整张图
            links.setMaxHeight(Region.USE_PREF_SIZE);
            pane.getChildren().add(links);
        }

        if (card.link != null && !card.link.isEmpty()) {
            pane.setCursor(Cursor.HAND);
            pane.setOnMouseClicked(e -> {
                if (app != null) app.openUrl(card.link);
                e.consume();
            });
        }
        return pane;
    }
}
