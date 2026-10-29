package com.vloc.motion.hidden;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import de.robv.android.xposed.XposedBridge;

/**
 * 把解码后的视频帧渲染到目标 App 的预览 Surface 上（EGL + GLES 外部纹理）。
 *
 * <p>为什么要这一层：直接把 MediaCodec 的输出 Surface 设成 App 的预览 Surface 时，
 * 解码器会按"填满"的方式铺，视频比例和预览比例不一致就会拉伸/看起来"放大"。
 * 这里先把视频解码进自己的 SurfaceTexture，再用 GL 画一个按目标比例做
 * center-crop（铺满不留黑边）的四边形，观感与相机预览一致。
 */
final class VideoSurfaceRenderer {

    private static final String TAG = "[VLocHidden] ";

    private static final String VERTEX_SHADER =
            "attribute vec4 aPos;\n"
                    + "attribute vec2 aTex;\n"
                    + "varying vec2 vTex;\n"
                    + "uniform vec2 uScale;\n"
                    + "void main() {\n"
                    + "  gl_Position = vec4(aPos.x * uScale.x, aPos.y * uScale.y, 0.0, 1.0);\n"
                    + "  vTex = aTex;\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTex;\n"
                    + "uniform samplerExternalOES uTex;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(uTex, vTex);\n"
                    + "}\n";

    private final Surface targetSurface;
    private final int targetWidth;
    private final int targetHeight;
    private final int videoWidth;
    private final int videoHeight;

    private HandlerThread thread;
    private Handler handler;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture inputTexture;
    private Surface inputSurface;
    private int textureId;
    private int program;
    private int uScaleLocation;
    private int aPosLocation;
    private int aTexLocation;
    private boolean released;
    private boolean loggedFirstFrame;

    VideoSurfaceRenderer(Surface targetSurface, int targetWidth, int targetHeight,
                         int videoWidth, int videoHeight) {
        this.targetSurface = targetSurface;
        this.targetWidth = targetWidth > 0 ? targetWidth : videoWidth;
        this.targetHeight = targetHeight > 0 ? targetHeight : videoHeight;
        this.videoWidth = Math.max(1, videoWidth);
        this.videoHeight = Math.max(1, videoHeight);
    }

    /** 解码器的输出 Surface（视频帧先写到这里）。 */
    Surface inputSurface() {
        return inputSurface;
    }

    /** 初始化 EGL + GL 管线；失败返回 false（调用方回落到直接渲染）。 */
    boolean start() {
        try {
            thread = new HandlerThread("VLocHidden-cam-gl");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(this::initGl);
            return true;
        } catch (Throwable t) {
            log("renderer start failed: " + t);
            return false;
        }
    }

    private void initGl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

            int[] configAttrs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(eglDisplay, configAttrs, 0, configs, 0, 1, numConfigs, 0);
            if (numConfigs[0] == 0) {
                throw new IllegalStateException("no egl config");
            }
            int[] contextAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                    EGL14.EGL_NO_CONTEXT, contextAttrs, 0);
            int[] surfaceAttrs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0],
                    targetSurface, surfaceAttrs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("eglCreateWindowSurface failed");
            }
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

            textureId = createExternalTexture();
            inputTexture = new SurfaceTexture(textureId);
            inputTexture.setDefaultBufferSize(videoWidth, videoHeight);
            inputSurface = new Surface(inputTexture);

            program = buildProgram();
            aPosLocation = android.opengl.GLES20.glGetAttribLocation(program, "aPos");
            aTexLocation = android.opengl.GLES20.glGetAttribLocation(program, "aTex");
            uScaleLocation = android.opengl.GLES20.glGetUniformLocation(program, "uScale");

            inputTexture.setOnFrameAvailableListener(st -> handler.post(this::drawFrame));
            log("gl renderer ready " + targetWidth + "x" + targetHeight
                    + " video=" + videoWidth + "x" + videoHeight);
        } catch (Throwable t) {
            log("gl init failed: " + t);
            release();
        }
    }

    private int createExternalTexture() {
        int[] ids = new int[1];
        android.opengl.GLES20.glGenTextures(1, ids, 0);
        android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0]);
        android.opengl.GLES20.glTexParameterf(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR);
        android.opengl.GLES20.glTexParameterf(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR);
        return ids[0];
    }

    private int buildProgram() {
        int vs = compile(android.opengl.GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compile(android.opengl.GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int prog = android.opengl.GLES20.glCreateProgram();
        android.opengl.GLES20.glAttachShader(prog, vs);
        android.opengl.GLES20.glAttachShader(prog, fs);
        android.opengl.GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        android.opengl.GLES20.glGetProgramiv(prog, android.opengl.GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("link failed: "
                    + android.opengl.GLES20.glGetProgramInfoLog(prog));
        }
        android.opengl.GLES20.glDeleteShader(vs);
        android.opengl.GLES20.glDeleteShader(fs);
        return prog;
    }

    private int compile(int type, String source) {
        int shader = android.opengl.GLES20.glCreateShader(type);
        android.opengl.GLES20.glShaderSource(shader, source);
        android.opengl.GLES20.glCompileShader(shader);
        int[] status = new int[1];
        android.opengl.GLES20.glGetShaderiv(shader, android.opengl.GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            throw new IllegalStateException("compile failed: "
                    + android.opengl.GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private void drawFrame() {
        if (released) {
            return;
        }
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            inputTexture.updateTexImage();

            android.opengl.GLES20.glViewport(0, 0, targetWidth, targetHeight);
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f);
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT);

            android.opengl.GLES20.glUseProgram(program);
            android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0);
            android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    textureId);

            // center-crop：视频比目标"更宽"就裁左右，反之裁上下，铺满且不变形
            float targetAspect = (float) targetWidth / Math.max(1, targetHeight);
            float videoAspect = (float) videoWidth / Math.max(1, videoHeight);
            float scaleX = 1f;
            float scaleY = 1f;
            if (videoAspect > targetAspect) {
                scaleX = targetAspect / videoAspect;
            } else {
                scaleY = videoAspect / targetAspect;
            }
            android.opengl.GLES20.glUniform2f(uScaleLocation, scaleX, scaleY);

            FloatBuffer pos = buffer(new float[]{
                    -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
            });
            FloatBuffer tex = buffer(new float[]{
                    0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
            });
            android.opengl.GLES20.glEnableVertexAttribArray(aPosLocation);
            android.opengl.GLES20.glVertexAttribPointer(aPosLocation, 2,
                    android.opengl.GLES20.GL_FLOAT, false, 0, pos);
            android.opengl.GLES20.glEnableVertexAttribArray(aTexLocation);
            android.opengl.GLES20.glVertexAttribPointer(aTexLocation, 2,
                    android.opengl.GLES20.GL_FLOAT, false, 0, tex);
            android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4);
            android.opengl.GLES20.glDisableVertexAttribArray(aPosLocation);
            android.opengl.GLES20.glDisableVertexAttribArray(aTexLocation);

            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
            if (!loggedFirstFrame) {
                loggedFirstFrame = true;
                log("gl first frame rendered");
            }
        } catch (Throwable t) {
            log("draw frame failed: " + t);
        }
    }

    private static FloatBuffer buffer(float[] data) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(data).position(0);
        return buffer;
    }

    /** 释放 GL / EGL 资源（幂等）。 */
    void release() {
        released = true;
        try {
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (inputSurface != null) {
                inputSurface.release();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (inputTexture != null) {
                inputTexture.release();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        if (thread != null) {
            try {
                thread.quitSafely();
            } catch (Throwable ignored) {
                // ignore
            }
            thread = null;
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log(TAG + message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
