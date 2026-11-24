package org.dynmap.bukkit.overlays;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class EssentialsWarpOverlayTest {
    @Test void protectedWarpsRequireExplicitPublicationAndHiddenAlwaysWins(){assertTrue(EssentialsWarpOverlay.publish("spawn",false,List.of(),List.of()));assertFalse(EssentialsWarpOverlay.publish("admin",true,List.of(),List.of()));assertTrue(EssentialsWarpOverlay.publish("Spawn",true,List.of("spawn"),List.of()));assertFalse(EssentialsWarpOverlay.publish("spawn",false,List.of("spawn"),List.of("spawn")));}
    @Test void allWarpsArePublicByDefaultEvenWhenEssentialsPermissionsAreEnabled(){
        var config=new org.dynmap.ConfigurationNode();
        boolean restricted=true&&config.getBoolean("essentials-warps/respect-per-warp-permissions",false);
        assertTrue(EssentialsWarpOverlay.publish("admin",restricted,List.of(),List.of()));
    }
    @Test void explicitAllowListLimitsEvenUnrestrictedWarps(){assertFalse(EssentialsWarpOverlay.publish("admin",false,List.of("spawn"),List.of()));}
    @Test void popupEscapesNamesAndContainsCoordinates(){var html=EssentialsWarpOverlay.description("<warp>","<world>",-4,64,20);assertFalse(html.contains("<warp>"));assertFalse(html.contains("<world>"));assertTrue(html.contains("X: -4 Y: 64 Z: 20"));}
}
