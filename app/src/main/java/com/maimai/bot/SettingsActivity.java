package com.maimai.bot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

/**
 * 设置页。
 * <p>
 * 从首页右上角的齿轮进来的。现在里面只有「关于」一项，
 * 但布局按「分组 + 卡片」的结构搭好了，以后加「日志级别」「清理缓存」
 * 这类开关时直接往对应分组里插行，不用重排。
 * <p>
 * 为什么单独开一个 Activity 而不是做成弹窗：设置以后会越加越多，
 * 弹窗塞不下；而且独立页面能用系统返回手势退出，符合安卓习惯。
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ImageButton back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
        }

        // 「关于」那一行：整行可点，不只是文字
        View rowAbout = findViewById(R.id.row_about);
        if (rowAbout != null) {
            rowAbout.setOnClickListener(v ->
                    startActivity(new Intent(this, AboutActivity.class)));
        }

        // 底部显示当前版本号，让用户一眼知道自己装的是哪一版
        TextView tvVersion = findViewById(R.id.tv_version);
        if (tvVersion != null) {
            tvVersion.setText("版本 " + versionName());
        }
    }

    /**
     * 读本 App 的 versionName。
     * 读不到就返回空串 —— 不为了一个显示用的版本号去抛异常。
     */
    private String versionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}