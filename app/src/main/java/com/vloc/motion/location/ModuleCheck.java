package com.vloc.motion.location;

import android.content.Context;
import android.content.pm.PackageManager;

/** 模块链路自检：LSPosed 是否安装、本应用是否被它管理。 */
public final class ModuleCheck {

    private ModuleCheck() {
    }

    /** 常见 LSPosed 管理器包名。 */
    private static final String[] LSPOSED_PACKAGES = {
            "org.lsposed.manager", "io.github.lsposed.manager", "de.robv.android.xposed.installer"
    };

    public static String detectManager(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : LSPOSED_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                if ("de.robv.android.xposed.installer".equals(pkg)) {
                    return "Xposed Installer";
                }
                return "LSPosed";
            } catch (PackageManager.NameNotFoundException ignored) {
                // 继续找
            }
        }
        return null;
    }

    public static boolean isLsposedInstalled(Context context) {
        return detectManager(context) != null;
    }
}
