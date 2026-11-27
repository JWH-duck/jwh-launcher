# -*- coding: utf-8 -*-
# 补丁19：
#  A. GameInstaller 独立取消标志，主 jar 下载把 this 传给下载层，install 阶段检查点兜底。
#  B. DownloadProgressDialog / Ui.progressDialog 去系统装饰标题栏（UNDECORATED + 透明场景）。
#  C. Ui.styleDialog 给所有 Alert 挂监听，去掉系统标题栏。
import io, sys

ROOT = r"C:\Users\33095\Doubao\chats\2026-10-21\new-chat\jwh-launcher\src\main\java\com\jwh\launcher"

def load(p):
    with io.open(p, "r", encoding="utf-8") as f:
        return f.read().replace("\r\n", "\n")

def save(p, s):
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)

def rep(src, old, new, tag):
    if old not in src:
        raise SystemExit("NOT FOUND: " + tag)
    return src.replace(old, new, 1)

# ---------- A. GameInstaller ----------
gi = ROOT + r"\game\GameInstaller.java"
s = load(gi)

# A1. 独立取消标志字段（挂在 activeDm 声明旁）
s = rep(s,
"""    private volatile DownloadManager activeDm;""",
"""    private volatile DownloadManager activeDm;
    /** 独立取消标志：主 jar 阶段 activeDm 尚未建立，取消请求也要能落到下载层。 */
    private final java.util.concurrent.atomic.AtomicBoolean cancelledFlag =
            new java.util.concurrent.atomic.AtomicBoolean();""",
"gi-field")

# A2. cancel() 置标志
s = rep(s,
"""    @Override
    public void cancel() {
        cancelDownload();
    }""",
"""    @Override
    public void cancel() {
        cancelledFlag.set(true);
        cancelDownload();
    }""",
"gi-cancel")

# A3. isCancelled() 合并标志
s = rep(s,
"""    @Override
    public boolean isCancelled() {
        DownloadManager d = activeDm;
        return d != null && d.isCancelled();
    }""",
"""    @Override
    public boolean isCancelled() {
        if (cancelledFlag.get()) return true;
        DownloadManager d = activeDm;
        return d != null && d.isCancelled();
    }

    /** 阶段切换兜底检查：已请求取消则中止安装流程。 */
    private void checkCancelled() throws java.io.IOException {
        if (isCancelled()) throw new java.io.IOException("下载已取消");
    }""",
"gi-iscancelled")

# A4. 主 jar 下载把 this 作为取消控制传入（原来传 null）
s = rep(s,
"""        return com.jwh.launcher.download.Downloader.fetch(target, -1,
                com.jwh.launcher.download.ArtifactProbe.Kind.of(target, -1),
                label, null, mirrored, url);""",
"""        return com.jwh.launcher.download.Downloader.fetch(target, -1,
                com.jwh.launcher.download.ArtifactProbe.Kind.of(target, -1),
                label, this, mirrored, url);""",
"gi-fetch-ctl")

# A5. install 阶段检查点：方法开头 + 主 jar 前 + 素材前
s = rep(s,
"""    public void install(String id, String versionJsonUrl, InstallListener listener) throws Exception {
        // 1. 版本 JSON""",
"""    public void install(String id, String versionJsonUrl, InstallListener listener) throws Exception {
        checkCancelled();
        // 1. 版本 JSON""",
"gi-install-head")

s = rep(s,
"""        // 2. 主 jar（慢速网络下最多尝试 8 次，Http.download 内部 60 秒读超时 + 断点续传，
        //    每次至少推进部分字节，不会长时间卡死）""",
"""        checkCancelled();
        // 2. 主 jar（慢速网络下最多尝试 8 次，Http.download 内部 60 秒读超时 + 断点续传，
        //    每次至少推进部分字节，不会长时间卡死）""",
"gi-install-jar")

s = rep(s,
"""        // 4. assets 索引 + 对象
        downloadAssets(v, listener);""",
"""        checkCancelled();
        // 4. assets 索引 + 对象
        downloadAssets(v, listener);""",
"gi-install-assets")

save(gi, s)
print("OK GameInstaller")

# ---------- B1. DownloadProgressDialog ----------
dp = ROOT + r"\ui\DownloadProgressDialog.java"
s = load(dp)
s = rep(s,
"""        stage = new Stage(StageStyle.UTILITY);""",
"""        stage = new Stage(StageStyle.UNDECORATED);""",
"dp-style")
s = rep(s,
"""        Scene scene = new Scene(box);
        Ui.applyStylesheet(scene);
        stage.setScene(scene);""",
"""        Scene scene = new Scene(box);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        Ui.applyStylesheet(scene);
        stage.setScene(scene);""",
"dp-scene-fill")
save(dp, s)
print("OK DownloadProgressDialog")

# ---------- B2. Ui.progressDialog ----------
ui = ROOT + r"\ui\Ui.java"
s = load(ui)
s = rep(s,
"""        javafx.stage.Stage stage = new javafx.stage.Stage(javafx.stage.StageStyle.UTILITY);""",
"""        javafx.stage.Stage stage = new javafx.stage.Stage(javafx.stage.StageStyle.UNDECORATED);""",
"ui-progress-style")
s = rep(s,
"""        javafx.scene.Scene scene = new javafx.scene.Scene(box);
        applyStylesheet(scene);
        stage.setScene(scene);""",
"""        javafx.scene.Scene scene = new javafx.scene.Scene(box);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        applyStylesheet(scene);
        stage.setScene(scene);""",
"ui-progress-fill")

# ---------- C. styleDialog：Alert 去系统标题栏 ----------
s = rep(s,
"""    private static void styleDialog(Alert a) {
        try {
            a.getDialogPane().setStyle("-fx-font-size:14px;");
        } catch (Exception ignored) {
        }""",
"""    private static void styleDialog(Alert a) {
        try {
            a.getDialogPane().setStyle("-fx-font-size:14px;");
        } catch (Exception ignored) {
        }
        // 去掉 Alert 外层系统装饰标题栏：监听场景/窗口绑定，在 Stage 显示前改为无装饰样式。
        try {
            javafx.scene.control.DialogPane dp = a.getDialogPane();
            dp.sceneProperty().addListener((obs, oldSc, sc) -> {
                if (sc == null) return;
                sc.windowProperty().addListener((o2, oldW, win) -> {
                    if (win instanceof javafx.stage.Stage st) {
                        try { st.initStyle(javafx.stage.StageStyle.UNDECORATED); } catch (Exception ignored) { }
                    }
                });
            });
        } catch (Exception ignored) {
        }""",
"ui-alert-undecorated")

save(ui, s)
print("OK Ui")
