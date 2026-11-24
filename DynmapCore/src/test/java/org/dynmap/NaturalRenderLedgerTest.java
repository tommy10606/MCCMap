package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NaturalRenderLedgerTest {
    private DynmapWorld world(String name) {
        return mock(DynmapWorld.class, withSettings().useConstructor(name, 320, 63, -64).defaultAnswer(CALLS_REAL_METHODS));
    }
    private MapType map(String name, String prefix) {
        var map = mock(MapType.class); when(map.getName()).thenReturn(name); when(map.getPrefix()).thenReturn(prefix); return map;
    }
    @Test void completedMapsSurviveRestartAndNewLayoutsBecomeEligible() {
        var world = world("world"); var surface = map("surface", "surface_v1"); var cave = map("cave", "cave"); world.maps.addAll(List.of(surface, cave));
        var ledger = new NaturalRenderLedger(null); ledger.complete(world, surface);
        ledger = new NaturalRenderLedger(ledger.save());
        assertFalse(ledger.eligible(world, surface)); assertTrue(ledger.eligible(world, cave));
        when(surface.getPrefix()).thenReturn("surface_v2"); assertTrue(ledger.eligible(world, surface));
        var other = world("other"); other.maps.add(surface); assertTrue(ledger.eligible(other, surface));
    }
    @Test void cancellationIsDurableUntilResumeAndReadOnlyMapsStayExcluded() {
        var world = world("world"); var map = map("surface", "surface"); world.maps.add(map);
        var ledger = new NaturalRenderLedger(null); ledger.suspend("world");
        ledger = new NaturalRenderLedger(ledger.save()); assertFalse(ledger.eligible(world, map));
        ledger.resume(); assertTrue(ledger.eligible(world, map));
        when(map.isReadOnly()).thenReturn(true); assertFalse(ledger.eligible(world, map));
    }
    @Test void quarterSpeedUsesOriginalBaselineWhileOfflinePolicyRemainsSeparate() {
        var natural = new FullRenderSpeedPolicy(4, 200, 125, 25);
        natural.recordBatch(50_000_000, 4, 100_000_000);
        assertEquals(750_000_000L, natural.remainingDelayNanos(100_000_000, true));
        assertEquals(110_000_000L, natural.remainingDelayNanos(100_000_000, false));
        var manual = new FullRenderSpeedPolicy(4, 200, 125, 50);
        manual.recordBatch(50_000_000, 4, 100_000_000);
        assertEquals(350_000_000L, manual.remainingDelayNanos(100_000_000, true));
    }
    @Test void delayedInvalidationsKeepBackgroundRenderingOutUntilProcessed() {
        var state = new MapTypeState(mock(DynmapWorld.class), mock(MapType.class));
        assertFalse(state.hasPendingUpdates()); state.invalidateTile(3, -4);
        assertTrue(state.hasPendingUpdates()); assertEquals(0, state.getInvCount());
    }
    @Test void migrationAddsNaturalDefaultsAndPreservesExplicitChoices() {
        var config = new ConfigurationNode();
        config.put("natural-render", new LinkedHashMap<>(Map.of("enabled", false, "players-online-percent", 20)));
        assertTrue(PublicMapSettings.migrate(config));
        assertFalse(config.getBoolean("natural-render/enabled", true));
        assertEquals(20, config.getInteger("natural-render/players-online-percent", 0));
        assertEquals(60, config.getInteger("natural-render/startup-delay-seconds", 0));
        assertFalse(PublicMapSettings.migrate(config));
    }

    @Test void reuseRequiresEveryConfiguredDayNightVariant() {
        var world = world("world"); var map = map("surface", "surface");
        when(map.getVariants()).thenReturn(new MapType.ImageVariant[]{MapType.ImageVariant.STANDARD, MapType.ImageVariant.DAY});
        var storage = mock(org.dynmap.storage.MapStorage.class); doReturn(storage).when(world).getMapStorage();
        var standard = mock(org.dynmap.storage.MapStorageTile.class, withSettings().useConstructor(world, map, 1, 2, 0, MapType.ImageVariant.STANDARD).defaultAnswer(CALLS_REAL_METHODS));
        var day = mock(org.dynmap.storage.MapStorageTile.class);
        when(storage.getTile(world, map, 1, 2, 0, MapType.ImageVariant.STANDARD)).thenReturn(standard);
        when(storage.getTile(world, map, 1, 2, 0, MapType.ImageVariant.DAY)).thenReturn(day);
        when(standard.exists()).thenReturn(true);
        assertFalse(NaturalRenderLedger.reusable(standard));
        when(day.exists()).thenReturn(true); assertTrue(NaturalRenderLedger.reusable(standard));
        verify(day, times(2)).cleanup();
    }

}
