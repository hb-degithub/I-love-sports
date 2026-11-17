package com.vloc.motion.core;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 设置清单的完整性约束。
 *
 * <p>背景：此前 {@code accuracy} 只有读取没有界面入口（永远用默认 5m），
 * 这一组测试就是为了防止再出现「带了 key 却改不了」的设置。
 */
public class SettingsSchemaTest {

    @Test
    public void everyItemHasUniqueKeyAndTitle() {
        Set<String> keys = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (SettingsSchema.Item item : SettingsSchema.items()) {
            assertTrue("key 重复: " + item.key, keys.add(item.key));
            assertTrue("标题重复: " + item.title, titles.add(item.title));
            assertFalse("标题不能为空", item.title.trim().isEmpty());
        }
    }

    @Test
    public void allFourGroupsAreUsed() {
        for (SettingsSchema.Group group : SettingsSchema.Group.values()) {
            assertFalse("分组「" + group.label + "」下没有设置项",
                    SettingsSchema.byGroup(group).isEmpty());
        }
        assertEquals("分组总数", 4, SettingsSchema.Group.values().length);
    }

    /** 精度必须有界面可改（这是修复过的真实缺陷，不能再退化）。 */
    @Test
    public void accuracyIsConfigurableWithSaneRange() {
        SettingsSchema.Item accuracy = SettingsSchema.find("accuracy");
        assertNotNull("精度必须出现在设置清单里", accuracy);
        assertTrue("精度必须是数值项", accuracy.numeric);
        assertEquals(SettingsSchema.Group.LOCATION, accuracy.group);
        assertTrue("下限应 > 0，避免 0 精度导致的异常", accuracy.min > 0f);
        assertTrue("上限应合理（100m 以内）", accuracy.max <= 100f);
    }

    @Test
    public void clampKeepsValuesInRange() {
        SettingsSchema.Item altitude = SettingsSchema.find("base_altitude");
        assertEquals(9000f, SettingsSchema.clamp(altitude, 99999f), 1e-3);
        assertEquals(-500f, SettingsSchema.clamp(altitude, -99999f), 1e-3);
        assertEquals(50f, SettingsSchema.clamp(altitude, 50f), 1e-3);

        SettingsSchema.Item accuracy = SettingsSchema.find("accuracy");
        assertEquals(0.5f, SettingsSchema.clamp(accuracy, 0f), 1e-3);
        assertEquals(0.5f, SettingsSchema.clamp(accuracy, -5f), 1e-3);
        assertEquals(100f, SettingsSchema.clamp(accuracy, 999f), 1e-3);
    }

    @Test
    public void booleanItemsAreNotNumeric() {
        for (SettingsSchema.Item item : SettingsSchema.items()) {
            if (item.numeric) {
                assertTrue("数值项上限应大于下限: " + item.key, item.max > item.min);
            }
        }
    }

    @Test
    public void findReturnsNullForUnknownKey() {
        assertNull(SettingsSchema.find("not_a_setting"));
        assertNull(SettingsSchema.find(null));
    }

    /** 关键设置项一个都不能少。 */
    @Test
    public void requiredSettingsAreAllPresent() {
        List<String> required = java.util.Arrays.asList(
                "system_inject", "providers", "realism", "speed_variance",
                "attitude_replay", "base_altitude", "accuracy",
                "auto_stop_on_finish", "random_start", "clear_logcat_on_stop");
        for (String key : required) {
            assertNotNull("缺少设置项: " + key, SettingsSchema.find(key));
        }
        assertEquals("设置项总数（新增项必须同步改这里）", 10, SettingsSchema.items().size());
        assertEquals("required 清单必须与 items() 等长：新增项要加进 required",
                required.size(), SettingsSchema.items().size());
    }

    /**
     * 「停止时清理日志（logcat）」必须在设置清单里登记。
     *
     * <p>它是 {@code TraceCleaner.Options.clearLogcat} 的唯一主持久开关（默认关、破坏性：
     * 清空整个 logcat 缓冲区），此前长期只有 {@code RouteStore} 的读写入口而没有界面入口，
     * 正是本项目「带了 key 却改不了」那类缺陷，所以单独加一条回归约束。
     */
    @Test
    public void clearLogcatOnStopIsRegisteredAsBehaviorSwitch() {
        SettingsSchema.Item item = SettingsSchema.find("clear_logcat_on_stop");
        assertNotNull("停止时清理日志必须出现在设置清单里", item);
        assertFalse("它是开关，不是数值项", item.numeric);
        assertEquals(SettingsSchema.Group.BEHAVIOR, item.group);
    }
}
