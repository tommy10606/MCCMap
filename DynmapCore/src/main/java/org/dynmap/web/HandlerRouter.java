// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.web;

import org.eclipse.jetty.ee8.nested.AbstractHandler;
import org.eclipse.jetty.ee8.nested.Handler;
import org.eclipse.jetty.ee8.nested.Request;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Exact and prefix routes for the public, session-free map server. */
public class HandlerRouter extends AbstractHandler {
    private record Route(String path, Handler handler, Servlet servlet) {
        boolean matches(String target) {
            return path.equals("/") || (path.endsWith("/*")
                ? target.equals(path.substring(0, path.length() - 2)) || target.startsWith(path.substring(0, path.length() - 1))
                : target.equals(path));
        }
    }
    private final List<Route> routes = new CopyOnWriteArrayList<>();
    public void addHandler(String path, Handler handler) {
        if (getServer() != null) handler.setServer(getServer());
        addBean(handler);
        routes.add(new Route(path, handler, null));
    }
    @Override public void setServer(org.eclipse.jetty.server.Server server) {
        super.setServer(server);
        for (Route route : routes) if (route.handler != null) route.handler.setServer(server);
    }
    public void addServlet(String path, Servlet servlet) { routes.add(new Route(path, null, servlet)); }
    public void clear() {
        for (Route route : routes) if (route.handler != null) removeBean(route.handler);
        routes.clear();
    }
    @Override public void handle(String target, Request baseRequest, HttpServletRequest request,
            HttpServletResponse response) throws IOException, ServletException {
        String path = request.getPathInfo();
        Route match = null;
        for (Route route : routes) if (route.matches(path) && (match == null || route.path.length() > match.path.length())) match = route;
        if (match == null) { response.sendError(404); baseRequest.setHandled(true); return; }
        String childPath = path;
        if (match.path.endsWith("/*")) childPath = path.substring(match.path.length() - 2);
        final String routedPath = childPath;
        HttpServletRequest routed = match.path.endsWith("/*") ? new javax.servlet.http.HttpServletRequestWrapper(request) {
            @Override public String getPathInfo() { return routedPath; }
        } : request;
        {
            if (match.handler != null) match.handler.handle(childPath, baseRequest, routed, response);
            else { match.servlet.service(routed, response); baseRequest.setHandled(true); }
        }
    }
}
