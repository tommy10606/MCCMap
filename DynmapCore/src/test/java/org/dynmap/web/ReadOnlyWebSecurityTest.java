package org.dynmap.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.dynmap.servlet.MapStorageResourceHandler;
import org.dynmap.*;
import org.dynmap.storage.MapStorage;
import org.eclipse.jetty.ee8.nested.Request;
import org.junit.jupiter.api.Test;

class ReadOnlyWebSecurityTest {
    @Test void disallowsWriteAndTraceMethodsBeforeDispatch() throws Exception {
        for(String method:new String[]{"POST","PUT","DELETE","TRACE","CONNECT","OPTIONS"}) {
            var request=mock(HttpServletRequest.class);when(request.getMethod()).thenReturn(method);
            var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
            new FileNameFilter(null).doFilter(request,response,chain);
            verify(response).sendError(405);verify(response).setHeader("Allow","GET, HEAD");verifyNoInteractions(chain);
        }
    }
    @Test void blocksHiddenFilesAndUnsafeSeparatorsButKeepsPublicAssets() throws Exception {
        for(String path:new String[]{"/.env","/.git/config","/nested/.secret","/foo/../private","/foo\\bar","/x\n","/private.progress","/world.pending"}) {
            var request=mock(HttpServletRequest.class);when(request.getMethod()).thenReturn("GET");when(request.getPathInfo()).thenReturn(path);
            var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
            new FileNameFilter(null).doFilter(request,response,chain);verify(response).sendError(404);verifyNoInteractions(chain);
        }
        for(String method:new String[]{"GET","HEAD"}) {
            var request=mock(HttpServletRequest.class);when(request.getMethod()).thenReturn(method);when(request.getPathInfo()).thenReturn("/js/map.js");
            var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
            new FileNameFilter(null).doFilter(request,response,chain);verify(chain).doFilter(request,response);
            verify(response).setHeader("X-Content-Type-Options","nosniff");
        }
    }
    @Test void missingMarkersAndEmptyPathsReturn404WithoutThrowing() throws Exception {
        var core=mock(DynmapCore.class);when(core.getDefaultMapStorage()).thenReturn(mock(MapStorage.class));
        var handler=new MapStorageResourceHandler();handler.setCore(core);
        for(String path:new String[]{null,"","/_markers_/","/_markers_/missing.png","/_markers_/marker_missing.json"}) {
            var request=mock(HttpServletRequest.class);when(request.getPathInfo()).thenReturn(path);
            var response=mock(HttpServletResponse.class);
            assertDoesNotThrow(()->handler.handle(path,mock(Request.class),request,response));
            verify(response).sendError(404);
        }
    }
}
