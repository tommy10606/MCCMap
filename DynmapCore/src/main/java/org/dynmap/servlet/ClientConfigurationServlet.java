// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.dynmap.DynmapCore;
import org.dynmap.InternalClientUpdateComponent;
import org.json.simple.JSONObject;

/** Public map configuration without accounts or session creation. */
public class ClientConfigurationServlet extends HttpServlet {
    private static final long serialVersionUID = 9106801553080522469L;
    public ClientConfigurationServlet(DynmapCore core) { }
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        JSONObject snapshot = InternalClientUpdateComponent.getClientConfig();
        byte[] bytes = (snapshot == null ? "{}" : snapshot.toJSONString()).getBytes(StandardCharsets.UTF_8);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
