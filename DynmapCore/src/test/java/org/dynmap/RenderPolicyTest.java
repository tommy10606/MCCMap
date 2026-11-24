package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.dynmap.common.DynmapPlayer;
import org.junit.jupiter.api.Test;

class RenderPolicyTest {
    @Test void lowTpsHoldsTenMinutesWithoutEarlyRecoveryOrRepeatedTransitions() {
        var gate=new TpsRenderGate();long start=1;
        assertTrue(gate.paused(14,15,1,false,start));
        assertTrue(gate.paused(20,15,1,false,start+TpsRenderGate.RETRY_NANOS-1));
        assertFalse(gate.paused(20,15,1,false,start+TpsRenderGate.RETRY_NANOS));
        assertTrue(gate.paused(10,15,1,false,start+TpsRenderGate.RETRY_NANOS+1));
        assertTrue(gate.paused(10,15,1,false,start+2*TpsRenderGate.RETRY_NANOS+1));
    }
    @Test void emptyServerAndExplicitBypassImmediatelyReleaseTheTpsHold() {
        var gate=new TpsRenderGate();assertTrue(gate.paused(10,15,2,false,1));
        assertFalse(gate.paused(1,15,0,false,2));assertTrue(gate.paused(1,15,1,false,3));
        assertFalse(gate.paused(1,15,1,true,4));assertTrue(gate.paused(1,15,1,false,5));
    }
    @Test void migrationChangesOnlyOldDefaultsAndRunsOnce() {
        var config=new ConfigurationNode();config.put("update-min-tps",18);config.put("fullrender-min-tps",12);config.put("zoomout-min-tps",18);
        assertTrue(TpsSettings.migrate(config));assertEquals(15,config.getDouble("update-min-tps",0));assertEquals(12,config.getDouble("fullrender-min-tps",0));
        config.put("update-min-tps",18);assertFalse(TpsSettings.migrate(config));assertEquals(18,config.getDouble("update-min-tps",0));
    }
    @Test void bypassCommandRequiresAdministrativePermission() {
        var core=new DynmapCore();core.mapManager=mock(MapManager.class);var player=mock(DynmapPlayer.class);
        core.processCommand(player,"mccmap","mccmap",new String[]{"tpscheck","off"});verify(core.mapManager,never()).setTpsBypass(anyBoolean());
        when(player.hasPrivilege("tpscheck")).thenReturn(true);
        core.processCommand(player,"mccmap","mccmap",new String[]{"tpscheck","off"});verify(core.mapManager).setTpsBypass(true);
        core.processCommand(player,"mccmap","mccmap",new String[]{"tpscheck","on"});verify(core.mapManager).setTpsBypass(false);
    }
    @Test void statsUseDistinctHeaderHealthyWarningAndErrorColors() {
        assertTrue(StatsColors.format("Storage usage:").startsWith("\u00a7b\u00a7l"));
        assertTrue(StatsColors.format("  Cap: unlimited").contains("\u00a7a"));
        assertTrue(StatsColors.format("  Storage pause: paused").contains("\u00a7e"));
        assertTrue(StatsColors.format("  Scan: failed").contains("\u00a7c"));
    }
}
