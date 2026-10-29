package com.vloc.motion.hidden;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 虚拟摄像头（模块侧）：在目标 App 进程内，用主 App 相册里选中的视频替代相机画面。
 *
 * <p>数据来自主 App 的 ContentProvider：
 * <ul>
 *   <li>{@code content://com.vloc.motion.env/camera}：开关 / 当前视频索引 / 视频列表；</li>
 *   <li>{@code content://com.vloc.motion.env/camera_video?index=N}：视频文件 FD。</li>
 * </ul>
 *
 * <p>接管方式（不改系统、不动 HAL）：
 * <ol>
 *   <li>App 创建相机 Session 时，把它的输出 Surface 列表换成我们自己的临时 Surface（真实相机照常工作、
 *       但画面只写进临时缓冲，不干扰后续）；</li>
 *   <li>同时用 MediaCodec 把视频解码进 App 自己的预览 Surface（SurfaceTexture / SurfaceView 都能接收）；</li>
 *   <li>App 拍照时，把当前视频帧编码成 JPEG / YUV 写进 App 的 ImageReader。</li>
 * </ol>
 *
 * <p>任何一步失败都回落到真实相机：所有 hook 独立 try/catch，异常只记日志。
 */
final class CameraReplay {

    private static final String TAG = "[VLocHidden] ";
    private static final String CAMERA_URI_STRING = "content://com.vloc.motion.env/camera";
    private static final String VIDEO_URI_STRING = "content://com.vloc.motion.env/camera_video";
    private static final String CAMERA_URI_STRING_FOR_CALL = "content://com.vloc.motion.env/camera";
    private static final long CONFIG_CACHE_MS = 1000L;

    // ---- 配置缓存（1s） ----
    private static volatile long sConfigAt;
    private static volatile boolean sCachedEnabled;
    private static volatile boolean sCachedPlaying = true;
    private static volatile boolean sCachedImuUpright = true;
    private static volatile int sCachedIndex;
    private static volatile int sCachedCount;
    private static volatile boolean sLoggedActive;
    private static volatile boolean sLoggedInactive;
    private static volatile int sHookCount;
    private static volatile boolean sLoggedHookFail;

    // ---- 当前会话 ----
    /** 会话用的临时输出 Surface（真实相机写这里，画面被丢弃）。 */
    private static volatile Surface sSinkSurface;
    /** App 原本的相机输出 Surface（请求目标要改写为 sink）。 */
    private static final List<Surface> sAppSurfaceSet = new ArrayList<>();
    private static volatile boolean sLoggedTargetRewrite;
    /** 恢复真实会话期间置位，避免 hook 再次拦截（防止递归）。 */
    private static final ThreadLocal<Boolean> sBypass = new ThreadLocal<>();
    private static volatile Object sDeviceRef;
    private static volatile List<Surface> sOriginalSurfaces = new ArrayList<>();
    private static volatile CameraCaptureSession.StateCallback sOriginalCallback;
    private static volatile Handler sOriginalHandler;

    private static volatile VideoSurfaceRenderer sRenderer;
    /** 持续给 App 分析用 ImageReader 喂视频帧的线程。 */
    private static volatile Thread sAnalysisThread;
    private static volatile boolean sAnalysisRunning;
    /** SurfaceTexture → 它被 App 设置的 buffer 尺寸；Surface → 它的 SurfaceTexture。 */
    private static final java.util.Map<Object, int[]> sTextureSizes =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<Surface, android.graphics.SurfaceTexture> sSurfaceTextures =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static volatile MediaCodec sDecoder;
    private static volatile Thread sDecodeThread;
    private static volatile boolean sDecoding;
    private static volatile Surface sLastAppSurface;

    // ---- ImageReader 注册表（Surface → format），用于拍照时写帧 ----
    private static final Object LOCK = new Object();
    private static final List<ReaderRef> sReaders = new ArrayList<>();
    private static final List<Surface> sAppSurfaces = new ArrayList<>();

    // ---- 拍照单帧 ----
    private static volatile byte[] sLastJpeg;
    private static volatile byte[] sLastNv21;
    private static volatile int sLastFrameWidth;
    private static volatile int sLastFrameHeight;

    private CameraReplay() {
    }

    // ================================================================== 安装

    static int install(ClassLoader classLoader) {
        int hooked = 0;
        // 1) CameraManager#openCamera：记录 App 打开相机（真实相机仍然打开，保证 App 流程不变）
        try {
            Class<?> manager = XposedHelpers.findClass("android.hardware.camera2.CameraManager", classLoader);
            int n = XposedBridge.hookAllMethods(manager, "openCamera", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (isActive()) {
                            log("camera open requested: " + param.args[0]);
                            reportEvent(sHookCount, "camera2 open id=" + param.args[0]);
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }).size();
            if (n > 0) {
                hooked++;
                log("hooked CameraManager#openCamera (" + n + " overload(s))");
            }
        } catch (Throwable t) {
            logHookFailOnce("CameraManager#openCamera", t);
        }

        // 2) ImageReader.newInstance：记住 App 的 ImageReader 与格式（拍照写帧需要）
        try {
            Class<?> reader = XposedHelpers.findClass("android.media.ImageReader", classLoader);
            int n = XposedBridge.hookAllMethods(reader, "newInstance", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.getResult() instanceof ImageReader)) {
                            return;
                        }
                        int width = (Integer) param.args[0];
                        int height = (Integer) param.args[1];
                        int format = (Integer) param.args[2];
                        ImageReader instance = (ImageReader) param.getResult();
                        synchronized (LOCK) {
                            sReaders.add(new ReaderRef(instance, format, width, height));
                            if (sReaders.size() > 8) {
                                sReaders.remove(0);
                            }
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }).size();
            if (n > 0) {
                hooked++;
                log("hooked ImageReader#newInstance (" + n + " overload(s))");
            }
        } catch (Throwable t) {
            logHookFailOnce("ImageReader#newInstance", t);
        }

        // 3) CameraDeviceImpl 的各类"创建会话"方法：换掉输出 Surface 并接管画面
        final String[] sessionMethods = {
                "createCaptureSession",
                "createCaptureSessionByOutputConfigurations",
                "createReprocessableCaptureSession",
                "createReprocessableCaptureSessionByConfigurations",
                "createConstrainedHighSpeedCaptureSession"
        };
        try {
            Class<?> deviceImpl = XposedHelpers.findClass(
                    "android.hardware.camera2.impl.CameraDeviceImpl", classLoader);
            // 动态补充：所有名字里带 CaptureSession 的 create* 方法都挂上（兼容各版本改名）
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (String method : sessionMethods) {
                names.add(method);
            }
            for (java.lang.reflect.Method method : deviceImpl.getDeclaredMethods()) {
                String name = method.getName();
                if (name.startsWith("create") && name.toLowerCase().contains("capturesession")) {
                    names.add(name);
                }
            }
            for (String method : names) {
                try {
                    int n = XposedBridge.hookAllMethods(deviceImpl, method, SESSION_HOOK).size();
                    if (n > 0) {
                        hooked++;
                        log("hooked CameraDeviceImpl#" + method + " (" + n + " overload(s))");
                    }
                } catch (Throwable ignored) {
                    // 该方法在本版本不存在：跳过
                }
            }
        } catch (Throwable t) {
            logHookFailOnce("CameraDeviceImpl session methods", t);
        }

        // 3.1) Camera1（android.hardware.Camera）：预览接管 + 拍照回调注入
        hooked += hookCamera1(classLoader);

        // 4) CameraCaptureSessionImpl#capture：App 拍照时把当前视频帧写进它的 ImageReader
        try {
            Class<?> sessionImpl = XposedHelpers.findClass(
                    "android.hardware.camera2.impl.CameraCaptureSessionImpl", classLoader);
            int n = XposedBridge.hookAllMethods(sessionImpl, "capture", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!isActive()) {
                            return;
                        }
                        final Context context = StepReplay.currentApplication();
                        if (context == null) {
                            return;
                        }
                        final int index = sCachedIndex;
                        final Thread worker = new Thread(() -> writeFrameToReaders(context, index),
                                "VLocHidden-camera-photo");
                        worker.setDaemon(true);
                        worker.start();
                    } catch (Throwable t) {
                        log("capture hook failed: " + t);
                    }
                }
            }).size();
            if (n > 0) {
                hooked++;
                log("hooked CameraCaptureSessionImpl#capture (" + n + " overload(s))");
            }
        } catch (Throwable t) {
            logHookFailOnce("CameraCaptureSessionImpl#capture", t);
        }

        // 5) 相机设备关闭：停止视频渲染，避免泄漏
        try {
            Class<?> deviceImpl = XposedHelpers.findClass(
                    "android.hardware.camera2.impl.CameraDeviceImpl", classLoader);
            int n = XposedBridge.hookAllMethods(deviceImpl, "close", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        stopRendering();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }).size();
            if (n > 0) {
                hooked++;
                log("hooked CameraDeviceImpl#close (" + n + " overload(s))");
            }
        } catch (Throwable t) {
            logHookFailOnce("CameraDeviceImpl#close", t);
        }

        // 5) CaptureRequest.Builder#addTarget：App 的请求目标也要换成临时 Surface，
        //    否则相机会因"请求引用了会话之外的 Surface"直接拒绝（这是接管失败的常见原因）
        try {
            Class<?> builder = XposedHelpers.findClass(
                    "android.hardware.camera2.CaptureRequest$Builder", classLoader);
            int n = XposedBridge.hookAllMethods(builder, "addTarget", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!isActive() || param.args.length != 1
                                || !(param.args[0] instanceof Surface)) {
                            return;
                        }
                        final Surface surface = (Surface) param.args[0];
                        final Surface sink = sSinkSurface;
                        if (sink == null) {
                            return;
                        }
                        synchronized (LOCK) {
                            if (!sAppSurfaceSet.contains(surface)) {
                                return;
                            }
                        }
                        param.args[0] = sink;
                        if (!sLoggedTargetRewrite) {
                            sLoggedTargetRewrite = true;
                            log("capture request target rewritten to sink surface");
                        }
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }).size();
            if (n > 0) {
                hooked++;
                log("hooked CaptureRequest$Builder#addTarget (" + n + " overload(s))");
            }
        } catch (Throwable t) {
            logHookFailOnce("CaptureRequest$Builder#addTarget", t);
        }

        sHookCount = hooked;
        // 采集预览 Surface 的尺寸（EGL 渲染做等比适配要用）
        try {
            XposedBridge.hookAllMethods(android.graphics.SurfaceTexture.class,
                    "setDefaultBufferSize", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length == 2) {
                                    sTextureSizes.put(param.thisObject, new int[]{
                                            (Integer) param.args[0], (Integer) param.args[1]});
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
            XposedHelpers.findAndHookConstructor(android.view.Surface.class,
                    android.graphics.SurfaceTexture.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.thisObject instanceof Surface
                                        && param.args[0] instanceof android.graphics.SurfaceTexture) {
                                    sSurfaceTextures.put((Surface) param.thisObject,
                                            (android.graphics.SurfaceTexture) param.args[0]);
                                }
                            } catch (Throwable ignored) {
                                // ignore
                            }
                        }
                    });
            hooked++;
            log("hooked SurfaceTexture#setDefaultBufferSize / Surface(SurfaceTexture)");
        } catch (Throwable t) {
            logHookFailOnce("surface size hooks", t);
        }

        log("camera hooks=" + hooked + " (camera2+camera1)");
        report(hooked, false, false, null);
        return hooked;
    }

    // ================================================================== 配置读取

    /** 1s 缓存地读取主 App 的虚拟摄像头配置。 */
    static boolean isActive() {
        final long now = System.currentTimeMillis();
        if (now - sConfigAt < CONFIG_CACHE_MS) {
            return sCachedEnabled && sCachedCount > 0;
        }
        sConfigAt = now;
        try {
            final Context context = StepReplay.currentApplication();
            if (context == null) {
                sCachedEnabled = false;
                reportInactive("context 未就绪（进程刚启动，稍后再试）");
                return false;
            }
            final Uri uri = Uri.parse(CAMERA_URI_STRING);
            try (android.database.Cursor cursor = context.getContentResolver()
                    .query(uri, new String[]{"json"}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) {
                    sCachedEnabled = false;
                    reportInactive("Provider 没有摄像头配置（主 App 未运行？）");
                    return false;
                }
                // Provider 的列顺序是 mode/json/updated_at，必须按列名取，不能假设第 0 列是 JSON
                int jsonColumn = cursor.getColumnIndex("json");
                if (jsonColumn < 0) {
                    jsonColumn = 1;
                }
                final JSONObject root = new JSONObject(cursor.getString(jsonColumn));
                sCachedEnabled = root.optBoolean("enabled", false);
                sCachedPlaying = root.optBoolean("playing", true);
                sCachedImuUpright = root.optBoolean("imuUpright", true);
                sCachedIndex = root.optInt("index", 0);
                sCachedCount = root.optInt("count", 0);
            }
            if (!sCachedEnabled) {
                reportInactive("主 App 里虚拟摄像头开关是关的");
            } else if (sCachedCount <= 0) {
                reportInactive("主 App 里还没有添加视频");
            }
            final boolean active = sCachedEnabled && sCachedCount > 0;
            if (active && !sLoggedActive) {
                sLoggedActive = true;
                log("camera replay active: clip=" + (sCachedIndex + 1) + "/" + sCachedCount);
            } else if (!active && !sLoggedInactive) {
                sLoggedInactive = true;
                log("camera replay inactive (passthrough)");
            }
            return active;
        } catch (Throwable t) {
            sCachedEnabled = false;
            reportInactive("配置解析失败: " + t);
            return false;
        }
    }

    /** 只在原因变化时回报一次，避免每秒都调用 Provider。 */
    private static volatile String sLastInactiveReason = "";

    private static void reportInactive(String reason) {
        if (reason == null || reason.equals(sLastInactiveReason)) {
            return;
        }
        sLastInactiveReason = reason;
        report(sHookCount, false, false, reason);
    }

    // ================================================================== Session 接管

    private static final XC_MethodHook SESSION_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (Boolean.TRUE.equals(sBypass.get())) {
                    return;   // 恢复真实会话时的内部调用，不要再拦
                }
                if (!isActive()) {
                    return;   // 未启用：完全不动，真实相机照常工作
                }
                final int listIndex = indexOfSurfaceList(param.args);
                final int configIndex = listIndex < 0 ? indexOfOutputConfigurationList(param.args) : -1;
                if (listIndex < 0 && configIndex < 0) {
                    log("createCaptureSession: unsupported overload, passthrough");
                    return;
                }
                final List<Surface> appSurfaces = new ArrayList<>();
                if (listIndex >= 0) {
                    @SuppressWarnings("unchecked")
                    final List<Surface> surfaces = (List<Surface>) param.args[listIndex];
                    appSurfaces.addAll(surfaces);
                } else {
                    @SuppressWarnings("unchecked")
                    final List<android.hardware.camera2.params.OutputConfiguration> configs =
                            (List<android.hardware.camera2.params.OutputConfiguration>) param.args[configIndex];
                    for (Object config : configs) {
                        if (config instanceof android.hardware.camera2.params.OutputConfiguration) {
                            appSurfaces.add(((android.hardware.camera2.params.OutputConfiguration) config)
                                    .getSurface());
                        }
                    }
                }
                if (appSurfaces.isEmpty()) {
                    return;
                }
                // 我们的临时输出：一个小的 YUV ImageReader，真实相机往这里写，画面被丢掉
                ImageReader sink = null;
                for (int[] size : new int[][]{{640, 480}, {320, 240}, {1280, 720}}) {
                    try {
                        sink = ImageReader.newInstance(size[0], size[1],
                                ImageFormat.YUV_420_888, 2);
                        break;
                    } catch (Throwable ignored) {
                        // 换下一个尺寸
                    }
                }
                if (sink == null) {
                    log("camera: cannot create sink ImageReader, passthrough");
                    return;
                }
                final Surface sinkSurface = sink.getSurface();
                sSinkSurface = sinkSurface;
                synchronized (LOCK) {
                    sAppSurfaceSet.clear();
                    sAppSurfaceSet.addAll(appSurfaces);
                    // 自己的 sink 不要被当成 App 的 ImageReader 注册（否则拍照会写回自己）
                    for (int i = sReaders.size() - 1; i >= 0; i--) {
                        if (sReaders.get(i).reader.getSurface().equals(sinkSurface)) {
                            sReaders.remove(i);
                        }
                    }
                }
                if (listIndex >= 0) {
                    final List<Surface> ourSurfaces = new ArrayList<>();
                    ourSurfaces.add(sinkSurface);
                    param.args[listIndex] = ourSurfaces;
                } else {
                    final List<android.hardware.camera2.params.OutputConfiguration> ourConfigs =
                            new ArrayList<>();
                    ourConfigs.add(new android.hardware.camera2.params.OutputConfiguration(sinkSurface));
                    param.args[configIndex] = ourConfigs;
                }

                synchronized (LOCK) {
                    sAppSurfaces.clear();
                    sAppSurfaces.addAll(appSurfaces);
                }
                // 一旦会话配置成功就开始把视频渲染到 App 的 Surface
                sDeviceRef = param.thisObject;
                sOriginalSurfaces = new ArrayList<>(appSurfaces);
                for (Object arg : param.args) {
                    if (arg instanceof CameraCaptureSession.StateCallback) {
                        sOriginalCallback = (CameraCaptureSession.StateCallback) arg;
                    } else if (arg instanceof Handler) {
                        sOriginalHandler = (Handler) arg;
                    }
                }
                wrapCallback(param, appSurfaces);
                log("camera session intercepted: appSurfaces=" + appSurfaces.size());
                report(sHookCount, true, false, null);
            } catch (Throwable t) {
                log("createCaptureSession hook failed (passthrough): " + t);
            }
        }
    };

    /** 会话配置失败时，用原参数重建真实相机会话（避免目标 App 相机直接不可用）。 */
    private static void recoverRealSession() {
        final Object device = sDeviceRef;
        final List<Surface> surfaces = new ArrayList<>(sOriginalSurfaces);
        final CameraCaptureSession.StateCallback callback = sOriginalCallback;
        final Handler handler = sOriginalHandler;
        if (device == null || surfaces.isEmpty() || callback == null) {
            return;
        }
        sBypass.set(Boolean.TRUE);
        try {
            XposedHelpers.callMethod(device, "createCaptureSession", surfaces, callback, handler);
            log("camera: recovered real session (passthrough)");
        } catch (Throwable t) {
            log("camera: recover real session call failed: " + t);
        } finally {
            sBypass.remove();
        }
        stopRendering();
    }

    private static int indexOfOutputConfigurationList(Object[] args) {
        if (args == null) {
            return -1;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof List) {
                List<?> list = (List<?>) args[i];
                if (list.isEmpty()) {
                    continue;
                }
                if (list.get(0) instanceof android.hardware.camera2.params.OutputConfiguration) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int indexOfSurfaceList(Object[] args) {
        if (args == null) {
            return -1;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof List) {
                List<?> list = (List<?>) args[i];
                if (list.isEmpty() || list.get(0) instanceof Surface) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 包一层 Session 回调：配置成功后启动视频渲染。 */
    private static void wrapCallback(XC_MethodHook.MethodHookParam param, List<Surface> appSurfaces) {
        for (int i = 0; i < param.args.length; i++) {
            if (!(param.args[i] instanceof CameraCaptureSession.StateCallback)) {
                continue;
            }
            final CameraCaptureSession.StateCallback original =
                    (CameraCaptureSession.StateCallback) param.args[i];
            final CameraCaptureSession.StateCallback wrapper = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    startRendering(appSurfaces);
                    try {
                        original.onConfigured(session);
                    } catch (Throwable t) {
                        log("onConfigured callback error: " + t);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    // 接管配置失败：用 App 原本的 Surface 重建真实会话，保证相机可用（优雅降级）
                    try {
                        recoverRealSession();
                    } catch (Throwable t) {
                        log("recover real session failed: " + t);
                    }
                    try {
                        original.onConfigureFailed(session);
                    } catch (Throwable t) {
                        log("onConfigureFailed callback error: " + t);
                    }
                }

                @Override
                public void onReady(CameraCaptureSession session) {
                    try {
                        original.onReady(session);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }

                @Override
                public void onActive(CameraCaptureSession session) {
                    try {
                        original.onActive(session);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }

                @Override
                public void onClosed(CameraCaptureSession session) {
                    stopRendering();
                    try {
                        original.onClosed(session);
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            };
            param.args[i] = wrapper;
            return;
        }
    }

    // ================================================================== 视频渲染

    private static void startRendering(List<Surface> appSurfaces) {
        if (appSurfaces == null || appSurfaces.isEmpty()) {
            return;
        }
        final Context context = StepReplay.currentApplication();
        if (context == null) {
            return;
        }
        try {
            final Uri video = videoUri(sCachedIndex);
            final Surface preview = pickPreviewSurface(appSurfaces);
            if (preview == null) {
                log("camera replay: no preview surface found (all are ImageReader)");
                return;
            }
            sLastAppSurface = preview;
            stopRendering();
            final FileDescriptor fd = openVideoFd(context, video);
            if (fd == null) {
                log("camera replay: open video failed");
                return;
            }
            final int[] size = probeVideoSize(fd);
            sLastFrameWidth = size[0];
            sLastFrameHeight = size[1];
            // 用 EGL 做等比适配（避免视频被拉伸/看起来"放大"）；失败则回落到直接渲染
            int[] targetSize = targetSizeFor(preview);
            VideoSurfaceRenderer renderer = new VideoSurfaceRenderer(preview,
                    targetSize[0], targetSize[1], sLastFrameWidth, sLastFrameHeight);
            MediaCodec decoder;
            if (renderer.start()) {
                decoder = buildDecoder(fd, renderer.inputSurface());
                if (decoder != null) {
                    sRenderer = renderer;
                } else {
                    renderer.release();
                    renderer = null;
                    decoder = buildDecoder(fd, preview);
                }
            } else {
                decoder = buildDecoder(fd, preview);
            }
            if (decoder == null) {
                log("camera replay: decoder configure failed, passthrough");
                return;
            }
            sDecoder = decoder;
            sDecoding = true;
            final MediaCodec finalDecoder = decoder;
            final Thread thread = new Thread(() -> decodeLoop(fd, finalDecoder), "VLocHidden-camera");
            thread.setDaemon(true);
            sDecodeThread = thread;
            thread.start();
            log("camera replay started: " + sLastFrameWidth + "x" + sLastFrameHeight
                    + ", previewSurface=" + preview);
            report(sHookCount, false, true, null);
            // 相机接管期间（可选）：IMU 固定为"竖直静止"，避免姿态识别 App 拒绝采集。
            // 关掉这个开关时继续按「运动模拟」的跑步姿态回放。
            ImuReplay.setUprightMode(sCachedImuUpright);
            // 持续把视频帧喂给 App 的分析 ImageReader（否则它会一直报"光线过暗"）
            startAnalysisFeed(context, fd);
        } catch (Throwable t) {
            log("camera replay start failed: " + t);
            report(sHookCount, false, false, "start failed: " + t);
            stopRendering();
        }
    }

    private static void stopRendering() {
        sDecoding = false;
        stopAnalysisFeed();
        ImuReplay.setUprightMode(false);   // 接管结束：恢复运动模拟的跑步姿态
        VideoSurfaceRenderer renderer = sRenderer;
        sRenderer = null;
        if (renderer != null) {
            try {
                renderer.release();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        MediaCodec decoder = sDecoder;
        sDecoder = null;
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                decoder.release();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        Thread thread = sDecodeThread;
        sDecodeThread = null;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 预览 Surface：排除掉 App 的 ImageReader（那是给分析/拍照用的）。 */
    private static Surface pickPreviewSurface(List<Surface> appSurfaces) {
        synchronized (LOCK) {
            for (Surface surface : appSurfaces) {
                boolean isReader = false;
                for (ReaderRef ref : sReaders) {
                    if (ref.reader.getSurface().equals(surface)) {
                        isReader = true;
                        break;
                    }
                }
                if (!isReader) {
                    return surface;
                }
            }
        }
        return appSurfaces.isEmpty() ? null : appSurfaces.get(0);
    }

    /** 目标 Surface 的宽高（从 App 调 setDefaultBufferSize 的记录里取；取不到返回 0）。 */
    private static int[] targetSizeFor(Surface surface) {
        try {
            android.graphics.SurfaceTexture texture = sSurfaceTextures.get(surface);
            if (texture != null) {
                int[] size = sTextureSizes.get(texture);
                if (size != null && size[0] > 0 && size[1] > 0) {
                    return size;
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
        return new int[]{0, 0};
    }

    // ================================================================== 分析帧（YUV ImageReader）

    private static void startAnalysisFeed(Context context, FileDescriptor fd) {
        stopAnalysisFeed();
        sAnalysisRunning = true;
        Thread thread = new Thread(() -> analysisLoop(context, fd), "VLocHidden-camera-analysis");
        thread.setDaemon(true);
        sAnalysisThread = thread;
        thread.start();
    }

    private static void stopAnalysisFeed() {
        sAnalysisRunning = false;
        Thread thread = sAnalysisThread;
        sAnalysisThread = null;
        if (thread != null) {
            try {
                thread.join(300);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    /** 解码视频并把 NV21 帧缩放后写进每个 YUV ImageReader（约 12fps）。 */
    private static void analysisLoop(Context context, FileDescriptor fd) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        List<ImageWriter> writers = new ArrayList<>();
        List<ReaderRef> targets = new ArrayList<>();
        try {
            synchronized (LOCK) {
                for (ReaderRef ref : sReaders) {
                    if (ref.format == ImageFormat.JPEG) {
                        continue;   // JPEG 直出格式写不进去
                    }
                    try {
                        writers.add(ImageWriter.newInstance(ref.reader.getSurface(), 2));
                        targets.add(ref);
                    } catch (Throwable ignored) {
                        // 这个 reader 不兼容，跳过
                    }
                }
            }
            if (targets.isEmpty()) {
                return;
            }

            extractor = new MediaExtractor();
            extractor.setDataSource(new FileInputStream(fd).getFD());
            int videoTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    format = f;
                    break;
                }
            }
            if (videoTrack < 0 || format == null) {
                return;
            }
            extractor.selectTrack(videoTrack);
            final int videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            final int videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            long lastFrameAt = 0L;
            while (sAnalysisRunning) {
                if (!sCachedPlaying) {
                    Thread.sleep(120L);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (now - lastFrameAt < 80L) {   // 约 12fps，别把 CPU 吃满
                    Thread.sleep(10L);
                    continue;
                }
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    Image image = null;
                    try {
                        image = decoder.getOutputImage(outIndex);
                        if (image != null) {
                            byte[] nv21 = imageToNv21(image, videoWidth, videoHeight);
                            if (nv21 != null) {
                                writeAnalysisFrame(nv21, videoWidth, videoHeight, targets, writers);
                                lastFrameAt = System.currentTimeMillis();
                            }
                        }
                    } finally {
                        try {
                            decoder.releaseOutputBuffer(outIndex, false);
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        try {
                            decoder.flush();
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            inputDone = false;
                        } catch (Throwable t) {
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("analysis loop failed: " + t);
        } finally {
            for (ImageWriter writer : writers) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    private static void writeAnalysisFrame(byte[] nv21, int srcW, int srcH,
                                           List<ReaderRef> targets, List<ImageWriter> writers) {
        for (int i = 0; i < targets.size(); i++) {
            ReaderRef ref = targets.get(i);
            ImageWriter writer = writers.get(i);
            try {
                int dstW = ref.width;
                int dstH = ref.height;
                byte[] frame = (srcW == dstW && srcH == dstH)
                        ? nv21 : scaleNv21(nv21, srcW, srcH, dstW, dstH);
                if (frame == null) {
                    continue;
                }
                Image image = writer.dequeueInputImage();
                if (image == null) {
                    continue;
                }
                copyNv21ToImage(frame, dstW, dstH, image);
                writer.queueInputImage(image);
            } catch (Throwable ignored) {
                // 单个 reader 写入失败不影响其它
            }
        }
    }

    /** NV21 最近邻缩放（够用且便宜，供姿态识别使用）。 */
    private static byte[] scaleNv21(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        if (dstW <= 0 || dstH <= 0 || srcW <= 0 || srcH <= 0) {
            return null;
        }
        try {
            byte[] dst = new byte[dstW * dstH * 3 / 2];
            for (int y = 0; y < dstH; y++) {
                int sy = y * srcH / dstH;
                int srcRow = sy * srcW;
                int dstRow = y * dstW;
                for (int x = 0; x < dstW; x++) {
                    dst[dstRow + x] = src[srcRow + x * srcW / dstW];
                }
            }
            int srcUv = srcW * srcH;
            int dstUv = dstW * dstH;
            int halfH = dstH / 2;
            int halfW = dstW / 2;
            for (int y = 0; y < halfH; y++) {
                int sy = y * (srcH / 2) / Math.max(1, halfH);
                for (int x = 0; x < halfW; x++) {
                    int sx = x * (srcW / 2) / Math.max(1, halfW);
                    int srcIndex = srcUv + sy * srcW + sx * 2;
                    int dstIndex = dstUv + y * dstW + x * 2;
                    if (srcIndex + 1 < src.length && dstIndex + 1 < dst.length) {
                        dst[dstIndex] = src[srcIndex];
                        dst[dstIndex + 1] = src[srcIndex + 1];
                    }
                }
            }
            return dst;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Uri videoUri(int index) {
        return Uri.parse(VIDEO_URI_STRING + "?index=" + Math.max(0, index)
                + "&t=" + System.currentTimeMillis());
    }

    /** 通过主 App Provider 打开视频 FD。 */
    private static FileDescriptor openVideoFd(Context context, Uri videoUri) {
        try {
            android.content.ContentResolver resolver = context.getContentResolver();
            android.os.ParcelFileDescriptor pfd = resolver.openFileDescriptor(videoUri, "r");
            return pfd == null ? null : pfd.getFileDescriptor();
        } catch (Throwable t) {
            log("openVideoFd failed: " + t);
            return null;
        }
    }

    /** 探测视频宽高（MediaExtractor 读第一帧视频轨的格式）。 */
    private static int[] probeVideoSize(FileDescriptor fd) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(new FileInputStream(fd).getFD());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return new int[]{format.getInteger(MediaFormat.KEY_WIDTH),
                            format.getInteger(MediaFormat.KEY_HEIGHT)};
                }
            }
        } catch (Throwable t) {
            log("probeVideoSize failed: " + t);
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return new int[]{1280, 720};
    }

    private static MediaCodec buildDecoder(FileDescriptor fd, Surface output) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(new FileInputStream(fd).getFD());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                MediaCodec codec = MediaCodec.createDecoderByType(mime);
                codec.configure(format, output, null, 0);
                codec.start();
                return codec;
            }
        } catch (Throwable t) {
            log("buildDecoder failed: " + t);
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return null;
    }

    /** 解码循环：循环播放视频，帧直接渲染到预览 Surface。 */
    private static void decodeLoop(FileDescriptor fd, MediaCodec decoder) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(new FileInputStream(fd).getFD());
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            if (videoTrack < 0) {
                return;
            }
            extractor.selectTrack(videoTrack);
            long durationUs = 0L;
            try {
                MediaFormat trackFormat = extractor.getTrackFormat(videoTrack);
                if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = trackFormat.getLong(MediaFormat.KEY_DURATION);
                }
            } catch (Throwable ignored) {
                // ignore
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (sDecoding && decoder == sDecoder) {
                if (!sCachedPlaying) {
                    // 暂停：不再推新帧，目标 App 预览冻结在最后一帧
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            // 播放结束：回到开头循环
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    try {
                        reportProgress(Math.max(0L, info.presentationTimeUs / 1000L),
                                Math.max(0L, durationUs / 1000L));
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    decoder.releaseOutputBuffer(outIndex, true);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // 重置到开头继续循环
                        try {
                            decoder.flush();
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            inputDone = false;
                        } catch (Throwable t) {
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("decodeLoop failed: " + t);
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    // ================================================================== 拍照帧

    // ================================================================== 拍照：把当前视频帧写进 App 的 ImageReader

    private static volatile boolean sLoggedJpegUnsupported;

    /** 解码当前视频的一帧，写进所有已注册的 ImageReader（YUV 格式；JPEG 直出格式暂不支持）。 */
    private static void writeFrameToReaders(Context context, int index) {
        List<ReaderRef> readers;
        synchronized (LOCK) {
            readers = new ArrayList<>(sReaders);
        }
        if (readers.isEmpty()) {
            return;
        }
        final Uri video = videoUri(index);
        final byte[] nv21;
        final int width;
        final int height;
        Frame frame = decodeOneFrame(context, video);
        if (frame == null) {
            log("camera photo: decode frame failed");
            return;
        }
        nv21 = frame.nv21;
        width = frame.width;
        height = frame.height;
        for (ReaderRef ref : readers) {
            if (ref.format == ImageFormat.JPEG) {
                if (!sLoggedJpegUnsupported) {
                    sLoggedJpegUnsupported = true;
                    log("camera photo: JPEG ImageReader unsupported (only YUV), passthrough");
                }
                continue;
            }
            ImageWriter writer = null;
            try {
                writer = ImageWriter.newInstance(ref.reader.getSurface(), 2);
                Image image = writer.dequeueInputImage();
                if (image == null) {
                    continue;
                }
                copyNv21ToImage(nv21, width, height, image);
                writer.queueInputImage(image);
            } catch (Throwable t) {
                log("camera photo: write frame failed: " + t);
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    /** 解码一帧并转成 NV21（相机 ImageReader 的 YUV_420_888 写入需要）。 */
    private static Frame decodeOneFrame(Context context, Uri video) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            final android.os.ParcelFileDescriptor pfd =
                    context.getContentResolver().openFileDescriptor(video, "r");
            if (pfd == null) {
                return null;
            }
            extractor = new MediaExtractor();
            extractor.setDataSource(pfd.getFileDescriptor());
            int videoTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    format = f;
                    break;
                }
            }
            if (videoTrack < 0 || format == null) {
                return null;
            }
            extractor.selectTrack(videoTrack);
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();
            final int width = format.getInteger(MediaFormat.KEY_WIDTH);
            final int height = format.getInteger(MediaFormat.KEY_HEIGHT);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean fed = false;
            final long deadline = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < deadline) {
                if (!fed) {
                    int inIndex = decoder.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(inIndex);
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        fed = true;
                    }
                }
                int outIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    Image image = decoder.getOutputImage(outIndex);
                    byte[] nv21 = null;
                    if (image != null) {
                        nv21 = imageToNv21(image, width, height);
                    }
                    decoder.releaseOutputBuffer(outIndex, false);
                    if (nv21 != null) {
                        return new Frame(nv21, width, height);
                    }
                }
            }
        } catch (Throwable t) {
            log("decodeOneFrame failed: " + t);
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return null;
    }

    private static final class Frame {
        final byte[] nv21;
        final int width;
        final int height;

        Frame(byte[] nv21, int width, int height) {
            this.nv21 = nv21;
            this.width = width;
            this.height = height;
        }
    }

    /** Image(YUV_420_888) → NV21。 */
    private static byte[] imageToNv21(Image image, int width, int height) {
        try {
            Image.Plane[] planes = image.getPlanes();
            if (planes.length < 3) {
                return null;
            }
            byte[] nv21 = new byte[width * height * 3 / 2];
            copyPlane(planes[0], width, height, nv21, 0, 1);
            // VU 交错（NV21：V 在前）
            ByteBuffer vBuffer = planes[2].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            int vRowStride = planes[2].getRowStride();
            int vPixelStride = planes[2].getPixelStride();
            int uRowStride = planes[1].getRowStride();
            int uPixelStride = planes[1].getPixelStride();
            int chromaHeight = height / 2;
            int chromaWidth = width / 2;
            int offset = width * height;
            for (int row = 0; row < chromaHeight; row++) {
                for (int col = 0; col < chromaWidth; col++) {
                    int vIndex = row * vRowStride + col * vPixelStride;
                    int uIndex = row * uRowStride + col * uPixelStride;
                    nv21[offset + row * width + col * 2] =
                            (vIndex < vBuffer.limit()) ? vBuffer.get(vIndex) : 0;
                    nv21[offset + row * width + col * 2 + 1] =
                            (uIndex < uBuffer.limit()) ? uBuffer.get(uIndex) : 0;
                }
            }
            return nv21;
        } catch (Throwable t) {
            log("imageToNv21 failed: " + t);
            return null;
        }
    }

    private static void copyPlane(Image.Plane plane, int width, int height,
                                  byte[] out, int offset, int pixelStrideExpected) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int index = row * rowStride + col * pixelStride;
                out[offset + row * width + col] = (index < buffer.limit()) ? buffer.get(index) : 0;
            }
        }
    }

    /** 把 NV21 拷进 ImageWriter 的 YUV 平面。 */
    private static void copyNv21ToImage(byte[] nv21, int width, int height, Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            return;
        }
        int yOffset = 0;
        int uvOffset = width * height;
        for (int row = 0; row < height; row++) {
            ByteBuffer yBuffer = planes[0].getBuffer();
            int index = row * planes[0].getRowStride();
            for (int col = 0; col < width; col++) {
                yBuffer.put(index + col * planes[0].getPixelStride(), nv21[yOffset++]);
            }
        }
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                byte v = nv21[uvOffset + row * width + col * 2];
                byte u = nv21[uvOffset + row * width + col * 2 + 1];
                int vIndex = row * planes[2].getRowStride() + col * planes[2].getPixelStride();
                int uIndex = row * planes[1].getRowStride() + col * planes[1].getPixelStride();
                if (vIndex < planes[2].getBuffer().limit()) {
                    planes[2].getBuffer().put(vIndex, v);
                }
                if (uIndex < planes[1].getBuffer().limit()) {
                    planes[1].getBuffer().put(uIndex, u);
                }
            }
        }
    }

    private static final class ReaderRef {
        final ImageReader reader;
        final int format;
        final int width;
        final int height;

        ReaderRef(ImageReader reader, int format, int width, int height) {
            this.reader = reader;
            this.format = format;
            this.width = width;
            this.height = height;
        }
    }

    /** 把接管状态回报给主 App（控制页展示），失败静默。 */
    /** 注入早期 Context 还没就绪：主线程延迟重试上报（最多 6 次）。 */
    private static void reportRetry(final int hooks, final boolean session,
                                    final boolean render, final String error, final int attempt) {
        try {
            new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (StepReplay.currentApplication() != null) {
                        report(hooks, session, render, error);
                    } else if (attempt < 6) {
                        reportRetry(hooks, session, render, error, attempt + 1);
                    }
                } catch (Throwable ignored) {
                    // ignore
                }
            }, 1500L);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void report(int hooks, boolean session, boolean render, String error) {
        report(hooks, session, render, error, null);
    }

    private static long sLastProgressReportAt;

    /** 上报播放进度（每秒最多一次）。 */
    private static void reportProgress(long positionMs, long durationMs) {
        final long now = System.currentTimeMillis();
        if (now - sLastProgressReportAt < 900L) {
            return;
        }
        sLastProgressReportAt = now;
        try {
            final Context context = StepReplay.currentApplication();
            if (context == null) {
                return;
            }
            final android.os.Bundle bundle = new android.os.Bundle();
            bundle.putInt("hooks", sHookCount);
            bundle.putBoolean("session", false);
            bundle.putBoolean("render", true);
            bundle.putLong("posMs", positionMs);
            bundle.putLong("durMs", durationMs);
            context.getContentResolver().call(Uri.parse(CAMERA_URI_STRING_FOR_CALL),
                    "camera_report", null, bundle);
            reportProgressOnly(positionMs, durationMs);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private static void reportProgressOnly(long positionMs, long durationMs) {
        // 本地不需要额外处理：Provider 侧会写入 CameraSession
    }

    private static void reportEvent(int hooks, String event) {
        report(hooks, false, false, null, event);
    }

    private static void report(int hooks, boolean session, boolean render, String error,
                               String event) {
        sHookCount = hooks;
        try {
            final Context context = StepReplay.currentApplication();
            if (context == null) {
                // 进程刚注入时 Application 还没好：延迟重试，保证控制页能看到 hooks 数
                reportRetry(hooks, session, render, error, 0);
                return;
            }
            final android.os.Bundle bundle = new android.os.Bundle();
            bundle.putInt("hooks", hooks);
            bundle.putBoolean("session", session);
            bundle.putBoolean("render", render);
            if (error != null && !error.isEmpty()) {
                bundle.putString("error", error);
            }
            String process = "";
            try {
                Object name = XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentProcessName");
                if (name instanceof String) {
                    process = (String) name;
                }
            } catch (Throwable ignored) {
                // ignore
            }
            bundle.putString("pkg", process.isEmpty()
                    ? context.getPackageName()
                    : context.getPackageName() + "@" + process);
            if (event != null && !event.isEmpty()) {
                bundle.putString("event", event);
            }
            context.getContentResolver().call(Uri.parse(CAMERA_URI_STRING_FOR_CALL),
                    "camera_report", null, bundle);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ================================================================== Camera1（老接口）

    /** Camera1 的预览目标（Surface 或 SurfaceTexture 包出来的 Surface）。 */
    private static volatile Surface sCamera1Surface;
    private static volatile boolean sLoggedCamera1;

    private static int hookCamera1(ClassLoader classLoader) {
        int count = 0;
        try {
            final Class<?> camera1 = XposedHelpers.findClass("android.hardware.Camera", classLoader);

            // setPreviewDisplay(SurfaceHolder)：把 App 的 Surface 记下来，实际给相机关联一个临时目标
            try {
                XposedBridge.hookAllMethods(camera1, "setPreviewDisplay", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!isActive() || param.args.length != 1
                                    || !(param.args[0] instanceof android.view.SurfaceHolder)) {
                                return;
                            }
                            final android.view.SurfaceHolder holder =
                                    (android.view.SurfaceHolder) param.args[0];
                            final Surface surface = holder.getSurface();
                            if (surface == null || !surface.isValid()) {
                                return;
                            }
                            sCamera1Surface = surface;
                            logCamera1Once("SurfaceHolder");
                            startRendering(java.util.Collections.singletonList(surface));
                        } catch (Throwable t) {
                            log("camera1 setPreviewDisplay hook failed: " + t);
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {
                // 方法不存在
            }

            // setPreviewTexture(SurfaceTexture)：同上，记下 App 的 SurfaceTexture
            try {
                XposedBridge.hookAllMethods(camera1, "setPreviewTexture", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!isActive() || param.args.length != 1
                                    || !(param.args[0] instanceof android.graphics.SurfaceTexture)) {
                                return;
                            }
                            final android.graphics.SurfaceTexture texture =
                                    (android.graphics.SurfaceTexture) param.args[0];
                            final Surface surface = new Surface(texture);
                            sCamera1Surface = surface;
                            logCamera1Once("SurfaceTexture");
                            startRendering(java.util.Collections.singletonList(surface));
                        } catch (Throwable t) {
                            log("camera1 setPreviewTexture hook failed: " + t);
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {
                // 方法不存在
            }

            // takePicture：直接回调 App 的 JPEG 回调（返回当前视频帧），不再走真实相机
            try {
                XposedBridge.hookAllMethods(camera1, "takePicture", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!isActive()) {
                                return;
                            }
                            final Context context = StepReplay.currentApplication();
                            if (context == null) {
                                return;
                            }
                            final Object[] callbacks = findPictureCallbacks(param.args);
                            if (callbacks == null) {
                                return;
                            }
                            final int index = sCachedIndex;
                            final Handler main = new Handler(android.os.Looper.getMainLooper());
                            final Thread worker = new Thread(() -> {
                                final byte[] jpeg = decodeFrameJpeg(context, index);
                                if (jpeg == null) {
                                    log("camera1 takePicture: decode jpeg failed");
                                    return;
                                }
                                main.post(() -> deliverCamera1Jpeg(callbacks, jpeg));
                            }, "VLocHidden-camera1-photo");
                            worker.setDaemon(true);
                            worker.start();
                            // 不再走真实相机拍照（否则会拿到真实照片）
                            param.setResult(null);
                        } catch (Throwable t) {
                            log("camera1 takePicture hook failed: " + t);
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {
                // 方法不存在
            }

            // open：仅用于诊断
            try {
                XposedBridge.hookAllMethods(camera1, "open", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (isActive()) {
                                logCamera1Once("open");
                                reportEvent(sHookCount, "camera1 open");
                                report(sHookCount, true, sCamera1Surface != null, null);
                            }
                        } catch (Throwable ignored) {
                            // ignore
                        }
                    }
                });
                count++;
            } catch (Throwable ignored) {
                // ignore
            }
        } catch (Throwable t) {
            logHookFailOnce("android.hardware.Camera", t);
        }
        log("camera1 hooks=" + count + "/4");
        return count;
    }

    private static void logCamera1Once(String what) {
        if (sLoggedCamera1) {
            return;
        }
        sLoggedCamera1 = true;
        log("camera1 replay active (" + what + ")");
    }

    private static Object[] findPictureCallbacks(Object[] args) {
        if (args == null) {
            return null;
        }
        Object jpeg = null;
        Object shutter = null;
        Object raw = null;
        for (Object arg : args) {
            if (arg instanceof android.hardware.Camera.PictureCallback) {
                // 约定：最后一个 PictureCallback 是 JPEG 回调
                if (jpeg != null) {
                    raw = jpeg;
                }
                jpeg = arg;
            } else if (arg instanceof android.hardware.Camera.ShutterCallback) {
                shutter = arg;
            }
        }
        if (jpeg == null) {
            return null;
        }
        return new Object[]{shutter, raw, jpeg};
    }

    private static void deliverCamera1Jpeg(Object[] callbacks, byte[] jpeg) {
        try {
            if (callbacks[0] != null) {
                ((android.hardware.Camera.ShutterCallback) callbacks[0]).onShutter();
            }
            if (callbacks[1] != null) {
                ((android.hardware.Camera.PictureCallback) callbacks[1]).onPictureTaken(null, null);
            }
            if (callbacks[2] != null) {
                ((android.hardware.Camera.PictureCallback) callbacks[2]).onPictureTaken(jpeg, null);
            }
            log("camera1 takePicture delivered, bytes=" + (jpeg == null ? 0 : jpeg.length));
            report(sHookCount, true, true, null);
        } catch (Throwable t) {
            log("camera1 deliver jpeg failed: " + t);
        }
    }

    /** 解码当前视频一帧并压成 JPEG。 */
    private static byte[] decodeFrameJpeg(Context context, int index) {
        Frame frame = decodeOneFrame(context, videoUri(index));
        if (frame == null) {
            return null;
        }
        try {
            android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                    frame.nv21, ImageFormat.NV21, frame.width, frame.height, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, frame.width, frame.height), 90, out);
            return out.toByteArray();
        } catch (Throwable t) {
            log("decodeFrameJpeg failed: " + t);
            return null;
        }
    }

    private static void logHookFailOnce(String what, Throwable t) {
        if (sLoggedHookFail) {
            return;
        }
        sLoggedHookFail = true;
        log("skip " + what + ": " + t);
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
