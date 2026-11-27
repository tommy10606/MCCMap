package org.dynmap.web.https;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

/** The HTTP listener exposes only active ACME tokens and a fixed HTTPS redirect. */
final class Http01Server implements AutoCloseable {
    private record Token(String value,Instant expires) {}
    private final ConcurrentHashMap<String,Token> tokens=new ConcurrentHashMap<>();
    private final HttpsSettings settings;
    private Server server;
    private ServerConnector connector;
    Http01Server(HttpsSettings settings){this.settings=settings;}
    AutoCloseable publish(String token,String authorization) {
        if(!token.matches("[A-Za-z0-9_-]{16,256}")||!authorization.matches(java.util.regex.Pattern.quote(token)+"\\.[A-Za-z0-9_-]{32,256}"))throw new IllegalArgumentException("Invalid ACME token");
        if(tokens.size()>=4)throw new IllegalStateException("Too many ACME challenges");
        var value=new Token(authorization,Instant.now().plusSeconds(600));tokens.put(token,value);
        return ()->tokens.remove(token,value);
    }
    void start()throws Exception {
        var pool=new QueuedThreadPool(8,2,5000);pool.setName("MCCMap ACME HTTP");pool.setDaemon(true);pool.setReservedThreads(0);
        server=new Server(pool);var config=new HttpConfiguration();config.setSendServerVersion(false);config.setRequestHeaderSize(4096);
        connector=new ServerConnector(server,1,1,new HttpConnectionFactory(config));connector.setHost(settings.challengeBind());connector.setPort(settings.challengePort());connector.setIdleTimeout(5000);connector.setAcceptQueueSize(16);
        server.addConnector(connector);server.addBean(new ConnectionLimit(32,connector));
        server.setHandler(new Handler.Abstract(){
            @Override public boolean handle(Request request,Response response,org.eclipse.jetty.util.Callback callback) {
                response.getHeaders().put(HttpHeader.CACHE_CONTROL,"no-store");response.getHeaders().put("X-Content-Type-Options","nosniff");
                boolean head="HEAD".equals(request.getMethod());int status=404;String body="Not found\n";
                if(!"GET".equals(request.getMethod())&&!head){status=405;body="Method not allowed\n";response.getHeaders().put(HttpHeader.ALLOW,"GET, HEAD");}
                else if(settings.domain().equalsIgnoreCase(Request.getServerName(request))) {
                    String path=request.getHttpURI().getPath(),prefix="/.well-known/acme-challenge/";
                    if(path!=null&&path.startsWith(prefix)) {
                        String name=path.substring(prefix.length());var token=tokens.get(name);
                        if(token!=null && name.matches("[A-Za-z0-9_-]{16,256}") && token.expires().isAfter(Instant.now())) {status=200;body=token.value();}
                    } else if(settings.redirect()) {status=308;body="";response.getHeaders().put(HttpHeader.LOCATION,settings.url());}
                }
                byte[] bytes=body.getBytes(StandardCharsets.US_ASCII);response.setStatus(status);response.getHeaders().put(HttpHeader.CONTENT_TYPE,"text/plain; charset=us-ascii");response.getHeaders().put(HttpHeader.CONTENT_LENGTH,bytes.length);
                response.write(true,head?ByteBuffer.allocate(0):ByteBuffer.wrap(bytes),callback);return true;
            }
        });
        server.setStopTimeout(3000);server.start();
    }
    int port(){return connector.getLocalPort();}
    @Override public void close(){tokens.clear();if(server!=null)try{server.stop();}catch(Exception ignored){/* Map TLS remains fail-closed. */}}
}
