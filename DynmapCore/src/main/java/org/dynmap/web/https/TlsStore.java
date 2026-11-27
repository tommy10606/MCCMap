package org.dynmap.web.https;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.*;
import java.time.*;
import java.util.*;

/** Private, bounded, atomic data files; none are reachable from the map's web root. */
final class TlsStore {
    static final int LIMIT=1024*1024;
    final Path root;
    private final char[] password;
    TlsStore(Path data,Path web,HttpsSettings settings)throws Exception {
        Path base=data.toAbsolutePath().normalize().resolve("tls");
        for(Path p=base;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Linked TLS directory");
        if(canonical(base).startsWith(canonical(web)))throw new IOException("TLS files would be publicly served");
        directory(base);root=base.resolve(settings.scope());directory(root);
        byte[] secret=read("store-password",512);
        if(secret==null){secret=Base64.getUrlEncoder().withoutPadding().encode(random(32));write("store-password",secret);}
        String value=new String(secret,java.nio.charset.StandardCharsets.US_ASCII);
        if(!value.matches("[A-Za-z0-9_-]{32,128}"))throw new IOException("Invalid private TLS password file");
        password=value.toCharArray();
    }
    private static Path canonical(Path path)throws IOException {
        path=path.toAbsolutePath().normalize();var suffix=new ArrayDeque<String>();
        while(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)){suffix.addFirst(path.getFileName().toString());path=path.getParent();if(path==null)throw new IOException("Invalid TLS path");}
        path=path.toRealPath();for(String name:suffix)path=path.resolve(name);return path.normalize();
    }
    private static byte[] random(int size){byte[] bytes=new byte[size];new SecureRandom().nextBytes(bytes);return bytes;}
    private static void directory(Path path)throws IOException {
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid TLS directory");
        Files.createDirectories(path);privateMode(path,true);
    }
    private static void privateMode(Path path,boolean directory)throws IOException {
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null){posix.setPermissions(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));return;}
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null)throw new IOException("Filesystem cannot protect private TLS files");
        var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if(directory)entry.setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }
    private Path safe(String name)throws IOException {
        if(!name.matches("[a-z0-9.-]{1,80}")||name.equals(".")||name.equals(".."))throw new IOException("Invalid TLS file name");
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Linked TLS path");
        Path file=root.resolve(name);
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid private TLS file");
            try{if(((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)throw new IOException("Hard-linked TLS file");}catch(UnsupportedOperationException ignored){}
            privateMode(file,false);
        }
        return file;
    }
    synchronized byte[] read(String name,int limit)throws IOException {
        Path file=safe(name);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.size(file)>Math.min(LIMIT,limit))throw new IOException("TLS file too large");
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(Math.min(LIMIT,limit)+1);if(bytes.length>Math.min(LIMIT,limit))throw new IOException("TLS file too large");return bytes;}
    }
    synchronized void write(String name,byte[] bytes)throws IOException {
        if(bytes.length>LIMIT)throw new IOException("TLS file too large");Path destination=safe(name),temp=Files.createTempFile(root,".tls-",".tmp");
        try{privateMode(temp,false);Files.write(temp,bytes);try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}safe(name);try{Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException fallback){Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temp);}
    }
    synchronized void delete(String name)throws IOException {Files.deleteIfExists(safe(name));}
    synchronized KeyPair key(String name)throws Exception {
        byte[] encoded=read(name,16384);if(encoded==null){var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();write(name,pair.getPrivate().getEncoded());return pair;}
        var factory=KeyFactory.getInstance("RSA");var privateKey=factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        if(!(privateKey instanceof java.security.interfaces.RSAPrivateCrtKey rsa)||rsa.getModulus().bitLength()<2048)throw new IOException("Invalid TLS key");
        return new KeyPair(factory.generatePublic(new RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent())),privateKey);
    }
    char[] password(){return password.clone();}
    KeyStore load(String domain)throws Exception {
        byte[] bytes=read("certificate.p12",LIMIT);if(bytes==null)return null;
        var store=KeyStore.getInstance("PKCS12");store.load(new ByteArrayInputStream(bytes),password);
        var key=(PrivateKey)store.getKey("mccmap",password);var certificates=store.getCertificateChain("mccmap");
        if(key==null||certificates==null)throw new IOException("Invalid TLS key store");
        var chain=new ArrayList<X509Certificate>();for(var cert:certificates){if(!(cert instanceof X509Certificate x))throw new IOException("Invalid certificate chain");chain.add(x);}
        validate(domain,key,chain);return store;
    }
    KeyStore install(String domain,PrivateKey key,List<X509Certificate> chain)throws Exception {
        validate(domain,key,chain);var store=KeyStore.getInstance("PKCS12");store.load(null,password);
        store.setKeyEntry("mccmap",key,password,chain.toArray(new java.security.cert.Certificate[0]));
        var bytes=new ByteArrayOutputStream();store.store(bytes,password);write("certificate.p12",bytes.toByteArray());return store;
    }
    static X509Certificate leaf(KeyStore store)throws Exception{return (X509Certificate)store.getCertificate("mccmap");}
    static void validate(String domain,PrivateKey key,List<X509Certificate> chain)throws Exception {
        if(chain.isEmpty()||chain.size()>10)throw new IOException("Invalid TLS chain length");var leaf=chain.getFirst();leaf.checkValidity();
        boolean matches=false;var names=leaf.getSubjectAlternativeNames();
        if(names!=null)for(var name:names)if(Integer.valueOf(2).equals(name.get(0)) && domain.equalsIgnoreCase(String.valueOf(name.get(1))))matches=true;
        if(!matches||leaf.getBasicConstraints()>=0)throw new IOException("Certificate does not cover HTTPS hostname");
        String algorithm=switch(key.getAlgorithm()){case "RSA"->"SHA256withRSA";case "EC"->"SHA256withECDSA";default->throw new IOException("Unsupported TLS key");};
        if(key instanceof java.security.interfaces.RSAKey rsa && rsa.getModulus().bitLength()<2048)throw new IOException("TLS RSA key too small");
        if(key instanceof java.security.interfaces.ECKey ec && ec.getParams().getCurve().getField().getFieldSize()<256)throw new IOException("TLS EC key too small");
        var signature=Signature.getInstance(algorithm);byte[] proof="MCCMap TLS key check".getBytes(java.nio.charset.StandardCharsets.US_ASCII);signature.initSign(key);signature.update(proof);byte[] signed=signature.sign();signature.initVerify(leaf.getPublicKey());signature.update(proof);if(!signature.verify(signed))throw new IOException("Certificate and key do not match");
        for(int i=0;i<chain.size()-1;i++){chain.get(i+1).checkValidity();chain.get(i).verify(chain.get(i+1).getPublicKey());}
    }
    static Instant renewAt(X509Certificate cert) {
        long lifetime=Math.max(1,Duration.between(cert.getNotBefore().toInstant(),cert.getNotAfter().toInstant()).getSeconds());
        return cert.getNotAfter().toInstant().minusSeconds(Math.max(1,lifetime/3));
    }
}
