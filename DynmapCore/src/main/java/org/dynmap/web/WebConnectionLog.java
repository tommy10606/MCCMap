// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.dynmap.Log;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.util.component.AbstractLifeCycle;

/** Bounded, rotating TCP peer log. No request-controlled text is ever serialized. */
public final class WebConnectionLog extends AbstractLifeCycle implements NetworkTrafficListener {
    private final Path folder;
    private final ZoneId timezone;
    private final long maxBytes;
    private final int retainedFiles;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1024);
    private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running;
    private Thread worker;
    private FileChannel channel;
    private long size;

    public WebConnectionLog(Path folder, long maxBytes, int retainedFiles) {
        this(folder, maxBytes, retainedFiles, "system");
    }
    public WebConnectionLog(Path folder, long maxBytes, int retainedFiles, String timezone) {
        this.timezone = resolveTimezone(timezone, ZoneId.systemDefault(), Log::warning);
        this.folder = folder.toAbsolutePath().normalize();
        this.maxBytes = Math.max(1024, Math.min(100L * 1024 * 1024, maxBytes));
        this.retainedFiles = Math.max(1, Math.min(20, retainedFiles));
    }
    static ZoneId resolveTimezone(String configured, ZoneId host, java.util.function.Consumer<String> warning) {
        if (configured == null || configured.isBlank() || configured.trim().equalsIgnoreCase("system")) return host;
        try {
            if (configured.length() > 128) throw new java.time.DateTimeException("Invalid timezone");
            return ZoneId.of(configured.trim());
        } catch (java.time.DateTimeException invalid) {
            warning.accept("Invalid webserver-connection-log timezone; using the host server timezone");
            return host;
        }
    }
    static String timestamp(Instant instant, ZoneId zone) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone).format(instant);
    }
    /** Uses address bytes, never reverse DNS, host labels, forwarding headers or URI data. */
    static String format(Instant instant, SocketAddress peer, ZoneId timezone) {
        if (!(peer instanceof InetSocketAddress socket) || socket.isUnresolved() || socket.getPort() < 1) return null;
        try {
            byte[] address = socket.getAddress().getAddress();
            if (address.length != 4 && address.length != 16) return null;
            InetAddress numeric = InetAddress.getByAddress(address); // Removes IPv6 scope labels.
            String ip = numeric.getHostAddress();
            String remote = numeric instanceof java.net.Inet6Address ? "[" + ip + "]:" + socket.getPort() : ip + ":" + socket.getPort();
            return timestamp(instant, timezone) + " CONNECT " + remote + "\n";
        } catch (java.net.UnknownHostException impossible) { return null; }
    }
    @Override public void opened(Socket socket) {
        if (!running) return;
        String record = format(Instant.now(), socket.getRemoteSocketAddress(), timezone);
        if (record != null && !queue.offer(record)) dropped.incrementAndGet(); // Overload drops records instead of blocking server/network threads.
    }
    @Override protected void doStart() throws Exception {
        for (Path current = folder; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Connection log path contains a symbolic link");
        }
        Files.createDirectories(folder);
        setPermissions(folder, "rwx------");
        try { open(); } catch (IOException failure) { if (channel != null) channel.close(); throw failure; }
        running = true;
        worker = new Thread(this::writeRecords, "MCCMap web connection log");
        worker.setDaemon(true);
        worker.start();
        super.doStart();
    }
    private void writeRecords() {
        try {
            while (running || !queue.isEmpty() || dropped.get() != 0) {
                long lost = dropped.getAndSet(0);
                if (lost > 0) writeLine(timestamp(Instant.now(), timezone) + " DROPPED " + lost + "\n");
                String record;
                try { record = queue.poll(250, TimeUnit.MILLISECONDS); }
                catch (InterruptedException stop) { continue; }
                if (record == null) continue;
                writeLine(record);
            }
        } catch (IOException failure) {
            running = false;
            queue.clear();
            Log.warning("Web connection logging stopped after a file I/O failure");
        } finally {
            try { if (channel != null) channel.close(); } catch (IOException ignored) { }
        }
    }
    private void writeLine(String record) throws IOException {
        byte[] bytes = record.getBytes(StandardCharsets.US_ASCII);
        if (size + bytes.length > maxBytes) rotate();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        size += bytes.length;
    }
    private Path file(int index) { return folder.resolve(index == 0 ? "web-connections.log" : "web-connections." + index + ".log"); }
    private void open() throws IOException {
        Path file = file(0);
        verifyFile(file);
        channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
        setPermissions(file, "rw-------");
        size = channel.size();
    }
    private static void verifyFile(Path file) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe connection log file");
            try { if (((Number) Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw new IOException("Connection log file has multiple links"); }
            catch (UnsupportedOperationException unsupported) { /* Non-POSIX host. */ }
        }
    }
    private void rotate() throws IOException {
        channel.close();
        for (int i = 0; i <= retainedFiles; i++) verifyFile(file(i));
        Files.deleteIfExists(file(retainedFiles));
        for (int i = retainedFiles - 1; i >= 0; i--) {
            if (Files.exists(file(i), LinkOption.NOFOLLOW_LINKS)) Files.move(file(i), file(i + 1));
        }
        open();
    }
    private static void setPermissions(Path path, String mode) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    }
    @Override protected void doStop() throws Exception {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker.join(5000);
            if (worker.isAlive() && channel != null) { channel.close(); worker.join(1000); }
        }
        super.doStop();
    }
}
