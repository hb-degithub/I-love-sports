package com.vloc.motion.service;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 供 Zygisk 原生模块读取的传感器状态（系统级注入用）。
 *
 * <p>为什么用文件而不是 ContentProvider：Zygisk 模块运行在**每个 App 进程的原生层**，
 * 那里没有 Java Context，读 Provider 需要额外桥接；文件通道最简单可靠。
 *
 * <p>链路：
 * <ol>
 *   <li>主 App 每次推进传感器模型时把状态写成定长二进制（本类）；</li>
 *   <li>Magisk 模块的 {@code service.sh} 以 root 权限把这个文件每 100ms 复制到
 *       {@code /data/local/tmp/vloc/state.bin}（对 App 私有目录，只有 root 能读）；</li>
 *   <li>Zygisk 模块在目标进程里 mmap 该文件，按同一结构体解析并改写传感器事件。</li>
 * </ol>
 *
 * <p>结构体布局必须与 {@code zygisk/module.cpp} 里的 {@code VlocState} 完全一致。
 */
public final class VlocStateFile {

    private static final String TAG = "VlocState";
    public static final String FILE_NAME = "vloc_state.bin";

    /** 'V''L''O''C'，模块用它校验文件有效性。 */
    private static final int MAGIC = 0x434F4C56;
    private static final int VERSION = 1;
    /** 定长：4+4+4+8 + 4*4 + 4*3 + 4*3 + 4*3 + 4*4 + 4*3 + 4 + 8 = 128 字节。 */
    public static final int SIZE = 128;

    private static final Object LOCK = new Object();
    private static ByteBuffer sBuffer;
    private static File sFile;
    private static long sLastWriteMs;

    private VlocStateFile() {
    }

    /** 初始化（服务启动时调用一次）。 */
    public static void init(Context context) {
        synchronized (LOCK) {
            try {
                sFile = new File(context.getFilesDir(), FILE_NAME);
                sBuffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
            } catch (Throwable t) {
                Log.w(TAG, "init failed", t);
                sFile = null;
                sBuffer = null;
            }
        }
    }

    /**
     * 写入一帧状态。
     *
     * @param active   是否在模拟
     * @param steps    累计步数
     * @param cadence  步频 spm
     * @param speed    速度 m/s
     * @param heading  航向角（度）
     * @param accel    设备系加速度（含重力，3 个 float）
     * @param gyro     设备系角速度（rad/s，3 个 float）
     * @param gravity  设备系重力（3 个 float）
     * @param rotation 旋转矢量/四元数（w,x,y,z，4 个 float）
     * @param magnetic 磁力计（3 个 float）
     * @param attitude 姿态回放是否开启
     */
    public static void write(boolean active, long steps, float cadence, float speed,
                             float heading, float[] accel, float[] gyro, float[] gravity,
                             float[] rotation, float[] magnetic, boolean attitude) {
        synchronized (LOCK) {
            if (sBuffer == null || sFile == null) {
                return;
            }
            try {
                sBuffer.clear();
                sBuffer.putInt(MAGIC);
                sBuffer.putInt(VERSION);
                sBuffer.putInt(active ? 1 : 0);
                sBuffer.putInt(0);                       // 对齐填充
                sBuffer.putLong(steps);
                sBuffer.putFloat(cadence);
                sBuffer.putFloat(speed);
                sBuffer.putFloat(heading);
                sBuffer.putInt(attitude ? 1 : 0);
                putVec3(sBuffer, accel);
                putVec3(sBuffer, gyro);
                putVec3(sBuffer, gravity);
                putVec4(sBuffer, rotation);
                putVec3(sBuffer, magnetic);
                sBuffer.putLong(System.currentTimeMillis());

                try (FileOutputStream out = new FileOutputStream(sFile, false)) {
                    out.write(sBuffer.array(), 0, SIZE);
                }
                sLastWriteMs = System.currentTimeMillis();
            } catch (IOException e) {
                Log.w(TAG, "write failed", e);
            }
        }
    }

    /** 停止模拟：写一帧 active=0，模块据此透传真实传感器。 */
    public static void clear() {
        write(false, 0, 0f, 0f, 0f,
                new float[3], new float[3], new float[3], new float[4], new float[3], false);
    }

    /** 文件路径（供 root 拷贝脚本 / 诊断使用）。 */
    public static String path() {
        synchronized (LOCK) {
            return sFile == null ? "" : sFile.getAbsolutePath();
        }
    }

    private static void putVec3(ByteBuffer buffer, float[] values) {
        for (int i = 0; i < 3; i++) {
            buffer.putFloat(values != null && values.length > i ? values[i] : 0f);
        }
    }

    private static void putVec4(ByteBuffer buffer, float[] values) {
        for (int i = 0; i < 4; i++) {
            buffer.putFloat(values != null && values.length > i ? values[i] : 0f);
        }
    }
}
