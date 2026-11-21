package com.jwh.launcher.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 老 Forge / NeoForge 官方安装器补丁。
 *
 * <p>背景：官方安装器启动预热阶段会逐个解析 6 个域名并把 IP 打印到日志。
 * {@code DownloadUtils.getIps(host)} 在 {@code UnknownHostException} 时返回 {@code null}，
 * 而 {@code SimpleInstaller.main} 预热日志代码未判空直接 {@code .stream()}，触发 NPE，
 * 安装窗口在弹出之前就崩溃。国内网络下 {@code authserver.mojang.com} 常只返回 CNAME、
 * 末端无 A 记录，最容易踩中这条路径。
 *
 * <p>这里用 ASM 把 {@code getIps} 的失败分支改为返回 {@link java.util.Collections#emptyList()}，
 * 预热循环拿到空列表即可继续，其余方法与 jar 条目原样保留。补丁失败、安装器不含目标类、
 * 或补丁产物校验不过时，回退原始安装器 jar，不阻断安装流程。
 */
public final class InstallerPatcher {

    private static final String TARGET_CLASS = "net/minecraftforge/installer/DownloadUtils.class";

    private InstallerPatcher() {
    }

    /**
     * 返回应被拉起的安装器 jar：能补丁则返回补丁副本，否则返回原 jar。
     * 补丁副本与原 jar 同目录，文件名追加 {@code -jwhpatched}，并按原包修改时间幂等复用。
     */
    public static Path patch(Path installerJar) {
        try {
            if (!Files.isRegularFile(installerJar)) return installerJar;
            Map<String, byte[]> entries = readEntries(installerJar);
            if (!entries.containsKey(TARGET_CLASS)) return installerJar;

            Path patched = installerJar.resolveSibling(
                    installerJar.getFileName().toString().replaceAll("\\.jar$", "") + "-jwhpatched.jar");

            // 幂等复用：副本已存在、体积正常、不比原包旧，且仍是合法 zip。
            if (Files.isRegularFile(patched)
                    && Files.size(patched) > 100_000L
                    && Files.getLastModifiedTime(patched).toMillis()
                            >= Files.getLastModifiedTime(installerJar).toMillis()
                    && isValidZip(patched)) {
                return patched;
            }

            byte[] orig = entries.get(TARGET_CLASS);
            ClassReader cr = new ClassReader(orig);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if ("getIps".equals(name)
                            && "(Ljava/lang/String;)Ljava/util/List;".equals(desc)) {
                        return new GetIpsVisitor(mv);
                    }
                    return mv;
                }
            }, 0);
            entries.put(TARGET_CLASS, cw.toByteArray());

            writeEntries(patched, entries);
            if (!isValidZip(patched)) {
                Files.deleteIfExists(patched);
                return installerJar;
            }
            return patched;
        } catch (Exception e) {
            // 补丁是优化项，任何异常都回退原包，不能让补丁本身阻断安装。
            return installerJar;
        }
    }

    private static Map<String, byte[]> readEntries(Path jar) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                try (InputStream s = zf.getInputStream(e)) {
                    entries.put(e.getName(), s.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static void writeEntries(Path out, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zo.putNextEntry(new ZipEntry(e.getKey()));
                zo.write(e.getValue());
                zo.closeEntry();
            }
        }
    }

    private static boolean isValidZip(Path p) {
        try (ZipFile zf = new ZipFile(p.toFile())) {
            return zf.entries().hasMoreElements();
        } catch (Exception e) {
            return false;
        }
    }

    /** 重写 getIps：解析成功返回 IP 列表，失败返回空列表（不再返回 null）。 */
    private static final class GetIpsVisitor extends MethodVisitor {
        GetIpsVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        // 丢弃原方法体，全部在 visitCode 里重新生成。
        @Override public void visitInsn(int op) {}
        @Override public void visitIntInsn(int op, int v) {}
        @Override public void visitVarInsn(int op, int slot) {}
        @Override public void visitTypeInsn(int op, String t) {}
        @Override public void visitFieldInsn(int op, String o, String n, String d) {}
        @Override public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {}
        @Override public void visitInvokeDynamicInsn(String n, String d, Handle b, Object... a) {}
        @Override public void visitJumpInsn(int op, Label l) {}
        @Override public void visitLabel(Label l) {}
        @Override public void visitLdcInsn(Object v) {}
        @Override public void visitIincInsn(int slot, int inc) {}
        @Override public void visitTableSwitchInsn(int min, int max, Label d, Label... l) {}
        @Override public void visitLookupSwitchInsn(Label d, int[] k, Label... l) {}
        @Override public void visitMultiANewArrayInsn(String d, int n) {}
        @Override public void visitFrame(int t, int na, Object[] a, int nn, Object[] b) {}
        @Override public void visitTryCatchBlock(Label s, Label e, Label h, String t) {}
        @Override public void visitLocalVariable(String n, String d, String sig, Label s, Label e, int slot) {}
        @Override public void visitLineNumber(int l, Label s) {}
        @Override public void visitMaxs(int m, int n) {}
        @Override public void visitEnd() {}

        @Override
        public void visitCode() {
            mv.visitCode();
            Label tryStart = new Label(), tryEnd = new Label(), catchLabel = new Label();

            mv.visitLabel(tryStart);
            // InetAddress[] all = InetAddress.getAllByName(host);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/net/InetAddress", "getAllByName",
                    "(Ljava/lang/String;)[Ljava/net/InetAddress;", false);
            mv.visitVarInsn(Opcodes.ASTORE, 1);
            // List<String> out = new ArrayList<>();
            mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ASTORE, 2);
            // for (i = 0; i < all.length; i++) out.add(all[i].getHostAddress());
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.ARRAYLENGTH);
            mv.visitVarInsn(Opcodes.ISTORE, 3);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 4);
            Label loop = new Label(), endLoop = new Label();
            mv.visitLabel(loop);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitVarInsn(Opcodes.ILOAD, 3);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, endLoop);
            // 先压 receiver（List），再压参数（String）：invokeinterface 栈序要求。
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 4);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/InetAddress", "getHostAddress",
                    "()Ljava/lang/String;", false);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                    "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(Opcodes.POP);
            mv.visitIincInsn(4, 1);
            mv.visitJumpInsn(Opcodes.GOTO, loop);
            mv.visitLabel(endLoop);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(tryEnd);

            // catch (UnknownHostException e) { return Collections.emptyList(); }
            mv.visitLabel(catchLabel);
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "emptyList",
                    "()Ljava/util/List;", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitTryCatchBlock(tryStart, tryEnd, catchLabel, "java/net/UnknownHostException");
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
    }

    /** 安装器预热阶段会解析的 6 个域名（与官方 installer main 中的列表一致）。 */
    public static List<String> preflightHosts() {
        List<String> hosts = new ArrayList<>();
        hosts.add("files.minecraftforge.net");
        hosts.add("maven.minecraftforge.net");
        hosts.add("libraries.minecraft.net");
        hosts.add("launchermeta.mojang.com");
        hosts.add("piston-meta.mojang.com");
        hosts.add("authserver.mojang.com");
        return hosts;
    }
}
