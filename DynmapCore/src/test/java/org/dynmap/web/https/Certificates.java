package org.dynmap.web.https;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

final class Certificates {
    static CertificateIssuer.Material create(String domain,int serial,Instant before,Instant after)throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        var name=new X500Name("CN="+domain);var builder=new JcaX509v3CertificateBuilder(name,BigInteger.valueOf(serial),Date.from(before),Date.from(after),name,pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName,false,new GeneralNames(new GeneralName(GeneralName.dNSName,domain)));
        var cert=new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
        return new CertificateIssuer.Material(pair.getPrivate(),List.of(cert));
    }
}
