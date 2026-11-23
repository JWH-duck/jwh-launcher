package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.download.ContentService;
import com.jwh.launcher.download.CurseForgeApi;
import com.jwh.launcher.download.IconCache;
import com.jwh.launcher.download.ModrinthApi;
import com.jwh.launcher.util.CurseForgeKey;
import com.jwh.launcher.util.ModNames;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用 Modrinth 搜索下载 Tab（Mod / 材质包 复用；光影包由 ShaderTab 组合）。
 */
public class ModrinthTab {

    public enum Type {
        MOD("mod", "Mod 模组", true),
        SHADER("shader", "光影包", false),
        RESOURCEPACK("resourcepack", "材质包", false);

        public final String facet;
        public final String label;
        public final boolean loaderFilter;

        Type(String facet, String label, boolean loaderFilter) {
            this.facet = facet;
            this.label = label;
            this.loaderFilter = loaderFilter;
        }
    }

    private final LauncherApp app;
    private final Type type;
    private final ProgressSink sink;
    private final ModrinthApi api = new ModrinthApi();
    private final ListView<ModrinthApi.Hit> list = new ListView<>();
    private final TextField searchBox = new TextField();
    private final ComboBox<String> versionBox = new ComboBox<>();
    private final ComboBox<String> sourceBox = new ComboBox<>();
    private final Button searchBtn = new Button("搜索");
    private final Button refreshBtn = new Button("刷新");
    /** 版本下拉的 id → 显示名（HMCL 式：加载器实例显示 "1.21.1 · NeoForge 21.1.265"）。 */
    private final java.util.Map<String, String> displayNames = new java.util.HashMap<>();
    /** 版本下拉的 id → 纯游戏版本（neoforge-21.1.265 → 1.21.1，供搜索 API 使用）。 */
    private final java.util.Map<String, String> mcBases = new java.util.HashMap<>();
    /** 版本下拉的 id → Modrinth 加载器参数（目标版本自带加载器类别，无需单独选择）。 */
    private final java.util.Map<String, String> loaderOf = new java.util.HashMap<>();

    private final java.util.function.Consumer<com.jwh.launcher.util.Http.DownloadControl> attachCtrl;
    private final Runnable detachCtrl;

    public ModrinthTab(LauncherApp app, Type type, ProgressSink sink,
                       java.util.function.Consumer<com.jwh.launcher.util.Http.DownloadControl> attachCtrl,
                       Runnable detachCtrl) {
        this.app = app;
        this.type = type;
        this.sink = sink;
        this.attachCtrl = attachCtrl;
        this.detachCtrl = detachCtrl;
    }

    /** 真实字节进度控制：onBytes 回调自动转进度弹窗（后台线程安全），支持取消。 */
    private com.jwh.launcher.util.Http.DownloadControl bytesControl(long totalSize) {
        com.jwh.launcher.util.Http.CancelFlag flag = new com.jwh.launcher.util.Http.CancelFlag();
        return new com.jwh.launcher.util.Http.DownloadControl() {
            @Override
            public void onBytes(long bytes) {
                double frac = totalSize > 0
                        ? Math.min(1.0, (double) bytes / totalSize)
                        : Double.NaN;
                sink.progress(frac);
            }

            @Override
            public boolean isCancelled() {
                return flag.isCancelled();
            }
        };
    }

    /** 下载结束统一处理：detach 取消按钮；被取消时显示「已取消」而非错误弹窗。 */
    private boolean endDownload(com.jwh.launcher.util.Http.DownloadControl ctl, Runnable onDone) {
        detachCtrl.run();
        if (ctl != null && ctl.isCancelled()) {
            sink.progress(0);
            sink.message("已取消");
            return true;
        }
        onDone.run();
        return false;
    }

    public Tab asTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(12));

        // 目标版本（本地已装实例，HMCL 式：加载器实例显示合并名，被继承的原版隐藏）
        versionBox.setPromptText("安装到的版本（必选）");
        versionBox.setCellFactory(c -> new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });
        versionBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });

        // 数据源
        sourceBox.getItems().addAll("Modrinth（推荐）", "CurseForge");
        sourceBox.getSelectionModel().selectFirst();
        sourceBox.setPrefWidth(120);
        sourceBox.setOnAction(e -> {
            boolean cf = "CurseForge".equals(sourceBox.getValue());
            searchBox.setPromptText(cf ? "搜索模组/材质包（CurseForge）" : searchPrompt());
        });

        searchBox.setPromptText(searchPrompt());
        searchBox.setPrefWidth(280);
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        searchBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
        refreshBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");

        // 列表
        list.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";");
        list.setCellFactory(c -> new ListCell<>() {
            private final ImageView iconView = new ImageView();
            private final Label titleLabel = new Label();
            private final Label metaLabel = new Label();
            private final Label descLabel = new Label();
            private final VBox info = new VBox(2);
            private final HBox row = new HBox(10);
            private String currentIconUrl;

            {
                iconView.setFitWidth(36);
                iconView.setFitHeight(36);
                iconView.setPreserveRatio(true);
                iconView.setSmooth(true);
                titleLabel.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:14px; -fx-font-weight:bold;");
                metaLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
                descLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
                descLabel.setWrapText(true);
                descLabel.setMaxWidth(560);
                info.getChildren().addAll(titleLabel, metaLabel, descLabel);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getChildren().addAll(iconView, info);
            }

            @Override
            protected void updateItem(ModrinthApi.Hit h, boolean empty) {
                super.updateItem(h, empty);
                if (empty || h == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    titleLabel.setText(ModNames.display(h.slug(), h.title()));
                    metaLabel.setText("下载量 " + h.downloads()
                            + "    [" + String.join("/", h.categories()) + "]");
                    descLabel.setText(ModNames.desc(h.slug(), h.title(), h.description()));
                    // 官网头像：优先本地缓存（秒显），未缓存则下载成功后替换；失败保持草方块不空白
                    iconView.setImage(Ui.GRASS_BLOCK);
                    currentIconUrl = null;
                    String icon = h.iconUrl();
                    if (icon != null && !icon.isBlank()) {
                        currentIconUrl = icon;
                        Image cached = IconCache.cached(icon);
                        if (cached != null) {
                            iconView.setImage(cached);
                        } else {
                            IconCache.ensure(icon, img -> {
                                if (icon.equals(currentIconUrl)) {
                                    iconView.setImage(img);
                                }
                            });
                        }
                    }
                    setGraphic(row);
                    setText(null);
                    setStyle("-fx-background-color:transparent;");
                }
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        // 点击列表项 → 跳转全页面详情页（不再弹小窗；详情页内选择目标实例并下载）
        list.setOnMouseClicked(e -> {
            ModrinthApi.Hit h = list.getSelectionModel().getSelectedItem();
            if (h != null) openDetailPage(h);
        });

        // 顶部两行布局：第一行 数据源/目标版本（目标版本自带加载器类别，无需单独选加载器），
        // 第二行 搜索框独占整行，避免小窗口下控件互相挤压导致版本名/搜索提示被截断。
        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getChildren().addAll(new Label("数据源："), sourceBox,
                new Label("目标版本："), versionBox);
        HBox.setHgrow(versionBox, Priority.ALWAYS);
        HBox searchRow = new HBox(8);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.getChildren().addAll(searchBox, searchBtn, refreshBtn);

        searchBtn.setOnAction(e -> search());
        searchBox.setOnAction(e -> search());
        refreshBtn.setOnAction(e -> {
            refreshVersions();
            IconCache.resetFailures();   // 重置图标下载失败计数，重新尝试
            search();
        });
        box.getChildren().addAll(top, searchRow, list);
        Tab tab = new Tab(type.label, box);
        // 进入下载界面第一时间自动加载热门列表，无需手动点搜索
        tab.selectedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now) && list.getItems().isEmpty()) {
                refreshVersions();
                search();
            }
        });
        return tab;
    }

    /**
     * 刷新：填充本地可见实例下拉（HMCL 式）。
     * 下拉显示合并名（如 "1.21.1 · NeoForge 21.1.265"），值为实例 id；
     * 搜索 API 用纯游戏版本（mcBase），下载落点用实例 id 目录。
     */
    /** 版本下拉的本地实例列表（扫描放后台：版本目录多时避免阻塞 UI）。 */
    private record VersionOptions(java.util.Map<String, String> names,
                                  java.util.Map<String, String> bases,
                                  java.util.Map<String, String> loaders,
                                  List<String> items) {
    }

    public void refreshVersions() {
        Ui.async2(() -> {
            java.util.Map<String, String> names = new java.util.HashMap<>();
            java.util.Map<String, String> bases = new java.util.HashMap<>();
            java.util.Map<String, String> loaders = new java.util.HashMap<>();
            List<String> items = new ArrayList<>();
            for (var v : app.localVersions.scanVisible()) {
                names.put(v.id(), v.displayName());
                bases.put(v.id(), v.mcBase() == null || v.mcBase().isBlank() ? v.id() : v.mcBase());
                loaders.put(v.id(), loaderParam(v.loaderName()));
                items.add(v.id());
            }
            return new VersionOptions(names, bases, loaders, items);
        }, r -> {
            displayNames.clear();
            displayNames.putAll(r.names());
            mcBases.clear();
            mcBases.putAll(r.bases());
            loaderOf.clear();
            loaderOf.putAll(r.loaders());
            versionBox.getItems().setAll(r.items());
            if (versionBox.getSelectionModel().getSelectedItem() == null && !r.items().isEmpty()) {
                versionBox.getSelectionModel().selectFirst();
            }
        }, e -> { /* 扫描失败保持当前列表 */ });
    }

    /** 加载器显示名 → Modrinth/CurseForge API 的加载器参数（目标版本自带类别，自动推导）。 */
    private static String loaderParam(String loaderName) {
        if (loaderName == null || loaderName.isBlank()) return null;
        String n = loaderName.toLowerCase();
        if (n.contains("fabric")) return "fabric";
        if (n.contains("quilt")) return "quilt";
        if (n.contains("neoforge")) return "neoforge";
        if (n.contains("forge")) return "forge";
        return null;
    }

    /** 当前选中目标版本自带的加载器参数（纯原版 → null，即不限加载器）。 */
    private String selectedLoader() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        return id == null ? null : loaderOf.get(id);
    }

    /** 当前选中实例的纯游戏版本（供 Modrinth / CurseForge API 使用）。 */
    private String selectedMc() {
        String id = versionBox.getSelectionModel().getSelectedItem();
        if (id == null) return null;
        return mcBases.getOrDefault(id, id);
    }

    private String searchPrompt() {
        return type == Type.MOD ? "搜索模组，支持中英文：jei / sodium / 钠 / 小地图" : "搜索材质包，例如：XeKr / 原版高亮";
    }

    private void search() {
        String q = searchBox.getText().trim();
        String mc = selectedMc();
        String loader = type.loaderFilter ? selectedLoader() : null;
        list.getItems().clear();
        app.setStatus("正在搜索…");

        if ("CurseForge".equals(sourceBox.getValue())) {
            searchCurseForge(q, mc, loader);
            return;
        }
        Ui.async(() -> {
            List<ModrinthApi.Hit> merged = searchAll(q, mc, loader);
            Platform.runLater(() -> {
                list.getItems().setAll(merged);
                app.setStatus("找到 " + merged.size() + " 个结果");
                if (merged.isEmpty()) {
                    Ui.info("没有找到结果",
                            "「" + (q.isBlank() ? "（空关键词）" : q) + "」在 " + (mc == null ? "全部版本" : mc)
                                    + (loader != null ? " + " + loader : "") + " 下没有匹配的" + type.label + "。\n\n"
                                    + "建议：\n1. 确认目标版本是纯游戏版本（如 1.21.1 / 26.3），而不是加载器版本\n"
                                    + "2. 试试更短的关键词（如 sodium、jei，或中文名如「钠」「小地图」）\n3. 换一个较新/较旧的游戏版本");
                }
            });
            return null;
        }, ok -> {
        }, err -> {
            app.setStatus("搜索失败");
            Ui.error("搜索失败", err);
        });
    }


    /** 合并搜索：中文关键词先按汉化表扩展为英文关键词（中英文搜索），按 projectId 去重。 */
    private List<ModrinthApi.Hit> searchAll(String q, String mc, String loader) throws Exception {
        List<String> keys = new ArrayList<>();
        keys.add(q);
        if (hasHan(q)) {
            keys.addAll(ModNames.searchKeys(q));
        }
        List<ModrinthApi.Hit> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Exception firstErr = null;
        for (String key : keys) {
            if (key == null) continue;
            try {
                for (ModrinthApi.Hit h : api.search(key, apiType(), mc, loader, 60)) {
                    if (seen.add(h.projectId())) merged.add(h);
                }
            } catch (Exception ex) {
                if (firstErr == null) firstErr = ex;
            }
        }
        if (merged.isEmpty() && firstErr != null) throw firstErr;
        return merged;
    }

    /** 是否含汉字（中文搜索触发扩展）。 */
    private static boolean hasHan(String s) {
        if (s == null || s.isBlank()) return false;
        return s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }
    private void searchCurseForge(String q, String mc, String loader) {
        CurseForgeApi cf = new CurseForgeApi(CurseForgeKey.get());
        if (!cf.hasKey()) {
            openCurseForgeWeb(q, mc);
            Ui.info("需要 CurseForge API Key",
                    "已为你打开 CurseForge 网页搜索。\n\n想用启动器内搜索下载，需要免费申请 Key：\n1. 打开 https://console.curseforge.com 用 CurseForge 账号登录\n2. 点击 My API Key 生成\n3. 设置环境变量 JWH_CURSEFORGE_API_KEY（PCL 同款机制，启动器内不显示 Key）后重新启动启动器");
            return;
        }
        Ui.async(() -> {
            List<String> keys = new ArrayList<>();
            keys.add(q);
            if (hasHan(q)) keys.addAll(ModNames.searchKeys(q));
            List<ModrinthApi.Hit> merged = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Exception firstErr = null;
            for (String key : keys) {
                if (key == null) continue;
                try {
                    for (CurseForgeApi.Hit h : cf.search(key, mc, curseForgeClassId(), loader, 50)) {
                        if (seen.add(h.projectId())) {
                            merged.add(new ModrinthApi.Hit(h.projectId(), "", h.title(), h.description(),
                                    h.logoUrl(), h.downloads(), new ArrayList<>()));
                        }
                    }
                } catch (Exception ex) {
                    if (firstErr == null) firstErr = ex;
                }
            }
            if (merged.isEmpty() && firstErr != null) throw firstErr;
            Platform.runLater(() -> {
                list.getItems().setAll(merged);
                app.setStatus("找到 " + merged.size() + " 个结果");
                if (merged.isEmpty()) {
                    Ui.info("没有找到结果",
                            "「" + (q.isBlank() ? "（空关键词）" : q) + "」在 " + (mc == null ? "全部版本" : mc)
                                    + " 下没有匹配的" + type.label + "。\n\n建议：确认目标版本是纯游戏版本（如 1.21.1），试试更短的关键词或中文名。");
                }
            });
            return null;
        }, ok -> {
        }, err -> {
            app.setStatus("CurseForge 搜索失败");
            Ui.error("CurseForge 搜索失败", err);
        });
    }

    private int curseForgeClassId() {
        return switch (type) {
            case MOD -> 6;
            default -> 12; // 材质包 / 光影包
        };
    }

    /** 打开 CurseForge 网页搜索兜底。 */
    private void openCurseForgeWeb(String q, String mc) {
        try {
            String cls = type == Type.MOD ? "mods" : "texture-packs";
            String url = "https://www.curseforge.com/minecraft/search?class=" + cls
                    + "&version=" + (mc == null ? "" : mc)
                    + "&search=" + java.net.URLEncoder.encode(q == null ? "" : q, java.nio.charset.StandardCharsets.UTF_8);
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ex) {
            Ui.error("无法打开浏览器", ex);
        }
    }

    private ModrinthApi.Type apiType() {
        return switch (type) {
            case MOD -> ModrinthApi.Type.MOD;
            case SHADER -> ModrinthApi.Type.SHADER;
            default -> ModrinthApi.Type.RESOURCEPACK;
        };
    }

    /**
     * 点击列表项 → 跳转全页面详情页（取代原来的小弹窗）。
     * 页面右上角只有「返回」，不带上一页的任何选项；
     * 上方默认选中实例管理中已有的游戏版本与加载器，
     * 若没有任何与内容适配的实例，则提示「未检测到mod适配的加载器」。
     */
    /**
     * 点击列表项 → 跳转全页面详情页（取代原来的小弹窗）。
     * 页面右上角只有「返回」，不带上一页的任何选项；
     * 上方默认选中实例管理中已有的游戏版本与加载器，
     * 若没有任何与内容适配的实例，则提示「未检测到mod适配的加载器」。
     */
    private void openDetailPage(ModrinthApi.Hit hit) {
        VBox page = new VBox(12);
        page.setPadding(new Insets(20));
        // 与下载中心同款圆角面板（同色垫层铺满，圆角外不露窗口底色）
        page.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-background-radius:20; -fx-background-insets:0;"
                + "-fx-border-radius:20; -fx-border-color:rgba(31,35,40,0.18);");

        // 顶部：目标图标 + 名称 + 下载量，右上角「返回」
        javafx.scene.image.ImageView icon = new javafx.scene.image.ImageView(Ui.GRASS_BLOCK);
        icon.setFitWidth(56);
        icon.setFitHeight(56);
        icon.setPreserveRatio(true);
        icon.setSmooth(false);
        javafx.scene.shape.Rectangle iconClip = new javafx.scene.shape.Rectangle(56, 56);
        iconClip.setArcWidth(10);
        iconClip.setArcHeight(10);
        icon.setClip(iconClip);
        String iconUrl = hit.iconUrl();
        if (iconUrl != null && !iconUrl.isBlank()) {
            Image cached = IconCache.cached(iconUrl);
            if (cached != null) {
                icon.setImage(cached);
            } else {
                IconCache.ensure(iconUrl, icon::setImage);
            }
        }

        Label title = new Label(ModNames.display(hit.slug(), hit.title()));
        title.setStyle("-fx-font-size:19px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
        Label dlLabel = new Label("下载量 " + hit.downloads());
        dlLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        VBox titleBox = new VBox(2);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.getChildren().addAll(title, dlLabel);

        Button backBtn = new Button("返回");
        backBtn.setStyle("-fx-text-fill:" + Ui.TEXT + ";");

        HBox headSpacer = new HBox();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        HBox head = new HBox(12);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(icon, titleBox, headSpacer, backBtn);

        Label desc = new Label(ModNames.desc(hit.slug(), hit.title(), hit.description()));
        desc.setWrapText(true);
        desc.setMaxHeight(72);
        desc.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:13px;");

        // 目标实例：来自实例管理里已有的游戏版本与加载器
        Label sec = new Label("目标实例（游戏版本 + 加载器）");
        sec.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT_DIM + ";");
        ComboBox<String> targetBox = new ComboBox<>();
        targetBox.setPrefWidth(460);
        targetBox.setPromptText("实例管理中的游戏版本");
        targetBox.setCellFactory(c -> new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });
        targetBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String id, boolean empty) {
                super.updateItem(id, empty);
                setText(empty || id == null ? null : displayNames.getOrDefault(id, id));
            }
        });
        Label adaptHint = new Label("正在检测本地实例与内容的适配情况…");
        adaptHint.setWrapText(true);
        adaptHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        // 下载版本：直接铺成列表（像 Mod 下载列表一样），只列与所选实例（游戏版本 + 加载器）都匹配的版本
        Label verSec = new Label("下载版本（仅显示与所选实例匹配的版本）");
        verSec.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT_DIM + ";");
        // 当前筛选用的游戏版本/加载器：列表行只显示这一项，避免长串撑出横向滚动条
        final String[] curMc = {null};
        final String[] curLd = {null};
        ListView<ModrinthApi.Version> verList = new ListView<>();
        verList.setPrefHeight(232);
        verList.setMaxWidth(Double.MAX_VALUE);
        verList.setDisable(true);
        verList.setStyle("-fx-background-color:" + Ui.BG_LIGHT + "; -fx-background-radius:12;"
                + "-fx-border-radius:12; -fx-border-color:rgba(31,35,40,0.14);");
        verList.setCellFactory(lv -> new ListCell<ModrinthApi.Version>() {
            @Override
            protected void updateItem(ModrinthApi.Version v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String num = (v.versionNumber() == null || v.versionNumber().isBlank())
                        ? v.name() : v.versionNumber();
                Label numLbl = new Label(num == null ? v.id() : num);
                numLbl.setStyle("-fx-font-size:14px; -fx-font-weight:bold; -fx-text-fill:" + Ui.TEXT + ";");
                java.util.List<String> mcVers = v.gameVersions() == null
                        ? java.util.List.of() : v.gameVersions();
                java.util.List<String> lds = v.loaders() == null
                        ? java.util.List.of() : v.loaders();
                String mcShow = curMc[0] != null && mcVers.contains(curMc[0]) ? curMc[0]
                        : (mcVers.isEmpty() ? "—" : mcVers.get(0));
                String ldShow = curLd[0] != null && lds.contains(curLd[0]) ? curLd[0]
                        : (lds.isEmpty() ? "原版" : lds.get(0));
                Label metaLbl = new Label("MC " + mcShow + "  ·  " + ldShow + "  ·  " + shortDate(v.datePublished()));
                metaLbl.setStyle("-fx-font-size:12px; -fx-text-fill:" + Ui.TEXT_DIM + ";");
                VBox line = new VBox(1, numLbl, metaLbl);
                setText(null);
                setGraphic(line);
            }
        });
        Label verEmpty = new Label("该实例没有匹配的下载版本");
        verEmpty.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        verList.setPlaceholder(verEmpty);
        Label verHint = new Label("正在读取与所选实例匹配的版本…");
        verHint.setWrapText(true);
        verHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        Button downloadBtn = new Button("下载");
        downloadBtn.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;"
                + " -fx-font-weight:bold; -fx-padding:8 26;");
        downloadBtn.setDisable(true);

        HBox targetRow = new HBox(10);
        targetRow.setAlignment(Pos.CENTER_LEFT);
        Label tl = new Label("目标实例：");
        tl.setMinWidth(64);
        targetRow.getChildren().addAll(tl, targetBox);
        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_RIGHT);
        actionRow.getChildren().add(downloadBtn);

        // 页面底部留白靠 Region 撑开，让下载按钮贴到页脚（满页布局更稳）
        javafx.scene.layout.Region footSpacer = new javafx.scene.layout.Region();
        VBox.setVgrow(footSpacer, Priority.ALWAYS);

        page.getChildren().addAll(head, desc, new Separator(), sec, targetRow, adaptHint,
                verSec, verList, verHint, new Separator(), footSpacer, actionRow);

        javafx.scene.layout.StackPane wrap = new javafx.scene.layout.StackPane(page);
        wrap.setStyle("-fx-background-color:" + Ui.BG_LIGHT + ";");
        app.showFullPage("下载 " + ModNames.display(hit.slug(), hit.title()), wrap);

        backBtn.setOnAction(e -> {
            app.showDownload();
        });

        boolean cf = "CurseForge".equals(sourceBox.getValue());
        java.util.List<ModrinthApi.Version> versCache = new ArrayList<>();

        // 按当前目标实例（游戏版本 + 加载器）过滤版本：两者都必须匹配
        Runnable fillVersions = () -> {
            String id = targetBox.getSelectionModel().getSelectedItem();
            verList.getItems().clear();
            verList.getSelectionModel().clearSelection();
            verList.setDisable(true);
            if (id == null || versCache.isEmpty()) return;
            String mc = mcBases.getOrDefault(id, id);
            String ld = loaderOf.get(id);
            curMc[0] = mc;
            curLd[0] = type == Type.MOD ? ld : null;
            java.util.List<ModrinthApi.Version> matches = new ArrayList<>();
            for (ModrinthApi.Version v : versCache) {
                if (v.gameVersions() == null || !v.gameVersions().contains(mc)) continue;
                if (type == Type.MOD && (ld == null || v.loaders() == null || !v.loaders().contains(ld))) continue;
                matches.add(v);
            }
            if (matches.isEmpty()) {
                verHint.setText("该实例（" + displayNames.getOrDefault(id, id) + "）没有匹配的下载版本，请切换目标实例。");
                verHint.setStyle("-fx-text-fill:#d9534f; -fx-font-size:12px; -fx-font-weight:bold;");
                return;
            }
            verList.getItems().setAll(matches);
            verList.setDisable(false);
            verList.getSelectionModel().selectFirst();
            verList.scrollTo(0);
            verHint.setText("共 " + matches.size() + " 个匹配版本，默认选中最新发布的版本。");
            verHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        };

        // 收集「实例管理里已有、且与内容适配」的实例；无匹配则给出提示
        java.util.List<String> candidates = new ArrayList<>();
        Runnable applyCandidates = () -> {
            if (candidates.isEmpty()) {
                adaptHint.setText(type == Type.MOD
                        ? "未检测到mod适配的加载器"
                        : "未检测到适配的实例（请先在「游戏版本」中安装对应版本的游戏）");
                adaptHint.setStyle("-fx-text-fill:#d9534f; -fx-font-size:13px; -fx-font-weight:bold;");
                downloadBtn.setDisable(true);
                return;
            }
            targetBox.getItems().setAll(candidates);
            // 默认选中与下载中心当前目标一致的实例；不在候选内则取第一个
            String cur = versionBox.getSelectionModel().getSelectedItem();
            if (cur != null && candidates.contains(cur)) targetBox.getSelectionModel().select(cur);
            else targetBox.getSelectionModel().selectFirst();
            adaptHint.setText(type == Type.MOD
                    ? "默认使用实例管理中已有的游戏版本与加载器，可下拉切换其他适配实例。"
                    : "默认使用实例管理中已有的游戏版本，可下拉切换其他适配实例。");
            adaptHint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
            downloadBtn.setDisable(false);
            fillVersions.run();
        };

        targetBox.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> fillVersions.run());

        if (cf) {
            // CurseForge 无全量适配清单：直接列本地实例（Mod 仅列带加载器的实例），下载时再校验文件
            for (String id : versionBox.getItems()) {
                if (type == Type.MOD && loaderOf.get(id) == null) continue;
                candidates.add(id);
            }
            // 列表在此源下没有内容可展示，整块收起，只留一行说明
            verSec.setText("下载版本（CurseForge 数据源不提供版本清单）");
            verList.setVisible(false);
            verList.setManaged(false);
            verHint.setText("下载时自动挑选与所选实例匹配的最新文件。");
            applyCandidates.run();
        } else {
            Ui.async2(() -> api.allVersions(hit.projectId()), vers -> {
                versCache.addAll(vers);
                java.util.Set<String> gameVers = new java.util.HashSet<>();
                java.util.Set<String> loaders = new java.util.HashSet<>();
                for (ModrinthApi.Version v : vers) {
                    gameVers.addAll(v.gameVersions());
                    if (v.loaders() != null) loaders.addAll(v.loaders());
                }
                for (String id : versionBox.getItems()) {
                    String mc = mcBases.getOrDefault(id, id);
                    if (!gameVers.contains(mc)) continue;
                    String ld = loaderOf.get(id);
                    if (type == Type.MOD && (ld == null || !loaders.contains(ld))) continue;
                    candidates.add(id);
                }
                applyCandidates.run();
            }, err -> {
                // 适配清单读取失败：退化为本地实例列表（Mod 仅列带加载器的实例）
                for (String id : versionBox.getItems()) {
                    if (type == Type.MOD && loaderOf.get(id) == null) continue;
                    candidates.add(id);
                }
                adaptHint.setText("版本清单读取失败：" + (err.getMessage() == null
                        ? "网络异常" : err.getMessage()));
                adaptHint.setStyle("-fx-text-fill:#d9534f; -fx-font-size:12px;");
                applyCandidates.run();
            });
        }

        downloadBtn.setOnAction(e -> {
            String id = targetBox.getSelectionModel().getSelectedItem();
            if (id == null) {
                Ui.error("未选择实例", "请先在上方选择目标实例。");
                return;
            }
            String mc = mcBases.getOrDefault(id, id);
            String loader = type == Type.MOD ? loaderOf.get(id) : null;
            ModrinthApi.Version picked = cf ? null : verList.getSelectionModel().getSelectedItem();
            if (!cf && picked == null) {
                Ui.error("没有可用版本", hit.title() + " 没有与所选实例匹配的下载版本。");
                return;
            }
            sink.progress(0);
            sink.message("就绪");
            doDownload(hit, mc, loader, id, picked);
        });
    }

    /** 版本列表里的发布日期：yyyy-MM-dd，缺失时显示占位。 */
    private static String shortDate(long epochMillis) {
        if (epochMillis <= 0) return "—";
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    /** 版本下拉条目文案：版本号 · 游戏版本 · 加载器。 */
    private static String versionText(ModrinthApi.Version v) {
        StringBuilder sb = new StringBuilder();
        String num = (v.versionNumber() == null || v.versionNumber().isBlank()) ? v.name() : v.versionNumber();
        if (num != null && !num.isBlank()) sb.append(num);
        else sb.append(v.id());
        if (v.gameVersions() != null && !v.gameVersions().isEmpty()) {
            sb.append("   · MC ").append(String.join("/", v.gameVersions()));
        }
        if (v.loaders() != null && !v.loaders().isEmpty()) {
            sb.append("   · ").append(String.join("/", v.loaders()));
        }
        return sb.toString();
    }

    /**
     * 按弹窗所选版本下载（不再弹第二个选择框）。
     * mc 为纯游戏版本（API 用）；target 为本地落点实例 id（加载器实例存在时落入其目录）。
     */
    private void doDownload(ModrinthApi.Hit hit, String mc, String loader, String targetId,
                             ModrinthApi.Version picked) {
        // 详情页显式指定的实例优先；兜底仍按游戏版本匹配实例落点
        String target = (targetId != null && !targetId.isBlank())
                ? targetId : app.localVersions.resolveInstanceFor(mc);
        if ("CurseForge".equals(sourceBox.getValue())) {
            CurseForgeApi cf = new CurseForgeApi(CurseForgeKey.get());
            if (!cf.hasKey()) {
                Ui.error("未配置 Key", "请通过环境变量 JWH_CURSEFORGE_API_KEY 配置 CurseForge API Key 后再下载（PCL 同款机制）。");
                return;
            }
            sink.message("正在解析 CurseForge 下载地址…");
            sink.progress(Double.NaN);
            com.jwh.launcher.util.Http.DownloadControl[] ctlRef = new com.jwh.launcher.util.Http.DownloadControl[1];
            Ui.async2(() -> {
                CurseForgeApi.File f = cf.pickFile(hit.projectId(), mc, loader);
                if (f == null) {
                    throw new IllegalStateException("该内容没有适配 " + mc
                            + (type == Type.MOD ? " 与所选加载器" : "") + " 的文件。");
                }
                long sz = com.jwh.launcher.util.Http.contentLength(f.url());
                ctlRef[0] = bytesControl(sz);
                attachCtrl.accept(ctlRef[0]);
                new ContentService(app.config).downloadTo(apiType(), target, f.url(), f.filename(), ctlRef[0]);
                return f.filename();
            }, fn -> {
                endDownload(ctlRef[0], () -> {
                    sink.message("下载完成：" + fn);
                    sink.progress(1);
                    Ui.info("下载完成", type.label + " 已放入目标版本 " + target + " 的 " + subdirLabel()
                            + " 目录。\n可在主页启动该版本后使用。");
                });
            }, err -> {
                endDownload(ctlRef[0], () -> {
                    sink.progress(0);
                    Ui.error("下载失败", err);
                });
            });
            return;
        }

        sink.message("正在查询可用版本…");
        sink.progress(Double.NaN);
        Ui.async2(() -> {
            // 详情页已明确选中版本时直接使用，绝不回退到其他加载器/游戏版本
            if (picked != null) return picked;
            List<ModrinthApi.Version> vers = api.versions(hit.projectId(), mc, loader);
            if (vers.isEmpty() && loader != null) vers = api.versions(hit.projectId(), mc, null);
            return vers.isEmpty() ? null : vers.get(0);
        }, v -> {
            if (v == null) {
                sink.progress(0);
                Ui.error("没有可用版本", hit.title() + " 没有适配 " + mc
                        + (type == Type.MOD ? " 与所选加载器" : "") + " 的版本。");
                return;
            }
            ModrinthApi.File f = v.primaryFile();
            if (f == null) {
                sink.progress(0);
                Ui.error("无下载文件", "该版本没有可下载的文件。");
                return;
            }
            if (type == Type.MOD) {
                downloadWithDependencies(hit, mc, loader, v, f, target);
                return;
            }
            sink.message("正在下载 " + f.filename() + " …");
            sink.progress(Double.NaN);
            long totalSize = f.size();
            com.jwh.launcher.util.Http.DownloadControl ctl = bytesControl(totalSize);
            attachCtrl.accept(ctl);
            Ui.async(() -> {
                new ContentService(app.config).downloadTo(apiType(), target, f.url(), f.filename(), ctl);
                return null;
            }, ok2 -> {
                endDownload(ctl, () -> {
                    sink.message("下载完成：" + f.filename());
                    sink.progress(1);
                    Ui.info("下载完成", type.label + " 已放入目标版本 " + target + " 的 " + subdirLabel()
                            + " 目录。\n可在主页启动该版本后使用。");
                });
            }, err -> {
                endDownload(ctl, () -> {
                    sink.progress(0);
                    Ui.error("下载失败", err);
                });
            });
        }, err -> {
            sink.progress(0);
            Ui.error("查询版本失败", err);
        });
    }

    /** 前置下载计划：missingTitles 用于弹窗文案；depFiles 为需下载的前置文件（含嵌套前置）。 */
    private record DepPlan(List<String> missingTitles, List<ModrinthApi.File> depFiles) {
    }

    /**
     * 下载主 Mod 前检查 required 前置：缺失则弹窗，确定后一并下载（递归到前置的前置，深度 ≤ 3）。
     * 已安装的前置自动跳过（按 mods 目录文件名匹配 slug）。
     */
    private void downloadWithDependencies(ModrinthApi.Hit hit, String mc, String loader,
                                          ModrinthApi.Version v, ModrinthApi.File mainFile, String target) {
        sink.message("正在检查前置依赖…");
        sink.progress(Double.NaN);
        Ui.async2(() -> {
            // 主 Mod 自身不参与前置收集：先把它标记为已处理，再显式遍历它的 required 前置链
            java.util.Set<String> handled = new java.util.LinkedHashSet<>();
            handled.add(hit.projectId());
            DepPlan plan = new DepPlan(new ArrayList<>(), new ArrayList<>());
            // 依赖按「用户实际选中的这个版本」解析，不再另行查询以免版本错配
            if (v.dependencies() != null) {
                for (ModrinthApi.Dependency dep : v.dependencies()) {
                    if ("required".equals(dep.dependencyType())) {
                        collectDeps(dep.projectId(), mc, loader, handled, plan, 1, target);
                    }
                }
            }
            return plan;
        }, plan -> {
            if (plan.missingTitles().isEmpty()) {
                startDownloads(plan, target, mainFile);
                return;
            }
            boolean go = Ui.confirm("需要前置 Mod",
                    "「" + ModNames.display(hit.slug(), hit.title()) + "」需要以下前置 Mod（未安装）：\n\n"
                            + String.join("\n", plan.missingTitles().stream()
                            .map(t -> "· " + t).toList())
                            + "\n\n点「确定」一并下载前置；点「否」仅下载主 Mod（游戏可能报错）。");
            if (go) {
                startDownloads(plan, target, mainFile);
            } else {
                startDownloads(new DepPlan(new ArrayList<>(), new ArrayList<>()), target, mainFile);
            }
        }, err -> {
            sink.progress(0);
            Ui.error("检查前置失败", err);
        });
    }

    /**
     * 递归收集 required 前置（跳过已安装的），并入 plan。
     * 被收集的项目会把自己的文件与标题计入 plan；主 Mod 由调用方预先放入 handled 排除。
     */
    private void collectDeps(String projectId, String mc, String loader,
                             java.util.Set<String> handled, DepPlan plan,
                             int depth, String target) throws Exception {
        if (depth > 3 || projectId == null || projectId.isBlank() || !handled.add(projectId)) {
            return;
        }
        ModrinthApi.Project proj = api.project(projectId);
        List<ModrinthApi.Version> vers = api.versions(projectId, mc, loader);
        if (vers.isEmpty()) vers = api.versions(projectId, mc, null);
        if (vers.isEmpty()) {
            plan.missingTitles().add(proj.title() + "（暂无可下载文件，请手动安装）");
            return;
        }
        ModrinthApi.Version vv = vers.get(0);
        if (isInstalled(proj.slug(), target)) {
            return; // 已安装的前置自动跳过
        }
        // 先收集嵌套前置（显示层级：前置的前置在前）
        for (ModrinthApi.Dependency dep : vv.dependencies()) {
            if ("required".equals(dep.dependencyType())) {
                collectDeps(dep.projectId(), mc, loader, handled, plan, depth + 1, target);
            }
        }
        ModrinthApi.File pf = vv.primaryFile();
        if (pf != null) {
            plan.depFiles().add(pf);
            plan.missingTitles().add(proj.title());
        } else {
            plan.missingTitles().add(proj.title() + "（无可用文件，请手动安装）");
        }
    }

    /** 目标版本 mods 目录里是否已存在该前置（按文件名包含 slug 判断）。 */
    private boolean isInstalled(String slug, String target) {
        if (slug == null || slug.isBlank()) return false;
        try {
            Path dir = app.config.gameDirFor(target).resolve(subdirLabel());
            if (!java.nio.file.Files.isDirectory(dir)) return false;
            try (var s = java.nio.file.Files.list(dir)) {
                return s.anyMatch(p -> {
                    String fn = p.getFileName().toString().toLowerCase();
                    return fn.endsWith(".jar") && fn.contains(slug.toLowerCase());
                });
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 依次下载所有文件（前置在前，主 Mod 最后）；支持取消，按文件数刷新进度。 */
    private void startDownloads(DepPlan plan, String target, ModrinthApi.File mainFile) {
        List<ModrinthApi.File> all = new ArrayList<>(plan.depFiles());
        all.add(mainFile);
        sink.message("正在下载 " + all.size() + " 个文件…");
        sink.progress(Double.NaN);
        com.jwh.launcher.util.Http.DownloadControl ctl = bytesControl(-1);
        attachCtrl.accept(ctl);
        Ui.async2(() -> {
            ContentService cs = new ContentService(app.config);
            int done = 0;
            for (ModrinthApi.File f : all) {
                if (ctl.isCancelled()) break;
                cs.downloadTo(apiType(), target, f.url(), f.filename(), ctl);
                done++;
                final int d = done;
                javafx.application.Platform.runLater(() -> {
                    sink.message("正在下载 " + f.filename() + "（" + d + "/" + all.size() + "）");
                    sink.progress((double) d / all.size());
                });
            }
            return done;
        }, n -> {
            endDownload(ctl, () -> {
                sink.message("下载完成：" + n + " 个文件");
                sink.progress(1);
                Ui.info("下载完成", type.label + " 与前置已放入目标版本 " + target + " 的 " + subdirLabel()
                        + " 目录。\n可在主页启动该版本后使用。");
            });
        }, err -> {
            endDownload(ctl, () -> {
                sink.progress(0);
                Ui.error("下载失败", err);
            });
        });
    }

    private String subdirLabel() {
        return switch (type) {
            case MOD -> "mods";
            case SHADER -> "shaderpacks";
            default -> "resourcepacks";
        };
    }
}
