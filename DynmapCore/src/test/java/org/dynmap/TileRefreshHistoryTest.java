package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TileRefreshHistoryTest {
    @TempDir Path root;
    private TileRefreshHistory history() throws Exception {
        Files.createDirectories(root.resolve("web"));
        return new TileRefreshHistory(root.resolve("history"), root.resolve("web"));
    }
    @Test void datesSurviveRestartWithoutChangingImageFiles() throws Exception {
        var history = history(); String key = TileRefreshHistory.namespace("world/map/day");
        long now = System.currentTimeMillis();
        assertTrue(history.record(key, 4, -7, now)); history.flush();
        assertEquals(now, history().lastSuccessful(key, 4, -7));
        assertEquals(0, history().lastSuccessful(key, 4, -6));
    }
    @Test void negativeCoordinatesAndGridEdgesRemainDistinct() throws Exception {
        var history = history(); String key = TileRefreshHistory.namespace("world/map");
        for (int x : new int[]{-33,-32,-1,0,31,32,Integer.MIN_VALUE,Integer.MAX_VALUE})
            assertTrue(history.record(key, x, x, (long)x - Integer.MIN_VALUE + 1));
        history.flush();
        var reopened = history();
        for (int x : new int[]{-33,-32,-1,0,31,32,Integer.MIN_VALUE,Integer.MAX_VALUE})
            assertEquals((long)x - Integer.MIN_VALUE + 1, reopened.lastSuccessful(key,x,x));
    }
    @Test void delayedOlderCompletionsCannotMoveDatesBackwards() throws Exception {
        var history = history(); String key=TileRefreshHistory.namespace("map");
        history.record(key,0,0,200); history.record(key,0,0,100); history.flush();
        assertEquals(200, history.lastSuccessful(key,0,0));
    }
    @Test void namespacesDoNotBecomePathsAndVariantsStaySeparate() throws Exception {
        var history=history();String a=TileRefreshHistory.namespace("../../outside\0standard"),b=TileRefreshHistory.namespace("../../outside\0day");
        assertNotEquals(a,b);history.record(a,0,0,100);history.flush();
        assertEquals(0,history.lastSuccessful(b,0,0));
        assertThrows(IllegalArgumentException.class,()->history.lastSuccessful("../../outside",0,0));
        try(var files=Files.list(root.resolve("history"))){assertTrue(files.allMatch(p->p.getFileName().toString().matches("[a-f0-9]{64}_-?[0-9]+_-?[0-9]+\\.age")));}
    }
    @Test void cacheEvictionKeepsDatesAndGridFilesHaveFixedSize() throws Exception {
        var history=history();String key=TileRefreshHistory.namespace("map");
        for(int i=0;i<140;i++)history.record(key,i*32,0,100+i);
        history.flush();var reopened=history();
        for(int i=0;i<140;i++)assertEquals(100+i,reopened.lastSuccessful(key,i*32,0));
        try(var files=Files.list(root.resolve("history"))){for(Path p:files.toList())assertEquals(8192,Files.size(p));}
    }
    @Test void refusesPublicOrSymlinkHistoryDirectories() throws Exception {
        Files.createDirectories(root.resolve("web"));
        assertThrows(java.io.IOException.class,()->new TileRefreshHistory(root.resolve("web/history"),root.resolve("web")));
        Files.createSymbolicLink(root.resolve("history"),root.resolve("web"));
        assertThrows(java.io.IOException.class,this::history);
    }
    @Test void corruptedAndSymlinkGridFilesCannotSupplyDates() throws Exception {
        var history=history();String key=TileRefreshHistory.namespace("map");Path file=root.resolve("history").resolve(key+"_0_0.age");
        Files.writeString(file,"not a timestamp grid");assertEquals(0,history.lastSuccessful(key,0,0));
        Files.delete(file);Files.createSymbolicLink(file,root.resolve("web"));
        assertThrows(java.io.IOException.class,()->history().lastSuccessful(key,0,0));
    }
    @Test void monthBoundaryUsesElapsedTimeAndRejectsUnknownFutureDates() {
        long now=System.currentTimeMillis(),month=TimeUnit.DAYS.toMillis(30);
        assertTrue(StaleTileRefresh.stale(now-month,now,month));
        assertFalse(StaleTileRefresh.stale(now-month+1,now,month));
        assertFalse(StaleTileRefresh.stale(0,now,month));
        assertFalse(StaleTileRefresh.stale(-1,now,month));
        assertFalse(StaleTileRefresh.stale(now+1000,now,month));
        assertEquals(now-month,StaleTileRefresh.lastRenderTime(now-month,Long.MAX_VALUE,now));
        assertEquals(now-1,StaleTileRefresh.lastRenderTime(now-month,now-1,now));
    }
}
