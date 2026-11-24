// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.servlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.dynmap.Client;
import org.dynmap.DynmapCore;
import org.dynmap.InternalClientUpdateComponent;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/** Anonymous, read-only world updates. Player visibility is applied before publication. */
@SuppressWarnings({"serial", "unchecked"})
public class ClientUpdateServlet extends HttpServlet {
    private static final Pattern UPDATE_PATH = Pattern.compile("/([^/]+)/([0-9]*)");
    private final DynmapCore core;
    public ClientUpdateServlet(DynmapCore core) { this.core = core; }
    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        var match = UPDATE_PATH.matcher(path == null ? "" : path);
        if (!match.matches() || core.mapManager == null) {
            response.sendError(404, "World not found");
            return;
        }
        var world = core.mapManager.getWorld(match.group(1));
        if (world == null) {
            response.sendError(404, "World not found");
            return;
        }
        long since = 0;
        try { since = Long.parseLong(match.group(2)); } catch (NumberFormatException ignored) { }
        JSONObject output = new JSONObject();
        JSONObject snapshot = InternalClientUpdateComponent.getWorldUpdate(world.getName());
        if (snapshot != null) output.putAll(snapshot);
        JSONArray updates = new JSONArray();
        if (output.get("updates") instanceof JSONArray source) {
            for (Object entry : source) {
                if (entry instanceof Client.Update update && update.timestamp >= since) updates.add(update);
            }
        }
        output.put("updates", updates);
        byte[] bytes = output.toJSONString().getBytes(StandardCharsets.UTF_8);
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
