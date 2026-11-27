package com.vloc.motion.hidden;

/** AOSP Android 8–11 GnssStatusListenerHelper callback format. */
final class LegacyGnssStatus {
    private LegacyGnssStatus() {}

    static boolean matches(Object[] args, boolean android11) {
        if (args == null || args.length != (android11 ? 7 : 6)
                || !(args[0] instanceof Integer) || !(args[1] instanceof int[])) {
            return false;
        }
        for (int i = 2; i < args.length; i++) {
            if (!(args[i] instanceof float[])) {
                return false;
            }
        }
        return true;
    }

    static int pack(boolean android11, int svid, int constellation,
                    boolean ephemeris, boolean almanac, boolean usedInFix, boolean carrier) {
        final int constellationShift = android11 ? 8 : 4;
        final int svidShift = constellationShift + 4;
        int flags = (ephemeris ? 1 : 0) | (almanac ? 2 : 0)
                | (usedInFix ? 4 : 0) | (carrier ? 8 : 0);
        return (svid << svidShift) | ((constellation & 0xf) << constellationShift) | flags;
    }
}
