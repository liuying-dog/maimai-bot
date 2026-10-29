package com.maimai.bot.core;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 流式 tar 解包器，不依赖任何第三方库。
 * <p>
 * 为什么要自己写：Android 的 JDK 没有 tar，系统 toybox 的 tar 又不带 xz；
 * 而且用外部命令解包还得处理路径穿越、权限位、符号链接这些事，
 * 在 Java 里一次做对比拼命令行更可控。
 * <p>
 * 支持的条目类型：普通文件、目录、符号链接、硬链接、GNU 长名字(L/K)、
 * PAX 扩展头(x/g)。设备节点等直接跳过。
 * <p>
 * 一个踩过的坑写在这里警告后人：tar 头里的 size / mode / mtime 是
 * <b>八进制</b>字符串，不是十进制。早先这里用 Long.parseLong 直接解，
 * 八进制的 "0000042" 被当成十进制 42，每个文件都算错长度，数据流当场错位，
 * 最后在几百条之后撞上一个空块就"正常结束"了——表面不报错，实际只解了 5%。
 */
public final class TarExtractor {

    private static final int BLOCK = 512;

    private final File destRoot;
    private final ProgressListener listener;
    private long bytesOut;
    private int entryCount;
    private int skipped;

    public interface ProgressListener {
        void onEntry(String path, long bytesWritten, int entries);
    }

    public TarExtractor(File destRoot, ProgressListener listener) {
        this.destRoot = destRoot;
        this.listener = listener;
    }

    public int entryCount() {
        return entryCount;
    }

    /**
     * 解包。in 必须是已经解压好的 tar 流（gzip/xz 由调用方负责）。
     *
     * @return 解出的条目数
     */
    public int extract(InputStream in) throws IOException {
        byte[] header = new byte[BLOCK];
        String pendingName = null;
        String pendingLink = null;
        boolean sawAnyHeader = false;

        while (true) {
            int read = readFully(in, header);
            if (read == 0) {
                // 干净的流结束
                break;
            }
            if (read < BLOCK) {
                // 末尾不足一个块：正规 tar 不会这样，多半是被截断
                throw new EOFException("tar 在偏移处被截断，只读到 " + read + " 字节的头");
            }

            if (isZeroBlock(header)) {
                // 空块是归档结束标记。但真结束后应该还有第二个空块，
                // 这里直接退出即可，不必强求。
                break;
            }
            sawAnyHeader = true;

            // ---- 头字段（全部按 tar 规范偏移读取）
            String name = cString(header, 0, 100);
            long mode = octal(header, 100, 8, 0644);
            long size = octal(header, 124, 12, 0);
            long mtime = octal(header, 136, 12, 0);
            char type = (char) (header[156] & 0xFF);
            String linkName = cString(header, 157, 100);
            String prefix = cString(header, 345, 155);

            if (size < 0) {
                throw new IOException("tar 头里的 size 非法: " + size + "（条目 " + name + "）");
            }

            // ---- 扩展头：先吃掉数据，再回到循环顶部
            if (type == 'L') {                    // GNU 长文件名
                pendingName = readPayloadString(in, size);
                continue;
            }
            if (type == 'K') {                    // GNU 长链接名
                pendingLink = readPayloadString(in, size);
                continue;
            }
            if (type == 'x') {                    // PAX 扩展头（下一个条目）
                String body = readPayloadString(in, size);
                String p = paxLookup(body, "path");
                if (p != null) {
                    pendingName = p;
                }
                String l = paxLookup(body, "linkpath");
                if (l != null) {
                    pendingLink = l;
                }
                continue;
            }
            if (type == 'g') {                    // PAX 全局头，直接丢弃
                skipExactly(in, size);
                skipPadding(in, size);
                continue;
            }

            // ---- 拼出最终名字
            boolean usesPrefix = (pendingName == null);
            String finalName = pendingName != null ? pendingName : name;
            pendingName = null;
            // ustar 的 prefix 只在没有 PAX/GNU 长名时参与拼接
            if (usesPrefix && !prefix.isEmpty()) {
                finalName = prefix + "/" + finalName;
            }
            String finalLink = pendingLink != null ? pendingLink : linkName;
            pendingLink = null;

            File target = safeResolve(finalName);
            if (target == null) {
                // 路径试图跳出 destRoot，数据照读但不落盘
                skipExactly(in, size);
                skipPadding(in, size);
                skipped++;
                continue;
            }

            switch (type) {
                case '0':
                case '\0':
                case '7': {                    // 普通文件
                    writeFile(in, target, size);
                    if (mtime > 0) {
                        target.setLastModified(mtime * 1000L);
                    }
                    chmod(target, mode);
                    break;
                }
                case '5': {                        // 目录
                    target.mkdirs();
                    chmod(target, mode);
                    skipExactly(in, size);
                    break;
                }
                case '2': {                        // 符号链接
                    createSymlink(target, finalLink);
                    skipExactly(in, size);
                    break;
                }
                case '1': {                        // 硬链接：安卓不支持真硬链，退化成复制
                    File src = safeResolve(finalLink);
                    if (src != null && src.isFile()) {
                        copyFile(src, target);
                    }
                    skipExactly(in, size);
                    break;
                }
                default: {                         // 设备节点、FIFO 等，跳过
                    skipExactly(in, size);
                    skipped++;
                    break;
                }
            }

            // 数据区之后按 512 对齐补齐。所有分支都在这里统一 skip 一次，
            // 别在每个 case 里各自 skip，否则极易多跳或少跳导致全线错位。
            skipPadding(in, size);

            entryCount++;
            if (listener != null) {
                listener.onEntry(finalName, bytesOut, entryCount);
            }
        }

        if (!sawAnyHeader) {
            throw new IOException("tar 里一个条目都没有，文件可能为空或不是 tar");
        }
        return entryCount;
    }

    /** 跳过的条目数（越界路径、设备节点等），供上层判断安装是否可疑。 */
    public int skippedCount() {
        return skipped;
    }

    // ---------------------------------------------------------------- 写入

    private void writeFile(InputStream in, File target, long size) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        // 目标可能是已有的符号链接，先删掉，避免顺着他写到别处
        if (isSymlink(target)) {
            deleteQuietly(target);
        }

        try (OutputStream out = new FileOutputStream(target)) {
            long remaining = size;
            byte[] buf = new byte[64 * 1024];
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    throw new EOFException("文件 " + target.getName()
                            + " 数据被截断，还差 " + remaining + " 字节");
                }
                out.write(buf, 0, n);
                remaining -= n;
                bytesOut += n;
            }
        }
    }

    private void createSymlink(File target, String linkTo) {
        if (linkTo == null || linkTo.isEmpty()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        deleteQuietly(target);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "ln", "-s", linkTo, target.getAbsolutePath()
            });
            p.waitFor();
        } catch (Exception ignored) {
        }
        // ln 不可用时退化成复制实体文件，能跑但会多占空间
        if (!isSymlink(target) && !target.exists()) {
            String rel = linkTo.startsWith("/") ? linkTo.substring(1) : linkTo;
            File real = safeResolve(rel);
            if (real != null && real.isFile()) {
                copyFile(real, target);
            }
        }
    }

    // ---------------------------------------------------------------- 路径

    /**
     * 把 tar 里的相对路径落到 destRoot 下。
     * 任何试图跳出 destRoot 的路径（../ 越界、绝对路径）返回 null。
     */
    private File safeResolve(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.isEmpty() || ".".equals(s)) {
            return destRoot;
        }

        List<String> parts = new ArrayList<>();
        for (String seg : s.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (parts.isEmpty()) {
                    return null;   // 越界
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(seg);
        }
        if (parts.isEmpty()) {
            return destRoot;
        }

        File cur = destRoot;
        for (String p : parts) {
            cur = new File(cur, p);
        }
        // 真实路径必须还在 destRoot 下（防符号链接穿透）
        try {
            String root = destRoot.getCanonicalPath();
            File parent = cur.getParentFile();
            if (parent != null && parent.exists()) {
                String cp = parent.getCanonicalPath();
                if (!cp.equals(root) && !cp.startsWith(root + File.separator)) {
                    return null;
                }
            }
        } catch (IOException ignored) {
        }
        return cur;
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 读 NUL 结尾的字符串字段。
     * 注意：绝对不能 trim，文件名里的空格是有意义的；
     * 早先在这里 trim 过一次，导致前缀拼接错位。
     */
    private static String cString(byte[] b, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /**
     * 解析 tar 的数值字段。
     * <p>
     * 两种编码：
     * <ul>
     *   <li>八进制 ASCII（常规情况，高位可能有一个空格或 0 前缀）</li>
     *   <li>base-256（首位 bit7 置位，用于超过 8GB 的大文件）</li>
     * </ul>
     * 这是整个解包器最关键的一段：早先误用十进制解析，
     * 八进制 "0000042" 被读成 42 而不是 34，全线错位。
     */
    private static long octal(byte[] b, int off, int len, long def) {
        if (len <= 0) {
            return def;
        }
        // base-256 编码
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7F;
            for (int i = 1; i < len; i++) {
                v = (v << 8) | (b[off + i] & 0xFF);
            }
            return v;
        }
        long v = 0;
        boolean any = false;
        for (int i = 0; i < len; i++) {
            int c = b[off + i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (any) {
                    break;      // 数字后面遇到空格/NUL 就结束
                }
                continue;        // 前导空格跳过
            }
            if (c < '0' || c > '7') {
                // 混进了非八进制字符，当作损坏字段
                if (any) {
                    break;
                }
                return def;
            }
            v = v * 8 + (c - '0');
            any = true;
        }
        return any ? v : def;
    }

    /** PAX 记录格式： "<总长> <key>=<value>\n"，总长包含自身。 */
    private static String paxLookup(String body, String key) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < body.length()) {
            int sp = body.indexOf(' ', i);
            if (sp < 0) {
                break;
            }
            int recLen;
            try {
                recLen = Integer.parseInt(body.substring(i, sp).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (recLen <= 0 || i + recLen > body.length()) {
                break;
            }
            String rec = body.substring(sp + 1, i + recLen);
            int eq = rec.indexOf('=');
            if (eq > 0 && rec.substring(0, eq).equals(key)) {
                String v = rec.substring(eq + 1);
                // 值末尾通常带一个 \n，去掉
                if (v.endsWith("\n")) {
                    v = v.substring(0, v.length() - 1);
                }
                return v;
            }
            i += recLen;
        }
        return null;
    }

    private String readPayloadString(InputStream in, long size) throws IOException {
        if (size <= 0 || size > 4L * 1024 * 1024) {
            skipExactly(in, size);
            skipPadding(in, size);
            return "";
        }
        byte[] buf = new byte[(int) size];
        int off = 0;
        while (off < size) {
            int n = in.read(buf, off, (int) size - off);
            if (n < 0) {
                throw new EOFException("扩展头数据被截断");
            }
            off += n;
        }
        skipPadding(in, size);
        String s = new String(buf, 0, off, StandardCharsets.UTF_8);
        int nul = s.indexOf('\u0000');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    // ---------------------------------------------------------------- 底层 IO

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off;
            }
            off += n;
        }
        return off;
    }

    private static void skipExactly(InputStream in, long count) throws IOException {
        long left = count;
        byte[] buf = new byte[16 * 1024];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                throw new EOFException("tar 数据区被截断，还差 " + left + " 字节");
            }
            left -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        if (pad > 0) {
            skipExactly(in, pad);
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 杂项

    private static void chmod(File f, long mode) {
        int m = (int) (mode & 0777);
        if (m == 0) {
            return;
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "chmod", Integer.toOctalString(m), f.getAbsolutePath()
            });
            p.waitFor();
        } catch (Exception ignored) {
        }
    }

    static boolean isSymlink(File f) {
        try {
            File canon = f.getParentFile() == null
                    ? f
                    : new File(f.getParentFile().getCanonicalFile(), f.getName());
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f.isDirectory() && !isSymlink(f)) {
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        deleteQuietly(k);
                    }
                }
            }
            f.delete();
        } catch (Exception ignored) {
        }
    }

    private static void copyFile(File src, File dst) {
        File parent = dst.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
        }
    }
}