package com.maimai.bot.core;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一的「启动过程日志」。
 * <p>
 * 为什么要单独一个类：之前点「启动」没反应时，App 里一句可读的话都没有 ——
 * 日志只往 logcat 打，用户在应用里看不到；写文件的又只有「脚本 stdout」，
 * 而启动失败往往发生在脚本跑起来之前（建目录、写配置、起 Xvfb），
 * 于是日志天然是空的。
 * <p>
 * 这里把「我们主动做的每一步」和「子进程吐出来的每一行」写进同一个文件
 * （{@code files/napcat.log}），带毫秒级时间戳和分级前缀：
 * <pre>
 *   12:34:56.789 [步骤] 1/7 检查环境
 *   12:34:56.812 [完成] 1/7 检查环境（23ms）
 *   12:34:57.001 [QQ]  [preload] succeeded...
 *   12:34:57.500 [错误] 3/7 启动 QQ 超时
 * </pre>
 * 这样用户点一下就能看到「它干了什么、卡在哪一步」，和 AstrBot 那种
 * 一步一步的启动输出一致。
 */
public final class BootLog {
    private static final String TAG = "BootLog";
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** 总步骤数，由调用方在开始时告知，用于显示「3/7」。 */
    public static final int TOTAL_STEPS = 8;

    private final File file;

    public BootLog(File baseDir) {
        this.file = new File(baseDir, "napcat.log");
    }

    public File file() {
        return file;
    }

    /** 每次启动前清空，避免上一次的日志混进来。 */
    public void reset() {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        write("[系统] ─── 开始启动 NapCat ───");
    }

    /** 开始一个步骤，返回开始时间（给 {@link #done} 用）。 */
    public long step(int index, String title) {
        write("[步骤] " + index + "/" + TOTAL_STEPS + " " + title);
        return System.currentTimeMillis();
    }

    /** 步骤完成，带上耗时。 */
    public void done(int index, String title, long startedAt) {
        long ms = System.currentTimeMillis() - startedAt;
        write("[完成] " + index + "/" + TOTAL_STEPS + " " + title + "（" + ms + "ms）");
    }

    /** 普通信息。 */
    public void info(String msg) {
        write("[信息] " + msg);
    }

    /** 警告：能继续，但要注意。 */
    public void warn(String msg) {
        write("[警告] " + msg);
    }

    /** 错误：这一路走不通了。 */
    public void error(String msg) {
        write("[错误] " + msg);
    }

    /** 子进程（QQ / Xvfb / proot）吐出来的一行。 */
    public void child(String prefix, String line) {
        write("[" + prefix + "] " + line);
    }

    /** 结果汇总。 */
    public void result(boolean ok, String detail) {
        write(ok ? "[结果] 启动成功 " + detail : "[结果] 启动失败 " + detail);
    }

    private void write(String line) {
        String full = TS.format(new Date()) + " " + line;
        Log.i(TAG, full);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((full + "\n").getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }
}