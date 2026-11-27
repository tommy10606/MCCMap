package org.dynmap.web.https;

/** Run with only the finished plugin JAR and this class, plus the SLF4J API normally provided by Paper. */
public final class BundledAcmeSmoke {
    public static void main(String[] args)throws Exception {
        String prefix="org.mccmap.internal.acme4j.";
        var sessionClass=Class.forName(prefix+"Session");
        Object session=sessionClass.getConstructor(String.class).newInstance("https://acme-staging-v02.api.letsencrypt.org/directory");
        if(sessionClass.getMethod("provider").invoke(session)==null)throw new AssertionError("Missing relocated ACME provider");
        var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        var csrClass=Class.forName(prefix+"util.CSRBuilder");var csr=csrClass.getConstructor().newInstance();csrClass.getMethod("addDomain",String.class).invoke(csr,"map.example.com");csrClass.getMethod("sign",java.security.KeyPair.class).invoke(csr,generator.generateKeyPair());
        if(((byte[])csrClass.getMethod("getEncoded").invoke(csr)).length<256)throw new AssertionError("Invalid bundled certificate request");
        var jwsClass=Class.forName("org.mccmap.internal.jose4j.jws.JsonWebSignature");var jws=jwsClass.getConstructor().newInstance();jwsClass.getMethod("setPayload",String.class).invoke(jws,"bundled ACME signature test");jwsClass.getMethod("setKey",java.security.Key.class).invoke(jws,generator.generateKeyPair().getPrivate());jwsClass.getMethod("setAlgorithmHeaderValue",String.class).invoke(jws,"RS256");if(((String)jwsClass.getMethod("getCompactSerialization").invoke(jws)).split("\\.").length!=3)throw new AssertionError("Invalid bundled signature");
        System.out.println("Bundled ACME provider, CSR and JWS signing passed");
    }
}
