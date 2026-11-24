// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.web;

import org.dynmap.DynmapCore;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

public class FileNameFilter implements Filter {

    public FileNameFilter(DynmapCore core) {
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException { }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse)response;
        resp.setHeader("X-Content-Type-Options", "nosniff");
        if (!"GET".equals(http.getMethod()) && !"HEAD".equals(http.getMethod())) {
            resp.setHeader("Allow", "GET, HEAD");
            resp.sendError(405);
            return;
        }
        String path = http.getPathInfo();
        if (path == null) path = http.getServletPath();
        if (path == null) path = "";
        path = path.toLowerCase(java.util.Locale.ROOT);
        String filename = path.substring(path.lastIndexOf('/') + 1);
        for (String part : path.split("/")) {
            if (part.startsWith(".") || part.indexOf('\\') >= 0 || part.chars().anyMatch(Character::isISOControl)) {
                resp.sendError(404);
                return;
            }
        }
        // Filter unneeded file requests
        if (path.endsWith(".php") || path.endsWith(".progress") || path.endsWith(".pending") || filename.startsWith("web-connections")) {
            resp.sendError(404);
        } else {
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() { }
}
