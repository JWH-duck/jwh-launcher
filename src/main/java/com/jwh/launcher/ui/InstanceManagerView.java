package com.jwh.launcher.ui;

import com.jwh.launcher.LauncherApp;
import com.jwh.launcher.download.IconCache;
import com.jwh.launcher.download.ModrinthApi;
import com.jwh.launcher.game.LocalVersions;
import com.jwh.launcher.util.Http;
import com.jwh.launcher.util.ModNames;
import com.jwh.launcher.util.ModpackExporter;
import com.jwh.launcher.util.ModpackImporter;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 实例管理（融入主窗口的子页面，不弹窗）：查看某个已装游戏版本下的
 * Mod / 资源包 / 光影包 / 加载器，每个分类带「打开相应文件夹」「检测更新
 * （Modrinth，结果内联展示）」「删除所选（多选，危险操作仍弹窗确认）」。
 * 由 LauncherApp.showSubPage 承载，左上角「< 返回」回到主页。
 */
public class InstanceManagerView {

    /** 可更新条目：本地文件 + 最新版本信息。 */
    private record UpdateItem(Path file, String curName, String newVer, String url, String newFilename) {
    }

    /** Mod 元数据：汉化显示名 + 项目图标 URL（Modrinth 查询结果，本地缓存避免重复联网）。 */
    private record ModMeta(String display, String iconUrl) {
    }

    /** 已解析的 Mod 元数据缓存（fileName → 元数据）；滑块/刷新重建列表后直接复用。 */
    private static final Map<String, ModMeta> MOD_META = new ConcurrentHashMap<>();
    /** 正在后台解析中的文件名（防止列表重建后重复发起查询）。 */
    private static final Set<String> MOD_META_INFLIGHT = ConcurrentHashMap.newKeySet();

    private final LauncherApp app;
    private final String versionId;
    private final Path versionDir;

    private InstanceManagerView(LauncherApp app, String versionId) {
        this.app = app;
        this.versionId = versionId;
        this.versionDir = app.config.gameDirFor(versionId);
    }

    /** 构建实例管理页面（直接作为主窗口内容区）。 */
    public static VBox content(LauncherApp app, String versionId) {
        InstanceManagerView v = new InstanceManagerView(app, versionId);
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        Label head = new Label("「" + versionId + "」实例文件（只显示选中的这一实例，版本隔离开启时 Mod 等随版本隔离）");
        head.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:14px; -fx-font-weight:bold;");
        head.setWrapText(true);
        head.setMinWidth(0);

        Button exportPack = new Button("导出整合包");
        // 标题很长时（如 quilt-loader-0.31.0-beta.4-1.21.1）按钮不能被压成「导出整…」
        exportPack.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        exportPack.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white; -fx-font-weight:bold;");
        exportPack.setOnAction(e -> v.exportModpack());
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headRow = new HBox(8, head, spacer, exportPack);
        headRow.setAlignment(Pos.CENTER_LEFT);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setStyle("-fx-background-color:" + Ui.BG + "; -fx-control-inner-background:" + Ui.BG + ";");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        tabs.getTabs().add(v.modTab());
        tabs.getTabs().add(v.fileTab("资源包", v.versionDir.resolve("resourcepacks"), "zip,jar"));
        tabs.getTabs().add(v.fileTab("光影包", v.versionDir.resolve("shaderpacks"), "zip,jar"));
        tabs.getTabs().add(v.saveTab());
        tabs.getTabs().add(v.loaderTab());

        // Tab 切换动效：与整页切换一致的右侧滑入 + 淡入
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) return;
            javafx.scene.Node c = newT.getContent();
            if (c == null) return;
            javafx.application.Platform.runLater(() -> Ui.pageIn(c));
        });

        box.getChildren().addAll(headRow, tabs);
        return box;
    }

    // ---------- 整合包一键导出（整个实例打包成可分发 / 可再导入的 zip） ----------

    /**
     * 导出当前实例为整合包 zip：
     * 点一下 → 弹一张内容清单确认 → 选保存位置 → 后台打包 → 完成提示。
     *
     * <p>包里带：mods（含已禁用的 .jar.disabled，保持原状态）、config、options.txt、
     * servers.dat、资源包、光影包；不带存档 saves（个人进度，体积大）。
     * 结构为 manifest.json（CurseForge 清单）+ overrides/ + jwh-pack.json（本启动器元数据），
     * PCL / HMCL 等启动器可直接识别为整合包。
     */
    private void exportModpack() {
        LocalVersions.LocalVersion info = null;
        try {
            for (LocalVersions.LocalVersion v : new LocalVersions(app.config).scan()) {
                if (v.id().equals(versionId)) {
                    info = v;
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        // 统计将被打包的内容，先让用户看清楚再决定
        Path modsDir = versionDir.resolve("mods");
        int modCount = 0;
        if (Files.isDirectory(modsDir)) {
            try (var s = Files.list(modsDir)) {
                modCount = (int) s.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().toLowerCase().contains("customskinloader"))
                        .count();
            } catch (Exception ignored) {
            }
        }
        int packsCount = countFiles(versionDir.resolve("resourcepacks"));
        int shadersCount = countFiles(versionDir.resolve("shaderpacks"));
        boolean hasConfig = Files.isDirectory(versionDir.resolve("config"));
        boolean hasSaves = Files.isDirectory(versionDir.resolve("saves"));

        StringBuilder sb = new StringBuilder();
        String packName = LocalVersions.isGameVersion(versionId)
                ? versionId : (info == null ? versionId : info.displayName());
        sb.append("实例：").append(packName);
        if (info != null && info.displayName() != null && !info.displayName().equals(packName)) {
            sb.append("（").append(info.displayName()).append("）");
        }
        sb.append("\n\n");
        sb.append("将打包：\n");
        sb.append("  · Mod 模组 ").append(modCount).append(" 个（含已禁用的）\n");
        sb.append("  · 配置 config ").append(hasConfig ? "（有）" : "（无）").append("\n");
        sb.append("  · 资源包 ").append(packsCount).append(" 个\n");
        sb.append("  · 光影包 ").append(shadersCount).append(" 个\n");
        sb.append("  · options.txt / servers.dat（存在才带）\n\n");
        sb.append("存档 saves：").append(hasSaves ? "有，可勾选一起打包" : "无").append("\n");
        sb.append("不打包：日志、崩溃报告、CustomSkinLoader 皮肤模组残留\n\n");
        sb.append("包格式在下面选，确认后选保存位置。");
        int[] opt = Ui.packExportOptions("导出整合包", sb.toString(), hasSaves);
        if (opt == null) return;
        boolean withSaves = opt[0] == 1;
        boolean mrpack = opt[1] == 1;

        FileChooser fc = new FileChooser();
        fc.setTitle("导出整合包");
        try {
            fc.setInitialDirectory(new File(app.config.minecraftDir));
        } catch (Exception ignored) {
        }
        String base = safeFileName(LocalVersions.isGameVersion(versionId)
                ? versionId : (info == null ? versionId : info.displayName()));
        String ext = mrpack ? ".mrpack" : ".zip";
        fc.setInitialFileName(base + "-整合包" + ext);
        fc.getExtensionFilters().add(mrpack
                ? new FileChooser.ExtensionFilter("Modrinth 整合包 (*.mrpack)", "*.mrpack")
                : new FileChooser.ExtensionFilter("整合包压缩包 (*.zip)", "*.zip"));
        File f = fc.showSaveDialog(null);
        if (f == null) return;
        String path = f.getPath().toLowerCase().endsWith(ext) ? f.getPath() : f.getPath() + ext;
        final String targetPath = path;

        String author = "JWH-Launcher";
        try {
            int idx = app.config.selectedAccount;
            if (idx >= 0 && idx < app.config.accounts.size()
                    && app.config.accounts.get(idx).displayName != null
                    && !app.config.accounts.get(idx).displayName.isBlank()) {
                author = app.config.accounts.get(idx).displayName;
            }
        } catch (Exception ignored) {
        }

        app.showToast("正在打包整合包…");
        final LocalVersions.LocalVersion vInfo = info;
        final Path dirForExport = versionDir;
        final String authorFinal = author;
        final ModpackExporter.Format fmt = mrpack ? ModpackExporter.Format.MODRINTH : ModpackExporter.Format.CURSEFORGE;
        final boolean savesFinal = withSaves;
        Ui.async2(() -> ModpackExporter.export(dirForExport, Path.of(targetPath), vInfo, authorFinal, fmt, savesFinal),
                res -> {
                    // 先弹窗再更新状态栏：showAndWait 会阻塞 FX 线程，提前改文案会来不及重绘
                    boolean open = Ui.confirm("导出完成", "整合包已保存到：\n" + targetPath + "\n\n"
                            + "格式：" + (mrpack ? "Modrinth .mrpack" : "CurseForge .zip")
                            + (savesFinal ? "（含存档）" : "（不含存档）") + "\n"
                            + "文件数：" + res.files() + "\n原始大小：" + formatSize(res.bytes())
                            + "\n压缩后：" + formatSize(res.zipSize()) + "\n\n是否打开所在文件夹？");
                    app.showToast("整合包已导出：" + Path.of(targetPath).getFileName());
                    if (open) {
                        openDir(Path.of(targetPath).getParent());
                    }
                },
                err -> Ui.error("导出整合包失败", err));
    }

    /** 统计目录下文件数（资源包/光影包按文件计）。 */
    private static int countFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return (int) s.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 文件名安全化：去掉 Windows 不允许的字符，空格换下划线。 */
    private static String safeFileName(String s) {
        if (s == null || s.isBlank()) return "modpack";
        String out = s.replace(' ', '_').replaceAll("[\\\\/:*?\"<>|·]", "_");
        return out.isBlank() ? "modpack" : out;
    }

    /** 文件列表 Tab：列表 + 按钮；检测更新结果内联替换为更新列表视图。 */
    private Tab fileTab(String title, Path dir, String extCsv) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        Label pathLabel = new Label("文件夹：" + dir.toAbsolutePath());
        pathLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        pathLabel.setWrapText(true);

        ListView<CheckBox> list = new ListView<>();
        list.setPrefHeight(260);
        Ui.bindWheelScroll(list);
        list.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";");
        fillFiles(list, dir, extCsv);

        Button update = new Button("检测更新");
        update.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
        update.setOnAction(e -> checkUpdates(dir, extCsv, box, list));

        Button delete = new Button("删除所选");
        delete.setStyle("-fx-background-color:#c0392b; -fx-text-fill:white;");
        delete.setOnAction(e -> deleteSelected(list, dir, extCsv));

        Button open = new Button("打开相应文件夹");
        open.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        open.setOnAction(e -> openDir(dir));

        HBox btns = new HBox(8, update, delete, open);
        btns.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("勾选要删除的文件（可勾选多个）；「检测更新」按 Modrinth 来源比对最新适配版本，结果在本页直接展示。");
        hint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        VBox.setVgrow(list, Priority.ALWAYS);
        box.getChildren().addAll(pathLabel, list, btns, hint);
        return new Tab(title, box);
    }

    // ---------- Mod 模组 Tab（启用/禁用滑块 + 更新 + 删除） ----------

    /** Mod 模组 Tab：每行「勾选框（删除用）+ 启用/禁用滑块」。
     *  禁用通过重命名 xxx.jar → xxx.jar.disabled 实现（Fabric/Forge/NeoForge/Quilt 均只加载 .jar），
     *  任意加载器/版本通用；禁用后文件保留、列表显示「（已禁用）」灰字。 */
    private Tab modTab() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        Path dir = versionDir.resolve("mods");
        Label pathLabel = new Label("文件夹：" + dir.toAbsolutePath());
        pathLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        pathLabel.setWrapText(true);

        ListView<HBox> list = new ListView<>();
        list.setPrefHeight(260);
        Ui.bindWheelScroll(list);
        list.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";");
        rebuildMods(list, dir);

        Button update = new Button("检测更新");
        update.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
        update.setOnAction(e -> checkUpdatesMod(dir, box, list));

        Button delete = new Button("删除所选");
        delete.setStyle("-fx-background-color:#c0392b; -fx-text-fill:white;");
        delete.setOnAction(e -> deleteSelectedMods(list, dir));

        Button open = new Button("打开相应文件夹");
        open.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        open.setOnAction(e -> openDir(dir));

        HBox btns = new HBox(8, update, delete, open);
        btns.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("滑块可单独启用/禁用某个 Mod（禁用后启动游戏时不加载，文件保留可随时恢复）；"
                + "勾选用于多选删除；「检测更新」按 Modrinth 来源比对最新适配版本，结果在本页直接展示。");
        hint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        hint.setWrapText(true);

        VBox.setVgrow(list, Priority.ALWAYS);
        box.getChildren().addAll(pathLabel, list, btns, hint);
        return new Tab("Mod 模组", box);
    }

    /** 填充 Mod 列表：启用 jar（.jar）与禁用 jar（.jar.disabled）按基名配对排序显示。 */
    private static void fillMods(ListView<HBox> list, Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                list.getItems().setAll(placeholderRow("（目录尚未创建：" + dir.toAbsolutePath() + "）"));
                return;
            }
            List<String> names;
            try (var s = Files.list(dir)) {
                names = s.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            }
            List<String> keys = new ArrayList<>();
            for (String n : names) {
                String low = n.toLowerCase();
                String key;
                if (low.endsWith(".jar.disabled")) key = n.substring(0, n.length() - ".disabled".length());
                else if (low.endsWith(".jar")) key = n;
                else continue;
                if (!keys.contains(key)) keys.add(key);
            }
            keys.sort(String::compareToIgnoreCase);
            if (keys.isEmpty()) {
                list.getItems().setAll(placeholderRow("（暂无 Mod）"));
                return;
            }
            List<HBox> rows = new ArrayList<>();
            for (String k : keys) {
                Path active = dir.resolve(k);
                boolean enabled = Files.isRegularFile(active);
                rows.add(modRow(enabled ? active : dir.resolve(k + ".disabled"), enabled, list, dir));
            }
            list.getItems().setAll(rows);
        } catch (Exception e) {
            list.getItems().setAll(placeholderRow("（读取失败：" + e.getMessage() + "）"));
        }
    }

    /** 单个 Mod 行：勾选框（删除用）+ 图标 + 汉化名/文件名 + 启用/禁用滑块。
     *  汉化名与图标由后台按 Modrinth 查询回填（enrichMods），未解析前显示文件名 + 草方块占位。 */
    private static HBox modRow(Path file, boolean enabled, ListView<HBox> list, Path dir) {
        String real = file.getFileName().toString();
        // 禁用文件显示纯文件名（状态只靠滑块位置表达：关=禁用，开=启用）
        String show = enabled ? real : real.substring(0, real.length() - ".disabled".length());
        CheckBox cb = new CheckBox();
        cb.setUserData(real); // 删除时用的真实文件名

        ImageView icon = new ImageView(Ui.GRASS_BLOCK);
        icon.setFitWidth(36);
        icon.setFitHeight(36);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);

        Label title = new Label(show);
        title.setStyle("-fx-text-fill:" + (enabled ? Ui.TEXT : Ui.TEXT_DIM) + "; -fx-font-size:14px; -fx-font-weight:bold;");
        Label sub = new Label(enabled ? real : show);
        sub.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:11px;");
        VBox info = new VBox(2, title, sub);
        info.setAlignment(Pos.CENTER_LEFT);

        SwitchBox sw = new SwitchBox(enabled);
        sw.setOnAction(() -> {
            try {
                toggleMod(dir, real, enabled);
                rebuildMods(list, dir);
            } catch (Exception ex) {
                rebuildMods(list, dir); // 失败则按真实文件状态重建（滑块回到正确位置）
                Ui.error("切换失败", ex);
            }
        });
        HBox row = new HBox(10, cb, icon, info, sw);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setUserData(real); // 元数据回填 / 定位用（placeholderRow 无 userData）
        return row;
    }

    /** 重建 Mod 列表并异步回填汉化名 / 图标（缓存命中立即显示）。 */
    private static void rebuildMods(ListView<HBox> list, Path dir) {
        fillMods(list, dir);
        enrichMods(list, dir);
    }

    /** 后台按 SHA1 → Modrinth 查询每个文件的汉化名与图标，结果回填对应行。 */
    private static void enrichMods(ListView<HBox> list, Path dir) {
        for (HBox row : list.getItems()) {
            Object ud = row.getUserData();
            if (!(ud instanceof String fn)) continue; // 占位行（目录未创建 / 暂无 Mod）
            ModMeta meta = MOD_META.get(fn);
            if (meta != null) {
                applyMeta(row, meta);
                continue;
            }
            if (!MOD_META_INFLIGHT.add(fn)) continue; // 已在查询中
            Ui.async2(() -> {
                try {
                    String sha1 = sha1(dir.resolve(fn));
                    ModrinthApi api = new ModrinthApi();
                    ModrinthApi.Version v = api.versionByHash(sha1);
                    ModrinthApi.Project p = api.project(v.projectId());
                    ModMeta m = new ModMeta(ModNames.display(p.slug(), p.title()), p.iconUrl());
                    MOD_META.put(fn, m);
                    return m;
                } catch (Exception e) {
                    return null; // 非 Modrinth 来源 / 网络失败：保持文件名 + 占位图标
                }
            }, m -> {
                if (m == null) return;
                for (HBox r : list.getItems()) {
                    if (fn.equals(r.getUserData())) {
                        applyMeta(r, m);
                        break;
                    }
                }
            }, err -> {
            });
        }
    }

    /** 把 Mod 行更新为「汉化名 + 图标」（未获取到图标时保持占位）。
     *  图标写法与下载中心 Mod 列表一致：草方块占位 → 缓存命中秒显 → 异步下载成功后
     *  防串图替换（行属性记录当前 URL，下载完成时核对仍属于本行才换）。 */
    private static void applyMeta(HBox row, ModMeta m) {
        Label title = null, sub = null;
        ImageView icon = null;
        for (var c : row.getChildren()) {
            if (c instanceof ImageView iv) icon = iv;
            else if (c instanceof VBox vb) {
                var kids = vb.getChildren();
                if (kids.size() >= 1 && kids.get(0) instanceof Label l) title = l;
                if (kids.size() >= 2 && kids.get(1) instanceof Label l2) sub = l2;
            }
        }
        if (title != null && m.display() != null && !m.display().isBlank()) {
            title.setText(m.display());
            if (sub != null) sub.setVisible(true);
        }
        if (icon == null || m.iconUrl() == null || m.iconUrl().isBlank()) return;
        String url = m.iconUrl();
        row.getProperties().put("modIconUrl", url);
        Image cached = IconCache.cached(url);
        if (cached != null) {
            icon.setImage(cached);
        } else {
            ImageView iconView = icon;
            IconCache.ensure(url, img -> {
                if (url.equals(row.getProperties().get("modIconUrl"))) {
                    iconView.setImage(img);
                }
            });
        }
    }

    /** 禁用=改名 .jar → .jar.disabled；启用=改回。 */
    private static void toggleMod(Path dir, String real, boolean enabled) throws Exception {
        Path p = dir.resolve(real);
        if (enabled) {
            Files.move(p, dir.resolve(real + ".disabled"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            String base = real.substring(0, real.length() - ".disabled".length());
            Files.move(p, dir.resolve(base), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Mod 版检测更新：只检测启用中的 jar（禁用的不参与更新比对）。 */
    private void checkUpdatesMod(Path dir, VBox box, ListView<HBox> list) {
        List<Path> files = listFiles(dir, "jar");
        if (files.isEmpty()) {
            app.showToast("该目录暂无启用的 Mod。");
            return;
        }
        String mc = versionId;
        for (var v : app.localVersions.scanVisible()) {
            if (v.id().equals(versionId)) {
                mc = v.mcBase() == null || v.mcBase().isBlank() ? versionId : v.mcBase();
                break;
            }
        }
        final String queryMc = mc;
        app.showToast("正在检测更新…");
        Ui.async2(() -> {
            List<UpdateItem> upd = new ArrayList<>();
            ModrinthApi api = new ModrinthApi();
            for (Path f : files) {
                try {
                    String sha1 = sha1(f);
                    ModrinthApi.Version cur = api.versionByHash(sha1);
                    String loader = loaderOf(f.getFileName().toString());
                    List<ModrinthApi.Version> vs = api.versions(cur.projectId(), queryMc, loader);
                    ModrinthApi.Version latest = null;
                    for (ModrinthApi.Version x : vs) {
                        if (latest == null || x.datePublished() > latest.datePublished()) latest = x;
                    }
                    if (latest != null && latest.datePublished() > cur.datePublished()) {
                        ModrinthApi.File pf = latest.primaryFile();
                        if (pf != null) {
                            upd.add(new UpdateItem(f, cur.versionNumber(), latest.versionNumber(),
                                    pf.url(), pf.filename()));
                        }
                    }
                } catch (Exception ignored) {
                    // 非 Modrinth 来源文件（手装/其它源）跳过
                }
            }
            return upd;
        }, upd -> {
            if (upd.isEmpty()) {
                app.showToast("该目录下所有 Mod 均已是最新适配版本（Modrinth 来源）。");
                return;
            }
            showModUpdates(box, list, dir, upd);
        }, err -> Ui.error("检测更新失败", err));
    }

    /** Mod 更新结果内联：勾选更新，完成后恢复带滑块的列表。 */
    private void showModUpdates(VBox box, ListView<HBox> modList, Path dir, List<UpdateItem> items) {
        VBox updBox = new VBox(8);
        updBox.setPadding(new Insets(4));

        Label head = new Label("检测到 " + items.size() + " 个 Mod 有更新的适配版本：");
        head.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:14px; -fx-font-weight:bold;");

        ListView<CheckBox> list = new ListView<>();
        list.setPrefHeight(260);
        Ui.bindWheelScroll(list);
        list.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";");
        for (UpdateItem it : items) {
            CheckBox cb = new CheckBox(it.file().getFileName() + "   →   " + it.newVer());
            cb.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
            cb.setSelected(true);
            list.getItems().add(cb);
        }
        VBox.setVgrow(list, Priority.ALWAYS);

        Button all = new Button("全选");
        all.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        all.setOnAction(e -> list.getItems().forEach(c -> c.setSelected(true)));
        Button none = new Button("全不选");
        none.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        none.setOnAction(e -> list.getItems().forEach(c -> c.setSelected(false)));
        Button update = new Button("更新所选");
        update.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white; -fx-font-weight:bold;");
        update.setOnAction(e -> {
            List<UpdateItem> sel = new ArrayList<>();
            for (int i = 0; i < list.getItems().size(); i++) {
                if (list.getItems().get(i).isSelected()) sel.add(items.get(i));
            }
            if (sel.isEmpty()) {
                Ui.error("未选择", "请至少勾选一个要更新的 Mod。");
                return;
            }
            Ui.async2(() -> {
                int okc = 0;
                for (UpdateItem it : sel) {
                    try {
                        Path target = dir.resolve(it.newFilename());
                        // 实例内容更新统一走 Downloader：jar 必须过 ZIP 头 + CRC，坏包当场发现
                        boolean ok = com.jwh.launcher.download.Downloader.fetch(target, -1,
                                com.jwh.launcher.download.ArtifactProbe.Kind.of(target, -1),
                                it.newFilename(), null,
                                com.jwh.launcher.download.Downloader.Policy.of(60, 180), it.url());
                        if (ok) {
                            Files.deleteIfExists(it.file());
                            okc++;
                        }
                    } catch (Exception ignored) {
                    }
                }
                return okc;
            }, n -> {
                restoreModView(box, modList, dir);
                app.showToast("已更新 " + n + " 个 Mod（" + sel.size() + " 个勾选项中下载成功的）。启动游戏即可生效。");
            }, err -> Ui.error("更新失败", err));
        });
        Button back = new Button("返回列表");
        back.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        back.setOnAction(e -> restoreModView(box, modList, dir));

        HBox btns = new HBox(8, all, none, update, back);
        btns.setAlignment(Pos.CENTER_LEFT);

        updBox.getChildren().addAll(head, list, btns);
        box.getChildren().setAll(updBox);
    }

    /** 恢复 Mod 列表视图（更新完成 / 返回列表后，保留滑块）。 */
    private void restoreModView(VBox box, ListView<HBox> modList, Path dir) {
        rebuildMods(modList, dir);
        VBox old = new VBox(8);
        old.setPadding(new Insets(4));
        Label pathLabel = new Label("文件夹：" + dir.toAbsolutePath());
        pathLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        pathLabel.setWrapText(true);
        Button update = new Button("检测更新");
        update.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
        update.setOnAction(e -> checkUpdatesMod(dir, box, modList));
        Button delete = new Button("删除所选");
        delete.setStyle("-fx-background-color:#c0392b; -fx-text-fill:white;");
        delete.setOnAction(e -> deleteSelectedMods(modList, dir));
        Button open = new Button("打开相应文件夹");
        open.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        open.setOnAction(e -> openDir(dir));
        HBox btns = new HBox(8, update, delete, open);
        btns.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("滑块可单独启用/禁用某个 Mod（禁用后启动游戏时不加载，文件保留可随时恢复）；"
                + "勾选用于多选删除；「检测更新」按 Modrinth 来源比对最新适配版本，结果在本页直接展示。");
        hint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        hint.setWrapText(true);
        VBox.setVgrow(modList, Priority.ALWAYS);
        old.getChildren().addAll(pathLabel, modList, btns, hint);
        box.getChildren().setAll(old);
    }

    /** 删除勾选的 Mod（真实文件名从行内勾选框 userData 取；.jar 与 .jar.disabled 均可删）。 */
    private void deleteSelectedMods(ListView<HBox> list, Path dir) {
        List<String> sel = new ArrayList<>();
        for (HBox row : list.getItems()) {
            CheckBox cb = checkboxOf(row);
            if (cb != null && cb.isSelected() && cb.getUserData() != null) {
                sel.add((String) cb.getUserData());
            }
        }
        if (sel.isEmpty()) {
            Ui.error("未选择", "请先勾选要删除的 Mod（可勾选多个）。");
            return;
        }
        boolean go = Ui.confirm("删除所选",
                "确定删除勾选的 " + sel.size() + " 个 Mod？\n\n"
                        + String.join("\n", sel.stream().map(t -> "· " + t).toList()));
        if (!go) return;
        int okc = 0;
        for (String n : sel) {
            try {
                Files.deleteIfExists(dir.resolve(n));
                okc++;
            } catch (Exception ignored) {
            }
        }
        rebuildMods(list, dir);
        app.showToast("已删除 " + okc + " 个 Mod。");
    }

    private static CheckBox checkboxOf(HBox row) {
        for (var c : row.getChildren()) {
            if (c instanceof CheckBox cb) return cb;
        }
        return null;
    }

    private static HBox placeholderRow(String text) {
        CheckBox cb = new CheckBox(text);
        cb.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + ";");
        HBox row = new HBox(cb);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** 检测更新：逐文件按 SHA1 查 Modrinth，结果内联到该 Tab。 */
    private void checkUpdates(Path dir, String extCsv, VBox box, ListView<CheckBox> fileList) {
        List<Path> files = listFiles(dir, extCsv);
        if (files.isEmpty()) {
            app.showToast("该目录暂无文件。");
            return;
        }
        // 当前实例的纯游戏版本（更新查询用）
        String mc = versionId;
        for (var v : app.localVersions.scanVisible()) {
            if (v.id().equals(versionId)) {
                mc = v.mcBase() == null || v.mcBase().isBlank() ? versionId : v.mcBase();
                break;
            }
        }
        final String queryMc = mc;
        app.showToast("正在检测更新…");
        Ui.async2(() -> {
            List<UpdateItem> upd = new ArrayList<>();
            ModrinthApi api = new ModrinthApi();
            for (Path f : files) {
                try {
                    String sha1 = sha1(f);
                    ModrinthApi.Version cur = api.versionByHash(sha1);
                    String loader = loaderOf(f.getFileName().toString());
                    List<ModrinthApi.Version> vs = api.versions(cur.projectId(), queryMc, loader);
                    ModrinthApi.Version latest = null;
                    for (ModrinthApi.Version x : vs) {
                        if (latest == null || x.datePublished() > latest.datePublished()) latest = x;
                    }
                    if (latest != null && latest.datePublished() > cur.datePublished()) {
                        ModrinthApi.File pf = latest.primaryFile();
                        if (pf != null) {
                            upd.add(new UpdateItem(f, cur.versionNumber(), latest.versionNumber(),
                                    pf.url(), pf.filename()));
                        }
                    }
                } catch (Exception ignored) {
                    // 非 Modrinth 来源文件（手装/其它源）跳过
                }
            }
            return upd;
        }, upd -> {
            if (upd.isEmpty()) {
                app.showToast("该目录下所有内容均已是最新适配版本（Modrinth 来源）。");
                return;
            }
            showUpdateInline(box, fileList, dir, extCsv, upd);
        }, err -> Ui.error("检测更新失败", err));
    }

    /** 更新列表内联：把 Tab 内容替换为「勾选更新」视图，更新完/取消后恢复文件列表。 */
    private void showUpdateInline(VBox box, ListView<CheckBox> fileList, Path dir, String extCsv,
                                  List<UpdateItem> items) {
        VBox updBox = new VBox(8);
        updBox.setPadding(new Insets(4));

        Label head = new Label("检测到 " + items.size() + " 个内容有更新的适配版本：");
        head.setStyle("-fx-text-fill:" + Ui.TEXT + "; -fx-font-size:14px; -fx-font-weight:bold;");

        ListView<CheckBox> list = new ListView<>();
        list.setPrefHeight(260);
        Ui.bindWheelScroll(list);
        list.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";");
        for (UpdateItem it : items) {
            CheckBox cb = new CheckBox(it.file().getFileName() + "   →   " + it.newVer());
            cb.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
            cb.setSelected(true);
            list.getItems().add(cb);
        }
        VBox.setVgrow(list, Priority.ALWAYS);

        Button all = new Button("全选");
        all.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        all.setOnAction(e -> list.getItems().forEach(c -> c.setSelected(true)));
        Button none = new Button("全不选");
        none.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        none.setOnAction(e -> list.getItems().forEach(c -> c.setSelected(false)));
        Button update = new Button("更新所选");
        update.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white; -fx-font-weight:bold;");
        update.setOnAction(e -> {
            List<UpdateItem> sel = new ArrayList<>();
            for (int i = 0; i < list.getItems().size(); i++) {
                if (list.getItems().get(i).isSelected()) sel.add(items.get(i));
            }
            if (sel.isEmpty()) {
                Ui.error("未选择", "请至少勾选一个要更新的内容。");
                return;
            }
            doUpdates(dir, extCsv, box, fileList, sel);
        });
        Button back = new Button("返回列表");
        back.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        back.setOnAction(e -> restoreFileView(box, fileList, dir, extCsv));

        HBox btns = new HBox(8, all, none, update, back);
        btns.setAlignment(Pos.CENTER_LEFT);

        updBox.getChildren().addAll(head, list, btns);
        box.getChildren().setAll(updBox);
    }

    /** 恢复文件列表视图（更新完成 / 返回列表后）。 */
    private void restoreFileView(VBox box, ListView<CheckBox> fileList, Path dir, String extCsv) {
        fillFiles(fileList, dir, extCsv);
        VBox old = new VBox(8);
        old.setPadding(new Insets(4));
        Label pathLabel = new Label("文件夹：" + dir.toAbsolutePath());
        pathLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        pathLabel.setWrapText(true);
        Button update = new Button("检测更新");
        update.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
        update.setOnAction(e -> checkUpdates(dir, extCsv, box, fileList));
        Button delete = new Button("删除所选");
        delete.setStyle("-fx-background-color:#c0392b; -fx-text-fill:white;");
        delete.setOnAction(e -> deleteSelected(fileList, dir, extCsv));
        Button open = new Button("打开相应文件夹");
        open.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        open.setOnAction(e -> openDir(dir));
        HBox btns = new HBox(8, update, delete, open);
        btns.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("勾选要删除的文件（可勾选多个）；「检测更新」按 Modrinth 来源比对最新适配版本，结果在本页直接展示。");
        hint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        VBox.setVgrow(fileList, Priority.ALWAYS);
        old.getChildren().addAll(pathLabel, fileList, btns, hint);
        box.getChildren().setAll(old);
    }

    /** 下载新版文件并替换旧文件（下载失败时保留旧文件），完成后恢复列表并 toast。 */
    private void doUpdates(Path dir, String extCsv, VBox box, ListView<CheckBox> fileList,
                           List<UpdateItem> sel) {
        Ui.async2(() -> {
            int okc = 0;
            for (UpdateItem it : sel) {
                try {
                    Path target = dir.resolve(it.newFilename());
                    // 实例内容更新统一走 Downloader：jar 必须过 ZIP 头 + CRC，坏包当场发现
                    boolean ok = com.jwh.launcher.download.Downloader.fetch(target, -1,
                            com.jwh.launcher.download.ArtifactProbe.Kind.of(target, -1),
                            it.newFilename(), null,
                            com.jwh.launcher.download.Downloader.Policy.of(60, 180), it.url());
                    if (ok) {
                        Files.deleteIfExists(it.file());
                        okc++;
                    }
                } catch (Exception ignored) {
                }
            }
            return okc;
        }, n -> {
            restoreFileView(box, fileList, dir, extCsv);
            app.showToast("已更新 " + n + " 个内容（" + sel.size() + " 个勾选项中下载成功的）。启动游戏即可生效。");
        }, err -> Ui.error("更新失败", err));
    }

    /** 加载器 Tab：检测该版本目录及 versions 下同系列的加载器版本；CheckBox 勾选多选删除。 */
    private Tab loaderTab() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));

        ListView<CheckBox> list = new ListView<>();
        list.setPrefHeight(260);
        Ui.bindWheelScroll(list);
        list.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";");

        // 条目文本 → 真实路径（用于删除）
        Map<String, Path> itemPath = new LinkedHashMap<>();
        List<CheckBox> items = new ArrayList<>();
        // 1) 版本目录里直接安装的加载器文件/目录
        if (Files.isDirectory(versionDir)) {
            try (var s = Files.list(versionDir)) {
                s.filter(p -> isLoaderEntry(p.getFileName().toString()))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> {
                            String text = "[" + loaderKind(p.getFileName().toString()) + "] "
                                    + p.getFileName() + (Files.isDirectory(p) ? "  （目录）" : "");
                            CheckBox cb = new CheckBox(text);
                            cb.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
                            items.add(cb);
                            itemPath.put(text, p);
                        });
            } catch (Exception ignored) {
            }
        }
        // 2) versions 下与当前版本同系列的加载器版本目录（如 1.21.1-fabric-0.16.9）
        Path versionsRoot = Path.of(app.config.minecraftDir).resolve("versions");
        if (Files.isDirectory(versionsRoot)) {
            try (var vs = Files.list(versionsRoot)) {
                vs.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith(versionId + "-")
                                || p.getFileName().toString().startsWith(versionId + "_"))
                        .filter(p -> isLoaderEntry(p.getFileName().toString()))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> {
                            String text = "[" + loaderKind(p.getFileName().toString()) + "] "
                                    + p.getFileName() + "  （独立版本目录）";
                            CheckBox cb = new CheckBox(text);
                            cb.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
                            items.add(cb);
                            itemPath.put(text, p);
                        });
            } catch (Exception ignored) {
            }
        }

        if (items.isEmpty()) {
            list.getItems().add(new CheckBox("（未检测到加载器，当前为原版）"));
        } else {
            list.getItems().setAll(items);
        }

        Button update = new Button("检测更新");
        update.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
        update.setOnAction(e -> app.showToast(
                "加载器的新版本请在「下载中心 → 游戏版本 → 选择版本与加载器」重新安装；旧版可在此处勾选删除。"));

        Button delete = new Button("删除所选");
        delete.setStyle("-fx-background-color:#c0392b; -fx-text-fill:white;");
        delete.setOnAction(e -> deleteLoaderSelected(list, itemPath));

        Button open = new Button("打开相应文件夹");
        open.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        open.setOnAction(e -> openDir(versionDir));

        HBox btns = new HBox(8, update, delete, open);
        btns.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("勾选要删除的加载器（可勾选多个）；删除目录不可恢复，请谨慎。");
        hint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");

        VBox.setVgrow(list, Priority.ALWAYS);
        box.getChildren().addAll(list, btns, hint);
        return new Tab("加载器", box);
    }

    // ---------- 存档 Tab（导出 / 导入） ----------

    /** 存档 Tab：列出 saves 目录下的世界文件夹（勾选多选导出为 zip），支持导入 zip 存档。 */
    private Tab saveTab() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        Path dir = versionDir.resolve("saves");
        Label pathLabel = new Label("文件夹：" + dir.toAbsolutePath());
        pathLabel.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        pathLabel.setWrapText(true);

        ListView<CheckBox> list = new ListView<>();
        list.setPrefHeight(260);
        Ui.bindWheelScroll(list);
        list.setStyle("-fx-background-color:" + Ui.PANEL + "; -fx-control-inner-background:" + Ui.PANEL + ";");
        fillSaves(list, dir);

        Button export = new Button("导出所选");
        export.setStyle("-fx-background-color:" + Ui.ACCENT + "; -fx-text-fill:white;");
        export.setOnAction(e -> exportSaves(dir, list));

        Button imp = new Button("导入存档");
        imp.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        imp.setOnAction(e -> importSaves(dir, list));

        Button open = new Button("打开相应文件夹");
        open.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
        open.setOnAction(e -> openDir(dir));

        HBox btns = new HBox(8, export, imp, open);
        btns.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("勾选要导出的存档（可勾选多个），导出为一个 zip 备份包，可备份到网盘/移动硬盘；"
                + "「导入存档」选择 zip 包解压到本实例存档目录（兼容本启动器导出包与常见备份包结构）。");
        hint.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + "; -fx-font-size:12px;");
        hint.setWrapText(true);

        VBox.setVgrow(list, Priority.ALWAYS);
        box.getChildren().addAll(pathLabel, list, btns, hint);
        return new Tab("存档", box);
    }

    /** 填充存档列表（saves 目录下的世界文件夹，名称 + 占用大小）。
     *  大小统计在后台进行：大存档目录文件多，同步统计会卡住界面（表现为点击未响应）。 */
    private static void fillSaves(ListView<CheckBox> list, Path dir) {
        Ui.async2(() -> {
            if (!Files.isDirectory(dir)) return null;
            List<Object[]> rows = new ArrayList<>(); // {name, size}
            try (var s = Files.list(dir)) {
                for (Path w : s.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .toList()) {
                    rows.add(new Object[]{w.getFileName().toString(), dirSize(w)});
                }
            }
            return rows;
        }, rows -> {
            if (rows == null) {
                CheckBox ph = new CheckBox("（目录尚未创建：" + dir.toAbsolutePath() + "）");
                ph.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + ";");
                list.getItems().setAll(ph);
                return;
            }
            if (rows.isEmpty()) {
                CheckBox ph = new CheckBox("（暂无存档，游戏内创建的世界会出现在这里）");
                ph.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + ";");
                list.getItems().setAll(ph);
                return;
            }
            List<CheckBox> cbs = new ArrayList<>();
            for (Object[] row : rows) {
                String name = (String) row[0];
                long size = (Long) row[1];
                CheckBox cb = new CheckBox(name + "  ·  " + formatSize(size));
                cb.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
                cb.setUserData(name);
                cbs.add(cb);
            }
            list.getItems().setAll(cbs);
        }, e -> {
            CheckBox ph = new CheckBox("（读取失败：" + e.getMessage() + "）");
            ph.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + ";");
            list.getItems().setAll(ph);
        });
    }

    /** 导出勾选的存档世界为 zip（选择保存位置；后台打包，完成后 toast）。 */
    private void exportSaves(Path dir, ListView<CheckBox> list) {
        List<String> sel = new ArrayList<>();
        for (CheckBox cb : list.getItems()) {
            if (cb.isSelected() && cb.getUserData() != null) sel.add((String) cb.getUserData());
        }
        if (sel.isEmpty()) {
            Ui.error("未选择", "请先勾选要导出的存档（可勾选多个）。");
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("导出存档（" + sel.size() + " 个）");
        Path init = Files.isDirectory(dir) ? dir : versionDir;
        try {
            fc.setInitialDirectory(init.toFile());
        } catch (Exception ignored) {
        }
        fc.setInitialFileName(sel.size() == 1 ? sel.get(0) + ".zip" : "saves-backup.zip");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("存档压缩包", "*.zip"));
        File f = fc.showSaveDialog(null);
        if (f == null) return;
        String target = f.getPath().toLowerCase().endsWith(".zip") ? f.getPath() : f.getPath() + ".zip";
        final String targetPath = target;
        app.showToast("正在导出…");
        Ui.async2(() -> {
            Path out = Path.of(targetPath);
            zipWorlds(out, dir, sel);
            return out.toFile().length();
        }, size -> {
            app.showToast("已导出 " + sel.size() + " 个存档：" + targetPath + "（" + formatSize(size) + "）");
        }, err -> Ui.error("导出失败", err));
    }

    /** 把多个世界文件夹打包为 zip（条目顶层 = 世界名，导入时可原样解压到 saves）。 */
    private static void zipWorlds(Path out, Path savesDir, List<String> names) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            for (String name : names) {
                Path root = savesDir.resolve(name);
                if (!Files.isDirectory(root)) continue;
                try (var w = Files.walk(root)) {
                    List<Path> paths = w.sorted(Comparator.comparingInt(Path::getNameCount)).toList();
                    for (Path p : paths) {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        String entry = name + (rel.isEmpty() ? "" : "/" + rel);
                        if (Files.isDirectory(p)) {
                            zos.putNextEntry(new ZipEntry(entry + "/"));
                            zos.closeEntry();
                        } else {
                            zos.putNextEntry(new ZipEntry(entry));
                            Files.copy(p, zos);
                            zos.closeEntry();
                        }
                    }
                }
            }
        }
    }

    /** 导入存档 zip（后台解压，完成后刷新列表）。兼容：本启动器导出包 / 套层目录 / 单世界散文件包。 */
    private void importSaves(Path dir, ListView<CheckBox> list) {
        FileChooser fc = new FileChooser();
        fc.setTitle("导入存档");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("存档压缩包", "*.zip"));
        File f = fc.showOpenDialog(null);
        if (f == null) return;
        final Path zip = f.toPath();
        app.showToast("正在导入…");
        Ui.async2(() -> {
            if (!Files.isDirectory(dir)) Files.createDirectories(dir);
            Path tmp = Files.createTempDirectory("jwh-save-import");
            try {
                ModpackImporter.unzip(zip, tmp); // 防路径穿越解压
                Path root = ModpackImporter.detectRoot(tmp);
                if (Files.isRegularFile(root.resolve("level.dat"))) {
                    // 单世界散文件包（无世界文件夹包裹）：放入以 zip 基名命名的世界目录
                    String base = zip.getFileName().toString();
                    if (base.toLowerCase().endsWith(".zip")) base = base.substring(0, base.length() - 4);
                    Path world = dir.resolve(base);
                    Files.createDirectories(world);
                    try (var s = Files.list(root)) {
                        for (Path p : s.toList()) {
                            Path dst = world.resolve(p.getFileName().toString()).normalize();
                            if (!dst.startsWith(world)) throw new java.io.IOException("非法路径条目");
                            Files.move(p, dst, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } else {
                    ModpackImporter.mergeInto(root, dir);
                }
                return 0;
            } finally {
                ModpackImporter.deleteRecursively(tmp);
            }
        }, n -> {
            fillSaves(list, dir);
            app.showToast("导入完成。新存档已出现在列表中，启动游戏即可看到。");
        }, err -> Ui.error("导入失败", err));
    }

    /** 目录占用大小（递归统计所有文件字节数）。 */
    private static long dirSize(Path dir) {
        try (var w = Files.walk(dir)) {
            return w.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (Exception ignored) {
                    return 0L;
                }
            }).sum();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** 人类可读大小。 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 删除勾选的文件（CheckBox 多选；危险操作仍弹窗确认，完成后 toast）。 */
    private void deleteSelected(ListView<CheckBox> list, Path dir, String extCsv) {
        List<String> sel = new ArrayList<>();
        for (CheckBox cb : list.getItems()) {
            if (cb.isSelected() && !cb.getText().startsWith("（")) sel.add(cb.getText());
        }
        if (sel.isEmpty()) {
            Ui.error("未选择", "请先勾选要删除的文件（可勾选多个）。");
            return;
        }
        boolean go = Ui.confirm("删除所选",
                "确定删除勾选的 " + sel.size() + " 个文件？\n\n"
                        + String.join("\n", sel.stream().map(t -> "· " + t).toList()));
        if (!go) return;
        int okc = 0;
        for (String n : sel) {
            try {
                Files.deleteIfExists(dir.resolve(n));
                okc++;
            } catch (Exception ignored) {
            }
        }
        fillFiles(list, dir, extCsv);
        app.showToast("已删除 " + okc + " 个文件。");
    }

    /** 删除勾选的加载器（文件或目录，目录递归删除）。 */
    private void deleteLoaderSelected(ListView<CheckBox> list, Map<String, Path> itemPath) {
        List<String> sel = new ArrayList<>();
        for (CheckBox cb : list.getItems()) {
            if (cb.isSelected() && !cb.getText().startsWith("（")) sel.add(cb.getText());
        }
        if (sel.isEmpty()) {
            Ui.error("未选择", "请先勾选要删除的加载器（可勾选多个）。");
            return;
        }
        boolean go = Ui.confirm("删除所选",
                "确定删除勾选的 " + sel.size() + " 个加载器？目录会连同内容一起删除，不可恢复。\n\n"
                        + String.join("\n", sel.stream().map(t -> "· " + t).toList()));
        if (!go) return;
        int okc = 0;
        for (String n : sel) {
            Path p = itemPath.get(n);
            if (p == null) continue;
            try {
                if (Files.isDirectory(p)) {
                    try (var w = Files.walk(p)) {
                        w.sorted(Comparator.reverseOrder()).forEach(pp -> {
                            try {
                                Files.deleteIfExists(pp);
                            } catch (Exception ignored) {
                            }
                        });
                    }
                } else {
                    Files.deleteIfExists(p);
                }
                okc++;
            } catch (Exception ignored) {
            }
        }
        app.showToast("已删除 " + okc + " 个加载器。加载器列表已变化，请点「< 返回」后重新进入查看。");
    }

    private static List<Path> listFiles(Path dir, String extCsv) {
        List<Path> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) return out;
            try (var s = Files.list(dir)) {
                s.filter(p -> matchesExt(p.getFileName().toString(), extCsv))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(out::add);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void fillFiles(ListView<CheckBox> list, Path dir, String extCsv) {
        try {
            if (!Files.isDirectory(dir)) {
                CheckBox ph = new CheckBox("（目录尚未创建：" + dir.toAbsolutePath() + "）");
                ph.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + ";");
                list.getItems().setAll(ph);
                return;
            }
            List<String> names;
            try (var s = Files.list(dir)) {
                names = s.map(p -> p.getFileName().toString())
                        .filter(n -> matchesExt(n, extCsv))
                        .sorted(String::compareToIgnoreCase)
                        .collect(Collectors.toList());
            }
            if (names.isEmpty()) {
                CheckBox ph = new CheckBox("（暂无文件）");
                ph.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + ";");
                list.getItems().setAll(ph);
            } else {
                List<CheckBox> cbs = new ArrayList<>();
                for (String n : names) {
                    CheckBox cb = new CheckBox(n);
                    cb.setStyle("-fx-text-fill:" + Ui.TEXT + ";");
                    cbs.add(cb);
                }
                list.getItems().setAll(cbs);
            }
        } catch (Exception e) {
            CheckBox ph = new CheckBox("（读取失败：" + e.getMessage() + "）");
            ph.setStyle("-fx-text-fill:" + Ui.TEXT_DIM + ";");
            list.getItems().setAll(ph);
        }
    }

    private static boolean matchesExt(String name, String extCsv) {
        String low = name.toLowerCase();
        for (String ext : extCsv.split(",")) {
            if (low.endsWith("." + ext.trim())) return true;
        }
        return false;
    }

    private static boolean isLoaderEntry(String name) {
        String low = name.toLowerCase();
        return low.contains("fabric") || low.contains("forge") || low.contains("neoforge")
                || low.contains("quilt") || low.contains("loader");
    }

    private static String loaderKind(String name) {
        String low = name.toLowerCase();
        if (low.contains("neoforge")) return "NeoForge";
        if (low.contains("fabric")) return "Fabric";
        if (low.contains("forge")) return "Forge";
        if (low.contains("quilt")) return "Quilt";
        return "其它";
    }

    /** 从文件名推断加载器（更新查询用；无则不限）。 */
    private static String loaderOf(String filename) {
        String low = filename.toLowerCase();
        if (low.contains("neoforge")) return "neoforge";
        if (low.contains("fabric")) return "fabric";
        if (low.contains("forge")) return "forge";
        if (low.contains("quilt")) return "quilt";
        return null;
    }

    /** 计算文件 SHA1（Modrinth 文件哈希索引用）。 */
    private static String sha1(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void openDir(Path dir) {
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
            }
            Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) {
            Ui.error("打开文件夹失败", e);
        }
    }
}
