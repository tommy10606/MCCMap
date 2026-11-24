package org.dynmap.bukkit.helper;

import org.bukkit.World;

/** Read-only bridge to Paper's per-world clocks; website time uses a 24,000-tick dial. */
public final class PaperWorldClock {
    private PaperWorldClock() {}

    public static long time(World world) {
        if (world == null) return -1;
        var clock = world.getDefaultClock();
        return clock == null ? -1 : Math.floorMod(world.getFullTime(clock), 24000L);
    }

    public static double rate(World world) {
        if (world == null) return 0;
        var clock = world.getDefaultClock();
        if (clock == null || world.isClockPaused(clock)) return 0;
        double rate = world.getClockRate(clock);
        return Double.isFinite(rate) && rate >= 0 ? rate : 0;
    }
}
