package org.dynmap.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
class PrivateLogFilterTest {
    @Test void blocksPrivateLogBasenamesUsingTheDecodedPath() throws Exception {
        for (String path : new String[]{"/logs/web-connections.log","/web-connections.2.log","/WEB-CONNECTIONS.LOG;download=1"}) {
            var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
            when(request.getPathInfo()).thenReturn(path);
            when(request.getMethod()).thenReturn("GET");
            new FileNameFilter(null).doFilter(request,response,chain);
            verify(response).sendError(404);verifyNoInteractions(chain);
        }
    }
    @Test void ordinaryMapAssetsRemainAccessible() throws Exception {
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        when(request.getPathInfo()).thenReturn("/tiles/world/flat/0_0/0_0.jpg");
        when(request.getMethod()).thenReturn("GET");
        new FileNameFilter(null).doFilter(request,response,chain);
        verify(chain).doFilter(request,response);verify(response).setHeader("X-Content-Type-Options","nosniff");
    }
}
