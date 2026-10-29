package com.vloc.motion.hidden;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块说明页：讲清模块做了什么、怎么在 LSPosed 里启用、有哪些已知限制，以及免责声明。
 *
 * <p>本页面只是普通的 App 页面（模块自身不被注入），不申请任何权限、不联网。
 */
public final class InfoActivity extends Activity {

    /**
     * 模块能力版本（0.4.0 起增加基站 / Wi-Fi 环境快照回放）。
     * AGP 默认不生成 BuildConfig，故此处硬编码；hidden/build.gradle 的 versionName 由仓库维护者维护。
     */
    private static final String MODULE_VERSION = "0.4.0";

    private static final String LOG_FILTER = "adb logcat -s LSPosed-Bridge | grep VLocHidden";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);
        setTitle(R.string.app_name);

        TextView versionView = findViewById(R.id.info_version);
        if (versionView != null) {
            versionView.setText(getString(R.string.info_version, MODULE_VERSION));
        }

        TextView logFilterView = findViewById(R.id.info_log_filter);
        if (logFilterView != null) {
            logFilterView.setText(LOG_FILTER);
        }

        Button copyButton = findViewById(R.id.btn_copy_log);
        if (copyButton != null) {
            copyButton.setOnClickListener(v -> copyLogFilter());
        }
    }

    /** 复制 adb 日志过滤命令，方便确认模块是否已在目标进程中注入。 */
    private void copyLogFilter() {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast(R.string.info_copy_failed);
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("VLocHidden log filter", LOG_FILTER));
            toast(R.string.info_copied);
        } catch (Throwable t) {
            // 部分 ROM 对剪贴板有额外限制，失败时提示手动输入即可
            toast(R.string.info_copy_failed);
        }
    }

    private void toast(int messageResId) {
        try {
            Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 说明页不因提示失败而中断
        }
    }
}
