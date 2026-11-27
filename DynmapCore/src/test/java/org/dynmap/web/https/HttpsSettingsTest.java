package org.dynmap.web.https;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.dynmap.ConfigurationNode;
import org.junit.jupiter.api.Test;

class HttpsSettingsTest {
    @Test void disabledHttpsNeverNeedsAHostnameOrAcceptsTerms() {assertNull(HttpsSettings.read(new ConfigurationNode(),8123));}
    @Test void requiresExplicitTermsAndValidPublicHostname() {
        var config=new ConfigurationNode();config.put("https",new LinkedHashMap<>(Map.of("enabled",true,"domain","map.example.com")));
        assertThrows(IllegalArgumentException.class,()->HttpsSettings.read(config,8123));
        config.getNode("https").put("letsencrypt",Map.of("agree-to-terms",true));
        var settings=HttpsSettings.read(config,8123);assertEquals("https://map.example.com:8123/",settings.url());assertEquals(8080,settings.challengePort());assertTrue(settings.directory().startsWith("https://acme-v02.api.letsencrypt.org/"));
        for(String bad:List.of("","https://example.com","example.com:8123","../keys","*.example.com","127.0.0.1","localhost","good.com\r\nLocation: evil.com","good..com","-bad.example","trailing.example."))assertThrows(IllegalArgumentException.class,()->HttpsSettings.hostname(bad),bad);
    }
    @Test void normalizesIdnAndKeepsStagingSeparateFromProductionAndManualFiles() {
        assertEquals("xn--bcher-kva.example",HttpsSettings.hostname("Bücher.example"));
        var prod=new HttpsSettings("map.example.com",443,true,false,"","127.0.0.1",8080,true);
        var stage=new HttpsSettings("map.example.com",443,true,true,"","127.0.0.1",8080,true);
        var manual=new HttpsSettings("map.example.com",443,false,false,"","127.0.0.1",8080,true);
        assertNotEquals(prod.scope(),stage.scope());assertNotEquals(prod.scope(),manual.scope());assertEquals("https://map.example.com/",prod.url());
    }
}
