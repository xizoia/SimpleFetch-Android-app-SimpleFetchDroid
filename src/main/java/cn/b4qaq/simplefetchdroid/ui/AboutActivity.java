package cn.b4qaq.simplefetchdroid.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import cn.b4qaq.simplefetchdroid.BuildConfig;
import cn.b4qaq.simplefetchdroid.R;

/**
 * 「关于」大页面：列出使用的开源项目，并提供「查看此应用开源代码」按钮跳转浏览器。
 */
public class AboutActivity extends Activity {

    /** 本应用开源仓库地址（用户指定）。 */
    private static final String APP_REPO =
            "https://github.com/xizoia/SimpleFetch-Android-app-SimpleFetchDroid";

    /** 使用的开源项目：{名称, 说明, 仓库地址}（仓库地址为空表示仅展示）。 */
    private static final String[][] PROJECTS = {
            {"SimpleFetch（B4QAQ）", "小米手环/手表互联协议，本应用桥接逻辑来源",
                    "https://github.com/B4QAQ/SimpleFetch-AstroBoxV2-Plugins"},
            {"AstroBox V2", "SimpleFetch 原 Rust 宿主，本应用移植来源", ""},
            {"Gadgetbridge", "V2 帧协议（SppPacketV2）参考实现",
                    "https://github.com/Freeyourgadget/Gadgetbridge"},
            {"Android Open Source Project", "Android 平台基础（AOSP）",
                    "https://source.android.com"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView ver = findViewById(R.id.about_version);
        ver.setText("版本 v" + BuildConfig.VERSION_NAME + "（" + BuildConfig.VERSION_CODE + "）");

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_open_source).setOnClickListener(v -> openUrl(APP_REPO));

        LinearLayout list = findViewById(R.id.project_list);
        for (String[] p : PROJECTS) {
            list.addView(buildProjectRow(p[0], p[1], p[2]));
        }
    }

    /** 构建一条可点击（有仓库地址时）的开源项目卡片。 */
    private View buildProjectRow(String name, String desc, final String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.card_bg);
        boolean clickable = url != null && !url.isEmpty();
        if (clickable) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> openUrl(url));
        }

        TextView t1 = new TextView(this);
        t1.setText(name);
        t1.setTextSize(14);
        t1.setTextColor(getColor(R.color.md_on_surface));
        row.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextSize(12);
        t2.setTextColor(getColor(R.color.md_on_surface_variant));
        t2.setPadding(0, dp(4), 0, 0);
        row.addView(t2);

        if (clickable) {
            TextView t3 = new TextView(this);
            t3.setText(url);
            t3.setTextSize(11);
            t3.setTextColor(getColor(R.color.md_primary));
            t3.setPadding(0, dp(6), 0, 0);
            row.addView(t3);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** 用系统浏览器打开外部链接；无浏览器时给出提示。 */
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器: " + url, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
