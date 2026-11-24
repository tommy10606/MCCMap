package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
class FullRenderSpeedPolicyTest {
    private static final long MS=1_000_000;
    @Test void emptyServerUsesOneHundredTwentyFivePercentOfOriginalTimeslice() {
        var policy=new FullRenderSpeedPolicy(1,1000,125,50);
        policy.recordBatch(100*MS,1,100*MS);
        assertEquals(700*MS,policy.remainingDelayNanos(100*MS,false));
    }
    @Test void onlineSpeedIsHalfOriginalRatherThanHalfBoostedSpeed() {
        var policy=new FullRenderSpeedPolicy(1,1000,125,50);
        policy.recordBatch(100*MS,1,100*MS);
        assertEquals(1900*MS,policy.remainingDelayNanos(100*MS,true));
        assertEquals(700*MS,policy.remainingDelayNanos(100*MS,false));
    }
    @Test void cpuBoundOnlineRendersWaitForEqualWorkTime() {
        var policy=new FullRenderSpeedPolicy(4,0,125,50);
        policy.recordBatch(100*MS,4,100*MS);
        assertEquals(100*MS,policy.remainingDelayNanos(100*MS,true));
        assertEquals(0,policy.remainingDelayNanos(100*MS,false));
    }
    @Test void fractionalOfflineParallelismAddsExactlyOneQuarterOverFourBatches() {
        var policy=new FullRenderSpeedPolicy(1,0,125,50);
        assertEquals(1,policy.nextBatchSize(false));assertEquals(1,policy.nextBatchSize(false));
        assertEquals(1,policy.nextBatchSize(false));assertEquals(2,policy.nextBatchSize(false));
        assertEquals(2,FullRenderSpeedPolicy.maximumParallelism(0,125));
        var parallel=new FullRenderSpeedPolicy(4,0,125,50);assertEquals(5,parallel.nextBatchSize(false));
    }
    @Test void joiningResetsBoostHeadroomAndAccountsForAnAlreadyExpandedBatch() {
        var policy=new FullRenderSpeedPolicy(1,1000,125,50);
        for(int i=0;i<3;i++)policy.nextBatchSize(false);
        assertEquals(1,policy.nextBatchSize(true));assertEquals(1,policy.nextBatchSize(false));
        policy.recordBatch(100*MS,2,100*MS);
        assertEquals(3900*MS,policy.remainingDelayNanos(100*MS,true));
    }
    @Test void idleOrPauseTimeCannotAccumulateABurstBudget() {
        var policy=new FullRenderSpeedPolicy(1,1000,125,50);
        assertEquals(0,policy.remainingDelayNanos(0,true));
        policy.recordBatch(100*MS,1,100*MS);assertEquals(0,policy.remainingDelayNanos(10000*MS,true));
        policy.recordBatch(100*MS,1,10100*MS);assertEquals(1900*MS,policy.remainingDelayNanos(10100*MS,true));
    }
    @Test void presenceChangesAreForwardedFromTheCoreWithoutTouchingStaleSettings() {
        var core=new DynmapCore();core.mapManager=mock(MapManager.class);
        core.applyRenderingPauses(1);verify(core.mapManager).updateRenderPlayerCount(1);
        core.applyRenderingPauses(0);verify(core.mapManager).updateRenderPlayerCount(0);
        assertEquals(900*MS,StaleTileRefreshSettings.cooldownNanos(100*MS,0,1,10));
    }
    @Test void partialFinalBatchesKeepTheOriginalTimesliceBaseline() {
        var policy=new FullRenderSpeedPolicy(4,1000,125,50);
        policy.recordBatch(100*MS,1,100*MS);
        assertEquals(1900*MS,policy.remainingDelayNanos(100*MS,true));
        assertEquals(700*MS,policy.remainingDelayNanos(100*MS,false));
    }
    @Test void invalidMultipliersUseSafeDefaults() {
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-1,201})assertEquals(125,FullRenderSpeedPolicy.percent(bad,125));
    }
    @Test void onlineWorkerLimitReducesBurstsWithoutMultiplyingTheThroughputReduction() {
        var manual=new FullRenderSpeedPolicy(2,0,125,25,1);
        assertEquals(1,manual.nextBatchSize(true));manual.recordBatch(100*MS,1,100*MS);
        assertEquals(100*MS,manual.remainingDelayNanos(100*MS,true)); // 1 tile / 200ms = 25% of 20/sec baseline.
        var natural=new FullRenderSpeedPolicy(2,0,125,10,1);natural.recordBatch(100*MS,1,100*MS);
        assertEquals(400*MS,natural.remainingDelayNanos(100*MS,true));
        manual.recordBatch(100*MS,3,100*MS);assertEquals(500*MS,manual.remainingDelayNanos(100*MS,true)); // Joined during boosted work.
        assertEquals(2,manual.nextBatchSize(false));assertEquals(3,manual.nextBatchSize(false));
    }
    @Test void oneWorkerBaselineUsesTheNewQuarterSpeedAndPreservesTheOfflineBoost() {
        var policy=new FullRenderSpeedPolicy(1,0,125,25,1);policy.recordBatch(100*MS,1,100*MS);
        assertEquals(300*MS,policy.remainingDelayNanos(100*MS,true));assertEquals(0,policy.remainingDelayNanos(100*MS,false));
        assertEquals(900*MS,StaleTileRefreshSettings.cooldownNanos(100*MS,0,1,10));
    }

}
