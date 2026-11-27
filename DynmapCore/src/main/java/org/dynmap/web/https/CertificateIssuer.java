package org.dynmap.web.https;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

interface CertificateIssuer {
    record Material(PrivateKey key,List<X509Certificate> chain) {}
    Material issue()throws Exception;
    default void committed()throws Exception {}
}
