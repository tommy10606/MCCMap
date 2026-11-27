package org.dynmap.web.https;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;

class Http01ServerTest {
    private String get(Http01Server server,String method,String path,String host)throws Exception {
        try(var socket=new java.net.Socket("127.0.0.1",server.port())) {
            socket.setSoTimeout(5000);socket.getOutputStream().write((method+" "+path+" HTTP/1.1\r\nHost: "+host+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII);
        }
    }
    @Test void servesOnlyLiveTokensForConfiguredHostAndUsesAStaticRedirect()throws Exception {
        var settings=new HttpsSettings("map.example.com",443,true,true,"","127.0.0.1",0,true);
        try(var server=new Http01Server(settings)) {
            server.start();String token="abcdefghijklmnopqrstuvwxyz0123456789",authorization=token+".abcdefghijklmnopqrstuvwxyz0123456789";
            try(var lease=server.publish(token,authorization)) {
                String response=get(server,"GET","/.well-known/acme-challenge/"+token,"map.example.com");assertTrue(response.startsWith("HTTP/1.1 200"));assertTrue(response.endsWith(authorization));
                assertTrue(get(server,"GET","/.well-known/acme-challenge/"+token,"evil.example").startsWith("HTTP/1.1 404"));
                assertTrue(get(server,"POST","/.well-known/acme-challenge/"+token,"map.example.com").startsWith("HTTP/1.1 405"));
                String traversal=get(server,"GET","/.well-known/acme-challenge/../store-password","map.example.com");assertFalse(traversal.startsWith("HTTP/1.1 200"));assertFalse(traversal.contains("private-key"));
                String redirect=get(server,"GET","/tiles?next=https://evil.example/","map.example.com");assertTrue(redirect.startsWith("HTTP/1.1 308"),redirect);assertTrue(redirect.toLowerCase(java.util.Locale.ROOT).contains("location: https://map.example.com/"),redirect);
            }
            assertTrue(get(server,"GET","/.well-known/acme-challenge/"+token,"map.example.com").startsWith("HTTP/1.1 404"));
        }
    }
}
