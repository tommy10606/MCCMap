package org.dynmap;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.util.*;

/** Filesystem headroom, including space needed for concurrent atomic image replacements. */
final class DiskSpaceReserve {
    record Space(Object volume, long total, long usable) {}
    interface Probe { Space read(Path path) throws IOException; }
    record Volume(long total, long usable, long required) {}
    record Snapshot(double percent, List<Volume> volumes, long measuredAt, boolean failed) {}
    private final double percent;
    private final List<Path> roots;
    private final Probe probe;
    private final Map<Object, Space> spaces = new LinkedHashMap<>();
    private final Map<Path, Object> rootVolumes = new LinkedHashMap<>();
    private final Map<Object, Long> reservations = new HashMap<>();
    private long measuredAt;
    private boolean failed, denied;

    DiskSpaceReserve(List<Path> roots, double percent, Probe probe) {
        if (!Double.isFinite(percent) || percent < 0 || percent >= 100)
            throw new IllegalArgumentException("Minimum free disk percent must be between 0 (disabled) and 100 (exclusive)");
        this.roots = roots; this.percent = percent; this.probe = probe;
    }
    static Space readSpace(Path path) throws IOException {
        Path existing = path.toAbsolutePath().normalize();
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("No existing filesystem ancestor");
        FileStore store = Files.getFileStore(existing);
        return new Space(store, store.getTotalSpace(), store.getUsableSpace());
    }
    private Space checked(Path path) throws IOException {
        Space result = probe.read(path);
        if (result.volume == null || result.total <= 0 || result.usable < 0 || result.usable > result.total)
            throw new IOException("Invalid filesystem capacity");
        return result;
    }
    private long required(long total) {
        return BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValueExact();
    }
    synchronized void refresh() {
        if (failed) return; // Capacity failures stay in fallback mode until a new startup.
        try {
            Map<Object, Space> measured = new LinkedHashMap<>();
            Map<Path, Object> measuredRoots = new LinkedHashMap<>();
            for (Path root : roots) {
                Space value = checked(root); measured.put(value.volume, value); measuredRoots.put(root, value.volume);
            }
            rootVolumes.clear(); rootVolumes.putAll(measuredRoots);
            spaces.clear(); spaces.putAll(measured); measuredAt = System.currentTimeMillis();
            failed = false; denied = false;
        } catch (IOException | RuntimeException failure) { failed = true; }
    }
    synchronized String pauseReason() {
        if (failed) return null;
        if (measuredAt == 0) return "Waiting for filesystem free-space measurement";
        if (percent == 0) return null;
        for (Space space : spaces.values()) {
            if (space.usable <= required(space.total)) return "Filesystem free space at or below " + percent + "% reserve";
        }
        if (denied) return "Disk reserve: insufficient free space for the next tile write";
        return null;
    }
    synchronized Lease reserve(Path target, long temporaryBytes) {
        if (percent == 0 || failed) return new Lease(null, 0);
        try {
            Path normalized = target.toAbsolutePath().normalize();
            if (normalized.getParent() != null && Files.exists(normalized.getParent())) normalized = normalized.getParent().toRealPath().resolve(normalized.getFileName());
            final Path destination = normalized;
            Object volume = rootVolumes.entrySet().stream().filter(e -> destination.startsWith(e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().getNameCount())).map(Map.Entry::getValue).orElse(null);
            Space space = spaces.get(volume);
            if (space == null) return null;
            if (pauseReason() != null) return null;
            long pending = reservations.getOrDefault(space.volume, 0L);
            long headroom = space.usable - required(space.total);
            if (pending > headroom || temporaryBytes > headroom - pending) { denied = true; return null; }
            reservations.put(space.volume, Math.addExact(pending, temporaryBytes));
            return new Lease(space.volume, temporaryBytes);
        } catch (IOException | RuntimeException failure) { failed = true; return null; }
    }
    final class Lease implements AutoCloseable {
        private final Object volume;
        private final long bytes;
        private boolean closed;
        Lease(Object volume, long bytes) { this.volume = volume; this.bytes = bytes; }
        void commit(long growth) {
            synchronized (DiskSpaceReserve.this) {
                if (!closed && volume != null) {
                    Space old = spaces.get(volume);
                    if (old != null) spaces.put(volume, new Space(volume, old.total, Math.max(0, old.usable - growth)));
                }
                close();
            }
        }
        @Override public void close() {
            synchronized (DiskSpaceReserve.this) {
                if (closed) return; closed = true;
                if (volume != null) reservations.computeIfPresent(volume, (key, value) -> value == bytes ? null : value - bytes);
            }
        }
    }
    synchronized Snapshot snapshot() {
        return new Snapshot(percent, spaces.values().stream().map(space ->
                new Volume(space.total, space.usable, required(space.total))).toList(), measuredAt, failed);
    }
}
