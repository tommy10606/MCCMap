package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
class RenderProgressLogTest {
    @Test void progressIsTimeLimitedEvenWhenTilesFinishQuickly() {
        var log=new RenderProgressLog();long minute=TimeUnit.MINUTES.toNanos(1);
        assertTrue(log.due(100,minute));
        for(int i=1;i<6000;i++)assertFalse(log.due(100+TimeUnit.MILLISECONDS.toNanos(i*10L),minute));
        assertTrue(log.due(100+minute,minute));assertFalse(log.due(100+minute+1,minute));
    }
    @Test void configurationCanDisableProgressAndDefaultsToOneMinute() {
        var config=new ConfigurationNode();assertEquals(TimeUnit.MINUTES.toNanos(1),RenderProgressLog.interval(config));
        config.put("render-progress-log-interval-seconds",0);assertEquals(0,RenderProgressLog.interval(config));assertFalse(new RenderProgressLog().due(1000,0));
    }
}
