package org.dynmap.web.https;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.dynmap.Log;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.shredzone.acme4j.exception.AcmeRateLimitedException;

/** Certificate work never occupies a Minecraft or map-render thread. */
public final class NativeHttps implements AutoCloseable {
    private final HttpsSettings settings;
    private final Clock clock;
    private final TlsStore store;
    private final SslContextFactory.Server ssl=new SslContextFactory.Server();
    private final Http01Server challenge;
    private final CertificateIssuer issuer;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{var t=new Thread(task,"MCCMap certificate renewal");t.setDaemon(true);return t;});
    private Server mapServer;
    private Runnable startMap;
    private boolean closed,challengeStarted;
    private volatile String state="not started";
    private X509Certificate activeCertificate;
    private byte[] activeFingerprint;
    private long retryAt,caNotBefore;

    public NativeHttps(HttpsSettings settings,Path data,Path web)throws Exception {this(settings,new TlsStore(data,web,settings),null);}
    NativeHttps(HttpsSettings settings,TlsStore store,CertificateIssuer testIssuer)throws Exception {this(settings,store,testIssuer,Clock.systemUTC());}
    NativeHttps(HttpsSettings settings,TlsStore store,CertificateIssuer testIssuer,Clock clock)throws Exception {
        this.clock=clock;this.settings=settings;this.store=store;challenge=settings.letsEncrypt()?new Http01Server(settings):null;
        issuer=testIssuer!=null?testIssuer:settings.letsEncrypt()?new AcmeCertificateIssuer(settings,store,challenge):null;
        ssl.setIncludeProtocols("TLSv1.3","TLSv1.2");ssl.setCertAlias("mccmap");ssl.setSniRequired(true);ssl.setKeyStorePassword(new String(store.password()));
        byte[] retry=store.read("retry-state",128);
        if(retry!=null)try{String[] values=new String(retry,StandardCharsets.US_ASCII).split(" ");retryAt=Math.max(0,Long.parseLong(values[0]));caNotBefore=values.length>1?Math.max(0,Long.parseLong(values[1])):0;}catch(RuntimeException invalid){throw new java.io.IOException("Invalid HTTPS retry state");}
    }
    public NetworkTrafficServerConnector connector(Server server) {
        var http=new HttpConfiguration();http.setSecureScheme("https");http.setSecurePort(settings.publicPort());http.setSendServerVersion(false);
        var secure=new SecureRequestCustomizer();secure.setSniHostCheck(true);http.addCustomizer(secure);
        var httpFactory=new HttpConnectionFactory(http);
        if(!settings.redirect())return new NetworkTrafficServerConnector(server,httpFactory,ssl);
        var tls=new SslConnectionFactory(ssl,httpFactory.getProtocol());
        var detector=new DetectorConnectionFactory(tls);
        var connector=new NetworkTrafficServerConnector(server,httpFactory);
        connector.setConnectionFactories(List.of(detector,httpFactory));
        connector.setDefaultProtocol(detector.getProtocol());
        return connector;
    }
    public Handler protect(Handler map){return new HttpsRedirectHandler(settings,map);}
    public void start(Server server,Runnable startMap)throws Exception {start(server,startMap,true);}
    void start(Server server,Runnable startMap,boolean schedule)throws Exception {
        synchronized(this){if(closed)return;mapServer=server;this.startMap=startMap;}
        // A valid cached certificate keeps HTTPS available even if the CA is down.
        try{var cached=store.load(settings.domain());if(cached!=null)apply(cached);}catch(Exception invalid){state="waiting for a valid certificate";}
        if(schedule)worker.scheduleWithFixedDelay(this::check,0,1,TimeUnit.HOURS);
        if(activeCertificate==null)Log.info("HTTPS is waiting for a certificate; the map listener will start after a valid certificate is installed. Public HTTP fallback is disabled.");
        if(settings.letsEncrypt()&&settings.staging())Log.warning("HTTPS uses Let's Encrypt staging; browsers will not trust staging certificates.");
    }
    private void ensureChallenge()throws Exception {
        synchronized(this){if(closed)throw new InterruptedException();if(challenge!=null&&!challengeStarted){try{challenge.start();challengeStarted=true;}catch(Exception failure){challenge.close();throw failure;}}}
    }
    void check() {
        synchronized(this){if(closed)return;}
        try {
            try{var cached=store.load(settings.domain());if(cached!=null)apply(cached);}catch(Exception invalid){if(issuer==null)throw invalid;}
            boolean due=activeCertificate==null || !clock.instant().isBefore(TlsStore.renewAt(activeCertificate));
            // The HTTP listener redirects browsers between renewals too.
            if(challenge!=null && clock.millis()>=retryAt)ensureChallenge();
            if(issuer==null){if(activeCertificate==null)throw new java.io.IOException("External TLS certificate is missing");state="active (external PKCS12 certificate)";return;}
            if(!due){state="active; certificate expires "+activeCertificate.getNotAfter().toInstant();return;}
            if(clock.millis()<Math.max(retryAt,caNotBefore)){state="certificate retry scheduled for "+Instant.ofEpochMilli(Math.max(retryAt,caNotBefore));return;}
            state="obtaining certificate";var material=issuer.issue();
            synchronized(this) {
                if(closed)return;
                var installed=store.install(settings.domain(),material.key(),material.chain());
                issuer.committed();apply(installed);retryAt=0;caNotBefore=0;store.delete("retry-state");
                Log.info("HTTPS certificate installed for "+settings.domain()+"; expires "+activeCertificate.getNotAfter().toInstant()+". Renewal is automatic.");
            }
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
        catch(Exception failure) {
            synchronized(this){if(closed)return;}
            retryAt=clock.millis()+TimeUnit.HOURS.toMillis(1);
            if(failure instanceof AcmeRateLimitedException limited)caNotBefore=limited.getRetryAfter().orElse(Instant.ofEpochMilli(retryAt)).toEpochMilli();
            retryAt=Math.max(retryAt,caNotBefore);state="certificate work failed; next retry "+Instant.ofEpochMilli(retryAt);
            try{store.write("retry-state",(retryAt+" "+caNotBefore).getBytes(StandardCharsets.US_ASCII));}catch(Exception ignored){Log.warning("HTTPS retry schedule could not be saved; check private TLS storage permissions");}
            Log.warning("HTTPS certificate work failed ("+failure.getClass().getSimpleName()+"). Check DNS, public TCP port 80, map port, server clock and private TLS files. Next retry: "+Instant.ofEpochMilli(retryAt)+". No public HTTP fallback.");
        } finally {
            synchronized(this) {
                if(!closed && activeCertificate!=null && !activeCertificate.getNotAfter().toInstant().isAfter(clock.instant()) && mapServer!=null && mapServer.isStarted())try{mapServer.stop();state="certificate expired; HTTPS listener stopped";Log.severe("HTTPS certificate expired; map listener stopped. Rendering remains enabled.");}catch(Exception ignored){Log.severe("HTTPS listener could not be stopped after certificate expiry");}
            }
        }
    }
    private synchronized void apply(KeyStore keys)throws Exception {
        if(closed)return;var digest=MessageDigest.getInstance("SHA-256");for(var cert:keys.getCertificateChain("mccmap"))digest.update(cert.getEncoded());byte[] fingerprint=digest.digest();
        if(!Arrays.equals(activeFingerprint,fingerprint)) {
            if(ssl.isStarted())ssl.reload(factory->factory.setKeyStore(keys));else ssl.setKeyStore(keys);
            activeCertificate=TlsStore.leaf(keys);activeFingerprint=fingerprint;
        }
        if(mapServer!=null&&!mapServer.isStarted())startMap.run();
        state="active; certificate expires "+activeCertificate.getNotAfter().toInstant();
    }
    public String describe(){return "HTTPS: "+state+"; "+settings.url();}
    public String url(){return settings.url();}
    @Override public void close() {
        synchronized(this){if(closed)return;closed=true;state="stopped";}
        worker.shutdownNow();if(challenge!=null)challenge.close();
    }
}
