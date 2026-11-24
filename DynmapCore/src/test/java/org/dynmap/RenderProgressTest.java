package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RenderProgressTest {
    @Test void throughputIncludesPacingAndFormatsRemainingTime() {
        var progress = new RenderProgress();
        progress.sample(0, 0, false);
        assertTrue(progress.describe(0, 100, 0, null).contains("ETA calculating"));
        progress.sample(25, 5000, false);
        assertTrue(progress.describe(25, 100, 5000, null).contains("~25.0%"));
        assertTrue(progress.describe(25, 100, 5000, null).contains("~15s remaining"));
    }
    @Test void pausesRestartAndPresenceChangesRequireNewRateSamples() {
        var progress = new RenderProgress();
        progress.sample(0, 0, false); progress.sample(25, 5000, false);
        assertTrue(progress.describe(25, 100, 5000, "low TPS").contains("ETA paused (low TPS)"));
        progress.reset(); progress.sample(25, 1000000, false);
        assertTrue(progress.describe(25, 100, 1000000, null).contains("ETA calculating"));
        progress.sample(50, 1005000, false);
        progress.sample(50, 1005001, true);
        assertTrue(progress.describe(50, 100, 1005001, null).contains("ETA calculating"));
        progress.sample(55, 1010001, true);
        assertTrue(progress.describe(55, 100, 1010001, null).contains("~45s remaining"));
        assertTrue(new RenderProgress().describe(55, 100, 0, null).contains("ETA calculating"));
    }
    @Test void unknownTotalsAndUnderestimatesDoNotInventCompletion() {
        var progress = new RenderProgress();
        assertTrue(progress.describe(100, 0, 0, null).contains("percentage unavailable; ETA unavailable"));
        assertTrue(progress.describe(120, 100, 0, null).contains("~99.9%"));
        assertFalse(progress.describe(120, 100, 0, null).contains("0s remaining"));
        assertEquals("2d 1h", RenderProgress.duration(176400));
        assertEquals("1h 1m", RenderProgress.duration(3660));
    }
}
