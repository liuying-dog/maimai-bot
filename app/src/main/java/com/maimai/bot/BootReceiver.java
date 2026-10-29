package com.maimai.bot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机或应用更新后按用户设置决定是否自动拉起麦麦。
 * 注意 Android 10+ 对后台启动前台服务有限制，所以这里只是"尝试"，
 * 真正能不能起来取决于系统是否允许；失败也不会崩，用户手动开即可。
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        BotState st = BotState.get(context);
        if (!st.autoStart()) {
            return;
        }

        Intent svc = new Intent(context, BotService.class)
                .setAction(BotService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            st.appendLog("[开机] 自动启动被系统拒绝: " + e.getMessage());
        }
    }
}