package org.dynmap;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background logical file accounting and a growth budget for renderer writes. */
public final class StorageUsageMonitor implements AutoCloseable {
    public record Snapshot(long measuredBytes, long accountedBytes, long files, long measuredAt,
                           long limitBytes, boolean scanning, boolean scanFailed, String pauseReason,
                           DiskSpaceReserve.Snapshot disk) {}
    record Measurement(long bytes, long files) {}
    private final List<Path> roots;
    private final long limitBytes;
    private final long fallbackBytes;
    static final long FREE_SPACE_CHECK_SECONDS = 86400;
    private final DiskSpaceReserve disk;
    private final ScheduledExecutorService executor;
    private final ProgressCheckpoint inventoryCheckpoint;
    private volatile ConfigurationNode inventoryProgress;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private long measuredBytes, accountedBytes, files, measuredAt, positiveCommitted, reserved;
    private boolean scanFailed, denied;
    private volatile boolean closed;

    public StorageUsageMonitor(List<Path> paths, long limitBytes, long scanSeconds) throws IOException {
        this(paths, limitBytes, scanSeconds, 0);
    }
    public StorageUsageMonitor(List<Path> paths, long limitBytes, long scanSeconds, double minimumFreePercent) throws IOException {
        this(paths, limitBytes, scanSeconds, minimumFreePercent, 10_000_000_000L);
    }
    public StorageUsageMonitor(List<Path> paths, long limitBytes, long scanSeconds, double minimumFreePercent, long fallbackBytes) throws IOException {
        this(paths, limitBytes, scanSeconds, minimumFreePercent, fallbackBytes, DiskSpaceReserve::readSpace);
    }
    StorageUsageMonitor(List<Path> paths, long limitBytes, long scanSeconds, double minimumFreePercent,
                        DiskSpaceReserve.Probe probe) throws IOException {
        this(paths, limitBytes, scanSeconds, minimumFreePercent, 10_000_000_000L, probe);
    }
    StorageUsageMonitor(List<Path> paths, long limitBytes, long scanSeconds, double minimumFreePercent,
                        long fallbackBytes, DiskSpaceReserve.Probe probe) throws IOException {
        this(paths,limitBytes,scanSeconds,minimumFreePercent,fallbackBytes,probe,null);
    }
    StorageUsageMonitor(List<Path> paths,long limitBytes,long scanSeconds,double minimumFreePercent,long fallbackBytes,DiskSpaceReserve.Probe probe,ProgressCheckpoint checkpoint)throws IOException {
        inventoryCheckpoint=checkpoint;
        if (limitBytes < 0) throw new IllegalArgumentException("Negative storage limit");
        if (fallbackBytes <= 0) throw new IllegalArgumentException("Fallback cap must be positive");
        this.roots = canonicalRoots(paths); this.limitBytes = limitBytes; this.fallbackBytes = fallbackBytes;
        disk = new DiskSpaceReserve(roots, minimumFreePercent, probe);
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "MCCMap storage usage"); thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY); return thread;
        });
        executor.scheduleWithFixedDelay(this::refreshDisk, 0, FREE_SPACE_CHECK_SECONDS, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> {
            if (!closed && scanning.compareAndSet(false, true)) scan();
        }, 0, Math.max(5, Math.min(3600, scanSeconds)), TimeUnit.SECONDS);
    }
    synchronized void refreshDisk() {
        if (closed || disk.snapshot().failed()) return;
        String previous = pauseReason(); disk.refresh();
        if (disk.snapshot().failed()) { denied = false; reportDiskFailure(); }
        logPauseChange(previous);
    }
    private void reportDiskFailure() {
        Log.severe("Cannot measure filesystem free space; applying fallback storage cap of " + bytes(effectiveLimit())
            + ". No further checks until next startup. Configure storage.free-space-failure-max-gb to change the cap.");
    }
    private void logPauseChange(String previous) {
        String reason = pauseReason();
        if (!Objects.equals(previous, reason)) {
            if (reason == null) Log.info("Storage headroom available; rendering may resume");
            else Log.info("Rendering paused: " + reason);
        }
    }
    public static long parseLimitGB(String value) {
        try { return new BigDecimal(value).multiply(BigDecimal.valueOf(1000000000L)).setScale(0, java.math.RoundingMode.FLOOR).longValueExact(); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Fallback GB cap must be a positive number", failure); }
    }
    public static long parseLimitGiB(String value) {
        try {
            BigDecimal gib = new BigDecimal(value);
            if (gib.signum() < 0) throw new IllegalArgumentException("Negative storage limit");
            long bytes = gib.multiply(BigDecimal.valueOf(1024L * 1024 * 1024)).setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
            if (gib.signum() > 0 && bytes == 0) throw new IllegalArgumentException("Storage limit is smaller than one byte");
            return bytes;
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException("Storage limit must be a nonnegative GiB value representable in whole bytes");
        }
    }
    static List<Path> canonicalRoots(List<Path> paths) throws IOException {
        List<Path> roots = new ArrayList<>();
        for (Path path : paths) {
            Path canonical = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
            if (roots.stream().anyMatch(canonical::startsWith)) continue;
            roots.removeIf(existing -> existing.startsWith(canonical)); roots.add(canonical);
        }
        return List.copyOf(roots);
    }
    static Measurement measure(List<Path> roots) throws IOException {
        long[] result = new long[2];
        for (Path root : roots) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    if (attrs.isRegularFile()) { result[0] = Math.addExact(result[0], attrs.size()); result[1]++; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
                    if (failure instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                    throw failure;
                }
            });
        }
        return new Measurement(result[0], result[1]);
    }
    public void requestScan() {
        if (closed || !scanning.compareAndSet(false, true)) return;
        try { executor.execute(this::scan); }
        catch (RejectedExecutionException stopped) { scanning.set(false); }
    }
    private Measurement measureInventory()throws IOException {
        if(inventoryCheckpoint==null)return measure(roots);
        int index=0;long bytes=0,files=0;var cursor=new org.dynmap.utils.ScanCursor();boolean resumed=false;
        try{var saved=inventoryCheckpoint.read();if(saved!=null&&roots.toString().equals(saved.getString("roots",""))){index=saved.getInteger("root",0);bytes=(long)saved.getLong("bytes",0);files=(long)saved.getLong("files",0);var position=saved.getNode("cursor");if(position!=null)cursor=new org.dynmap.utils.ScanCursor(position);resumed=true;}}
        catch(IOException|RuntimeException invalid){index=0;bytes=files=0;cursor=new org.dynmap.utils.ScanCursor();}
        if(index<0||index>roots.size()||bytes<0||files<0)throw new IOException("Invalid inventory checkpoint");
        long next=System.currentTimeMillis()+inventoryCheckpoint.intervalMillis();
        try {
            while(index<roots.size()){
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                Path root=roots.get(index);
                if(Files.isRegularFile(root,LinkOption.NOFOLLOW_LINKS)){bytes=Math.addExact(bytes,Files.size(root));files++;index++;cursor=new org.dynmap.utils.ScanCursor();continue;}
                Path file=cursor.next(root);if(file==null){index++;cursor=new org.dynmap.utils.ScanCursor();continue;}
                try{bytes=Math.addExact(bytes,Files.size(file));files++;}catch(NoSuchFileException removed){}
                cursor.acknowledge();var n=new ConfigurationNode();n.put("roots",roots.toString());n.put("root",index);n.put("cursor",cursor.save());n.put("bytes",bytes);n.put("files",files);inventoryProgress=n;
                if(System.currentTimeMillis()>=next){inventoryCheckpoint.write(n);next=System.currentTimeMillis()+inventoryCheckpoint.intervalMillis();}
            }
            // Files may change while offline. Partial old totals must never authorize capped writes.
            Measurement result=resumed?measure(roots):new Measurement(bytes,files);
            inventoryCheckpoint.delete();inventoryProgress=null;return result;
        }finally{if(inventoryProgress!=null)inventoryCheckpoint.write(inventoryProgress);}
    }
    private void scan() {
        long atStart;
        synchronized (this) { atStart = positiveCommitted; }
        try {
            Measurement measurement = measureInventory();
            synchronized (this) {
                String previous = pauseReason();
                measuredBytes = measurement.bytes; files = measurement.files; measuredAt = System.currentTimeMillis();
                // Conservatively retain writes made during the walk and outstanding reservations.
                accountedBytes = Math.addExact(measuredBytes, Math.addExact(positiveCommitted - atStart, reserved));
                scanFailed = false; denied = false;
                logPauseChange(previous);
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) {
                if (!scanFailed && !closed) Log.warning("Storage usage scan failed; capped rendering waits for a successful scan");
                scanFailed = true;
            }
        } finally { scanning.set(false); }
    }
    public synchronized String pauseReason() {
        String diskPause = disk.pauseReason();
        if (diskPause != null) return diskPause;
        return logicalPauseReason();
    }
    private long effectiveLimit() { return disk.snapshot().failed() ? (limitBytes == 0 ? fallbackBytes : Math.min(limitBytes, fallbackBytes)) : limitBytes; }
    private String logicalPauseReason() {
        long limitBytes = effectiveLimit();
        if (limitBytes == 0) return null;
        if (measuredAt == 0 || scanFailed) return "Waiting for storage usage measurement";
        if (accountedBytes >= limitBytes) return "Storage cap reached";
        if (denied) return "Storage cap: insufficient room for the next tile write";
        return null;
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(measuredBytes, accountedBytes, files, measuredAt, effectiveLimit(), scanning.get(), scanFailed, pauseReason(), disk.snapshot());
    }
    public synchronized Reservation reserve(long growth) {
        return reserve(roots.getFirst(), growth, Math.max(0, growth));
    }
    public synchronized Reservation reserve(Path target, long growth, long temporaryBytes) {
        long limitBytes = effectiveLimit();
        growth = Math.max(0, growth);
        if (limitBytes > 0 && (logicalPauseReason() != null || growth > limitBytes - accountedBytes)) {
            if (!denied && measuredAt != 0) Log.warning("Rendering paused: the storage limit would be exceeded");
            denied = true; return null;
        }
        String previous = pauseReason();
        boolean wasFailed = disk.snapshot().failed();
        var diskLease = disk.reserve(target, Math.max(0, temporaryBytes));
        if (!wasFailed && disk.snapshot().failed()) { denied = false; reportDiskFailure(); }
        if (diskLease == null) { logPauseChange(previous); return null; }
        accountedBytes = Math.addExact(accountedBytes, growth); reserved = Math.addExact(reserved, growth);
        return new Reservation(growth, diskLease);
    }
    public final class Reservation implements AutoCloseable {
        private final long growth;
        private final DiskSpaceReserve.Lease diskLease;
        private boolean finished;
        private Reservation(long growth, DiskSpaceReserve.Lease diskLease) { this.growth = growth; this.diskLease = diskLease; }
        public void commit() { finish(true); }
        @Override public void close() { finish(false); }
        private void finish(boolean success) {
            synchronized (StorageUsageMonitor.this) {
                if (finished) return; finished = true; reserved -= growth;
                if (success) positiveCommitted = Math.addExact(positiveCommitted, growth);
                else accountedBytes = Math.max(0, accountedBytes - growth);
                if (success) diskLease.commit(growth); else diskLease.close();
            }
        }
    }
    public static String bytes(long value) {
        if (value < 1024) return value + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"}; double size = value;
        int unit = -1; do { size /= 1024; unit++; } while (size >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.2f %s", size, units[unit]);
    }
    public static List<String> describe(Snapshot snapshot, long now) {
        List<String> lines = new ArrayList<>(); lines.add("Storage usage (managed local files):");
        if (snapshot.measuredAt == 0) lines.add("  Usage: awaiting first background scan");
        else {
            lines.add("  Measured: " + bytes(snapshot.measuredBytes) + " (" + snapshot.measuredBytes + " bytes), " + snapshot.files + " files");
            lines.add("  Measured " + Math.max(0, (now - snapshot.measuredAt) / 1000) + "s ago; scan "
                + (snapshot.scanFailed ? "failed (last result retained)" : snapshot.scanning ? "running" : "idle"));
            lines.add("  Render budget usage: " + bytes(snapshot.accountedBytes) + " (includes reserved growth)");
        }
        lines.add("  Cap: " + (snapshot.limitBytes == 0 ? "unlimited" : bytes(snapshot.limitBytes)));
        var disk = snapshot.disk;
        if (disk.failed()) lines.add("  Free-space measurement unavailable: fallback cap ACTIVE until next startup");
        lines.add("  Minimum filesystem free space: " + (disk.percent() == 0 ? "disabled" : disk.percent() + "%"));
        if (disk.percent() > 0) {
            if (disk.measuredAt() == 0) lines.add("  Filesystem free space: awaiting first measurement");
            else {
                int number = 0;
                for (var volume : disk.volumes()) lines.add(String.format(Locale.ROOT,
                    "  Filesystem %d: %s usable free / %s total (%.2f%%); reserve %s", ++number,
                    bytes(volume.usable()), bytes(volume.total()), 100.0 * volume.usable() / volume.total(), bytes(volume.required())));
                lines.add("  Filesystem sample: " + Math.max(0, (now - disk.measuredAt()) / 1000) + "s ago"
                    + (disk.failed() ? "; failed (last result retained)" : ""));
            }
        }
        lines.add("  Storage pause: " + (snapshot.pauseReason == null ? "none" : snapshot.pauseReason)); return lines;
    }
    @Override public void close() { closed = true; executor.shutdownNow(); }
}
