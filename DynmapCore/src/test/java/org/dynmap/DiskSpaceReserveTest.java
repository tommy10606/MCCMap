package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskSpaceReserveTest {
    @TempDir Path root;
    @Test void tenPercentThresholdPausesAndAutomaticallyClearsAfterRefresh() {
        AtomicLong free = new AtomicLong(99);
        var reserve = new DiskSpaceReserve(List.of(root), 10, p -> new DiskSpaceReserve.Space("disk",1000,free.get()));
        assertNotNull(reserve.pauseReason()); reserve.refresh(); assertNotNull(reserve.pauseReason());
        free.set(100); reserve.refresh(); assertNotNull(reserve.pauseReason());
        free.set(200); reserve.refresh(); assertNull(reserve.pauseReason());
        assertEquals(100,reserve.snapshot().volumes().getFirst().required());
    }
    @Test void atomicReplacementNeedsTheEntireTemporaryImageAndFailedLeaseReleasesSpace() {
        var reserve = new DiskSpaceReserve(List.of(root),10,p -> new DiskSpaceReserve.Space("disk",1000,200));
        reserve.refresh(); var first=reserve.reserve(root.resolve("tile.png"),80); assertNotNull(first);
        assertNull(reserve.reserve(root.resolve("other.png"),30)); first.close(); first.close();
        reserve.refresh(); try(var next=reserve.reserve(root.resolve("other.png"),100)){assertNotNull(next);}
    }
    @Test void filesystemReservationsAreSeparateButLowSpaceOnAnyManagedDiskPausesRendering() {
        Path other=root.resolve("other");AtomicLong freeOther=new AtomicLong(150);
        var reserve = new DiskSpaceReserve(List.of(root,other),10,p -> new DiskSpaceReserve.Space(p.equals(other)?"other":"data",1000,p.equals(other)?freeOther.get():150));
        reserve.refresh(); assertEquals(2,reserve.snapshot().volumes().size());
        try(var data=reserve.reserve(root,50); var external=reserve.reserve(other,50)) {assertNotNull(data);assertNotNull(external);}
        freeOther.set(90); reserve.refresh(); assertNotNull(reserve.pauseReason()); assertNull(reserve.reserve(root,1));
    }
    @Test void sharedFilesystemIsReportedOnceAndConcurrentWritersCannotSpendTheSameHeadroom() throws Exception {
        var reserve=new DiskSpaceReserve(List.of(root,root.resolve("nested")),10,p -> new DiskSpaceReserve.Space("disk",1000,1000));
        reserve.refresh(); assertEquals(1,reserve.snapshot().volumes().size());
        ExecutorService threads=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);
        List<DiskSpaceReserve.Lease> leases=Collections.synchronizedList(new ArrayList<>());List<Future<?>> jobs=new ArrayList<>();
        try {
            for(int i=0;i<20;i++)jobs.add(threads.submit(()->{try{start.await();var lease=reserve.reserve(root,100);if(lease!=null)leases.add(lease);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            start.countDown(); for(var job:jobs)job.get(5,TimeUnit.SECONDS); assertEquals(9,leases.size());
        } finally { leases.forEach(DiskSpaceReserve.Lease::close);threads.shutdownNow(); }
    }
    @Test void measurementFailureStaysInFallbackUntilANewInstanceStarts() {
        AtomicBoolean failure=new AtomicBoolean(true);
        var reserve=new DiskSpaceReserve(List.of(root),10,p->{if(failure.get())throw new IOException("unavailable");return new DiskSpaceReserve.Space("disk",1000,500);});
        reserve.refresh(); assertNull(reserve.pauseReason());assertTrue(reserve.snapshot().failed());
        failure.set(false);reserve.refresh();assertTrue(reserve.snapshot().failed());
        var restarted=new DiskSpaceReserve(List.of(root),10,p->new DiskSpaceReserve.Space("disk",1000,500));restarted.refresh();assertFalse(restarted.snapshot().failed());assertNull(restarted.pauseReason());
        failure.set(true);reserve.refresh();assertTrue(reserve.snapshot().failed());try(var lease=reserve.reserve(root,1)){assertNotNull(lease);}
    }
    @Test void zeroPercentDisablesTheReserveButKeepsCapacityChecksForFallback() {
        var reserve=new DiskSpaceReserve(List.of(root),0,p->new DiskSpaceReserve.Space("disk",1000,500));
        reserve.refresh();assertNull(reserve.pauseReason());try(var lease=reserve.reserve(root,Long.MAX_VALUE)){assertNotNull(lease);}
        for(double invalid:new double[]{-1,100,101,Double.NaN,Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->new DiskSpaceReserve(List.of(root),invalid,p->null));
    }
    @Test void percentageRoundingDoesNotOverflowOnLargeFilesystems() {
        var reserve=new DiskSpaceReserve(List.of(root),10,p->new DiskSpaceReserve.Space("disk",Long.MAX_VALUE,Long.MAX_VALUE));
        reserve.refresh();assertEquals(922337203685477581L,reserve.snapshot().volumes().getFirst().required());
        var small=new DiskSpaceReserve(List.of(root),10,p->new DiskSpaceReserve.Space("disk",11,11));small.refresh();assertEquals(2,small.snapshot().volumes().getFirst().required());
    }
    @Test void missingDirectoriesUseTheirExistingAncestorForRealFilesystemMeasurements() throws Exception {
        var space=DiskSpaceReserve.readSpace(root.resolve("not-created/yet"));assertTrue(space.total()>0);assertTrue(space.usable()>=0);
    }
    @Test void unlimitedMapCapStillEnforcesReserveAndStatsShowItWithoutPaths() throws Exception {
        AtomicLong free=new AtomicLong(90);
        try(var monitor=new StorageUsageMonitor(List.of(root),0,60,10,p->new DiskSpaceReserve.Space("disk",1000,free.get()))){
            ready(monitor); assertEquals(0,monitor.snapshot().limitBytes());assertNotNull(monitor.pauseReason());
            assertNull(monitor.reserve(root.resolve("tile.png"),0,1));
            free.set(200);long old=monitor.snapshot().disk().measuredAt();Thread.sleep(2);monitor.refreshDisk();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while((monitor.snapshot().disk().measuredAt()<=old||monitor.pauseReason()!=null)&&System.nanoTime()<end)Thread.sleep(5);
            assertNull(monitor.pauseReason());try(var write=monitor.reserve(root.resolve("tile.png"),0,80)){assertNotNull(write);write.commit();}
            String stats=String.join("\n",StorageUsageMonitor.describe(monitor.snapshot(),System.currentTimeMillis()));
            assertTrue(stats.contains("10.0%"));assertTrue(stats.contains("20.00%"));assertFalse(stats.contains(root.toString()));
            assertNull(monitor.reserve(root.resolve("tile.png"),0,101)); // no logical growth, but full replacement space is required
        }
    }
    @Test void startupAndDailyScheduleUsesFallbackCapAndNeverOverridesAStricterCap() throws Exception {
        assertEquals(86400,StorageUsageMonitor.FREE_SPACE_CHECK_SECONDS);
        AtomicBoolean fail=new AtomicBoolean(true); AtomicInteger calls=new AtomicInteger();
        DiskSpaceReserve.Probe probe=p->{calls.incrementAndGet();if(fail.get())throw new IOException("not available");return new DiskSpaceReserve.Space("disk",1000,500);};
        try(var monitor=new StorageUsageMonitor(List.of(root),0,60,10,1234,probe)) {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while((monitor.snapshot().measuredAt()==0||!monitor.snapshot().disk().failed())&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals(1234,monitor.snapshot().limitBytes());assertNull(monitor.pauseReason());
            try(var lease=monitor.reserve(root,100,100)){assertNotNull(lease);lease.commit();}
            assertNull(monitor.reserve(root,2000,2000));int checked=calls.get();monitor.requestScan();Thread.sleep(50);assertEquals(checked,calls.get());
            fail.set(false);monitor.refreshDisk();assertEquals(1234,monitor.snapshot().limitBytes());assertEquals(checked,calls.get());
        }
        fail.set(true);
        try(var monitor=new StorageUsageMonitor(List.of(root),100,60,10,1234,probe)) {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!monitor.snapshot().disk().failed()&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals(100,monitor.snapshot().limitBytes());
        }
        assertEquals(10_000_000_000L,StorageUsageMonitor.parseLimitGB("10"));assertEquals(500_000_000L,StorageUsageMonitor.parseLimitGB("0.5"));
    }
    @Test void aLaterDailyFailureStopsAllAttemptsUntilTheNextStartup() throws Exception {
        AtomicBoolean fail=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();
        DiskSpaceReserve.Probe probe=p->{calls.incrementAndGet();if(fail.get())throw new IOException("unavailable");return new DiskSpaceReserve.Space("disk",1000,500);};
        try(var monitor=new StorageUsageMonitor(List.of(root),0,60,10,1234,probe)) {
            ready(monitor);assertEquals(1,calls.get());assertEquals(0,monitor.snapshot().limitBytes());
            fail.set(true);monitor.refreshDisk();assertEquals(2,calls.get());assertEquals(1234,monitor.snapshot().limitBytes());
            fail.set(false);for(int i=0;i<10;i++)monitor.refreshDisk();monitor.requestScan();
            try(var write=monitor.reserve(root.resolve("tile.png"),1,1)){assertNotNull(write);}
            assertEquals(2,calls.get());assertTrue(monitor.snapshot().disk().failed());
            String stats=String.join("\n",StorageUsageMonitor.describe(monitor.snapshot(),System.currentTimeMillis()));assertTrue(stats.contains("until next startup"));
        }
        try(var restarted=new StorageUsageMonitor(List.of(root),0,60,10,1234,probe)) {
            ready(restarted);assertEquals(3,calls.get());assertEquals(0,restarted.snapshot().limitBytes());assertFalse(restarted.snapshot().disk().failed());
        }
    }
    @Test void cachedSpaceTracksCommittedGrowthWithoutQueryingFilesystemDuringWrites() {
        AtomicInteger calls=new AtomicInteger();var reserve=new DiskSpaceReserve(List.of(root),10,p->{calls.incrementAndGet();return new DiskSpaceReserve.Space("disk",1000,300);});
        reserve.refresh();try(var lease=reserve.reserve(root,100)){assertNotNull(lease);lease.commit(100);}
        assertEquals(200,reserve.snapshot().volumes().getFirst().usable());assertEquals(1,calls.get());
        assertNull(reserve.reserve(root,101));assertEquals(1,calls.get());
    }
    private void ready(StorageUsageMonitor monitor) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while((monitor.snapshot().measuredAt()==0||monitor.snapshot().scanning())&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue(monitor.snapshot().measuredAt()>0);assertTrue(monitor.snapshot().disk().measuredAt()>0);
    }

    @Test void aDailyDiskRefreshDoesNotLatchTheSeparateMapCapUntilTheNextDirectoryScan() throws Exception {
        AtomicLong free=new AtomicLong(90);
        try(var monitor=new StorageUsageMonitor(List.of(root),10000,60,10,p->new DiskSpaceReserve.Space("disk",1000,free.get()))){
            ready(monitor);long usageSample=monitor.snapshot().measuredAt();
            assertNull(monitor.reserve(root.resolve("tile.png"),1,1));free.set(200);monitor.refreshDisk();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
            while(monitor.pauseReason()!=null&&System.nanoTime()<end)Thread.sleep(10);
            assertNull(monitor.pauseReason());assertEquals(usageSample,monitor.snapshot().measuredAt());
            try(var write=monitor.reserve(root.resolve("tile.png"),1,1)){assertNotNull(write);}
        }
    }

    @Test void filetreeReserveRefusalPreservesTheOldImageAndRetainsItsRenderWork() throws Exception {
        Path tiles=Files.createDirectories(root.resolve("tiles"));
        var active=new AtomicReference<StorageUsageMonitor>();
        DynmapCore core=new DynmapCore(){@Override public StorageUsageMonitor getStorageUsage(){return active.get();}};
        core.configuration=new ConfigurationNode();core.setDataFolder(root.toFile());
        var directory=DynmapCore.class.getDeclaredField("tilesDirectory");directory.setAccessible(true);directory.set(core,tiles.toFile());
        DynmapWorld world=org.mockito.Mockito.mock(DynmapWorld.class);org.mockito.Mockito.when(world.getName()).thenReturn("world");
        MapType map=org.mockito.Mockito.mock(MapType.class);org.mockito.Mockito.when(map.getName()).thenReturn("flat");
        org.mockito.Mockito.when(map.getPrefix()).thenReturn("flat");org.mockito.Mockito.when(map.getImageFormat()).thenReturn(MapType.ImageFormat.FORMAT_PNG);
        MapTypeState state=org.mockito.Mockito.mock(MapTypeState.class);org.mockito.Mockito.when(world.getMapState(map)).thenReturn(state);
        var storage=new org.dynmap.storage.filetree.FileTreeMapStorage();assertTrue(storage.init(core));
        var tile=storage.getTile(world,map,0,0,0,MapType.ImageVariant.STANDARD);
        var old=new org.dynmap.utils.BufferOutputStream();old.write(new byte[]{1,2,3,4});assertTrue(tile.write(1,old,1000));
        Path image=tiles.resolve("world/flat/0_0/0_0.png");
        try(var monitor=new StorageUsageMonitor(List.of(root),0,60,10,p->new DiskSpaceReserve.Space("disk",1000,90))){
            ready(monitor);active.set(monitor);var replacement=new org.dynmap.utils.BufferOutputStream();replacement.write(new byte[40]);
            assertFalse(tile.write(2,replacement,2000));assertArrayEquals(new byte[]{1,2,3,4},Files.readAllBytes(image));
            org.mockito.Mockito.verify(state).invalidateTile(0,0);assertFalse(Files.exists(image.resolveSibling("0_0.png.new")));
            assertTrue(storage.setMarkerFile("world","{\"sets\":{}}"));
        }
    }
}
