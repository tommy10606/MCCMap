package org.dynmap.web.https;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.eclipse.jetty.server.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NativeHttpsTest {
    @TempDir Path temp;
    private final HttpsSettings settings=new HttpsSettings("map.example.com",443,true,true,"","127.0.0.1",0,true);
    private Server server(NativeHttps https) {
        var server=new Server();var connector=https.connector(server);connector.setHost("127.0.0.1");connector.setPort(0);server.addConnector(connector);return server;
    }
    private Runnable starter(Server server){return ()->{try{server.start();}catch(Exception failure){throw new IllegalStateException(failure);}};}
    private java.security.cert.X509Certificate peer(Server server)throws Exception {
        var trust=new X509TrustManager(){public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}public void checkClientTrusted(java.security.cert.X509Certificate[] c,String a){}public void checkServerTrusted(java.security.cert.X509Certificate[] c,String a){}};
        var context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{trust},null);
        int port=((ServerConnector)server.getConnectors()[0]).getLocalPort();
        try(var raw=new java.net.Socket("127.0.0.1",port);var socket=(SSLSocket)context.getSocketFactory().createSocket(raw,"map.example.com",port,true)) {
            socket.setSoTimeout(5000);socket.startHandshake();return (java.security.cert.X509Certificate)socket.getSession().getPeerCertificates()[0];
        }
    }
    @Test void firstFailureNeverStartsPlaintextAndRetrySurvivesRestart()throws Exception {
        var calls=new AtomicInteger();CertificateIssuer failed=()->{calls.incrementAndGet();throw new java.io.IOException("secret details must not be logged");};
        var store=new TlsStore(temp,temp.resolve("web"),settings);
        try(var https=new NativeHttps(settings,store,failed)) {
            var server=server(https);try{https.start(server,starter(server),false);https.check();assertFalse(server.isStarted());assertEquals(1,calls.get());https.check();assertEquals(1,calls.get());}finally{server.stop();}
        }
        try(var https=new NativeHttps(settings,new TlsStore(temp,temp.resolve("web"),settings),failed)) {
            var server=server(https);try{https.start(server,starter(server),false);https.check();assertFalse(server.isStarted());assertEquals(1,calls.get());}finally{server.stop();}
        }
    }
    @Test void startsWithCachedCertificateAndKeepsItDuringAnOutage()throws Exception {
        var now=Instant.now();var material=Certificates.create(settings.domain(),1,now.minusSeconds(800),now.plusSeconds(200));
        var store=new TlsStore(temp,temp.resolve("web"),settings);store.install(settings.domain(),material.key(),material.chain());
        try(var https=new NativeHttps(settings,store,()->{throw new java.io.IOException("CA down");})) {
            var server=server(https);try{https.start(server,starter(server),false);assertTrue(server.isStarted());assertEquals(material.chain().getFirst(),peer(server));https.check();assertTrue(server.isStarted());assertEquals(material.chain().getFirst(),peer(server));}finally{server.stop();}
        }
    }
    @Test void issuesThenHotReloadsWithoutRestartingTheMap()throws Exception {
        var now=Instant.now();var first=Certificates.create(settings.domain(),2,now.minusSeconds(800),now.plusSeconds(200));
        var renewed=Certificates.create(settings.domain(),3,now.minusSeconds(5),now.plusSeconds(10000));
        var calls=new AtomicInteger();CertificateIssuer issuer=()->calls.getAndIncrement()==0?first:renewed;
        var starts=new AtomicInteger();
        try(var https=new NativeHttps(settings,new TlsStore(temp,temp.resolve("web"),settings),issuer)) {
            var server=server(https);try{https.start(server,()->{starts.incrementAndGet();starter(server).run();},false);assertFalse(server.isStarted());https.check();assertEquals(first.chain().getFirst(),peer(server));https.check();assertEquals(renewed.chain().getFirst(),peer(server));assertEquals(1,starts.get());https.check();assertEquals(2,calls.get());}finally{server.stop();}
        }
    }
    @Test void closedControllerCannotStartTheMap()throws Exception {
        var now=Instant.now();var material=Certificates.create(settings.domain(),4,now.minusSeconds(5),now.plusSeconds(10000));
        var https=new NativeHttps(settings,new TlsStore(temp,temp.resolve("web"),settings),()->material);var server=server(https);
        try{https.start(server,starter(server),false);https.close();https.check();assertFalse(server.isStarted());}finally{https.close();server.stop();}
    }
    @Test void expiryStopsListenerIfRenewalFails()throws Exception {
        var now=Instant.now();var material=Certificates.create(settings.domain(),5,now.minusSeconds(800),now.plusSeconds(200));
        var store=new TlsStore(temp,temp.resolve("web"),settings);store.install(settings.domain(),material.key(),material.chain());
        try(var https=new NativeHttps(settings,store,()->{throw new java.io.IOException("CA down");},Clock.fixed(now.plusSeconds(300),ZoneOffset.UTC))) {
            var server=server(https);try{https.start(server,starter(server),false);https.check();assertFalse(server.isStarted());assertTrue(https.describe().contains("expired"));}finally{server.stop();}
        }
    }
    @Test void caRetryAfterPersistsAcrossRestarts()throws Exception {
        var now=Instant.now();var later=now.plusSeconds(10800);var calls=new AtomicInteger();
        var limited=org.mockito.Mockito.mock(org.shredzone.acme4j.exception.AcmeRateLimitedException.class);
        org.mockito.Mockito.when(limited.getRetryAfter()).thenReturn(java.util.Optional.of(later));
        CertificateIssuer issuer=()->{calls.incrementAndGet();throw limited;};
        var store=new TlsStore(temp,temp.resolve("web"),settings);
        try(var https=new NativeHttps(settings,store,issuer,Clock.fixed(now,ZoneOffset.UTC))) {
            var server=server(https);try{https.start(server,starter(server),false);https.check();assertEquals(1,calls.get());assertFalse(server.isStarted());}finally{server.stop();}
        }
        try(var https=new NativeHttps(settings,store,issuer,Clock.fixed(now.plusSeconds(3601),ZoneOffset.UTC))) {
            var server=server(https);try{https.start(server,starter(server),false);https.check();assertEquals(1,calls.get());assertFalse(server.isStarted());}finally{server.stop();}
        }
        try(var https=new NativeHttps(settings,store,issuer,Clock.fixed(later.plusSeconds(1),ZoneOffset.UTC))) {
            var server=server(https);try{https.start(server,starter(server),false);https.check();assertEquals(2,calls.get());}finally{server.stop();}
        }
    }
    @Test void manualModeWithoutACertificateStaysOffline()throws Exception {
        var manual=new HttpsSettings(settings.domain(),443,false,false,"","127.0.0.1",8080,true);
        try(var https=new NativeHttps(manual,new TlsStore(temp,temp.resolve("web"),manual),null)) {
            var server=server(https);try{https.start(server,starter(server),false);https.check();assertFalse(server.isStarted());assertTrue(https.describe().contains("failed"));}finally{server.stop();}
        }
    }

}
