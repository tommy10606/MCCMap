package org.dynmap;
import static org.junit.Assert.*;
import org.junit.Test;
public class HtmlSafetyTest {
    @Test public void markerFormattingSurvivesWithoutActiveContent() {
        String safe = Client.sanitizeHTML("<b>Marker</b><script>alert(1)</script><img src=x onerror=alert(1)><a href='javascript:alert(1)'>link</a>");
        assertTrue(safe.contains("<b>Marker</b>"));
        assertFalse(safe.contains("<script"));
        assertFalse(safe.contains("onerror"));
        assertFalse(safe.contains("javascript:"));
    }
    @Test public void playerDisplayNamesCannotInjectEventHandlers() {
        String safe = Client.stripColor("<svg onload=alert(1)>Player</svg>");
        assertFalse(safe.contains("onload"));
        assertFalse(safe.contains("<svg"));
    }
}
