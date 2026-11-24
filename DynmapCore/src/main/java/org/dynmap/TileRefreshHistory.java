package org.dynmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import org.dynmap.storage.MapStorageTile;

/** Persistent render dates in fixed-size grids; image changes are tracked separately. */
final class TileRefreshHistory {
    private static final int GRID_BYTES = 32 * 32 * Long.BYTES;
    private static final int MAX_CACHED_GRIDS = 128;
    private static final java.util.regex.Pattern NAMESPACE = java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private record Update(String namespace, int x, int y, long timestamp) {}
    private static final class Grid {
        final ByteBuffer data = ByteBuffer.allocate(GRID_BYTES);
        boolean dirty;
    }
    private final Path directory;
    private final ArrayBlockingQueue<Update> updates = new ArrayBlockingQueue<>(4096);
    private final LinkedHashMap<String, Grid> grids = new LinkedHashMap<>(128, 0.75f, true);

    TileRefreshHistory(Path directory, Path webDirectory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Path parent = this.directory.getParent().toRealPath();
        Path site = webDirectory.toRealPath();
        if (parent.resolve(this.directory.getFileName()).startsWith(site)
            || Files.isSymbolicLink(this.directory)) throw new IOException("Unsafe history directory");
        try { Files.createDirectory(this.directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        catch (FileAlreadyExistsException exists) { if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)) throw exists; }
        catch (UnsupportedOperationException nonPosix) { Files.createDirectories(this.directory); }
        try { Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException nonPosix) { /* Host ACLs apply. */ }
    }

    static String namespace(MapStorageTile tile) {
        return namespace(tile.world.getName() + "\0" + tile.map.getName() + "\0" + tile.map.getPrefix() + "\0" + tile.var.name());
    }
    static String namespace(String identity) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static int offset(int x, int y) { return (((y & 31) * 32) + (x & 31)) * Long.BYTES; }
    private static String key(String namespace, int x, int y) {
        if (!NAMESPACE.matcher(namespace).matches()) throw new IllegalArgumentException("Invalid namespace");
        return namespace + "_" + (x >> 5) + "_" + (y >> 5) + ".age";
    }
    private Grid grid(String key) throws IOException {
        Grid grid = grids.get(key);
        if (grid != null) return grid;
        Path path = directory.resolve(key);
        grid = new Grid();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe history file");
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (channel.size() == GRID_BYTES) {
                    while (grid.data.hasRemaining() && channel.read(grid.data) > 0) { }
                    grid.data.clear();
                }
            }
        }
        if (grids.size() >= MAX_CACHED_GRIDS) {
            var oldest = grids.entrySet().iterator();
            var entry = oldest.next(); write(entry.getKey(), entry.getValue()); oldest.remove();
        }
        grids.put(key, grid);
        return grid;
    }
    synchronized long lastSuccessful(String namespace, int x, int y) throws IOException {
        return grid(key(namespace, x, y)).data.getLong(offset(x, y));
    }
    /** Render threads never wait for filesystem I/O. A full queue simply leaves the tile eligible later. */
    boolean record(String namespace, int x, int y, long timestamp) {
        return timestamp > 0 && updates.offer(new Update(namespace, x, y, timestamp));
    }
    synchronized void flush() throws IOException {
        Update update;
        while ((update = updates.poll()) != null) {
            Grid grid = grid(key(update.namespace, update.x, update.y));
            int offset = offset(update.x, update.y);
            long previous = grid.data.getLong(offset);
            if (update.timestamp > previous || previous > System.currentTimeMillis() + 300000L) {
                grid.data.putLong(offset, update.timestamp); grid.dirty = true;
            }
        }
        for (var entry : grids.entrySet()) write(entry.getKey(), entry.getValue());
    }
    private void write(String key, Grid grid) throws IOException {
        if (!grid.dirty) return;
        Path pending;
        try { pending = Files.createTempFile(directory, ".pending-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException nonPosix) { pending = Files.createTempFile(directory, ".pending-", ".tmp"); }
        try {
            Files.write(pending, grid.data.array());
            try { Files.move(pending, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException noAtomicMove) { Files.move(pending, directory.resolve(key), StandardCopyOption.REPLACE_EXISTING); }
            grid.dirty = false;
        } finally { Files.deleteIfExists(pending); }
    }
}
