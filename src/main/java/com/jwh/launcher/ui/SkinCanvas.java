package com.jwh.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 皮肤预览渲染器（2D 画布 + 正交投影）。
 *
 * <p>做法：把 Minecraft 玩家模型拆成 6 个方块部件（头 / 身 / 左右臂 / 左右腿）和
 * 一层"外皮肤层"（帽子 / 外套 / 袖子 / 裤腿），每个方块 6 个面，逐面按皮肤 UV
 * 贴图并叠加方向光；按深度从远到近绘制（画家算法）。主预览与列表缩略图共用本类，
 * 保证两处视觉完全一致。
 *
 * <p>不采用 JavaFX 3D 的原因：{@code PhongMaterial} 的纹理是双线性采样，64×64 皮肤
 * 放大到几百像素后会被糊掉；这里用 Canvas 逐面贴图 + 关闭图像平滑，边缘保持硬像素。
 */
public class SkinCanvas extends Canvas {

    /** 皮肤纹理边长（现代格式固定 64×64）。 */
    private static final int TEX = 64;

    /** 一个可绘制面：模型空间 4 顶点 + 纹理矩形 + 法线 + 所属部件。 */
    private static final class Face {
        double[] p = new double[12];
        int ux, uy, uw, uh;
        double nx, ny, nz;
        /** 0=躯干 1=头 2=右臂 3=左臂 4=右腿 5=左腿 6=披风。 */
        int part;
        /** 是否外皮肤层（比内层大一圈，必须比内层后画）。 */
        boolean outer;
        /** 该面从披风贴图取色（否则从皮肤贴图取色）。 */
        boolean cape;
        double depth;
        double[] sx = new double[4];
        double[] sy = new double[4];
        double[] sz = new double[4];
    }

    /** 各部件绕自身枢轴摆动用的枢轴点（模型坐标）。第 7 项是披风（不摆动，挂着随身体转）。 */
    private static final double[][] PIVOT = {
            {0, 12, 0}, {0, 24, 0}, {-6, 22, 0}, {6, 22, 0}, {-2, 12, 0}, {2, 12, 0}, {0, 24, -3}
    };

    /** 整体俯仰旋转的枢轴高度（腹部）：让角色绕腰转身，而不是绕脚底把人放倒。 */
    private static final double ROT_PIVOT_Y = 12.0;

    private int[] pixels;          // 64×64 ARGB
    /** 透明像素的兜底颜色（ARGB，alpha 已置 255）；0 表示邻近也没有不透明像素。 */
    private int[] fillPx;
    /** 披风贴图（64×64 ARGB，标准 64×32 披风会补成全 64 行）；null = 无披风 */
    private int[] capePx;
    /** 手动指定宽/窄模型：null=按贴图自动判定，true=细手臂，false=宽手臂 */
    private Boolean slimOverride;
    private static final boolean DEBUG_SKIN = Boolean.getBoolean("jwh.skin.debug");
    private Image tex;             // 64×64 纹理（供 drawImage）
    private boolean slim;          // 细手臂模型
    private final List<Face> faces = new ArrayList<>();

    /** 软件光栅化缓冲：颜色 + 深度，逐像素判定前后，彻底避免画家算法排序错乱。 */
    private int[] buf;
    private double[] zbuf;
    private WritableImage outImg;
    private PixelWriter outPw;
    private int bufW, bufH;

    private double yaw = 22.5;     // 水平旋转（度），对齐美西螈 initial-rotation = π/8
    private double pitch = 7;      // 俯仰（度），略微俯视才有立体感

    /** 走动开关：true 时播放迈步动画（双腿前后交替摆动、手臂同步反相），false 为待机站立。 */
    private boolean walking;

    /** 待机动画计时（秒）；不播放动画时恒为 0，即静止姿势。 */
    private double animTime;

    /**
     * 头部注视目标（度）。NaN = 不启用，保持原来的待机左右轻摆。
     * 主页小人用它让脑袋跟着鼠标转，皮肤库页面不设置，行为完全不变。
     */
    private double headAimYaw = Double.NaN;
    private double headAimPitch = Double.NaN;
    /** 平滑后的当前头部角度，由动画帧逐步逼近目标，避免鼠标一动头就瞬移。 */
    private double headYawNow;
    private double headPitchNow;
    private static final boolean PERF = Boolean.getBoolean("jwh.skin.perf");
    private static final boolean NOLIMIT = Boolean.getBoolean("jwh.skin.nolimit");
    private long perfFrames, perfNanos, perfStart;
    /** 点击能量 0..1，随时间衰减，驱动下蹲 + 抖动。 */
    private double energy;

    /** 点击互动动作：0=无，1=打招呼（挥手）。 */
    private int action;
    /** 当前动作已播放的秒数。 */
    private double actionTime;
    private static final double ACTION_WAVE_DUR = 1.5;

    private AnimationTimer anim;

    /** 启动待机动画（稳定 60fps）；缩略图卡片不调用，只渲染一帧省 CPU。 */
    public void startAnimation() {
        if (anim != null) return;
        AnimationTimer at = new AnimationTimer() {
            private long lastRender;   // 上一帧的实际时刻（System.nanoTime）
            private boolean started;

            @Override
            public void handle(long pulseNow) {
                // 用真实时钟节流：JavaFX 的 pulse 时间戳会抖动，按它算会把 60fps 拖成 30 多帧
                long now = System.nanoTime();
                if (started) {
                    long el = now - lastRender;
                    // 阈值取 12ms：阈值贴近 16.7ms 时会因 pulse 抖动频繁丢帧，反而掉到 30 多帧
                    if (el < 12000000L && !NOLIMIT) return;
                    energy = Math.max(0, energy - el / 1e9 * 4.0);
                    animTime += el / 1e9;
                    if (action != 0) {
                        actionTime += el / 1e9;
                        if (actionTime >= ACTION_WAVE_DUR) { action = 0; actionTime = 0; }
                    }
                }
                started = true;
                lastRender = now;
                render();
            }
        };
        at.start();
        anim = at;
    }

    /** 停止待机动画（页面不可见时调用，避免后台空转）。 */
    public void stopAnimation() {
        if (anim != null) {
            anim.stop();
            anim = null;
        }
        animTime = 0;
    }

    /**
     * 点击互动：打招呼（挥手）。跳跃动作已按反馈去掉，只保留这一个。
     * 原来那套下蹲 + 抖动改由挥手承担，所以不再叠加 energy。
     */
    public void clickPulse() {
        this.action = 1;
        this.actionTime = 0.0;
        this.energy = 0.0;
        render();
    }

    public SkinCanvas(double w, double h) {
        super(w, h);
    }

    // ---------------------------------------------------------------- 载入

    /** 载入皮肤 PNG；返回 false 表示不是合法皮肤（宽高不匹配）。 */
    public boolean setSkin(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return setSkin(new Image(in));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean setSkin(Image img) {
        if (img == null || img.isError()) return false;
        int iw = (int) Math.round(img.getWidth());
        int ih = (int) Math.round(img.getHeight());
        if (iw != 64 || (ih != 64 && ih != 32)) return false;
        PixelReader pr = img.getPixelReader();
        if (pr == null) return false;

        int[] src = new int[iw * ih];
        pr.getPixels(0, 0, iw, ih, PixelFormat.getIntArgbInstance(), src, 0, iw);

        pixels = new int[TEX * TEX];
        // 老版 64×32：前 32 行与新版布局一致，直接复制；左腿 / 左臂按右腿 / 右臂镜像补全。
        for (int y = 0; y < Math.min(ih, TEX); y++) {
            System.arraycopy(src, y * iw, pixels, y * TEX, Math.min(iw, TEX));
        }
        if (ih == 32) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    // 左腿 (16,48) ← 右腿 (0,16)，水平镜像
                    pixels[(48 + y) * TEX + (16 + x)] = src[(16 + y) * 64 + (15 - x)];
                    // 左臂 (32,48) ← 右臂 (40,16)，水平镜像
                    pixels[(48 + y) * TEX + (32 + x)] = src[(16 + y) * 64 + (55 - x)];
                }
            }
        }

        WritableImage out = new WritableImage(TEX, TEX);
        out.getPixelWriter().setPixels(0, 0, TEX, TEX,
                PixelFormat.getIntArgbInstance(), pixels, 0, TEX);
        tex = out;
        buildFill();
        slim = slimOverride != null ? slimOverride : detectSlim();
        buildModel();
        return true;
    }

    /**
     * 手动指定宽/窄模型（皮肤站可先在网页选好，启动器这边跟着走）。
     * 传 null 恢复「按贴图自动判定」。会立即重建模型，界面能马上看到手臂粗细变化。
     */
    public void setSlimOverride(Boolean slimValue) {
        this.slimOverride = slimValue;
        if (pixels != null) {
            this.slim = slimValue != null ? slimValue : detectSlim();
            buildModel();
        }
    }

    public Boolean getSlimOverride() {
        return slimOverride;
    }

    /** 当前实际使用的模型是否为细手臂。 */
    public boolean isSlim() {
        return slim;
    }

    /** 载入披风（标准 64×32 或 64×64 PNG）；返回 false 表示不是合法披风图。 */
    public boolean setCape(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return setCape(new Image(in));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean setCape(Image img) {
        if (img == null || img.isError()) return false;
        int iw = (int) Math.round(img.getWidth());
        int ih = (int) Math.round(img.getHeight());
        if (iw != 64 || (ih != 32 && ih != 64)) return false;
        PixelReader pr = img.getPixelReader();
        if (pr == null) return false;
        int[] src = new int[iw * ih];
        pr.getPixels(0, 0, iw, ih, PixelFormat.getIntArgbInstance(), src, 0, iw);
        capePx = new int[TEX * TEX];
        for (int y = 0; y < Math.min(ih, TEX); y++) {
            System.arraycopy(src, y * iw, capePx, y * TEX, Math.min(iw, TEX));
        }
        buildModel();
        return true;
    }

    /** 移除披风。 */
    public void clearCape() {
        capePx = null;
        buildModel();
    }

    public boolean hasCape() {
        return capePx != null;
    }

    /**
     * 给"透明像素"算一个兜底颜色，渲染本体层时用它顶上。
     * <p>很多皮肤只画了必要的面，比如这块皮肤的右臂后背只画了一半，另一半 alpha=0。
     * 这个渲染器只画外表面、不做双面补内壁，遇到 alpha=0 直接跳过就会露出背景，
     * 看着就是"手臂背部是透明的"。这里按"同一行左右最近 → 同一列上下最近"的顺序
     * 取邻近不透明像素的颜色，让缺失的那几列染上周围同样的颜色。
     */
    private void buildFill() {
        fillPx = new int[TEX * TEX];
        for (int y = 0; y < TEX; y++) {
            for (int x = 0; x < TEX; x++) {
                int i = y * TEX + x;
                if (((pixels[i] >>> 24) & 0xFF) >= 128) {
                    fillPx[i] = pixels[i] | 0xFF000000;
                    continue;
                }
                int c = 0;
                for (int d = 1; d < TEX && c == 0; d++) {
                    int xl = x - d, xr = x + d;
                    if (xl >= 0 && ((pixels[y * TEX + xl] >>> 24) & 0xFF) >= 128) {
                        c = pixels[y * TEX + xl];
                    } else if (xr < TEX && ((pixels[y * TEX + xr] >>> 24) & 0xFF) >= 128) {
                        c = pixels[y * TEX + xr];
                    }
                }
                if (c != 0) fillPx[i] = c | 0xFF000000;
            }
        }
        for (int x = 0; x < TEX; x++) {
            for (int y = 0; y < TEX; y++) {
                int i = y * TEX + x;
                if (fillPx[i] != 0) continue;
                int c = 0;
                for (int d = 1; d < TEX && c == 0; d++) {
                    int yt = y - d, yb = y + d;
                    if (yt >= 0 && ((pixels[yt * TEX + x] >>> 24) & 0xFF) >= 128) {
                        c = pixels[yt * TEX + x];
                    } else if (yb < TEX && ((pixels[yb * TEX + x] >>> 24) & 0xFF) >= 128) {
                        c = pixels[yb * TEX + x];
                    }
                }
                if (c != 0) fillPx[i] = c | 0xFF000000;
            }
        }
    }

    public boolean hasSkin() {
        return tex != null;
    }

    /** 细手臂判定：手臂贴图区 (54,20) 起 2×12 是否全透明（与主流皮肤预览实现一致）。 */
    private boolean detectSlim() {
        // 调试开关：-Djwh.skin.forceClassic=true 强制按经典 4 像素手臂渲染，用于肉眼对比
        if (Boolean.getBoolean("jwh.skin.forceClassic")) return false;
        // 调试开关：-Djwh.skin.forceSlim=true 强制按细手臂渲染
        if (Boolean.getBoolean("jwh.skin.forceSlim")) return true;
        // 经典 4 像素手臂的"后背"贴图区 (52,20,4,12) 只要画过东西，就一定是经典模型。
        // 只看 (54,20) 两列会把"后背只画了一半"的经典皮肤误判成细手臂。
        if (hasOpaque(52, 20, 4, 12)) return false;
        for (int y = 20; y < 32; y++) {
            for (int x = 54; x < 56; x++) {
                if ((pixels[y * TEX + x] >>> 24) != 0) return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 建模

    private void buildModel() {
        faces.clear();

        // 内层：头 / 身 / 双臂 / 双腿（第二参数为所属部件，供待机动画分别摆动）
        addBox(1, -4, 24, -4, 8, 8, 8, 0, 0, 8, 8, 8, false);
        addBox(0, -4, 12, -2, 8, 12, 4, 16, 16, 8, 12, 4, false);

        // slim 只收窄模型，贴图 UV 区仍是 4 像素宽，两者不能混用
        int armW = slim ? 3 : 4;
        if (slim) {
            addSlimArm(2, -4 - armW, 12, -2, armW, 12, 4, 44, 16, false);
            addSlimArm(3, 4, 12, -2, armW, 12, 4, 36, 48, false);
        } else {
            addBox(2, -4 - armW, 12, -2, armW, 12, 4, 40, 16, 4, 12, 4, false);
            addBox(3, 4, 12, -2, armW, 12, 4, 32, 48, 4, 12, 4, false);
        }

        addBox(4, -4, 0, -2, 4, 12, 4, 0, 16, 4, 12, 4, false);
        addBox(5, 0, 0, -2, 4, 12, 4, 16, 48, 4, 12, 4, false);

        // 外皮肤层（比内层大一圈；UV 展开仍按内层尺寸，贴图才不错位）
        imageLayer(1, -4.5, 23.5, -4.5, 9, 9, 9, 32, 0, 8, 8, 8);
        imageLayer(0, -4.5, 11.5, -2.5, 9, 13, 5, 16, 32, 8, 12, 4);
        if (slim) {
            if (hasOpaque(40, 28, 16, 20)) addSlimArm(2, -4.5 - armW, 11.5, -2.5, armW + 1, 13, 5, 40, 32, true);
            if (hasOpaque(48, 44, 16, 20)) addSlimArm(3, 3.5, 11.5, -2.5, armW + 1, 13, 5, 48, 48, true);
        } else {
            imageLayer(2, -4.5 - armW, 11.5, -2.5, armW + 1, 13, 5, 40, 32, 4, 12, 4);
            imageLayer(3, 3.5, 11.5, -2.5, armW + 1, 13, 5, 48, 48, 4, 12, 4);
        }
        imageLayer(4, -4.5, -0.5, -2.5, 5, 13, 5, 0, 32, 4, 12, 4);
        imageLayer(5, -0.5, -0.5, -2.5, 5, 13, 5, 0, 48, 4, 12, 4);

        // 披风：背后一块 10×16 的布，顶上贴身、下摆略往外张（和 MC 里垂着的样子一致）
        if (capePx != null) addCape();
    }

    /**
     * 披风建模。标准披风贴图 64×32：正面在 (1,1,10,16)，背面在 (12,1,10,16)。
     * 这里画两层（外侧朝 −Z、内侧朝 +Z），从任何角度看都不会露出薄片空洞。
     */
    private void addCape() {
        double x0 = -5.0, x1 = 5.0;
        double yTop = 24.0, yBot = 8.0;
        // 上沿贴背（身体背面在 z=-2），下摆往后张开约 1 像素，视觉上有垂坠感
        double zTopOut = -3.10, zBotOut = -4.20;
        double zTopIn = -2.25, zBotIn = -3.35;

        // 外侧（法线朝 −Z）——玩家从背后看到的这面
        addCapeFace(1, 1, 10, 16, 0, 0, -1,
                x1, yTop, zTopOut, x0, yTop, zTopOut, x0, yBot, zBotOut, x1, yBot, zBotOut);
        // 内侧（法线朝 +Z）——贴身体的那面
        addCapeFace(12, 1, 10, 16, 0, 0, 1,
                x0, yTop, zTopIn, x1, yTop, zTopIn, x1, yBot, zBotIn, x0, yBot, zBotIn);
    }

    /** 披风面：UV 从 capePx 取色，整块透明就跳过。 */
    private void addCapeFace(int u, int v, int w, int h, double nx, double ny, double nz,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz) {
        if (!hasOpaqueIn(capePx, u, v, w, h)) return;
        Face f = new Face();
        f.part = 6;
        f.outer = true;
        f.cape = true;
        f.p[0] = ax; f.p[1] = ay; f.p[2] = az;
        f.p[3] = bx; f.p[4] = by; f.p[5] = bz;
        f.p[6] = cx; f.p[7] = cy; f.p[8] = cz;
        f.p[9] = dx; f.p[10] = dy; f.p[11] = dz;
        f.ux = u; f.uy = v; f.uw = w; f.uh = h;
        f.nx = nx; f.ny = ny; f.nz = nz;
        faces.add(f);
    }

    /** 指定像素数组的不透明判定（披风用独立贴图，不能查皮肤那一份）。 */
    private boolean hasOpaqueIn(int[] arr, int u, int v, int w, int h) {
        if (arr == null) return false;
        for (int y = v; y < v + h; y++) {
            for (int x = u; x < u + w; x++) {
                if (x < 0 || y < 0 || x >= TEX || y >= TEX) continue;
                if ((arr[y * TEX + x] >>> 24) != 0) return true;
            }
        }
        return false;
    }

    /** 仅在纹理区域存在不透明像素时才建外壳（避免给空层白白画 6 个面）。 */
    private void imageLayer(int part, double x0, double y0, double z0, double w, double h, double d,
                            int u, int v, int uw, int uh, int ud) {
        // 第二层皮肤（帽檐/袖套/衣摆）就是这个"突起"的来源，只按面判断有没有像素即可
        if (!hasOpaque(u, v, 2 * (ud + uw), ud + uh)) return;
        addBox(part, x0, y0, z0, w, h, d, u, v, uw, uh, ud, true);
    }

    /** 统计一个贴图区域里不透明像素的占比，用于判断外层皮肤该不该整块绘制。 */
    private double opaqueRatio(int u, int v, int w, int h) {
        int total = 0, solid = 0;
        for (int y = v; y < v + h; y++) {
            for (int x = u; x < u + w; x++) {
                if (x < 0 || y < 0 || x >= TEX || y >= TEX) continue;
                total++;
                if ((pixels[y * TEX + x] >>> 24) >= 128) solid++;
            }
        }
        return total == 0 ? 0.0 : (double) solid / total;
    }

    private boolean hasOpaque(int u, int v, int w, int h) {
        for (int y = v; y < v + h; y++) {
            for (int x = u; x < u + w; x++) {
                if (x < 0 || y < 0 || x >= TEX || y >= TEX) continue;
                if ((pixels[y * TEX + x] >>> 24) != 0) return true;
            }
        }
        return false;
    }

    /**
     * 生成一个方块部件的 6 个面。
     * <p>UV 采用 Minecraft 标准展开：一行依次为 右(d×h) / 前(w×h) / 左(d×h) / 后(w×h)，
     * 顶面在 (u+d, v)，底面在 (u+d+w, v)。
     */
    private void addBox(int part, double x0, double y0, double z0, double w, double h, double d,
                        int u, int v, int uw, int uh, int ud, boolean outer) {
        double x1 = x0 + w, y1 = y0 + h, z1 = z0 + d;

        // 前面 (z+)
        addFace(part, outer, u + ud, v + ud, uw, uh, 0, 0, 1,
                x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
        // 后面 (z-)
        addFace(part, outer, u + ud + uw + ud, v + ud, uw, uh, 0, 0, -1,
                x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0);
        // 右面 (x-)
        addFace(part, outer, u, v + ud, ud, uh, -1, 0, 0,
                x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0);
        // 左面 (x+)
        addFace(part, outer, u + ud + uw, v + ud, ud, uh, 1, 0, 0,
                x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1);
        // 顶面 (y+)
        addFace(part, outer, u + ud, v, uw, ud, 0, 1, 0,
                x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        // 底面 (y-)
        addFace(part, outer, u + ud + uw, v, uw, ud, 0, -1, 0,
                x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
    }

    /**
     * 细手臂（slim）专用：手臂贴图区宽高只有 3×12，与粗手臂的 4×12 展开规则不同。
     * <p>标准布局（以区域左上角 u,v 为基准）：
     * 右 (u,v,4,12) / 前 (u+4,v,3,12) / 左 (u+7,v,4,12) / 后 (u+11,v,3,12)，
     * 顶 (u+4,v-4,3,4) / 底 (u+7,v-4,3,4)。
     */
    private void addSlimArm(int part, double x0, double y0, double z0, double w, double h, double d,
                            int u, int v, boolean outer) {
        double x1 = x0 + w, y1 = y0 + h, z1 = z0 + d;
        // 前面 (z+)
        addFace(part, outer, u + 4, v + 4, 3, 12, 0, 0, 1,
                x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
        // 后面 (z-)
        addFace(part, outer, u + 11, v + 4, 3, 12, 0, 0, -1,
                x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0);
        // 右面 (x-)
        addFace(part, outer, u, v + 4, 4, 12, -1, 0, 0,
                x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0);
        // 左面 (x+)
        addFace(part, outer, u + 7, v + 4, 4, 12, 1, 0, 0,
                x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1);
        // 顶面 (y+)
        addFace(part, outer, u + 4, v, 3, 4, 0, 1, 0,
                x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
        // 底面 (y-)
        addFace(part, outer, u + 7, v, 3, 4, 0, -1, 0,
                x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
    }

    private void addFace(int part, boolean outer, int ux, int uy, int uw, int uh, double nx, double ny, double nz,
                         double ax, double ay, double az, double bx, double by, double bz,
                         double cx, double cy, double cz, double dx, double dy, double dz) {
        // 纹理区域整块透明就不画（外皮肤层大量镂空，省掉大量无意义绘制）
        if (!hasOpaque(ux, uy, uw, uh)) return;
        Face f = new Face();
        f.part = part;
        f.outer = outer;
        f.p[0] = ax; f.p[1] = ay; f.p[2] = az;
        f.p[3] = bx; f.p[4] = by; f.p[5] = bz;
        f.p[6] = cx; f.p[7] = cy; f.p[8] = cz;
        f.p[9] = dx; f.p[10] = dy; f.p[11] = dz;
        f.ux = ux; f.uy = uy; f.uw = uw; f.uh = uh;
        f.nx = nx; f.ny = ny; f.nz = nz;
        faces.add(f);
    }

    // ---------------------------------------------------------------- 绘制

    public void setYaw(double degrees) {
        this.yaw = degrees;
    }

    public double getYaw() {
        return yaw;
    }

    /** 俯仰角：正数俯视、负数仰视，夹在 ±60° 内，再大就整人翻过去了。 */
    public void setPitch(double degrees) {
        this.pitch = Math.max(-60.0, Math.min(60.0, degrees));
    }

    public double getPitch() {
        return pitch;
    }

    /** 开关走动动画：开启后双腿交替迈步、双臂反相摆动，关闭则回到待机站立。 */
    public void setWalking(boolean walking) {
        this.walking = walking;
    }

    public boolean isWalking() {
        return walking;
    }

    /**
     * 让头部注视某个方向：yawDeg 正数向右扭头，pitchDeg 正数低头、负数抬头。
     * 只在动画帧里平滑逼近，不会瞬移；传 NaN 解除注视。
     */
    public void setHeadAim(double yawDeg, double pitchDeg) {
        if (Double.isNaN(yawDeg) || Double.isNaN(pitchDeg)) {
            this.headAimYaw = Double.NaN;
            this.headAimPitch = Double.NaN;
            return;
        }
        this.headAimYaw = Math.max(-60.0, Math.min(60.0, yawDeg));
        // 对外语义：正数低头、负数抬头；实测 headDip 取正即为低头，直接照传
        this.headAimPitch = Math.max(-30.0, Math.min(30.0, pitchDeg));
    }

    /** 解除注视，回到原来的待机轻摆。 */
    public void clearHeadAim() {
        this.headAimYaw = Double.NaN;
        this.headAimPitch = Double.NaN;
    }

    /** 按当前角度整幅重绘（含待机动画与点击反馈的当前帧状态）。 */
    public void render() {
        GraphicsContext gc = getGraphicsContext2D();
        double W = getWidth(), H = getHeight();
        long t0 = System.nanoTime();
        gc.clearRect(0, 0, W, H);
        if (tex == null || faces.isEmpty()) {
            gc.setFill(Color.web("#00000022"));
            gc.fillText("选择皮肤后在这里预览", W / 2 - 56, H / 2);
            return;
        }

        // 取景：对齐美西螈 page 预设（fov 35 / zoom 0.96），模型尽量占满舞台；顶部留 12% 余量避免切头
        double scale = Math.min(H * 0.80 / 32.0, W * 0.76 / 22.0);
        // 基准点必须落在整像素上：这个渲染器是硬边逐像素光栅化，没有抗锯齿，
        // 基准点带 .5 小数会让整个模型的边永远停在两像素之间，截帧看着像糊了一层。
        double cx = Math.rint(W / 2.0 - W * 0.048);
        double cy = Math.rint(H * 0.92);

        // 待机动画：头部小角度左右看 + 手臂小幅前后摆
        double t = animTime;
        double walkPhase = walking ? t * 7.0 : 0.0;

        // ---- 点击互动：打招呼（挥手），只动右臂，左臂保持原来的待机/走路摆幅
        double actArmRight = 0.0;   // 右臂额外举起的角度（叠加在前后摆上）
        double actArmRoll = 0.0;    // 右臂左右扫的角度：挥手时靠它甩起来，否则正面看只是抬着不动
        double actArmLeft = 0.0;    // 左臂配合微张，避免"一条胳膊动另一条钉住"的僵感
        double actHeadRoll = 0.0;   // 挥手时头部朝举手方向微微歪（绕 Z）
        double actHeadYaw = 0.0;    // 动作期间头部的固定朝向（此期间不吃鼠标）
        double actHeadPitch = 0.0;
        double actWaistTwist = 0.0; // 腰部带动的左右扭转（绕 Y）：脚和腿不动，腰以上跟着扭
        double actWaistBend = 0.0;  // 腰部带动的极小侧弯（绕 Z）：只作用于腰以上，绝非"整个身体倾斜"
        if (action == 1) {
            double p = Math.min(1.0, actionTime / ACTION_WAVE_DUR);
            // 抬臂 → 挥手 → 放下：0.22 处抬满，0.80 之后开始收
            double up = Math.sin(Math.min(1.0, p / 0.22) * Math.PI / 2);
            // 抬到位后给一点过冲再回落，纯正弦到顶停住会显得机器人
            if (p > 0.22 && p < 0.40) {
                up = 1.0 + 0.06 * Math.sin((p - 0.22) / 0.18 * Math.PI);
            }
            if (p > 0.80) up *= Math.max(0.0, (1.0 - p) / 0.20);
            // 主抬起角度走"绕 Z 侧举"：只绕 X 往前抬，正面看只是手臂变短，看不出举手
            actArmRight = -10.0 * up;                          // 稍微向前一点，正对观众
            // 斜上举，手落在头侧上方；角度再大会伸出画布被裁掉
            actArmRoll = (-122.0 + Math.sin(actionTime * 12.0) * 16.0) * up;
            // 左臂反向张一点，身体重心才不像被钉在地上
            actArmLeft = (-7.0 + Math.sin(actionTime * 12.0 + 1.6) * 3.0) * up;
            // 歪头跟着甩手走：相位错开，看着像"头随手动"而不是各自播放
            actHeadRoll = (6.5 + Math.sin(actionTime * 12.0 - 0.9) * 2.5) * up;
            // 动作期间头部锁在固定姿态：略朝举手那侧、几乎平视，鼠标移动不再牵动它
            actHeadYaw = -5.0 * up;
            actHeadPitch = 1.5 * up;
            // 腰部扭动：频率跟甩手同源、相位滞后一点，看着像"身子跟着胳膊使劲"，
            // 而不是和手臂各自播放。侧弯刻意压到 1.6° 以内 —— 腿和脚始终垂直，
            // 弯只发生在腰以上，和已被否决的"整躯干 4.2° roll"不是一回事。
            actWaistTwist = (1.2 + Math.sin(actionTime * 12.0 - 0.55) * 4.4) * up;
            actWaistBend = (0.6 + Math.sin(actionTime * 12.0 - 1.15) * 1.3) * up;
        }

        double legSwing = walking ? Math.sin(walkPhase) * 24.0 : 0.0;
        // 待机不做整体上下浮动：这个渲染器没有抗锯齿，全身亚像素位移会让边缘一直抖，看着发糊。
        // 走路时的步点起伏保留（那是腿部动画的一部分，且幅度是按步态算的）。
        double dy = 0.0;
        if (walking) {
            // 走动时身体随步点起伏（双脚并拢相位最低、迈开时最高）
            dy += Math.abs(Math.sin(walkPhase)) * 0.55 - 0.30;
        }
        double headTurn;
        double headDip = 0.0;
        if (action == 1) {
            // 动作期间头部锁定固定姿态，鼠标移动不再牵动它。
            // 同时把平滑值同步成这个姿态，动作结束后回到跟随模式时不会突然甩头。
            headTurn = actHeadYaw;
            headDip = actHeadPitch;
            this.headYawNow = actHeadYaw;
            this.headPitchNow = actHeadPitch;
        } else if (Double.isNaN(this.headAimYaw)) {
            // 常态：待机时脑袋自己轻轻左右扫视
            headTurn = Math.sin(t * 0.85) * 5.0;
        } else {
            // 注视模式：逐帧向目标角靠近。系数 0.35 ≈ 60fps 下 50ms 就跟到位，
            // 既有滑动感又不会明显慢半拍（0.14 时肉眼能看出延迟）
            this.headYawNow += (this.headAimYaw - this.headYawNow) * 0.35;
            this.headPitchNow += (this.headAimPitch - this.headPitchNow) * 0.35;
            headTurn = this.headYawNow;
            headDip = this.headPitchNow;
        }
        // 走动时双臂与双腿反相大幅摆（右腿前伸则右臂后摆），待机则维持小幅晃动
        double armSwing = walking ? -Math.sin(walkPhase) * 20.0 : Math.sin(t * 1.25) * 5.5;

        // 地面柔和阴影（美西螈的圆形聚光底），先画在模型之下；俯仰越大越"远"，阴影随之收缩
        double pitchRad0 = Math.toRadians(pitch);
        drawGround(gc, cx, cy, 15.5 * scale * Math.max(0.30, Math.cos(pitchRad0)));

        double ry = Math.toRadians(yaw);
        double rx = Math.toRadians(pitch);
        double cosY = Math.cos(ry), sinY = Math.sin(ry);
        double cosX = Math.cos(rx), sinX = Math.sin(rx);

        // 部件摆动的三角函数每帧只算一次，避免在每个顶点上重复求值
        double headR = Math.toRadians(headTurn);
        double headCa = Math.cos(headR), headSa = Math.sin(headR);
        // 头部俯仰（注视鼠标时才有值）：绕 X 轴点头/抬头
        double headDR = Math.toRadians(headDip);
        double headDCa = Math.cos(headDR), headDSa = Math.sin(headDR);
        double armRR = Math.toRadians(armSwing + actArmRight);
        double armRCa = Math.cos(armRR), armRSa = Math.sin(armRR);
        double armLR = Math.toRadians(-armSwing + actArmLeft);
        double armLCa = Math.cos(armLR), armLSa = Math.sin(armLR);
        // 挥手时右臂再绕 Z 左右扫（只在举起阶段生效，其余时候三角值恒等于直臂）
        double armRollR = Math.toRadians(actArmRoll);
        double armRollCa = Math.cos(armRollR), armRollSa = Math.sin(armRollR);
        boolean armRollOn = Math.abs(actArmRoll) > 0.001;
        // 头部歪斜（绕 Z）：挥手时才有值，正角度让头往画面左（举手那侧）倒
        double headRollR = Math.toRadians(actHeadRoll);
        double headRollCa = Math.cos(headRollR), headRollSa = Math.sin(headRollR);
        boolean headRollOn = Math.abs(actHeadRoll) > 0.001;
        // 腰部扭动（上半身整体、绕腰线）：法线不跟着转 —— 幅度只有几度，
        // 光照角的变化不到 2% 亮度，且这里没有背面剔除，不影响哪些面可见。
        double waistTwistR = Math.toRadians(actWaistTwist);
        double waistTwistCa = Math.cos(waistTwistR), waistTwistSa = Math.sin(waistTwistR);
        double waistBendR = Math.toRadians(actWaistBend);
        boolean waistOn = Math.abs(actWaistTwist) > 0.001 || Math.abs(actWaistBend) > 0.001;
        double legRR = Math.toRadians(legSwing);
        double legRCa = Math.cos(legRR), legRSa = Math.sin(legRR);
        double legLR = Math.toRadians(-legSwing);
        double legLCa = Math.cos(legLR), legLSa = Math.sin(legLR);

        for (Face f : faces) {
            double[] pv = PIVOT[f.part];
            double zsum = 0;
            for (int i = 0; i < 4; i++) {
                double wx = f.p[i * 3], wy = f.p[i * 3 + 1], wz = f.p[i * 3 + 2];
                // 部件局部摆动（绕自身枢轴），再回位到模型空间
                double lx0 = wx - pv[0], ly0 = wy - pv[1], lz0 = wz - pv[2];
                if (f.part == 1) {                       // 头：先点头（绕 X），再转头（绕 Y），最后歪头（绕 Z）
                    // 绕 X 正角度会让头部前侧往下沉 → headDip 为正表示低头，为负表示抬头
                    double tyH = ly0 * headDCa - lz0 * headDSa;
                    double tzH = ly0 * headDSa + lz0 * headDCa;
                    ly0 = tyH; lz0 = tzH;
                    double tx = lx0 * headCa + lz0 * headSa;
                    double tz = -lx0 * headSa + lz0 * headCa;
                    lx0 = tx; lz0 = tz;
                    if (headRollOn) {                     // 挥手：头朝举手方向倒一下
                        double tx3 = lx0 * headRollCa - ly0 * headRollSa;
                        double ty3 = lx0 * headRollSa + ly0 * headRollCa;
                        lx0 = tx3; ly0 = ty3;
                    }
                } else if (f.part == 2) {                 // 右臂：绕 X 前后摆
                    double ty = ly0 * armRCa - lz0 * armRSa;
                    double tz = ly0 * armRSa + lz0 * armRCa;
                    ly0 = ty; lz0 = tz;
                    if (armRollOn) {                      // 挥手：再绕 Z 左右扫一遍
                        double tx2 = lx0 * armRollCa - ly0 * armRollSa;
                        double ty2 = lx0 * armRollSa + ly0 * armRollCa;
                        lx0 = tx2; ly0 = ty2;
                    }
                } else if (f.part == 3) {                 // 左臂：反相
                    double ty = ly0 * armLCa - lz0 * armLSa;
                    double tz = ly0 * armLSa + lz0 * armLCa;
                    ly0 = ty; lz0 = tz;
                } else if (f.part == 4) {                 // 右腿：绕腰部枢轴前后迈步
                    double ty = ly0 * legRCa - lz0 * legRSa;
                    double tz = ly0 * legRSa + lz0 * legRCa;
                    ly0 = ty; lz0 = tz;
                } else if (f.part == 5) {                 // 左腿：与右腿反相
                    double ty = ly0 * legLCa - lz0 * legLSa;
                    double tz = ly0 * legLSa + lz0 * legLCa;
                    ly0 = ty; lz0 = tz;
                }
                double wx2 = lx0 + pv[0];
                // 旋转中心放在腹部：整体俯仰绕腰转身，而不是绕脚底把人"放倒"
                double wy2 = ly0 + pv[1] + dy - ROT_PIVOT_Y;
                double wz2 = lz0 + pv[2];

                // 腰部带动上半身自然扭动：腿(4/5)完全不参与，脚底、腿脚位置保持不动。
                // 枢轴就是腰线（ROT_PIVOT_Y），该处高度为 0、扭幅也为 0（k=0），
                // 往上 8 个单位线性涨到满幅，所以躯干底面与腿顶面始终对齐、不会露缝；
                // 扭动集中在腰段以上，看起来是"腰在拧"，不是整个人歪着。
                if (waistOn && f.part <= 3) {
                    double k = Math.min(1.0, Math.max(0.0, wy2 / 8.0));
                    double tw = waistTwistR * k;
                    double twC = Math.cos(tw), twS = Math.sin(tw);
                    double nx0 = wx2 * twC + wz2 * twS;     // 绕 Y：左右拧腰
                    double nz0 = -wx2 * twS + wz2 * twC;
                    wx2 = nx0; wz2 = nz0;
                    double bd = waistBendR * k;
                    double bdC = Math.cos(bd), bdS = Math.sin(bd);
                    double bx0 = wx2 * bdC - wy2 * bdS;     // 绕 Z：几乎察觉不到的侧弯
                    double by0 = wx2 * bdS + wy2 * bdC;
                    wx2 = bx0; wy2 = by0;
                }

                // 整机绕 Y
                double rx1 = wx2 * cosY + wz2 * sinY;
                double rz1 = -wx2 * sinY + wz2 * cosY;
                // 再绕 X（俯视）
                double ry1 = wy2 * cosX - rz1 * sinX;
                double rz2 = wy2 * sinX + rz1 * cosX;

                f.sx[i] = cx + rx1 * scale;
                // 旋转后把腹部枢轴平移回原位，人物整体高度基准不变
                f.sy[i] = cy - (ry1 + ROT_PIVOT_Y) * scale;
                f.sz[i] = rz2;
                zsum += rz2;
            }
            f.depth = zsum / 4.0;
        }

        // 光照方向（左上前方，与美西螈 [-3,4,-2] 同侧）
        double lx = -0.42, ly = 0.72, lz = 0.55;

        // 逐像素深度缓冲光栅化：不再依赖排序，内外层/相邻部件的前后关系由 z 判定，
        // 头顶"开口"、肩部缺面、袖子浮空这类画家算法的错序问题一次性消除。
        int iw = (int) Math.round(W), ih = (int) Math.round(H);
        ensureBuf(iw, ih);
        Arrays.fill(buf, 0);
        Arrays.fill(zbuf, Double.NEGATIVE_INFINITY);

        for (Face f : faces) {
            double fnx = f.nx, fny = f.ny, fnz = f.nz;
            // 头部的法线必须和顶点一起转：漏了这步，头一转侧面/下巴就被背面剔除当成背面丢掉，
            // 屏幕上就露出空白。顺序与顶点一致：先绕 X 点头，再绕 Y 转头。
            if (f.part == 1 && (headDCa != 1.0 || headDSa != 0.0 || headCa != 1.0 || headSa != 0.0 || headRollOn)) {
                double nyA = fny * headDCa - fnz * headDSa;
                double nzA = fny * headDSa + fnz * headDCa;
                double nxA = fnx * headCa + nzA * headSa;
                fnz = -fnx * headSa + nzA * headCa;
                fnx = nxA; fny = nyA;
                if (headRollOn) {                     // 歪头同样要转法线，否则侧脸会缺面
                    double nxB = fnx * headRollCa - fny * headRollSa;
                    double nyB = fnx * headRollSa + fny * headRollCa;
                    fnx = nxB; fny = nyB;
                }
            }
            double nx1 = fnx * cosY + fnz * sinY;
            double nz1 = -fnx * sinY + fnz * cosY;
            // 不做法线背面剔除：头部跟着鼠标转、手臂摆动的角度都很大，
            // 用未旋转坐标系判正反面必然错判，转过去的面直接被丢成空洞。
            // 这里是逐像素 z-buffer 光栅化，背面自己会被前面的面挡住，直接全画最稳。
            double ny1 = fny * cosX - nz1 * sinX;
            double nz2 = fny * sinX + nz1 * cosX;
            double lambert = Math.max(0, nx1 * lx + ny1 * ly + nz2 * lz);
            double bright = 0.72 + 0.30 * lambert;
            rasterFace(f, bright);
        }

        outPw.setPixels(0, 0, bufW, bufH, PixelFormat.getIntArgbInstance(), buf, 0, bufW);
        gc.setImageSmoothing(false);
        gc.drawImage(outImg, 0, 0);
        perfTick(System.nanoTime() - t0);
    }

    /** -Djwh.skin.perf=true 时每 60 帧打一行：单帧耗时 + 真实帧率。 */
    private void perfTick(long ns) {
        if (!PERF) return;
        perfNanos += ns;
        perfFrames++;
        if (perfFrames == 60) {
            long now = System.nanoTime();
            long span = now - perfStart;
            if (perfStart != 0) {
                double avg = perfNanos / 60.0 / 1e6;
                double fps = span == 0 ? 0 : 60.0 * 1e9 / span;
                System.out.println("[skin-perf] render=" + String.format("%.2f", avg) + "ms  realFps="
                        + String.format("%.1f", fps) + "  frames=60 span="
                        + String.format("%.0f", span / 1e6) + "ms");
            }
            perfStart = now;
            perfFrames = 0;
            perfNanos = 0;
        }
    }

    private void ensureBuf(int w, int h) {
        if (buf != null && bufW == w && bufH == h) return;
        bufW = w;
        bufH = h;
        buf = new int[w * h];
        zbuf = new double[w * h];
        outImg = new WritableImage(w, h);
        outPw = outImg.getPixelWriter();
    }

    /**
     * 把一个面（正交投影下是平行四边形）光栅化进颜色/深度缓冲。
     * <p>屏幕点 p(s,t) = p0 + s·(p1-p0) + t·(p3-p0)，s∈[0,1] 对应纹理横向，t 对应纵向；
     * 正交投影下 (s,t) 与屏幕坐标是线性关系，可逐像素反解出纹理坐标与深度。
     */
    private void rasterFace(Face f, double bright) {
        // 用副本做外扩：绝不能改 f.sx/f.sy 本体，否则动画每帧都会在旧值上再推 0.6px，
        // 几十帧后面就被"吹"变形（脸部贴图错位、边角支棱就是这么来的）。
        double[] sx = {f.sx[0], f.sx[1], f.sx[2], f.sx[3]};
        double[] sy = {f.sy[0], f.sy[1], f.sy[2], f.sy[3]};
        double[] sz = f.sz;

        // 顶点在屏幕空间轻微外扩，避免相邻面共边处漏出 1 像素缝
        double mcx = (sx[0] + sx[1] + sx[2] + sx[3]) * 0.25;
        double mcy = (sy[0] + sy[1] + sy[2] + sy[3]) * 0.25;
        for (int i = 0; i < 4; i++) {
            double vx = sx[i] - mcx, vy = sy[i] - mcy;
            double len = Math.sqrt(vx * vx + vy * vy);
            if (len > 1e-6) {
                sx[i] += vx / len * 0.6;
                sy[i] += vy / len * 0.6;
            }
        }

        double e1x = sx[1] - sx[0], e1y = sy[1] - sy[0];
        double e2x = sx[3] - sx[0], e2y = sy[3] - sy[0];
        double det = e1x * e2y - e2x * e1y;
        if (Math.abs(det) < 1e-9) return;

        double z0 = sz[0], dz1 = sz[1] - z0, dz3 = sz[3] - z0;

        int minX = (int) Math.floor(Math.min(Math.min(sx[0], sx[1]), Math.min(sx[2], sx[3])));
        int maxX = (int) Math.ceil(Math.max(Math.max(sx[0], sx[1]), Math.max(sx[2], sx[3])));
        int minY = (int) Math.floor(Math.min(Math.min(sy[0], sy[1]), Math.min(sy[2], sy[3])));
        int maxY = (int) Math.ceil(Math.max(Math.max(sy[0], sy[1]), Math.max(sy[2], sy[3])));
        if (minX < 0) minX = 0;
        if (minY < 0) minY = 0;
        if (maxX > bufW - 1) maxX = bufW - 1;
        if (maxY > bufH - 1) maxY = bufH - 1;

        double ux = f.ux, uy = f.uy, uw = f.uw, uh = f.uh;

        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5 - sy[0];
            int rowBase = y * bufW;
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5 - sx[0];
                double s = (px * e2y - e2x * py) / det;
                if (s < 0 || s > 1) continue;
                double t = (e1x * py - px * e1y) / det;
                if (t < 0 || t > 1) continue;

                double zz = z0 + s * dz1 + t * dz3;
                int idx = rowBase + x;
                if (zz <= zbuf[idx]) continue;

                int tu = (int) (ux + s * uw);
                int tv = (int) (uy + t * uh);

                if (tu < 0) tu = 0; else if (tu > TEX - 1) tu = TEX - 1;
                if (tv < 0) tv = 0; else if (tv > TEX - 1) tv = TEX - 1;
                // 披风有自己的贴图，不能拿皮肤像素来补
                int[] srcPx = f.cape ? capePx : pixels;
                int argb = srcPx[tv * TEX + tu];
                if (((argb >>> 24) & 0xFF) < 128) {
                    // 本体层遇到没画的面（alpha=0）用邻近色补上，否则手臂/背部会露底；
                    // 外皮肤层/披风必须保留镂空，否则帽子、外套、披风边会变成一整块实心
                    if (f.outer || f.cape) continue;
                    argb = fillPx[tv * TEX + tu];
                    if (argb == 0) continue;
                }

                int r = (int) (((argb >> 16) & 0xFF) * bright);
                int g = (int) (((argb >> 8) & 0xFF) * bright);
                int b = (int) ((argb & 0xFF) * bright);
                if (r > 255) r = 255;
                if (g > 255) g = 255;
                if (b > 255) b = 255;
                buf[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
                zbuf[idx] = zz;
            }
        }
    }

    /** 脚下的柔和光斑（径向渐变，模仿美西螈深色舞台上的一圈聚光）。 */
    private void drawGround(GraphicsContext gc, double cx, double groundY, double r) {
        RadialGradient rg = new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#8fb6d8", 0.20)),
                new Stop(0.55, Color.web("#8fb6d8", 0.08)),
                new Stop(1, Color.web("#8fb6d8", 0.0)));
        gc.setFill(rg);
        gc.fillOval(cx - r, groundY - r * 0.15, r * 2, r * 0.30);
        // 紧贴脚下的接触阴影，让模型"站"在台上而不是飘着
        RadialGradient core = new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#000000", 0.34)),
                new Stop(0.6, Color.web("#000000", 0.12)),
                new Stop(1, Color.web("#000000", 0.0)));
        gc.setFill(core);
        gc.fillOval(cx - r * 0.62, groundY - r * 0.10, r * 1.24, r * 0.20);
        gc.setFill(Color.TRANSPARENT);
    }
}
