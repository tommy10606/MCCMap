package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageUsageMonitorTest {
    @TempDir Path root;
    private void ready(StorageUsageMonitor monitor, long bytes) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            var snapshot=monitor.snapshot();
            if(snapshot.measuredAt()>0&&!snapshot.scanning()&&snapshot.measuredBytes()==bytes)return;
            Thread.sleep(5);
        }
        fail("Background measurement did not finish");
    }
    @Test void resumedInventoryCannotTrustOldCountsToAuthorizeCappedWrites() throws Exception {
        Path data=Files.createDirectory(root.resolve("inventory")),web=Files.createDirectory(root.resolve("web"));Files.write(data.resolve("tile"),new byte[100]);
        var checkpoint=new ProgressCheckpoint(root.resolve("progress"),web,"inventory");var saved=new ConfigurationNode();saved.put("roots",List.of(data.toRealPath()).toString());saved.put("root",1);saved.put("bytes",1);saved.put("files",1);checkpoint.write(saved);
        try(var monitor=new StorageUsageMonitor(List.of(data),50,60,0,10_000_000_000L,DiskSpaceReserve::readSpace,checkpoint)){
            ready(monitor,100);assertNull(monitor.reserve(1));assertNull(checkpoint.read());
        }
    }
    @Test void unlimitedByDefaultAndPositiveDecimalCapsAreExplicitGiB() {
        assertEquals(0,StorageUsageMonitor.parseLimitGiB("0"));
        assertEquals(1073741824L,StorageUsageMonitor.parseLimitGiB("1"));
        assertEquals(536870912L,StorageUsageMonitor.parseLimitGiB("0.5"));
        assertEquals(107374182L,StorageUsageMonitor.parseLimitGiB("0.1"));
        for(String invalid:List.of("-1","NaN","Infinity","huge","999999999999999999999","0.00000000001"))
            assertThrows(IllegalArgumentException.class,()->StorageUsageMonitor.parseLimitGiB(invalid));
    }
    @Test void countsNestedAndExternalDirectoriesOnceWithoutFollowingLinks() throws Exception {
        Path data=Files.createDirectories(root.resolve("data/web/tiles"));
        Files.write(root.resolve("data/config"),new byte[10]);Files.write(data.resolve("tile"),new byte[20]);
        Path external=Files.createDirectories(root.resolve("external"));Files.write(external.resolve("tile"),new byte[30]);
        Path outside=Files.createDirectories(root.resolve("outside"));Files.write(outside.resolve("secret"),new byte[100]);
        Files.createSymbolicLink(data.resolve("link"),outside);
        var roots=StorageUsageMonitor.canonicalRoots(List.of(data,root.resolve("data"),external,data));
        assertEquals(2,roots.size());var measured=StorageUsageMonitor.measure(roots);
        assertEquals(60,measured.bytes());assertEquals(3,measured.files());
    }
    @Test void unlimitedModeStillReportsUsageAndAllowsGrowth() throws Exception {
        Files.write(root.resolve("data"),new byte[50]);
        try(var monitor=new StorageUsageMonitor(List.of(root),0,60)){
            ready(monitor,50);assertNull(monitor.pauseReason());
            try(var growth=monitor.reserve(1000)){assertNotNull(growth);growth.commit();}
            assertEquals(1050,monitor.snapshot().accountedBytes());assertNull(monitor.pauseReason());
        }
    }
    @Test void simultaneousReservationsCannotSpendTheSameRemainingBudget() throws Exception {
        Files.write(root.resolve("data"),new byte[100]);
        try(var monitor=new StorageUsageMonitor(List.of(root),1000,60)){
            ready(monitor,100);var executor=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);
            var reservations=Collections.synchronizedList(new ArrayList<StorageUsageMonitor.Reservation>());
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<20;i++)tasks.add(executor.submit(()->{try{start.await();var r=monitor.reserve(100);if(r!=null)reservations.add(r);}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            start.countDown();for(var task:tasks)task.get(5,TimeUnit.SECONDS);executor.shutdownNow();
            assertEquals(9,reservations.size());assertEquals(1000,monitor.snapshot().accountedBytes());
            for(var reservation:reservations)reservation.close();
            assertEquals(100,monitor.snapshot().accountedBytes());
        }
    }
    @Test void failedWritesReleaseReservationsAndCommitDoesNotDoubleCharge() throws Exception {
        try(var monitor=new StorageUsageMonitor(List.of(root),100,60)){
            ready(monitor,0);try(var r=monitor.reserve(60)){assertNotNull(r);}
            assertEquals(0,monitor.snapshot().accountedBytes());
            try(var r=monitor.reserve(80)){r.commit();r.commit();}
            assertEquals(80,monitor.snapshot().accountedBytes());assertNull(monitor.reserve(30));
            assertNotNull(monitor.pauseReason());
        }
    }
    @Test void deletingDataAndRescanningAutomaticallyClearsStoragePause() throws Exception {
        Path file=root.resolve("tile");Files.write(file,new byte[100]);
        try(var monitor=new StorageUsageMonitor(List.of(root),50,60)){
            ready(monitor,100);assertNotNull(monitor.pauseReason());Files.delete(file);monitor.requestScan();
            ready(monitor,0);assertNull(monitor.pauseReason());
        }
    }
    @Test void statsExposeMeasurementAgeCapAndPauseWithoutFilesystemPaths() {
        var snapshot=new StorageUsageMonitor.Snapshot(2048,3072,2,1000,4096,false,false,"Storage cap reached",
            new DiskSpaceReserve.Snapshot(0,List.of(),0,false));
        String text=String.join("\n",StorageUsageMonitor.describe(snapshot,6000));
        assertTrue(text.contains("2048 bytes"));assertTrue(text.contains("2 files"));
        assertTrue(text.contains("5s ago"));assertTrue(text.contains("4.00 KiB"));
        assertTrue(text.contains("Storage cap reached"));assertFalse(text.contains(root.toString()));
    }
}
