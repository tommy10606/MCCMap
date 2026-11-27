package org.dynmap.web.https;

import java.nio.ByteBuffer;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/** Plain HTTP can only redirect; it never reaches map files or API handlers. */
final class HttpsRedirectHandler extends Handler.Wrapper {
    private final HttpsSettings settings;

    HttpsRedirectHandler(HttpsSettings settings, Handler map) {
        super(map);
        this.settings = settings;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        // Inspect the actual transport, never a URI scheme or forwarded header supplied by a client.
        if (request.getConnectionMetaData().isSecure()) {
            return super.handle(request, response, callback);
        }
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put(HttpHeader.CONNECTION, "close");
        response.getHeaders().put("X-Content-Type-Options", "nosniff");
        response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 0);
        if (!settings.redirect()) {
            response.setStatus(404);
        } else if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            response.setStatus(405);
            response.getHeaders().put(HttpHeader.ALLOW, "GET, HEAD");
        } else {
            String path = request.getHttpURI().getPath();
            String query = request.getHttpURI().getQuery();
            if (path == null || path.isEmpty()) path = "/";
            String suffix = path + (query == null ? "" : "?" + query);
            if (!path.startsWith("/") || path.startsWith("//") || suffix.indexOf('\\') >= 0
                    || suffix.indexOf('#') >= 0 || suffix.chars().anyMatch(c -> c < 32 || c == 127)) {
                response.setStatus(400);
            } else {
                response.setStatus(308);
                // The authority is fixed by validated config, not Host/Forwarded/request-target authority.
                String root = settings.url();
                response.getHeaders().put(HttpHeader.LOCATION, root.substring(0, root.length() - 1) + suffix);
            }
        }
        response.write(true, ByteBuffer.allocate(0), callback);
        return true;
    }
}
