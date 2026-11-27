package org.dynmap;

import java.util.concurrent.*;
import java.util.function.*;

/** One shared heartbeat for background work, independent of task count and status queries. */
final class BackgroundTaskLog implements AutoCloseable {
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task->{var t=new Thread(task,"MCCMap background status");t.setDaemon(true);return t;});
    private final Supplier<String> snapshot;
    private final Consumer<String> output;
    private final LongSupplier clock;
    private final long interval;
    private long last;
    private boolean closed;
    BackgroundTaskLog(ConfigurationNode config,Supplier<String> snapshot) {
        this(config,snapshot,Log::info,System::nanoTime,true);
    }
    BackgroundTaskLog(ConfigurationNode config,Supplier<String> snapshot,Consumer<String> output,LongSupplier clock,boolean schedule) {
        this.snapshot=snapshot;this.output=output;this.clock=clock;
        int minutes=config.getInteger("background-task-log-interval-minutes",30);
        interval=minutes<=0?0:TimeUnit.MINUTES.toNanos(Math.max(1,Math.min(1440,minutes)));
        last=clock.getAsLong();
        if(schedule&&interval>0)timer.scheduleWithFixedDelay(this::poll,interval,interval,TimeUnit.NANOSECONDS);
    }
    synchronized void poll() {
        if(closed||interval==0)return;
        long now=clock.getAsLong();if(now-last<interval)return;
        // Advance before gathering state so errors and long delays cannot create catch-up bursts.
        last=now;
        try {
            String text="Background tasks: "+snapshot.get();
            text=text.replaceAll("[\\p{Cc}\\p{Z}\\s]+"," ").trim();
            if(text.length()>1400)text=text.substring(0,1397)+"...";
            output.accept(text);
        }catch(RuntimeException failure){Log.warning("Could not collect background task status; rendering remains enabled");}
    }
    @Override public synchronized void close(){closed=true;timer.shutdownNow();}
}
