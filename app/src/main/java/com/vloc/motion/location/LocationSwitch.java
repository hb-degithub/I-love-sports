package com.vloc.motion.location;

import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 系统「定位」总开关的只读查询。
 *
 * <p>为什么需要它：开关关闭时 {@code addTestProvider()} 往往**仍然成功**
 * （真机实测：{@code dumpsys location} 里已有 {@code gps provider [mock] identity=…}），
 * 但 {@code user0 enabled=false}、{@code last mock location=null}，
 * 于是 {@code isProviderEnabled("gps")} 恒为 false。此时把失败归因到
 * {@code android:mock_location} appop 会给出完全错误的提示——真实原因只是"没开定位"。
 *
 * <p>本类只读、无副作用：查询失败时按"已启用"处理（{@code true}），宁可让后续真实
 * 失败暴露问题，也不误拦正常设备。
 */
public final class LocationSwitch {

    private static final String TAG = "VLocLocationSwitch";

    /** 定位总开关关闭时展示给用户的统一文案（{@link com.vloc.motion.service.LocationService}
     * 与 {@link TestProviderInjector} 共用，保证用户看到的措辞一致、可操作）。 */
    public static final String DISABLED_MSG =
            "系统定位开关已关闭，请先在快捷面板或「设置 → 位置信息」中打开「定位」后再开始";

    private LocationSwitch() {
    }

    /**
     * 系统定位总开关是否开启。
     *
     * <p>Android 9（API 28）起用 {@link LocationManager#isLocationEnabled()}；
     * 更低版本读 {@link Settings.Secure#LOCATION_MODE}（该 API 已废弃，仅覆盖 API 26–27）。
     * 两者都不需要定位权限。
     *
     * @return true=定位可用（或无法判断时保守放行）；false=总开关确实关闭
     */
    public static boolean isSystemLocationEnabled(Context context) {
        if (context == null) {
            return true;
        }
        try {
            LocationManager lm =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return true;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return lm.isLocationEnabled();
            }
            @SuppressWarnings("deprecation")
            int mode = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF);
            return mode != Settings.Secure.LOCATION_MODE_OFF;
        } catch (Throwable t) {
            // 查询失败（权限/厂商定制/系统异常）不能当成"已关闭"，否则会误拦
            Log.w(TAG, "query system location switch failed, assume enabled", t);
            return true;
        }
    }
}
