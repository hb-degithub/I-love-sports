package com.vloc.motion.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置项清单（单一事实源）：每个可配置项属于哪一组、默认值、合法范围、是否在界面上有入口。
 *
 * <p>存在的意义：之前出现过 {@code accuracy} 只有读取、**从来没有界面可以设置**的情况
 * （永远用默认 5m），以及同一个开关在两个弹窗里重复出现。把清单集中在这里，
 * 就能用单元测试守住「每个设置项都必须有界面入口」。
 */
public final class SettingsSchema {

    /** 设置分组（与 dialog_settings.xml 的四个小标题一一对应）。 */
    public enum Group {
        /** 注入方式：系统级注入开关、接管的 Provider。 */
        INJECTION("注入方式"),
        /** 拟真与反检测：拟真、速度混淆、姿态伪造。 */
        REALISM("拟真与反检测"),
        /** 定位参数：基础海拔、水平精度。 */
        LOCATION("定位参数"),
        /** 行为：到达自动停止、随机起点。 */
        BEHAVIOR("行为");

        public final String label;

        Group(String label) {
            this.label = label;
        }
    }

    /** 一个设置项的定义。 */
    public static final class Item {
        public final String key;
        public final Group group;
        public final String title;
        /** 数值项的最小/最大值（仅数值项有效）。 */
        public final float min;
        public final float max;
        public final boolean numeric;

        Item(String key, Group group, String title, boolean numeric, float min, float max) {
            this.key = key;
            this.group = group;
            this.title = title;
            this.numeric = numeric;
            this.min = min;
            this.max = max;
        }
    }

    private SettingsSchema() {
    }

    /** 所有应当出现在「设置」界面里的项。 */
    public static List<Item> items() {
        List<Item> list = new ArrayList<>();
        // 注入方式
        list.add(new Item("system_inject", Group.INJECTION,
                "系统级注入（所有 App 生效、无模拟标记）", false, 0, 0));
        list.add(new Item("providers", Group.INJECTION,
                "接管的定位 Provider（gps/network/fused）", false, 0, 0));
        // 拟真与反检测
        list.add(new Item("realism", Group.REALISM, "拟真模式", false, 0, 0));
        list.add(new Item("speed_variance", Group.REALISM, "速度混淆", false, 0, 0));
        list.add(new Item("attitude_replay", Group.REALISM, "伪造姿态传感器", false, 0, 0));
        // 定位参数
        list.add(new Item("base_altitude", Group.LOCATION, "基础海拔（米）", true, -500, 9000));
        list.add(new Item("accuracy", Group.LOCATION, "水平定位精度（米）", true, 0.5f, 100f));
        // 行为
        list.add(new Item("auto_stop_on_finish", Group.BEHAVIOR,
                "到达终点后自动停止", false, 0, 0));
        list.add(new Item("random_start", Group.BEHAVIOR, "每次开始随机更换起点", false, 0, 0));
        return list;
    }

    /** 把数值夹到该项的合法范围内（非数值项原样返回）。 */
    public static float clamp(Item item, float value) {
        if (item == null || !item.numeric) {
            return value;
        }
        return Math.max(item.min, Math.min(item.max, value));
    }

    /** 按 key 查找设置项；找不到返回 null。 */
    public static Item find(String key) {
        for (Item item : items()) {
            if (item.key.equals(key)) {
                return item;
            }
        }
        return null;
    }

    /** 某一组下的所有项。 */
    public static List<Item> byGroup(Group group) {
        List<Item> list = new ArrayList<>();
        for (Item item : items()) {
            if (item.group == group) {
                list.add(item);
            }
        }
        return list;
    }
}
