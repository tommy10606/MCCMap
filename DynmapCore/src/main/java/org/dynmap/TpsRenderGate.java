package org.dynmap;

/** A low TPS sample holds rendering for ten minutes without sampling again. */
final class TpsRenderGate {
    static final long RETRY_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
    private long retryAt;
    boolean paused(double tps, double minimum, int players, boolean bypass, long now) {
        if (players == 0 || bypass) { retryAt = 0; return false; }
        if (retryAt != 0 && now - retryAt < 0) return true;
        retryAt = tps < minimum ? now + RETRY_NANOS : 0;
        return retryAt != 0;
    }
}
