package com.vloc.motion.hidden.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯反射实现的 {@code XposedHelpers} 等价物。
 *
 * <p><b>为什么需要它</b>：新 API（{@code io.github.libxposed.api}）只提供
 * {@code hook / getInvoker / getRemotePreferences / log}，<b>没有</b>
 * {@code findClass / callMethod / getObjectField} 这一层助手（`javap` 已确认：
 * {@code XposedInterface} 公开方法里只有 hook 与 invoker）。
 * 现有 hidden/*.java 里有 {@code XposedHelpers.findClass(13) / callStaticMethod(12) /
 * callMethod(9) / findFieldIfExists(4) / findMethodExactIfExists(2) / newInstance(1)}，
 * 这些在新后端下只能落到纯反射。
 *
 * <p>方法名与 {@code XposedHelpers} 对齐，行为差异写在各自注释里；不做缓存
 * （现有代码全是“找到就用”的低频路径，热路径不调这些）。
 */
final class Reflect {

    private Reflect() {
    }

    static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        if (className == null) {
            throw new ClassNotFoundException("null");
        }
        return Class.forName(className, false, classLoader);
    }

    static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 等价 {@code XposedHelpers.findMethodExact(Class, String, Class...)}：只查本类声明。 */
    static Method findMethodExact(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            final Method m = clazz.getDeclaredMethod(name, parameterTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#" + name + describe(parameterTypes));
        }
    }

    static Method findMethodExactIfExists(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            return findMethodExact(clazz, name, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 沿继承链找同名字段（等价 {@code XposedHelpers.findFieldIfExists} 的 null 语义）。 */
    static Field findFieldIfExists(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                final Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    static Object callMethod(Object obj, String name, Object... args) {
        if (obj == null) {
            throw new NullPointerException("callMethod: thisObject == null (" + name + ")");
        }
        final Method m = findMethodBestMatch(obj.getClass(), name, args, false);
        return invoke(m, obj, args);
    }

    static Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        final Method m = findMethodBestMatch(clazz, name, args, true);
        return invoke(m, null, args);
    }

    static Object newInstance(Class<?> clazz, Object... args) {
        final Constructor<?> best = findConstructorBestMatch(clazz, args);
        try {
            best.setAccessible(true);
            return best.newInstance(args);
        } catch (InvocationTargetException e) {
            throw wrap(e.getCause());
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    // ---------------- 内部 ----------------

    private static Object invoke(Method m, Object receiver, Object[] args) {
        try {
            m.setAccessible(true);
            return m.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            throw wrap(e.getCause());
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    /** 与 {@code XposedHelpers.InvocationTargetError} 等价：把受检异常包成运行时。 */
    private static RuntimeException wrap(Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        return new IllegalStateException(t);
    }

    private static Method findMethodBestMatch(Class<?> clazz, String name, Object[] args,
                                              boolean staticPreferred) {
        final Object[] safeArgs = args == null ? new Object[0] : args;
        Method fallback = null;
        Class<?> current = clazz;
        while (current != null) {
            for (Method m : current.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                if (staticPreferred != java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                if (!isAssignable(m.getParameterTypes(), safeArgs)) {
                    continue;
                }
                // 优先参数个数完全一致的（避免 varargs 抢走更精确的匹配）
                if (m.getParameterCount() == safeArgs.length || m.isVarArgs()) {
                    return m;
                }
                if (fallback == null) {
                    fallback = m;
                }
            }
            current = current.getSuperclass();
        }
        if (fallback != null) {
            return fallback;
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + name + " with " + safeArgs.length + " arg(s)");
    }

    private static Constructor<?> findConstructorBestMatch(Class<?> clazz, Object[] args) {
        final Object[] safeArgs = args == null ? new Object[0] : args;
        final List<Constructor<?>> candidates = new ArrayList<>();
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (isAssignable(c.getParameterTypes(), safeArgs)) {
                if (c.getParameterCount() == safeArgs.length) {
                    return c;
                }
                candidates.add(c);
            }
        }
        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }
        throw new NoSuchMethodError(clazz.getName() + ".<init> with " + safeArgs.length + " arg(s)");
    }

    private static boolean isAssignable(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            // varargs 场景：允许把尾部多参数折叠，这里不做，交回调用方
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            final Object a = args[i];
            if (a == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!box(types[i]).isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static String describe(Class<?>[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return "()";
        }
        final StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameterTypes[i] == null ? "null" : parameterTypes[i].getName());
        }
        return sb.append(')').toString();
    }
}
