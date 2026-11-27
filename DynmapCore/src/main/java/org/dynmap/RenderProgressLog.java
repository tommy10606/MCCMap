package org.dynmap;

import java.util.concurrent.TimeUnit;

/** Time-based limit for unsolicited progress; on-demand status never consumes this budget. */
final class RenderProgressLog {
    private long last;
    private boolean sent;
    boolean due(long now,long intervalNanos) {
        if(intervalNanos<=0)return false;
        if(sent&&now-last<intervalNanos)return false;
        sent=true;last=now;return true;
    }
    static long interval(ConfigurationNode config) {
        int seconds=config.getInteger("render-progress-log-interval-seconds",60);
        return seconds<=0?0:TimeUnit.SECONDS.toNanos(Math.max(5,Math.min(3600,seconds)));
    }
}
