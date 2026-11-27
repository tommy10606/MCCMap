package org.dynmap.web.https;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.*;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Http01Challenge;

/** One-domain Let's Encrypt HTTP-01, with persisted in-progress orders and keys. */
final class AcmeCertificateIssuer implements CertificateIssuer {
    private final HttpsSettings settings;
    private final TlsStore store;
    private final Http01Server responder;
    private final Session testSession;
    AcmeCertificateIssuer(HttpsSettings settings,TlsStore store,Http01Server responder){this(settings,store,responder,null);}
    AcmeCertificateIssuer(HttpsSettings settings,TlsStore store,Http01Server responder,Session testSession){this.settings=settings;this.store=store;this.responder=responder;this.testSession=testSession;}
    @Override public Material issue()throws Exception {
        var session=testSession==null?new Session(settings.directory()):testSession;session.networkSettings().setTimeout(Duration.ofSeconds(30));
        var builder=new AccountBuilder().useKeyPair(store.key("acme-account.pk8")).agreeToTermsOfService();
        if(!settings.email().isEmpty())builder.addContact("mailto:"+settings.email());
        var login=builder.createLogin(session);
        byte[] pending=store.read("pending-order",2048);Order order;KeyPair key;
        if(pending!=null) {
            URI location=URI.create(new String(pending,StandardCharsets.US_ASCII));
            if(!"https".equals(location.getScheme()) || !session.getServerUri().getAuthority().equals(location.getAuthority()) || location.getUserInfo()!=null || location.getFragment()!=null || (testSession==null && !location.getPath().startsWith("/acme/order/")))throw new IOException("Invalid saved ACME order");
            if(store.read("pending-key.pk8",16384)==null)throw new IOException("Saved ACME order key is missing");
            key=store.key("pending-key.pk8");order=login.bindOrder(location.toURL());
        } else {
            store.delete("pending-key.pk8");key=store.key("pending-key.pk8");
            order=login.newOrder().domain(settings.domain()).create();store.write("pending-order",order.getLocation().toString().getBytes(StandardCharsets.US_ASCII));
        }
        var names=order.getIdentifiers();
        if(names.size()!=1||!Identifier.dns(settings.domain()).equals(names.getFirst()))throw new IOException("Saved ACME order hostname mismatch");
        if(order.getStatus()==Status.INVALID || (order.getStatus()!=Status.VALID && order.getExpires().filter(expiry->!expiry.isAfter(Instant.now())).isPresent())){committed();throw new IOException("ACME order was rejected or expired; check DNS and public port 80");}
        Instant deadline=Instant.now().plusSeconds(300);
        if(order.getStatus()==Status.PENDING)for(var auth:order.getAuthorizations()) {
            if(auth.getStatus()==Status.VALID)continue;
            var challenge=auth.findChallenge(Http01Challenge.class).orElseThrow(()->new IOException("HTTP-01 challenge unavailable"));
            try(var lease=responder.publish(challenge.getToken(),challenge.getAuthorization())) {
                if(challenge.getStatus()==Status.PENDING)challenge.trigger();
                if(auth.waitForCompletion(remaining(deadline))!=Status.VALID){committed();throw new IOException("HTTP-01 validation failed; check DNS and public port 80");}
            }
        }
        if(order.getStatus()!=Status.VALID && order.getStatus()!=Status.PROCESSING) {
            if(order.waitUntilReady(remaining(deadline))!=Status.READY)throw new IOException("ACME order did not become ready");
            order.execute(key);
        }
        if(order.getStatus()!=Status.VALID && order.waitForCompletion(remaining(deadline))!=Status.VALID)throw new IOException("ACME certificate order failed");
        return new Material(key.getPrivate(),order.getCertificate().getCertificateChain());
    }
    private static Duration remaining(Instant end)throws IOException {var duration=Duration.between(Instant.now(),end);if(duration.isNegative()||duration.isZero())throw new IOException("ACME validation timed out");return duration;}
    @Override public void committed()throws Exception {store.delete("pending-order");store.delete("pending-key.pk8");}
}
