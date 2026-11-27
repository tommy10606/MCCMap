package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class BackgroundTaskLogTest {
    @Test void oneSharedLinePerThirtyMinutesWithoutCatchUpBursts() {
        var clock=new AtomicLong();var lines=new ArrayList<String>();var current=new AtomicReference<>("natural active | stale paused | cleanup running");
        try(var log=new BackgroundTaskLog(new ConfigurationNode(),current::get,lines::add,clock::get,false)) {
            log.poll();assertTrue(lines.isEmpty());
            for(int minute=1;minute<30;minute++){clock.set(TimeUnit.MINUTES.toNanos(minute));log.poll();}
            assertTrue(lines.isEmpty());clock.set(TimeUnit.MINUTES.toNanos(30));log.poll();
            assertEquals(List.of("Background tasks: "+current.get()),lines);
            // Reading current status never consumes another heartbeat or resets the interval.
            for(int i=0;i<100;i++){current.get();log.poll();}assertEquals(1,lines.size());
            current.set("all idle");clock.set(TimeUnit.HOURS.toNanos(3));log.poll();log.poll();
            assertEquals(2,lines.size());assertEquals("Background tasks: all idle",lines.getLast());
        }
    }
    @Test void intervalCanBeChangedOrDisabledAndShutdownStopsOutput() {
        var config=new ConfigurationNode();config.put("background-task-log-interval-minutes",5);
        var clock=new AtomicLong();var lines=new ArrayList<String>();var log=new BackgroundTaskLog(config,()->"idle",lines::add,clock::get,false);
        clock.set(TimeUnit.MINUTES.toNanos(5));log.poll();assertEquals(1,lines.size());log.close();
        clock.set(TimeUnit.HOURS.toNanos(1));log.poll();assertEquals(1,lines.size());
        config.put("background-task-log-interval-minutes",0);
        try(var disabled=new BackgroundTaskLog(config,()->{fail("disabled logging must not collect status");return "";},lines::add,clock::get,false)) {
            clock.addAndGet(TimeUnit.DAYS.toNanos(2));disabled.poll();assertEquals(1,lines.size());
        }
    }
    @Test void summaryIsOnePhysicalLineAndCannotGrowWithoutBound() {
        var clock=new AtomicLong();var lines=new ArrayList<String>();
        try(var log=new BackgroundTaskLog(new ConfigurationNode(),()->"natural\nactive\r\ncleanup\t\u2028"+"x".repeat(3000),lines::add,clock::get,false)) {
            clock.set(TimeUnit.MINUTES.toNanos(30));log.poll();assertEquals(1,lines.size());
            assertFalse(lines.getFirst().contains("\n"));assertFalse(lines.getFirst().contains("\r"));assertFalse(lines.getFirst().contains("\u2028"));assertTrue(lines.getFirst().length()<=1400);
        }
    }
}
