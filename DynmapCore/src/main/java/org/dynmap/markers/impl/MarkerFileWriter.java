package org.dynmap.markers.impl;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.dynmap.Log;

/** Serialize disk writes, coalesce newer snapshots, and retry failures without losing dirty state. */
final class MarkerFileWriter implements AutoCloseable {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MCCMap marker files"); thread.setDaemon(true); return thread;
    });
    private final Map<String, BooleanSupplier> pending = new LinkedHashMap<>();
    private final Set<String> warned = new HashSet<>();
    private boolean scheduled, closed;
    synchronized void submit(String key, BooleanSupplier write) {
        if (closed) return;
        pending.put(key, write);
        if (!scheduled) { scheduled = true; worker.execute(this::pump); }
    }
    private void pump() {
        String key; BooleanSupplier write;
        synchronized(this) {
            if (pending.isEmpty()) { scheduled = false; return; }
            key = pending.keySet().iterator().next(); write = pending.get(key);
        }
        boolean success;
        try { success = write.getAsBoolean(); } catch (RuntimeException failure) { success = false; }
        synchronized(this) {
            if (success) { if (pending.get(key) == write) pending.remove(key); warned.remove(key); }
            else {
                if (warned.add(key)) Log.warning("Marker file write failed; retrying the latest snapshot");
                // A failing world must not prevent other worlds from being saved.
                if (pending.get(key) == write) { pending.remove(key); pending.put(key, write); }
            }
            if (closed) { scheduled = false; return; }
            worker.schedule(this::pump, success ? 0 : 1000, TimeUnit.MILLISECONDS);
        }
    }
    @Override public void close() {
        synchronized(this) { closed = true; }
        worker.shutdown();
        try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) { worker.shutdownNow(); Log.severe("Marker writer did not stop before shutdown; final snapshots remain pending"); return; } }
        catch (InterruptedException interrupted) {
            worker.shutdownNow(); Thread.currentThread().interrupt();
            Log.severe("Marker shutdown interrupted; final snapshots remain pending"); return;
        }
        synchronized(this) {
            for (var write : pending.values()) {
                try { if (!write.getAsBoolean()) Log.severe("Marker file could not be saved during shutdown"); }
                catch (RuntimeException failure) { Log.severe("Marker file could not be saved during shutdown"); }
            }
            pending.clear();
        }
    }
}
