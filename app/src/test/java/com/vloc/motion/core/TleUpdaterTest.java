package com.vloc.motion.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * {@link TleUpdater} 的纯逻辑测试：校验规则 / 历元年龄 / 原子写。
 *
 * <p>不覆盖网络拉取与 {@code Context} 读写（需要真机或 Robolectric）：
 * 那两条路径由 {@code TleUpdater#fetchGroup} / {@code #load} 承担，属于集成验证范围。
 */
public class TleUpdaterTest {

    private static final long DAY_MS = 86400000L;
    /** 一个固定的"现在"：2026-11-17T00:00:00Z，避免测试随运行时间漂移。 */
    private static final long NOW = 1794873600000L;

    @Test
    public void ageDays_countsWholeDays() {
        assertEquals(0, TleUpdater.ageDays(NOW, NOW));
        assertEquals(20, TleUpdater.ageDays(NOW - 20 * DAY_MS, NOW));
        assertEquals(45, TleUpdater.ageDays(NOW - 45 * DAY_MS, NOW));
        assertEquals(-1, TleUpdater.ageDays(0L, NOW));
    }

    @Test
    public void validate_acceptsFreshCatalog() {
        final TleUpdater.Result result = TleUpdater.validate(catalog(24, "26321.50000000"), NOW);
        assertTrue(result.error, result.ok);
        assertEquals(24, result.satCount);
        assertEquals(0, result.ageDays);
    }

    @Test
    public void validate_rejectsEmptyOrGarbage() {
        assertFalse(TleUpdater.validate("", NOW).ok);
        assertFalse(TleUpdater.validate("<html>rate limited</html>", NOW).ok);
        assertNotNull(TleUpdater.validate("", NOW).error);
    }

    @Test
    public void validate_rejectsTooFewSatellites() {
        final TleUpdater.Result result = TleUpdater.validate(catalog(19, "26321.50000000"), NOW);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("19"));
    }

    @Test
    public void validate_rejectsEpochBefore2020() {
        final TleUpdater.Result result = TleUpdater.validate(catalog(24, "15001.50000000"), NOW);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("历元过早"));
    }

    @Test
    public void validate_rejectsEpochTooFarInFuture() {
        // 2026-12-16（NOW + 29 天）超出"现在 + 7 天"
        final TleUpdater.Result result = TleUpdater.validate(catalog(24, "26350.00000000"), NOW);
        assertFalse(result.ok);
        assertTrue(result.error, result.error.contains("历元超前"));
    }

    @Test
    public void formatEpochUtc_handlesMissingEpoch() {
        assertEquals("未知", TleUpdater.formatEpochUtc(0L));
        assertTrue(TleUpdater.formatEpochUtc(NOW).endsWith("Z"));
    }

    @Test
    public void shouldRemind_onlyWhenStaleAndNotRemindedInSevenDays() {
        // 不超过 30 天：不提醒
        assertFalse(TleUpdater.shouldRemind(TleUpdater.STALE_DAYS, 0L, NOW));
        // 过期且从未提醒：提醒
        assertTrue(TleUpdater.shouldRemind(TleUpdater.STALE_DAYS + 1, 0L, NOW));
        // 6 天前提醒过：不提醒
        assertFalse(TleUpdater.shouldRemind(TleUpdater.STALE_DAYS + 1, NOW - 6 * DAY_MS, NOW));
        // 满 7 天：再次提醒
        assertTrue(TleUpdater.shouldRemind(TleUpdater.STALE_DAYS + 1, NOW - 7 * DAY_MS, NOW));
    }

    @Test
    public void writeAtomic_replacesFileWithoutLeavingTemp() throws Exception {
        final File dir = Files.createTempDirectory("tle-test").toFile();
        final File target = new File(dir, "gnss_tle.txt");
        Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));

        final String fresh = catalog(24, "26321.50000000");
        TleUpdater.writeAtomic(target, fresh);

        assertEquals(fresh, new String(Files.readAllBytes(target.toPath()),
                StandardCharsets.UTF_8));
        assertFalse(new File(dir, "gnss_tle.txt.tmp").exists());
        assertEquals(24, GnssTle.parse(fresh).size());
    }

    /** 造一份 {@code count} 颗星的合法 TLE（GPS 命名，历元字段可指定）。 */
    private static String catalog(int count, String epoch14) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            final int norad = 26407 + i;
            sb.append("GPS TEST ").append(i + 1).append('\n');
            sb.append(String.format(Locale.US,
                    "1 %05dU 98067A   %s  .00000033  00000+0  00000+0 0  9990",
                    norad, epoch14)).append('\n');
            sb.append(String.format(Locale.US,
                    "2 %05d %8.4f %8.4f %07d %8.4f %8.4f %11.8f",
                    norad, 55.0 + i * 0.1, 120.0 + i, 1000 + i, 45.0 + i, 90.0 + i,
                    2.00558680)).append('\n');
        }
        return sb.toString();
    }
}
