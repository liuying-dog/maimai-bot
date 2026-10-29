package com.maimai.bot.core;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 跟随 maibot 的 JSONL 文件日志，把新增行转成人能读的文本。
 * <p>
 * 为什么不能只看 stdout：maibot 启动时自己打印过
 * 「日志系统已初始化：控制台=INFO，文件=DEBUG」。
 * 也就是说控制台上只出现 INFO 以上，而磁盘上的 jsonl 里还有
 * 大量 DEBUG —— 实测同一段时间内 debug 253 条、info 只有 34 条，
 * 差了七倍多，另外还有 error 的完整 traceback。
 * <p>
 * 这就是「AstrBot 干啥都有日志、我们只有几行」的根本原因：
 * 不是它日志多，是它把详细的那一路也接出来了。
 * <p>
 * 实现上只做三件事：
 * <ol>
 *   <li>在 logs 目录里挑最新的那个 app_*.log.jsonl</li>
 *   <li>按字节偏移增量读（RandomAccessFile + seek），只消费新内容</li>
 *   <li>把 {"logger_name":..,"event":..,"level":..,"timestamp":..} 解析成
 *       一行可读文本</li>
 * </ol>
 * 日志轮转（换新文件）时自动切过去。
 */
public final class LogTailer {

    private static final String TAG = "LogTailer";

    /** 日志文件名前缀，maibot 的命名是 app_YYYYMMDD_HHMMSS.log.jsonl。 */
    private static final String PREFIX = "app_";
    private static final String SUFFIX = ".log.jsonl";

    private final File logsDir;
    /**
     * 直接跟随的单个文件（纯文本模式）。
     * <p>
     * maibot 的日志是 jsonl，要解析；而 NapCat / QQ 的输出就是普通文本行，
     * 原样显示最有信息量（"[启动] 1) 启动 Xvfb..." 这种）。
     * 两种模式共用一个类，靠这个字段区分。
     */
    private final File rawFile;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private File currentFile;
    private RandomAccessFile raf;
    private long offset;
    private final StringBuilder partial = new StringBuilder();

    public interface Listener {
        /** 已经格式化好的一行。 */
        void onLogLine(String line);
    }

    public LogTailer(File instanceDir) {
        this.logsDir = new File(instanceDir, "logs");
        this.rawFile = null;
    }

    /**
     * 纯文本模式：直接跟随某一个文件，一行一行原样输出。
     * <p>
     * 用于 NapCat 的启动日志（files/napcat.log）—— 那里的每一行
     * 已经是人话，不需要也不应该再套一层 json 解析。
     */
    public static LogTailer forFile(File file) {
        return new LogTailer(file, true);
    }

    private LogTailer(File file, boolean raw) {
        this.logsDir = null;
        this.rawFile = file;
    }

    /**
     * 读一次。调用方自己控制频率（比如每 700ms 一次）。
     * 首次调用只把游标移到文件末尾，避免把历史几百行全灌进界面。
     */
    public void poll(boolean fromEnd, Listener listener) {
        try {
            File newest = newestLogFile();
            if (newest == null) {
                return;
            }

            // 日志轮转了：关掉旧的，从头开始读新文件
            if (currentFile == null || !newest.getAbsolutePath().equals(currentFile.getAbsolutePath())) {
                closeQuietly();
                currentFile = newest;
                raf = new RandomAccessFile(currentFile, "r");
                if (fromEnd) {
                    offset = raf.length();
                } else {
                    offset = 0;
                }
            }

            long len = raf.length();
            if (len < offset) {
                // 文件被截断（轮转或重启），重头再来
                offset = 0;
                partial.setLength(0);
            }
            if (len == offset) {
                return;
            }

            raf.seek(offset);
            byte[] buf = new byte[(int) Math.min(len - offset, 256 * 1024)];
            int read = raf.read(buf);
            if (read <= 0) {
                return;
            }
            offset += read;

            String chunk = new String(buf, 0, read, StandardCharsets.UTF_8);
            partial.append(chunk);

            // 按行切，最后一段可能是半行，留在 partial 里等下次
            int start = 0;
            int nl;
            while ((nl = partial.indexOf("\n", start)) >= 0) {
                String line = partial.substring(start, nl);
                start = nl + 1;
                if (!line.isEmpty()) {
                    // raw 模式（跟随普通文本文件）直接原样输出；
                    // json 模式才走 format() 解析 logger_name / level。
                    String pretty = rawFile != null ? line : format(line);
                    if (pretty != null && listener != null) {
                        listener.onLogLine(pretty);
                    }
                }
            }
            partial.delete(0, start);
            if (partial.length() > 64 * 1024) {
                // 防止某行异常长把内存吃满
                partial.setLength(0);
            }
        } catch (IOException e) {
            Log.w(TAG, "poll 失败", e);
        }
    }

    /** 直接跳到文件末尾（用户点「只看新的」时用）。 */
    public void seekToEnd() {
        try {
            if (raf == null) {
                File newest = newestLogFile();
                if (newest != null) {
                    currentFile = newest;
                    raf = new RandomAccessFile(currentFile, "r");
                }
            }
            if (raf != null) {
                offset = raf.length();
                partial.setLength(0);
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * 回读文件末尾的最后 maxLines 行，并把游标停在末尾。
     * <p>
     * 这是「进去就能看到东西」的关键：如果一进来就 seekToEnd，
     * maibot 待机时不再写新行，屏幕上就永远是空的，看起来像功能没做。
     * 这里从尾部往回找若干个换行符，把最近这段整块读出来。
     *
     * @return 实际输出的行数
     */
    public int tailRecent(int maxLines, Listener listener) {
        int shown = 0;
        try {
            File newest = newestLogFile();
            if (newest == null) {
                return 0;
            }
            if (currentFile == null || !newest.getAbsolutePath().equals(currentFile.getAbsolutePath())) {
                closeQuietly();
                currentFile = newest;
                raf = new RandomAccessFile(currentFile, "r");
            }

            long len = raf.length();
            if (len <= 0) {
                offset = 0;
                return 0;
            }

            // 从尾部按块往回扫，凑够 maxLines 个换行就停。
            //
            // 回看窗口原来写死 256KB。对于「真要看全」的场景不够 ——
            // 启动日志里 python 的栈、插件诊断一次就能刷出几十 KB，
            // 一超窗口，最前面那段就永远读不到了。
            // 现在放宽到 8MB，配合调用方传 Integer.MAX_VALUE 就是「读全文件」。
            long scanFrom = Math.max(0, len - 8L * 1024 * 1024);
            raf.seek(scanFrom);
            byte[] buf = new byte[(int) (len - scanFrom)];
            int read = 0;
            while (read < buf.length) {
                int n = raf.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            String text = new String(buf, 0, read, StandardCharsets.UTF_8);
            // 只取最后 maxLines 行。
            //
            // 注意 maxLines <= 0 或特别大时（调用方传 Integer.MAX_VALUE
            // 表示「要全量」），这里直接从头开始，不做丢弃 ——
            // 之前用 `lines <= maxLines` 做循环条件，maxLines 是
            // Integer.MAX_VALUE 时虽然不会溢出，但语义上也该走「全要」。
            int startAt = 0;
            if (maxLines > 0 && maxLines < Integer.MAX_VALUE) {
                int lines = 0;
                int idx = text.length();
                while (idx > 0 && lines <= maxLines) {
                    idx = text.lastIndexOf('\n', idx - 1);
                    if (idx < 0) {
                        idx = 0;
                        break;
                    }
                    lines++;
                    if (lines > maxLines) {
                        break;
                    }
                }
                startAt = idx == 0 ? 0 : idx + 1;
            }

            for (String line : text.substring(startAt).split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                // tailRecent 取的是文件里最后的字节块。
                // 如果这块正好切在某条 traceback 中间，开头会是一堆
                // 孤立的 Python 帧（"<frozen importlib...>"、"File \"...\""、
                // "~~~^~~~"），直接显示出来全是噪声。这里挡掉。
                String t = line.trim();
                if (rawFile != null) {
                    // 纯文本文件：原样输出，不做 jsonl 那套过滤
                    if (listener != null) {
                        listener.onLogLine(line);
                    }
                    shown++;
                    continue;
                }
                if (t.startsWith("<frozen")
                        || t.startsWith("File \"")
                        || t.startsWith("~~~")
                        || t.startsWith("^")
                        || t.startsWith("Traceback")
                        || t.startsWith("return cls(")
                        || !t.startsWith("{")) {
                    // 只接受长得像 jsonl 的行，其余一律丢弃
                    continue;
                }
                String pretty = format(line);
                if (pretty != null) {
                    if (listener != null) {
                        listener.onLogLine(pretty);
                    }
                    shown++;
                }
            }

            // 游标停在末尾，之后的 poll 只读新增
            offset = len;
            partial.setLength(0);
        } catch (IOException e) {
            Log.w(TAG, "tailRecent 失败", e);
        }
        return shown;
    }

    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (raf != null) {
                raf.close();
            }
        } catch (IOException ignored) {
        }
        raf = null;
        currentFile = null;
    }

    /** 在 logs 目录里挑文件名最大的那个（命名里带时间戳，字典序即时间序）。 */
    private File newestLogFile() {
        if (rawFile != null) {
            // 纯文本模式：文件可能刚被 reset() 删掉，不存在就先返回 null
            return rawFile.isFile() ? rawFile : null;
        }
        if (!logsDir.isDirectory()) {
            return null;
        }
        File[] files = logsDir.listFiles();
        if (files == null) {
            return null;
        }
        File best = null;
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
                continue;
            }
            if (best == null || name.compareTo(best.getName()) > 0) {
                best = f;
            }
        }
        return best;
    }

    // ------------------------------------------------------------- 解析

    /**
     * 把一行 JSONL 转成 "HH:mm:ss [模块] 内容" 的形式。
     * <p>
     * 不引 JSON 库：maibot 的 jsonl 字段固定，用字符串定位足够，
     * 而且省掉一个依赖。
     */
    private String format(String json) {
        String ts = str(json, "timestamp");
        String name = str(json, "logger_name");
        String event = str(json, "event");
        String level = str(json, "level");

        if (event == null) {
            // 不是预期格式，原样给出去，总比吞掉好
            return json;
        }

        StringBuilder sb = new StringBuilder();
        if (ts != null) {
            sb.append(ts).append(' ');
        } else {
            sb.append(timeFmt.format(new Date())).append(' ');
        }

        // 级别标记：只给 warn/error 加醒目的前缀，info/debug 保持安静
        if ("error".equalsIgnoreCase(level) || "critical".equalsIgnoreCase(level)) {
            sb.append("!! ");
        } else if ("warning".equalsIgnoreCase(level)) {
            sb.append("! ");
        }

        if (name != null) {
            sb.append('[').append(name).append("] ");
        }
        sb.append(event);

        String ex = str(json, "exception");
        if (ex != null && !ex.isEmpty()) {
            // traceback 动辄十几行 importlib 帧，全铺出来会把有用信息淹掉。
            // 只挑最后一行非空且不像是帧的行（通常就是 "XxxError: 原因"）。
            String[] lines = ex.split("\n");
            String root = null;
            for (int i = lines.length - 1; i >= 0; i--) {
                String t = lines[i].trim();
                if (t.isEmpty()) {
                    continue;
                }
                // 跳过 "File ..." / 缩进代码 / "<frozen ...>" 这类帧行
                if (t.startsWith("File \"")
                        || t.startsWith("<frozen")
                        || t.startsWith("~~~")
                        || t.startsWith("^")) {
                    continue;
                }
                root = t;
                break;
            }
            if (root != null && root.length() > 220) {
                root = root.substring(0, 220) + "…";
            }
            if (root != null) {
                sb.append("  ← ").append(root);
            }
        }
        return sb.toString();
    }

    /** 从扁平 JSON 里取字符串值，处理常见转义。取不到返回 null。 */
    private static String str(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != '"') {
            return null;
        }
        p++;
        StringBuilder out = new StringBuilder();
        while (p < json.length()) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(p + 1);
                switch (n) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'r':
                        break;
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case 'u':
                        if (p + 5 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(
                                        json.substring(p + 2, p + 6), 16));
                                p += 4;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    default:
                        out.append(n);
                        break;
                }
                p += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
            p++;
        }
        return out.toString();
    }
}