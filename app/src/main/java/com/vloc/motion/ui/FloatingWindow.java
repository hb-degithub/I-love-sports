package com.vloc.motion.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vloc.motion.core.RouteStore;

/**
 * 前台悬浮窗：在其它应用上层显示当前速度/里程/海拔，并提供暂停、停止按钮。
 *
 * <p>需要 {@code SYSTEM_ALERT_WINDOW} 特殊权限（用户手动授予）；窗口可拖动，位置会记住。
 */
public final class FloatingWindow {

    /** 悬浮窗上的按钮回调。 */
    public interface Actions {
        /** 点击状态行（打开主界面查看详情 / 启用步骤） */
        void onStatusClick();

        /** 开始 / 继续移动。 */
        void onStart();

        /** 暂停移动（定位保持在当前位置）。 */
        void onPause();

        /** 停止移动（定位保持在当前位置，不恢复真实定位）。 */
        void onHold();
    }

    private final Context context;
    private final Actions actions;
    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private final LinearLayout root;

    private final TextView titleView;
    private final TextView detailView;
    private final TextView statusView;
    private final TextView collapseView;
    private final LinearLayout buttonRow;
    private boolean collapsed;
    private final TextView startView;
    private final TextView pauseView;
    private final TextView holdView;
    private boolean shown;

    public FloatingWindow(Context context, Actions actions) {
        this.context = context.getApplicationContext();
        this.actions = actions;
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.params = buildParams();

        this.root = new LinearLayout(this.context);
        this.root.setOrientation(LinearLayout.VERTICAL);
        this.root.setPadding(dp(14), dp(9), dp(14), dp(9));
        this.root.setMinimumWidth(dp(260));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6202124);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0x66455A64);
        this.root.setBackground(background);
        this.root.setElevation(dp(8));

        LinearLayout headerRow = new LinearLayout(this.context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        this.titleView = new TextView(this.context);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        titleView.setIncludeFontPadding(false);
        titleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        this.collapseView = new TextView(this.context);
        collapseView.setTextColor(0xFFB0BEC5);
        collapseView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        collapseView.setPadding(dp(14), dp(6), dp(10), dp(6));
        collapseView.setText("▾");
        collapseView.setClickable(true);
        collapseView.setOnClickListener(v -> setCollapsed(!collapsed));
        titleView.setClickable(true);
        titleView.setPadding(0, dp(6), 0, dp(6));
        titleView.setOnClickListener(v -> setCollapsed(!collapsed));
        headerRow.addView(titleView);
        headerRow.addView(collapseView);

        this.statusView = new TextView(this.context);
        statusView.setTextColor(0xFF80CBC4);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        statusView.setIncludeFontPadding(false);
        statusView.setPadding(0, dp(2), 0, 0);
        statusView.setClickable(true);
        statusView.setOnClickListener(v -> actions.onStatusClick());

        this.detailView = new TextView(this.context);
        detailView.setTextColor(0xFFB0BEC5);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        detailView.setIncludeFontPadding(false);
        detailView.setPadding(0, dp(2), 0, dp(6));

        this.buttonRow = new LinearLayout(this.context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        this.startView = makeButton("开始");
        this.pauseView = makeButton("暂停");
        this.holdView = makeButton("停止移动");

        buttonRow.addView(startView);
        buttonRow.addView(pauseView);
        buttonRow.addView(holdView);
        root.addView(headerRow);
        root.addView(statusView);
        root.addView(detailView);
        root.addView(buttonRow);

        startView.setOnClickListener(v -> actions.onStart());
        pauseView.setOnClickListener(v -> actions.onPause());
        holdView.setOnClickListener(v -> actions.onHold());
        root.setOnTouchListener(new DragTouchListener());
    }

    /**
     * 更新显示内容。
     *
     * @param moving 是否正在移动（true 显示「暂停」，false 显示「开始/继续」）
     */
    public void update(String title, String detail, String status, boolean moving) {
        titleView.setText(title);
        detailView.setText(detail);
        statusView.setText(status == null ? "" : status);
        startView.setVisibility(moving ? View.GONE : View.VISIBLE);
        pauseView.setVisibility(moving ? View.VISIBLE : View.GONE);
        startView.setText("开始");
        pauseView.setText("暂停");
        applyCollapsedState();
    }

    /**
     * 更新折叠区里的「虚拟摄像头」控制条。
     *
     * @param enabled 虚拟摄像头是否开启
     * @param index   当前第几个视频（0 基）
     * @param count   视频总数
     * @param name    当前视频名（可为空）
     */
    

    /** 折叠只保留标题行（状态/详情/按钮都收起），展开恢复全部。 */
    public void setCollapsed(boolean value) {
        this.collapsed = value;
        RouteStore.saveOverlayCollapsed(context, value);
        applyCollapsedState();
    }

    private void applyCollapsedState() {
        int visibility = collapsed ? View.GONE : View.VISIBLE;
        detailView.setVisibility(visibility);
        statusView.setVisibility(visibility);
        buttonRow.setVisibility(visibility);
        collapseView.setText(collapsed ? "▸" : "▾");
    }

    /** 恢复上次的折叠状态。 */
    public void restoreCollapsed() {
        this.collapsed = RouteStore.loadOverlayCollapsed(context);
        applyCollapsedState();
    }

    public boolean isShown() {
        return shown;
    }

    /** 显示悬浮窗（需要悬浮窗权限）。 */
    public void show() {
        if (shown || windowManager == null) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        try {
            windowManager.addView(root, params);
            shown = true;
        } catch (RuntimeException ignored) {
            shown = false;
        }
    }

    /** 移除悬浮窗。 */
    public void hide() {
        if (!shown || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(root);
        } catch (RuntimeException ignored) {
            // 已被系统移除
        }
        shown = false;
    }

    private WindowManager.LayoutParams buildParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        int[] saved = RouteStore.loadOverlayPosition(context);
        p.x = saved == null ? dp(20) : saved[0];
        p.y = saved == null ? dp(160) : saved[1];
        return p;
    }

    private TextView makeButton(String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        view.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(12));
        view.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        view.setLayoutParams(lp);
        view.setClickable(true);
        return view;
    }

    private int dp(float value) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** 拖动整块悬浮窗（按钮区域由按钮自己消费点击）。 */
    private final class DragTouchListener implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.abs(dx) + Math.abs(dy) > dp(6)) {
                        dragging = true;
                    }
                    if (dragging) {
                        params.x = startX + (int) dx;
                        params.y = startY + (int) dy;
                        clampToScreen();
                        if (shown) {
                            try {
                                windowManager.updateViewLayout(root, params);
                            } catch (RuntimeException ignored) {
                                // 窗口可能已被系统回收
                            }
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        clampToScreen();
                        RouteStore.saveOverlayPosition(context, params.x, params.y);
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void clampToScreen() {
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int width = root.getWidth() > 0 ? root.getWidth() : dp(180);
            int height = root.getHeight() > 0 ? root.getHeight() : dp(80);
            int maxX = Math.max(0, metrics.widthPixels - width);
            int maxY = Math.max(0, metrics.heightPixels - height);
            params.x = Math.max(0, Math.min(params.x, maxX));
            params.y = Math.max(0, Math.min(params.y, maxY));
        }
    }
}
