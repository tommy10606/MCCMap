package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
class StaleTileRefreshSettingsTest {
    private ConfigurationNode config(Map<String,Object> settings) {
        var config=new ConfigurationNode();config.put("stale-tile-refresh",new LinkedHashMap<>(settings));return config;
    }
    @Test void existingDefaultMigratesToSixtyDaysAndTenPercentOnce() {
        var config=config(Map.of("max-age-days",30,"tiles-per-minute",4,"enabled",false));
        assertTrue(StaleTileRefreshSettings.migrate(config));
        assertEquals(60,config.getInteger("stale-tile-refresh/max-age-days",0));
        assertEquals(10,config.getInteger("stale-tile-refresh/render-speed-percent",0));
        assertFalse(config.getBoolean("stale-tile-refresh/enabled",true));
        assertFalse(config.getNode("stale-tile-refresh").containsKey("tiles-per-minute"));
        assertFalse(StaleTileRefreshSettings.migrate(config));
    }
    @Test void explicitCustomAgesAndNewPacingChoicesSurviveMigration() {
        var custom=config(Map.of("max-age-days",90,"tiles-per-minute",20));
        StaleTileRefreshSettings.migrate(custom);assertEquals(90,custom.getInteger("stale-tile-refresh/max-age-days",0));
        var configured=config(Map.of("max-age-days",30,"render-speed-percent",5));
        assertFalse(StaleTileRefreshSettings.migrate(configured));assertEquals(30,configured.getInteger("stale-tile-refresh/max-age-days",0));
        assertEquals(5,configured.getInteger("stale-tile-refresh/render-speed-percent",0));
    }
    @Test void absentSettingsUseNewDefaultsAndExactSixtyDayBoundary() {
        var config=new ConfigurationNode();StaleTileRefreshSettings.migrate(config);
        long age=TimeUnit.DAYS.toMillis(config.getInteger("stale-tile-refresh/max-age-days",0)),now=TimeUnit.DAYS.toMillis(100);
        assertEquals(TimeUnit.DAYS.toMillis(60),age);
        assertTrue(StaleTileRefresh.stale(now-age,now,age));assertFalse(StaleTileRefresh.stale(now-age+1,now,age));
        assertFalse(StaleTileRefresh.stale(now-TimeUnit.DAYS.toMillis(31),now,age));
    }
    @Test void serialTenPercentAddsNineTimesTheMeasuredRenderWork() {
        long work=TimeUnit.MILLISECONDS.toNanos(100);
        assertEquals(work*9,StaleTileRefreshSettings.cooldownNanos(work,0,0,10));
        assertEquals(work*9,StaleTileRefreshSettings.cooldownNanos(work,0,1,10));
    }
    @Test void parallelFullRenderAndConfiguredTimesliceDetermineNormalThroughput() {
        long ms=TimeUnit.MILLISECONDS.toNanos(1);
        assertEquals(150*ms,StaleTileRefreshSettings.cooldownNanos(100*ms,0,4,10));
        assertEquals(400*ms,StaleTileRefreshSettings.cooldownNanos(100*ms,200*ms,4,10));
        assertEquals(1900*ms,StaleTileRefreshSettings.cooldownNanos(100*ms,200*ms,1,10));
        assertEquals(0,StaleTileRefreshSettings.cooldownNanos(100*ms,0,20,10));
    }
    @Test void invalidSpeedsAreSafeAndArithmeticIsBounded() {
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-5,101})assertEquals(10,StaleTileRefreshSettings.speedPercent(bad));
        assertEquals(0,StaleTileRefreshSettings.cooldownNanos(100,0,1,100));
        assertTrue(StaleTileRefreshSettings.cooldownNanos(Long.MAX_VALUE,Long.MAX_VALUE,1,1)>=0);
    }
}
