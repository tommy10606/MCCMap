package org.dynmap.web.https;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.shredzone.acme4j.Session;

/** Optional real ACME protocol/HTTP-01 test against a local Pebble CA, never production. */
@EnabledIfEnvironmentVariable(named="MCCMAP_PEBBLE_CERT",matches=".+")
class PebbleAcmeTest {
    @TempDir Path root;
    @Test void validatesRealHttp01IssuesResumesAndRenewsWithPersistedKeys()throws Exception {
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(var input=Files.newInputStream(Path.of(System.getenv("MCCMAP_PEBBLE_CERT")))){trust.setCertificateEntry("pebble",CertificateFactory.getInstance("X.509").generateCertificate(input));}
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);
        var client=HttpClient.newBuilder().sslContext(ssl).connectTimeout(java.time.Duration.ofSeconds(5)).build();
        Session session=new Session("https://localhost:14000/dir"){@Override public HttpClient getHttpClient(){return client;}};
        var settings=new HttpsSettings("mccmap-test.example",443,true,true,"","127.0.0.1",18080,true);
        var store=new TlsStore(root,root.resolve("web"),settings);var key=store.key("acme-account.pk8").getPublic();
        try(var responder=new Http01Server(settings)) {
            responder.start();var issuer=new AcmeCertificateIssuer(settings,store,responder,session);
            var first=issuer.issue();store.install(settings.domain(),first.key(),first.chain());
            assertNotNull(store.read("pending-order",2048));
            // Simulate a stop after download but before commit: resume the same order/key.
            var resumed=new AcmeCertificateIssuer(settings,new TlsStore(root,root.resolve("web"),settings),responder,session);
            var again=resumed.issue();assertArrayEquals(first.chain().getFirst().getEncoded(),again.chain().getFirst().getEncoded());assertArrayEquals(first.key().getEncoded(),again.key().getEncoded());resumed.committed();
            assertNull(store.read("pending-order",2048));
            var renewed=resumed.issue();store.install(settings.domain(),renewed.key(),renewed.chain());resumed.committed();
            assertNotEquals(first.chain().getFirst().getSerialNumber(),renewed.chain().getFirst().getSerialNumber());assertEquals(key,store.key("acme-account.pk8").getPublic());assertNotNull(store.load(settings.domain()));
        }
    }
}
