// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.servlet;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Date;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dynmap.DynmapCore;

public class ConfigJSServlet extends HttpServlet {
    private static final long serialVersionUID = 3543457384759837L;
    private byte[] outputBytes;

    public ConfigJSServlet(DynmapCore core) {
        Charset cs_utf8 = Charset.forName("UTF-8");
        var urls = new java.util.LinkedHashMap<String, String>();
        urls.put("configuration", core.configuration.getString("url/configuration", "up/configuration"));
        urls.put("update", core.configuration.getString("url/update", "up/world/{world}/{timestamp}"));
        urls.put("tiles", core.configuration.getString("url/tiles", "tiles/"));
        urls.put("markers", core.configuration.getString("url/markers", "tiles/"));
        StringBuilder sb = new StringBuilder("var config = ");
        // JSON quoting prevents configured URLs from becoming executable JavaScript.
        sb.append(org.dynmap.web.Json.stringifyJson(java.util.Map.of("url", urls))).append(";\n");
        outputBytes = sb.toString().getBytes(cs_utf8);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        String dateStr = new Date().toString();
        res.addHeader("Date", dateStr);
        res.setContentType("text/javascript; charset=utf-8");
        res.addHeader("Expires", "Thu, 01 Dec 1994 16:00:00 GMT");
        res.addHeader("Last-modified", dateStr);
        res.setContentLength(outputBytes.length);
        res.getOutputStream().write(outputBytes);
    }
}
