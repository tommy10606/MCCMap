// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee8.nested.Handler;
import org.eclipse.jetty.ee8.nested.Request;
import org.eclipse.jetty.ee8.nested.HandlerWrapper;

/** Managed filter chain for the embedded map server; no servlet sessions are needed. */
public class FilterHandler extends HandlerWrapper {
    private final List<Filter> filters = new ArrayList<>();
    public FilterHandler() {}
    public FilterHandler(Handler handler, Iterable<Filter> filters) {
        setHandler(handler);
        filters.forEach(this.filters::add);
    }
    public Iterable<Filter> getFilters() { return filters; }
    public void addFilter(Filter filter) { filters.add(filter); }
    @Override protected void doStart() throws Exception {
        for (Filter filter : filters) filter.init(new FilterConfig() {
            public String getFilterName() { return filter.getClass().getSimpleName(); }
            public ServletContext getServletContext() { return null; }
            public String getInitParameter(String name) { return null; }
            public Enumeration<String> getInitParameterNames() { return Collections.emptyEnumeration(); }
        });
        super.doStart();
    }
    @Override protected void doStop() throws Exception {
        try { super.doStop(); } finally { filters.forEach(Filter::destroy); }
    }
    @Override public void handle(String target, Request baseRequest, HttpServletRequest request,
            HttpServletResponse response) throws IOException, ServletException {
        Iterator<Filter> iterator = filters.iterator();
        FilterChain chain = new FilterChain() {
            @Override public void doFilter(ServletRequest req, ServletResponse res) throws IOException, ServletException {
                if (iterator.hasNext()) iterator.next().doFilter(req, res, this);
                else getHandler().handle(target, baseRequest, (HttpServletRequest) req, (HttpServletResponse) res);
            }
        };
        chain.doFilter(request, response);
    }
}
