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
 * 虚拟摄像头专用悬浮窗。
 *
 * <p>独立于定位悬浮窗：只要虚拟摄像头处于启用状态，就在屏幕上常驻一个小胶囊，
 * 显示「📹 虚拟摄像头 开 · i/n 名称」，点一下切下一个视频、长按开/关，右上角 ✕ 只收起悬浮窗
 * （摄像头保持启用，下次启用或从主界面重开会再出现）。
 *
 * <p>需要 {@code SYSTEM_ALERT_WINDOW}；窗口可拖动，位置单独记忆（不与定位悬浮窗共用）。
 */
public final class CameraOverlay {

    /** 悬浮窗上的交互回调。 */
    public interface Actions {
        /** 点一下：切换到下一个视频。 */
        void onSwitchClip();

        /** 长按：开 / 关虚拟摄像头。 */
        void onToggleCamera();

        /** 点 ✕：只隐藏悬浮窗（摄像头不变）。 */
        void onDismissOverlay();

        /** 播放 / 暂停虚拟摄像头视频。 */
        void onPlayPause();

        /** 上一个 / 下一个视频。 */
        void onPrevClip();

        void onNextClip();
    }

    private final Context context;
    private final Actions actions;
    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private final LinearLayout root;
    private final TextView labelView;
    private final TextView playView;
    private final TextView statusView;
    private final TextView collapseView;
    private final android.widget.ProgressBar progressBar;
    private boolean playing;
    private boolean collapsed = true;

    private boolean shown;

    public CameraOverlay(Context context, Actions actions) {
        this.context = context.getApplicationContext();
        this.actions = actions;
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.params = buildParams();

        this.root = new LinearLayout(this.context);
        this.root.setOrientation(LinearLayout.VERTICAL);
        this.root.setGravity(Gravity.CENTER_VERTICAL);
        this.root.setPadding(dp(12), dp(8), dp(8), dp(8));

        LinearLayout row = new LinearLayout(this.context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6202124);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0x66FFB74D);
        this.root.setBackground(background);
        this.root.setElevation(dp(8));

        this.labelView = new TextView(this.context);
        labelView.setTextColor(0xFFFFD180);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        labelView.setIncludeFontPadding(false);
        labelView.setText("📹 虚拟摄像头");
        labelView.setClickable(true);
        labelView.setOnClickListener(v -> actions.onSwitchClip());
        labelView.setOnLongClickListener(v -> {
            actions.onToggleCamera();
            return true;
        });
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.rightMargin = dp(6);
        row.addView(labelView, labelParams);

        this.playView = new TextView(this.context);
        playView.setTextColor(0xFFFFD180);
        playView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        playView.setIncludeFontPadding(false);
        playView.setPadding(dp(8), dp(4), dp(8), dp(4));
        playView.setClickable(true);
        playView.setText("暂停");
        playView.setOnClickListener(v -> actions.onPlayPause());
        row.addView(playView);

        TextView prevView = new TextView(this.context);
        prevView.setTextColor(0xFFFFD180);
        prevView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        prevView.setPadding(dp(6), dp(4), dp(6), dp(4));
        prevView.setClickable(true);
        prevView.setText("◀");
        prevView.setOnClickListener(v -> actions.onPrevClip());
        row.addView(prevView);

        this.collapseView = new TextView(this.context);
        collapseView.setTextColor(0xFFB0BEC5);
        collapseView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        collapseView.setIncludeFontPadding(false);
        collapseView.setPadding(dp(6), dp(4), dp(2), dp(4));
        collapseView.setClickable(true);
        collapseView.setText("▸");
        collapseView.setOnClickListener(v -> setCollapsed(!collapsed));
        row.addView(collapseView);

        TextView closeView = new TextView(this.context);
        closeView.setText("✕");
        closeView.setTextColor(0xFFB0BEC5);
        closeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        closeView.setIncludeFontPadding(false);
        closeView.setPadding(dp(8), dp(4), dp(6), dp(4));
        closeView.setClickable(true);
        TextView nextView = new TextView(this.context);
        nextView.setTextColor(0xFFFFD180);
        nextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        nextView.setPadding(dp(6), dp(4), dp(6), dp(4));
        nextView.setClickable(true);
        nextView.setText("▶");
        nextView.setOnClickListener(v -> actions.onNextClip());
        row.addView(nextView);

        closeView.setOnClickListener(v -> actions.onDismissOverlay());
        row.addView(closeView);

        this.statusView = new TextView(this.context);
        statusView.setTextColor(0xFFB0BEC5);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        statusView.setIncludeFontPadding(false);
        statusView.setPadding(0, dp(2), dp(6), 0);
        this.progressBar = new android.widget.ProgressBar(this.context, null,
                android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        barParams.topMargin = dp(3);

        statusView.setText("接管：检测中…");
        statusView.setVisibility(View.GONE);   // 默认折叠：一行胶囊 + 细进度条

        root.addView(row);
        root.addView(progressBar, barParams);
        root.addView(statusView);

        root.setOnTouchListener(new DragTouchListener());
    }

    /**
     * 刷新悬浮窗文案。
     *
     * @param label 例如 {@code 开 · 1/2 视频A}
     */
    /**
     * 刷新悬浮窗文案。
     *
     * @param label  例如 {@code 开 · 1/2 视频A}
     * @param status 接管状态，例如 {@code 接管：运行中}
     * @param playing 视频是否在播放（决定按钮显示"暂停"还是"播放"）
     */
    public void update(String label, String status, boolean playing) {
        update(label, status, playing, 0L, 0L);
    }

    /**
     * 刷新悬浮窗。
     *
     * @param label 例如 {@code 开 · 1/3 视频A}
     * @param status 接管状态（折叠时隐藏）
     * @param playing 是否播放中
     * @param posMs 当前播放位置
     * @param durMs 视频总时长
     */
    public void update(String label, String status, boolean playing, long posMs, long durMs) {
        this.playing = playing;
        if (labelView != null) {
            labelView.setText("📹 虚拟摄像头 " + (label == null ? "" : label));
        }
        if (playView != null) {
            playView.setText(playing ? "暂停" : "播放");
            playView.setAlpha(playing ? 1f : 0.75f);
        }
        String progress = formatProgress(posMs, durMs);
        if (progressBar != null) {
            if (durMs > 0) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress((int) Math.min(1000L, Math.max(0L, posMs * 1000L / durMs)));
            } else {
                progressBar.setVisibility(View.GONE);
            }
        }
        if (statusView != null) {
            String tail = status == null ? "" : status;
            statusView.setText(progress.isEmpty() ? tail : ("⏱ " + progress
                    + (tail.isEmpty() ? "" : " · " + tail)));
        }
    }

    private static String formatProgress(long posMs, long durMs) {
        if (durMs <= 0) {
            return "";
        }
        return formatTime(posMs) + "/" + formatTime(durMs);
    }

    private static String formatTime(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** 展开 / 折叠：折叠时只保留一行胶囊，展开时显示接管状态。 */
    public void setCollapsed(boolean value) {
        this.collapsed = value;
        if (statusView != null) {
            statusView.setVisibility(value ? View.GONE : View.VISIBLE);
        }
        if (collapseView != null) {
            collapseView.setText(value ? "▸" : "▾");
        }
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** 兼容旧调用：只更新文案。 */
    public void update(String label) {
        update(label, null, playing);
    }

    public boolean isShown() {
        return shown;
    }

    /** 显示悬浮窗；没有悬浮窗权限时直接返回 false。 */
    public boolean show() {
        if (shown || windowManager == null) {
            return shown;
        }
        if (!Settings.canDrawOverlays(context)) {
            return false;
        }
        try {
            windowManager.addView(root, params);
            shown = true;
        } catch (RuntimeException ignored) {
            shown = false;
        }
        return shown;
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
        int[] saved = RouteStore.loadCameraOverlayPosition(context);
        p.x = saved == null ? dp(20) : saved[0];
        p.y = saved == null ? dp(260) : saved[1];
        return p;
    }

    private int dp(float value) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** 拖动整块胶囊（点击由子 View 自己消费）。 */
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
                        try {
                            windowManager.updateViewLayout(root, params);
                        } catch (RuntimeException ignored) {
                            // 窗口已移除
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        RouteStore.saveCameraOverlayPosition(context, params.x, params.y);
                        dragging = false;
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
