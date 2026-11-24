package org.dynmap;

import java.util.concurrent.TimeUnit;

/** Full/radius-render pacing against immutable, pre-multiplier render settings. */
final class FullRenderSpeedPolicy {
    private final int baselineParallel, onlineParallel;
    private final long baselineSliceNanos;
    private final double offlinePercent, onlinePercent;
    private double fractionalWorkers;
    private long finished, elapsed;
    private int rendered;
    private boolean sampled;

    FullRenderSpeedPolicy(int parallel, long sliceMillis, double offline, double online) {
        this(parallel,sliceMillis,offline,online,Math.max(1,parallel));
    }
    FullRenderSpeedPolicy(int parallel,long sliceMillis,double offline,double online,int onlineWorkers) {
        baselineParallel = Math.max(1, parallel);
        onlineParallel = Math.max(1,Math.min(baselineParallel,onlineWorkers));
        baselineSliceNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, sliceMillis));
        offlinePercent = percent(offline, 125);
        onlinePercent = percent(online, 25);
    }
    static double percent(double value, double fallback) {
        return Double.isFinite(value) && value >= 1 && value <= 200 ? value : fallback;
    }
    static int maximumParallelism(int parallel, double offline) {
        return (int)Math.min(Integer.MAX_VALUE - 3L,
            Math.ceil(Math.max(1, parallel) * Math.max(100, percent(offline, 125)) / 100));
    }
    int nextBatchSize(boolean playersOnline) {
        if (playersOnline || offlinePercent <= 100) { fractionalWorkers = 0; return playersOnline ? onlineParallel : baselineParallel; }
        double target = baselineParallel * offlinePercent / 100;
        int count = (int)Math.floor(target);
        fractionalWorkers += target - count;
        if (fractionalWorkers >= 1) { count++; fractionalWorkers--; }
        return Math.max(1, count);
    }
    void recordBatch(long elapsedNanos, int count, long finishedNanos) {
        elapsed = Math.max(1, elapsedNanos); rendered = Math.max(1, count);
        finished = finishedNanos; sampled = true;
    }
    long remainingDelayNanos(long now, boolean playersOnline) {
        if (!sampled) return 0;
        // A deliberately smaller online batch represents only part of the original
        // baseline. Count it proportionally so the worker cap does not throttle twice.
        double fraction = playersOnline && onlineParallel < baselineParallel
            ? rendered / (double)baselineParallel : Math.max(1, rendered / (double)baselineParallel);
        double percent = playersOnline ? onlinePercent : offlinePercent;
        double period;
        if (playersOnline || percent <= 100) {
            // Also account for an expanded batch that finished just as a player joined.
            period = Math.max(elapsed, baselineSliceNanos) * fraction * 100 / percent;
        } else {
            // Parallel headroom supplies the CPU-bound boost; do not multiply it again.
            period = Math.max(elapsed, baselineSliceNanos * fraction) * 100 / percent;
        }
        long cooldown = (long)Math.max(0, Math.min(Long.MAX_VALUE / 4.0, Math.ceil(period - elapsed)));
        return Math.max(0, cooldown - Math.max(0, now - finished));
    }
}
