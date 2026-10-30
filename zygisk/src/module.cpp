// 虚拟运动 Zygisk 模块：系统级传感器注入。
//
// 与 LSPosed 方案的区别：Zygisk 会注入到**每一个 App 进程**，因此这里不需要逐个勾选
// 作用域——装好模块后所有 App 的传感器事件都会被接管（对用户而言即"系统级"）。
//
// 实现方式刻意保守：只用 Zygisk 官方 API 的 hookJniNativeMethods 替换 JNI 函数指针，
// 不改写任何机器指令，不 inline hook，因此不会破坏系统稳定性。
//
// 数据来源：主 App 把传感器状态写成定长二进制，Magisk 侧 service.sh 以 root 复制到
// /data/local/tmp/vloc/state.bin，本模块 mmap 读取（见 app 侧 VlocStateFile.java，
// 两边的结构体布局必须完全一致）。

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <unordered_map>

#include "zygisk.hpp"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "VLocZygisk", __VA_ARGS__)

namespace {

constexpr uint32_t kMagic = 0x434F4C56u;   // 'V''L''O''C'
constexpr const char *kStatePath = "/data/local/tmp/vloc/state.bin";
/** 状态超过这个时间视为过期（主 App 可能已被杀），透传真实传感器。 */
constexpr int64_t kStaleMs = 2500;

/** 与 app 侧 VlocStateFile 完全一致的布局（128 字节，小端）。 */
struct VlocState {
    uint32_t magic;
    uint32_t version;
    int32_t active;
    int32_t padding;
    int64_t steps;
    float cadenceSpm;
    float speedMps;
    float headingDeg;
    int32_t attitude;
    float accel[3];
    float gyro[3];
    float gravity[3];
    float rotation[4];
    float magnetic[3];
    int64_t updatedAtMs;
};

static_assert(sizeof(VlocState) == 128, "VlocState 布局必须与 Java 侧一致");

// ---- 传感器类型（android.hardware.Sensor）----
constexpr int TYPE_ACCELEROMETER = 1;
constexpr int TYPE_MAGNETIC_FIELD = 2;
constexpr int TYPE_ORIENTATION = 3;
constexpr int TYPE_GYROSCOPE = 4;
constexpr int TYPE_GRAVITY = 9;
constexpr int TYPE_LINEAR_ACCELERATION = 10;
constexpr int TYPE_ROTATION_VECTOR = 11;
constexpr int TYPE_GAME_ROTATION_VECTOR = 15;
constexpr int TYPE_STEP_COUNTER = 19;

// ============================================================ 状态文件

const VlocState *gState = nullptr;
void *gMap = nullptr;

bool ensureMapped() {
    if (gState != nullptr) {
        return true;
    }
    int fd = open(kStatePath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    void *map = mmap(nullptr, sizeof(VlocState), PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (map == MAP_FAILED) {
        return false;
    }
    auto *state = static_cast<const VlocState *>(map);
    if (state->magic != kMagic || state->version != 1) {
        munmap(map, sizeof(VlocState));
        return false;
    }
    gMap = map;
    gState = state;
    LOG("state file mapped: %s", kStatePath);
    return true;
}

int64_t nowMs() {
    struct timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

/** 当前是否应当接管（文件存在、magic 正确、active 且未过期）。 */
const VlocState *activeState() {
    if (!ensureMapped()) {
        return nullptr;
    }
    if (gState->active == 0) {
        return nullptr;
    }
    if (nowMs() - gState->updatedAtMs > kStaleMs) {
        return nullptr;
    }
    return gState;
}

// ============================================================ sensor handle → type

struct SensorInfo {
    int type = 0;
};

std::unordered_map<jint, SensorInfo> gHandleTypes;

jclass gSensorClass = nullptr;
jfieldID gSensorHandleField = nullptr;
jfieldID gSensorTypeField = nullptr;
jclass gEventClass = nullptr;
jfieldID gEventSensorField = nullptr;

/** 缓存 Sensor 类字段，避免每帧查找。 */
void cacheRefs(JNIEnv *env) {
    if (gSensorClass == nullptr) {
        jclass local = env->FindClass("android/hardware/Sensor");
        if (local != nullptr) {
            gSensorHandleField = env->GetFieldID(local, "mHandle", "I");
            gSensorTypeField = env->GetFieldID(local, "mType", "I");
            gSensorClass = static_cast<jclass>(env->NewGlobalRef(local));
            env->DeleteLocalRef(local);
        }
    }
    if (gEventClass == nullptr) {
        jclass local = env->FindClass("android/hardware/SensorEvent");
        if (local != nullptr) {
            gEventSensorField = env->GetFieldID(local, "sensor", "Landroid/hardware/Sensor;");
            gEventClass = static_cast<jclass>(env->NewGlobalRef(local));
            env->DeleteLocalRef(local);
        }
    }
}

/** 记录 handle 对应的传感器类型（由 nativeGetNextSensorEvent 填充）。 */
void rememberHandle(JNIEnv *env, jobject sensor) {
    if (sensor == nullptr || gSensorHandleField == nullptr || gSensorTypeField == nullptr) {
        return;
    }
    jint handle = env->GetIntField(sensor, gSensorHandleField);
    jint type = env->GetIntField(sensor, gSensorTypeField);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }
    SensorInfo info;
    info.type = type;
    gHandleTypes[handle] = info;
}

int typeOfHandle(jint handle) {
    auto it = gHandleTypes.find(handle);
    return it == gHandleTypes.end() ? 0 : it->second.type;
}

/** 按设备系重力推算俯仰角（近似；供 ORIENTATION 使用）。 */
float stackPitch(const VlocState *state) {
    const float gx = state->gravity[0];
    const float gz = state->gravity[2];
    const float horizontal = std::sqrt(gx * gx + gz * gz);
    if (horizontal < 0.0001f) {
        return 90.0f;   // 完全竖直
    }
    return std::atan2(-gz, horizontal) * 57.29578f;
}

// ============================================================ JNI hooks

void (*gOrigDispatch)(JNIEnv *, jclass, jint, jfloatArray, jint, jlong) = nullptr;
jboolean (*gOrigNextEvent)(JNIEnv *, jclass, jobject, jobject) = nullptr;

/**
 * 改写一帧传感器值。返回 true 表示已接管（调用方不再改动）。
 *
 * 只在"状态有效"时改写；否则返回 false 让原始数据照常透传。
 */
bool rewriteValues(int type, jfloatArray valuesArray, JNIEnv *env) {
    const VlocState *state = activeState();
    if (state == nullptr || valuesArray == nullptr) {
        return false;
    }
    jsize length = env->GetArrayLength(valuesArray);
    if (length <= 0) {
        return false;
    }
    jfloat buffer[8] = {0};
    jsize count = length > 8 ? 8 : length;

    switch (type) {
        case TYPE_STEP_COUNTER:
            buffer[0] = static_cast<jfloat>(state->steps);
            break;
        case TYPE_ACCELEROMETER:
        case TYPE_GRAVITY:
            if (count < 3) return false;
            buffer[0] = state->accel[0];
            buffer[1] = state->accel[1];
            buffer[2] = state->accel[2];
            break;
        case TYPE_LINEAR_ACCELERATION: {
            if (count < 3) return false;
            // 线加速度 = 加速度 - 重力
            buffer[0] = state->accel[0] - state->gravity[0];
            buffer[1] = state->accel[1] - state->gravity[1];
            buffer[2] = state->accel[2] - state->gravity[2];
            break;
        }
        case TYPE_GYROSCOPE:
            if (count < 3) return false;
            buffer[0] = state->gyro[0];
            buffer[1] = state->gyro[1];
            buffer[2] = state->gyro[2];
            break;
        case TYPE_MAGNETIC_FIELD:
            if (count < 3) return false;
            buffer[0] = state->magnetic[0];
            buffer[1] = state->magnetic[1];
            buffer[2] = state->magnetic[2];
            break;
        case TYPE_ROTATION_VECTOR:
        case TYPE_GAME_ROTATION_VECTOR:
            // 旋转矢量：前 3 个是 sin(θ/2) 分量，第 4 个是 cos(θ/2)
            if (count < 3) return false;
            buffer[0] = state->rotation[0];
            buffer[1] = state->rotation[1];
            buffer[2] = state->rotation[2];
            if (count >= 4) {
                buffer[3] = state->rotation[3];
            }
            break;
        case TYPE_ORIENTATION:
            if (count < 3) return false;
            buffer[0] = state->headingDeg;   // azimuth
            buffer[1] = stackPitch(state);
            buffer[2] = 0.0f;                // roll
            break;
        default:
            return false;
    }

    env->SetFloatArrayRegion(valuesArray, 0, count, buffer);
    return !env->ExceptionCheck();
}

void dispatchHook(JNIEnv *env, jclass clazz, jint handle, jfloatArray values,
                  jint accuracy, jlong timestamp) {
    int type = typeOfHandle(handle);
    if (type != 0) {
        rewriteValues(type, values, env);
    }
    if (gOrigDispatch != nullptr) {
        gOrigDispatch(env, clazz, handle, values, accuracy, timestamp);
    }
}

jboolean nextEventHook(JNIEnv *env, jclass clazz, jobject queue, jobject event) {
    if (gOrigNextEvent == nullptr) {
        return JNI_FALSE;
    }
    jboolean result = gOrigNextEvent(env, clazz, queue, event);
    if (result == JNI_TRUE && event != nullptr && gEventSensorField != nullptr) {
        jobject sensor = env->GetObjectField(event, gEventSensorField);
        if (sensor != nullptr) {
            rememberHandle(env, sensor);
            env->DeleteLocalRef(sensor);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
    return result;
}

}  // namespace

// ============================================================ Zygisk 模块

class VlocModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        // 自己的主 App 不接管（它需要读真实传感器做诊断）
        const char *process = nullptr;
        if (args != nullptr && args->nice_name != nullptr) {
            process = env->GetStringUTFChars(args->nice_name, nullptr);
        }
        skip = process != nullptr && std::strcmp(process, "com.vloc.motion") == 0;
        if (process != nullptr) {
            env->ReleaseStringUTFChars(args->nice_name, process);
        }
        if (skip) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        if (skip) {
            return;
        }
        cacheRefs(env);
        int hooked = 0;

        // 1) 记录 handle → 传感器类型
        JNINativeMethod nextEvent[] = {
                {
                        const_cast<char *>("nativeGetNextSensorEvent"),
                        const_cast<char *>("(Ljava/lang/Object;Landroid/hardware/SensorEvent;)Z"),
                        reinterpret_cast<void *>(nextEventHook)
                },
        };
        api->hookJniNativeMethods(env, "android/hardware/SystemSensorManager",
                                  nextEvent, 1);
        gOrigNextEvent = reinterpret_cast<decltype(gOrigNextEvent)>(nextEvent[0].fnPtr);
        if (gOrigNextEvent != nullptr) {
            hooked++;
        }

        // 2) 改写每帧传感器值
        JNINativeMethod dispatch[] = {
                {
                        const_cast<char *>("nativeDispatchSensorEvent"),
                        const_cast<char *>("(I[FIJ)V"),
                        reinterpret_cast<void *>(dispatchHook)
                },
        };
        api->hookJniNativeMethods(env, "android/hardware/SystemSensorManager",
                                  dispatch, 1);
        gOrigDispatch = reinterpret_cast<decltype(gOrigDispatch)>(dispatch[0].fnPtr);
        if (gOrigDispatch != nullptr) {
            hooked++;
        }

        if (hooked > 0) {
            LOG("sensor hooks installed: %d/2 (process hooked)", hooked);
        } else {
            LOG("sensor hooks NOT installed (JNI signature mismatch on this ROM)");
        }
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs *args) override {
        // system_server 不是传感器分发点（传感器走 native SensorService 到各 App），
        // 这里不注入，避免无意义的风险。
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    bool skip = false;
};

REGISTER_ZYGISK_MODULE(VlocModule)
