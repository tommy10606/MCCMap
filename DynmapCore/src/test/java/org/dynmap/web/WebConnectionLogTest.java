package org.dynmap.web;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class WebConnectionLogTest {
    @TempDir Path folder;
    @Test void discardsDnsLabelsLogInjectionAndUnresolvedNames() throws Exception {
        var address = InetAddress.getByAddress("${jndi:ldap://bad}\r\nFORGED\u001b",new byte[]{127,0,0,1});
        assertEquals("2026-11-07T00:00:00Z CONNECT 127.0.0.1:43210\n",WebConnectionLog.format(Instant.parse("2026-11-07T00:00:00Z"),new InetSocketAddress(address,43210),ZoneOffset.UTC));
        assertNull(WebConnectionLog.format(Instant.now(),InetSocketAddress.createUnresolved("evil\r\nFORGED",80),ZoneOffset.UTC));
    }
    @Test void ipv6IsNumericBracketedAndHasNoScopeName() throws Exception {
        var address = Inet6Address.getByAddress("bad\nname",InetAddress.getByName("fe80::1").getAddress(),7);
        assertEquals("2026-11-07T00:00:00Z CONNECT [fe80:0:0:0:0:0:0:1]:54321\n",WebConnectionLog.format(Instant.parse("2026-11-07T00:00:00Z"),new InetSocketAddress(address,54321),ZoneOffset.UTC));
    }
    @Test void rotationAndPermissionsAreBounded() throws Exception {
        WebConnectionLog logger = new WebConnectionLog(folder,1024,2);
        logger.start();
        for(int i=0;i<180;i++) {
            int port=40000+i;
            logger.opened(new Socket(){ @Override public SocketAddress getRemoteSocketAddress(){return new InetSocketAddress("127.0.0.1",port);} });
        }
        logger.stop();
        try(var files=Files.list(folder)) {
            var paths=files.toList(); assertTrue(paths.size()<=3);assertTrue(paths.size()>1);
            for(Path file:paths){assertTrue(Files.size(file)<=1024);assertTrue(Files.readString(file).lines().allMatch(x->x.matches("[0-9TZ:.+\\-]+ CONNECT 127\\.0\\.0\\.1:[0-9]+")));
                if(Files.getFileStore(file).supportsFileAttributeView("posix"))assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));
            }
        }
    }
    @Test void refusesSymlinkAndHardlinkTargets() throws Exception {
        Path target=folder.resolve("important.txt");Files.writeString(target,"UNCHANGED");
        Path logs=Files.createDirectory(folder.resolve("logs"));
        Files.createSymbolicLink(logs.resolve("web-connections.log"),target);
        assertThrows(Exception.class,()->new WebConnectionLog(logs,1024,2).start());
        assertEquals("UNCHANGED",Files.readString(target));
        Files.delete(logs.resolve("web-connections.log"));
        Files.createLink(logs.resolve("web-connections.log"),target);
        assertThrows(Exception.class,()->new WebConnectionLog(logs,1024,2).start());
        assertEquals("UNCHANGED",Files.readString(target));
    }
}
