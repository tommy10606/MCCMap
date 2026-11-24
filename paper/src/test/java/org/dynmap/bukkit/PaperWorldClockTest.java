package org.dynmap.bukkit;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import org.bukkit.World;
import io.papermc.paper.world.WorldClock;
import org.dynmap.bukkit.helper.PaperWorldClock;
import org.junit.jupiter.api.Test;

class PaperWorldClockTest {
    private static final class Fixture {
        WorldClock clock = (WorldClock)Proxy.newProxyInstance(WorldClock.class.getClassLoader(), new Class<?>[]{WorldClock.class}, (proxy, method, args) -> null);
        long ticks; float rate = 1; boolean paused;
        final World world = (World)Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getDefaultClock" -> clock;
                case "getFullTime" -> { assertNotNull(clock); assertEquals(1, args.length); assertSame(clock, args[0]); yield ticks; }
                case "getClockRate" -> rate;
                case "isClockPaused" -> paused;
                default -> throw new AssertionError("Unexpected clock call: " + method.getName());
            };
        });
    }
    @Test void usesTheWorldsActualDefaultClockAndNormalizesTheWebsiteDial() {
        var f = new Fixture(); f.ticks = 30000;
        assertEquals(6000, PaperWorldClock.time(f.world));
        f.ticks = -1000;
        assertEquals(23000, PaperWorldClock.time(f.world));
    }
    @Test void missingClockOrUnloadedWorldHasNoTimeAndNoInterpolation() {
        var f = new Fixture(); f.clock = null;
        assertEquals(-1, PaperWorldClock.time(f.world)); assertEquals(0, PaperWorldClock.rate(f.world));
        assertEquals(-1, PaperWorldClock.time(null)); assertEquals(0, PaperWorldClock.rate(null));
    }
    @Test void clockPauseAndRateControlReadOnlyWebsiteInterpolation() {
        var f = new Fixture(); f.rate = 0.5f; assertEquals(0.5, PaperWorldClock.rate(f.world));
        f.rate = 2; assertEquals(2, PaperWorldClock.rate(f.world));
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1}) { f.rate = bad; assertEquals(0, PaperWorldClock.rate(f.world)); }
        f.rate = 1; f.paused = true; assertEquals(0, PaperWorldClock.rate(f.world));
    }
}
