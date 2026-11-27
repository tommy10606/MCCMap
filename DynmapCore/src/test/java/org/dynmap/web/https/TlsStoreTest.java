package org.dynmap.web.https;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsStoreTest {
    @TempDir Path root;
    HttpsSettings settings(){return new HttpsSettings("map.example.com",8123,false,false,"","127.0.0.1",8080,true);}
    @Test void privateStorePersistsKeysAndCertificatesWithoutPublishingOrReplacingAGoodCertificateWithBadData()throws Exception {
        var store=new TlsStore(root,root.resolve("web"),settings());var material=Certificates.create(settings().domain(),1,Instant.now().minusSeconds(60),Instant.now().plusSeconds(86400));
        store.install(settings().domain(),material.key(),material.chain());var before=Files.readAllBytes(store.root.resolve("certificate.p12"));
        assertEquals(1,TlsStore.leaf(store.load(settings().domain())).getSerialNumber().intValue());
        var wrong=Certificates.create("other.example.com",2,Instant.now().minusSeconds(60),Instant.now().plusSeconds(86400));
        assertThrows(Exception.class,()->store.install(settings().domain(),wrong.key(),wrong.chain()));
        assertThrows(Exception.class,()->store.install(settings().domain(),wrong.key(),material.chain()));
        assertArrayEquals(before,Files.readAllBytes(store.root.resolve("certificate.p12")));
        assertEquals(store.key("acme-account.pk8").getPublic(),new TlsStore(root,root.resolve("web"),settings()).key("acme-account.pk8").getPublic());
        assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(store.root.resolve("certificate.p12")));
        assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(store.root));
    }
    @Test void refusesPublicStoresSymlinksHardlinksAndOversizedFiles()throws Exception {
        assertThrows(Exception.class,()->new TlsStore(root,root,settings()));
        var store=new TlsStore(root,root.resolve("web"),settings());var outside=root.resolve("outside");Files.writeString(outside,"private");
        Files.createSymbolicLink(store.root.resolve("linked"),outside);assertThrows(Exception.class,()->store.read("linked",100));
        Files.createLink(store.root.resolve("hard"),outside);assertThrows(Exception.class,()->store.write("hard",new byte[]{1}));
        Files.write(store.root.resolve("large"),new byte[100]);assertThrows(Exception.class,()->store.read("large",50));
        assertThrows(Exception.class,()->store.write("../web/key",new byte[]{1}));
    }
    @Test void renewalFollowsCertificateLifetimeRatherThanAssumingNinetyDays()throws Exception {
        var start=Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);var material=Certificates.create(settings().domain(),1,start,start.plus(Duration.ofDays(6)));
        assertEquals(start.plus(Duration.ofDays(4)),TlsStore.renewAt(material.chain().getFirst()));
    }
}
