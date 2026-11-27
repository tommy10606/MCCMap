package org.dynmap.web.https;

import java.net.IDN;
import java.util.Locale;
import org.dynmap.ConfigurationNode;

/** Explicit opt-in; no certificate order or terms acceptance is inferred. */
public record HttpsSettings(String domain,int publicPort,boolean letsEncrypt,boolean staging,
                            String email,String challengeBind,int challengePort,boolean redirect) {
    public static HttpsSettings read(ConfigurationNode config,int mapPort) {
        if(!config.getBoolean("https/enabled",false))return null;
        String domain=hostname(config.getString("https/domain",""));
        int port=config.getInteger("https/public-port",0);if(port==0)port=mapPort;
        int challenge=config.getInteger("https/letsencrypt/challenge-port",8080);
        if(port<1||port>65535||challenge<1||challenge>65535||challenge==mapPort)throw new IllegalArgumentException("HTTPS ports must be valid and the challenge port must differ from the map port");
        boolean acme=config.getBoolean("https/letsencrypt/enabled",true);
        if(acme&&!config.getBoolean("https/letsencrypt/agree-to-terms",false))throw new IllegalArgumentException("Set https.letsencrypt.agree-to-terms only after accepting Let's Encrypt's terms");
        String email=config.getString("https/letsencrypt/email","").trim();
        if(!email.isEmpty() && (!email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+")||email.length()>254))throw new IllegalArgumentException("Invalid HTTPS certificate contact email");
        String bind=config.getString("https/letsencrypt/challenge-bindaddress","0.0.0.0").trim();
        if(bind.isEmpty()||bind.length()>253||bind.chars().anyMatch(c->c<=32||c==127))throw new IllegalArgumentException("Invalid HTTPS challenge bind address");
        return new HttpsSettings(domain,port,acme,config.getBoolean("https/letsencrypt/staging",false),email,bind,challenge,config.getBoolean("https/redirect-http",true));
    }
    public static String hostname(String input) {
        String name;
        try{name=IDN.toASCII(input,IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("HTTPS needs a public DNS hostname without scheme, port, path or wildcard");}
        if(name.length()>253||!name.contains(".")||name.matches("[0-9.]+")||name.endsWith(".")||name.contains(".."))throw new IllegalArgumentException("HTTPS needs a public DNS hostname");
        for(String label:name.split("\\."))if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))throw new IllegalArgumentException("Invalid HTTPS hostname");
        return name;
    }
    public String url(){return "https://"+domain+(publicPort==443?"":":"+publicPort)+"/";}
    String scope(){return (letsEncrypt?(staging?"staging-":"production-"):"manual-")+domain;}
    String directory(){return staging?"https://acme-staging-v02.api.letsencrypt.org/directory":"https://acme-v02.api.letsencrypt.org/directory";}
}
