package org.dynmap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.dynmap.hdmap.HDMap;
import org.dynmap.utils.ScanCursor;
import org.dynmap.utils.TileFlags;

/** Bounded, restartable estimate from generated region headers, never chunk generation. */
final class RenderInventory {
    private String phase = "regions";
    private ScanCursor regions = new ScanCursor();
    private final TileFlags chunks = new TileFlags(), tiles = new TileFlags();
    private long total;
    private boolean retainSeeds;
    private TileFlags.Iterator seedIterator;

    RenderInventory() {}
    RenderInventory(ConfigurationNode n) throws IOException {
        if (n == null) return;
        phase = n.getString("phase", "regions");
        if (!List.of("regions", "project", "done", "unavailable").contains(phase)) throw new IOException("Invalid inventory phase");
        var cursor = n.getNode("regions");
        if (cursor != null) regions = new ScanCursor(cursor);
        chunks.load(n.getStrings("chunks", List.of()));
        tiles.load(n.getStrings("tiles", List.of()));
        total = Math.max(0, (long)n.getLong("total", 0));
        retainSeeds = n.getBoolean("retain-seeds", false);
    }
    synchronized ConfigurationNode save() {
        var n = new ConfigurationNode();
        n.put("phase", phase); n.put("regions", regions.save());
        n.put("chunks", chunks.save()); n.put("tiles", tiles.save()); n.put("total", total);
        n.put("retain-seeds", retainSeeds);
        return n;
    }
    synchronized void completed(long count) {phase="done";total=Math.max(0,count);chunks.clear();tiles.clear();}
    synchronized long total() { return total; }
    synchronized boolean done() { return phase.equals("done") || phase.equals("unavailable"); }
    synchronized String phase() { return phase; }
    synchronized void unavailable() { phase = "unavailable"; chunks.clear(); tiles.clear(); }
    synchronized void retainSeeds() { retainSeeds = true; }
    synchronized boolean hasSeeds() { return retainSeeds && tiles.countFlags() > 0; }
    synchronized TileFlags.TileCoord nextUnseenSeed(TileFlags found) {
        if(seedIterator==null)seedIterator=tiles.getIterator();
        var iterator = seedIterator; var coordinate = new TileFlags.TileCoord();
        int remaining = Math.min(4096, tiles.countFlags());
        while (remaining-- > 0 && iterator.next(coordinate)) {
            tiles.setFlag(coordinate.x, coordinate.y, false);
            if (found == null || !found.getFlag(coordinate.x, coordinate.y)) return coordinate;
        }
        return null;
    }

    synchronized boolean step(DynmapWorld world, HDMap map, MissingTileCheck.Snapshot snapshot,
                              int minX, int maxX, int minZ, int maxZ) throws IOException {
        if (done()) return true;
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        if (phase.equals("regions")) {
            Path directory = snapshot.regionDirectory();
            for (int i = 0; i < 32 && directory != null; i++) {
                Path file = regions.next(directory);
                if (file == null) { phase = "project"; break; }
                if (file.getParent().equals(directory.toAbsolutePath().normalize()))
                    MissingTileCheck.readRegionFile(file, null, chunks);
                regions.acknowledge();
            }
            if (directory == null) phase = "project";
            if (!phase.equals("project")) return false;
            for (var chunk : snapshot.loaded()) chunks.setFlag(chunk.x, chunk.z, true);
        }
        var iterator = chunks.getIterator();
        var coordinate = new TileFlags.TileCoord();
        int remaining = chunks.countFlags();
        for (int i = 0; i < 1024 && remaining-- > 0 && iterator.next(coordinate); i++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            int x = coordinate.x, z = coordinate.y;
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ)
                for (var tile : map.getTileCoords(world, x * 16, world.minY, z * 16, x * 16 + 15, world.worldheight - 1, z * 16 + 15))
                    tiles.setFlag(tile.x, tile.y, true);
            chunks.setFlag(x, z, false);
        }
        if (chunks.countFlags() == 0) {
            total = tiles.countFlags(); if (!retainSeeds) tiles.clear(); phase = "done";
            return true;
        }
        return false;
    }
}
