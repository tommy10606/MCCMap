package org.dynmap.markers.impl;
import static org.junit.jupiter.api.Assertions.*;
import org.dynmap.ConfigurationNode;
import org.junit.jupiter.api.Test;
class MarkerHtmlSecurityTest {
    private static final String ATTACK="<b>Region</b><img src=x onerror=alert(1)><script>alert(2)</script>";
    private void safe(String value) {assertNotNull(value);assertFalse(value.contains("onerror"));assertFalse(value.contains("<script"));assertTrue(value.contains("<b>Region</b>"));}
    @Test void pointCreationSanitizesMarkupAndFallbackIds() {
        safe(new MarkerImpl("id",ATTACK,true,"world",0,64,0,null,false,null).getLabel());
        safe(new MarkerImpl(ATTACK,null,true,"world",0,64,0,null,false,null).getLabel());
        assertEquals("fallback",new MarkerImpl("fallback",null,false,"world",0,64,0,null,false,null).getLabel());
    }
    @Test void persistedSafeFlagCannotBypassCurrentSanitizationForAnyMarkerType() {
        var node=new ConfigurationNode();node.put("label",ATTACK);node.put("desc",ATTACK);node.put("markup",true);
        var point=new MarkerImpl("point",null);assertTrue(point.loadPersistentData(node,true));safe(point.getLabel());safe(point.getDescription());
        var area=new AreaMarkerImpl("area",null);assertTrue(area.loadPersistentData(node,true));safe(area.getLabel());safe(area.getDescription());
        var line=new PolyLineMarkerImpl("line",null);assertTrue(line.loadPersistentData(node,true));safe(line.getLabel());safe(line.getDescription());
        var circle=new CircleMarkerImpl("circle",null);assertTrue(circle.loadPersistentData(node,true));safe(circle.getLabel());safe(circle.getDescription());
    }
    @Test void layerNamesArePlainTextWhenPublishedInLiveUpdates() {
        var set=new MarkerSetImpl("id",ATTACK,null,false);
        var update=new MarkerAPIImpl.MarkerSetUpdated(set,false);
        assertFalse(update.label.contains("<"));assertTrue(update.label.contains("&lt;"));
        assertEquals(ATTACK,set.getMarkerSetLabel());
    }
}
