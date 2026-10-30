package com.vloc.motion.service;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link VlocStateFile} 与 Zygisk 模块 {@code VlocState} 的字节级契约。
 *
 * <p>为什么必须测：C++ 侧按固定偏移读这个文件，布局一旦错位，模块读到的是垃圾数据，
 * 而且**不会有任何报错**（传感器数量级不对也不会崩），只能靠这里的偏移断言守住。
 *
 * <p>对应 ../../../../../zygisk/src/module.cpp 的 static_assert(sizeof(VlocState) == 112)。
 */
public class VlocStateFileTest {

    private static final float[] ACCEL = {1.5f, 9.6f, 0.3f};
    private static final float[] GYRO = {0.01f, 0.02f, 0.03f};
    private static final float[] GRAVITY = {0f, 9.81f, 0f};
    private static final float[] ROTATION = {0.1f, 0.2f, 0.3f, 0.9f};
    private static final float[] MAGNETIC = {12f, -30f, 41f};

    private static ByteBuffer encode(long steps, long updatedAt) {
        byte[] bytes = VlocStateFile.encode(true, steps, 170.5f, 3.33f, 167.4f,
                ACCEL, GYRO, GRAVITY, ROTATION, MAGNETIC, true, updatedAt);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    public void sizeMatchesNativeStruct() {
        byte[] bytes = VlocStateFile.encode(true, 1, 1, 1, 1,
                ACCEL, GYRO, GRAVITY, ROTATION, MAGNETIC, true, 1L);
        assertEquals("必须与 C++ sizeof(VlocState) 一致", 112, bytes.length);
    }

    @Test
    public void magicAndVersionAtFixedOffsets() {
        ByteBuffer buffer = encode(0, 0);
        assertEquals("magic@0", 0x434F4C56, buffer.getInt(0));
        assertEquals("version@4", 1, buffer.getInt(4));
        assertEquals("active@8", 1, buffer.getInt(8));
        assertEquals("padding@12", 0, buffer.getInt(12));
    }

    @Test
    public void scalarFieldsAtFixedOffsets() {
        ByteBuffer buffer = encode(12345L, 1700000000000L);
        assertEquals("steps@16", 12345L, buffer.getLong(16));
        assertEquals("cadence@24", 170.5f, buffer.getFloat(24), 1e-4);
        assertEquals("speed@28", 3.33f, buffer.getFloat(28), 1e-4);
        assertEquals("heading@32", 167.4f, buffer.getFloat(32), 1e-3);
        assertEquals("attitude@36", 1, buffer.getInt(36));
        assertEquals("updatedAt@104", 1700000000000L, buffer.getLong(104));
    }

    @Test
    public void vectorFieldsAtFixedOffsets() {
        ByteBuffer buffer = encode(0, 0);
        assertEquals("accel@40", 1.5f, buffer.getFloat(40), 1e-6);
        assertEquals("accel2@48", 0.3f, buffer.getFloat(48), 1e-6);
        assertEquals("gyro@52", 0.01f, buffer.getFloat(52), 1e-6);
        assertEquals("gravity@64", 0f, buffer.getFloat(64), 1e-6);
        assertEquals("gravity.y@68", 9.81f, buffer.getFloat(68), 1e-4);
        assertEquals("rotation@76", 0.1f, buffer.getFloat(76), 1e-6);
        assertEquals("rotation.w@88", 0.9f, buffer.getFloat(88), 1e-6);
        assertEquals("magnetic@92", 12f, buffer.getFloat(92), 1e-6);
    }

    @Test
    public void inactiveFrameZeroesActiveFlag() {
        byte[] bytes = VlocStateFile.encode(false, 0, 0, 0, 0,
                ACCEL, GYRO, GRAVITY, ROTATION, MAGNETIC, false, 0L);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("active=0 时模块必须透传真实传感器", 0, buffer.getInt(8));
        assertEquals(0, buffer.getInt(36));   // attitude=false
    }

    @Test
    public void accumulatorWaitsForEqualLengthArrays() {
        // 数组比约定短时按 0 填充，不能越界
        byte[] bytes = VlocStateFile.encode(true, 0, 0, 0, 0,
                new float[1], null, null, new float[2], null, false, 0L);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(112, bytes.length);
        assertEquals(0f, buffer.getFloat(40), 1e-6);
        assertTrue(buffer.getFloat(76) == 0f);
    }
}
