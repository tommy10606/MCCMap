package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RenderTpsNotificationsTest {
    @Test void alertsConsoleAndOwnerOncePerTransitionIncludingRecovery() {
        var notices=new RenderTpsNotifications();var console=new ArrayList<String>();var player=new ArrayList<String>();
        notices.update(false,"full render for world 'world'","ten-minute hold; other pause conditions apply",console::add,player::add);
        notices.update(true,"full render for world 'world'","ten-minute hold; other pause conditions apply",console::add,player::add);
        notices.update(true,"full render for world 'world'","ten-minute hold; other pause conditions apply",console::add,player::add);
        assertEquals(1,console.size());assertEquals(console,player);assertTrue(console.getFirst().contains("ten-minute hold"));
        notices.update(false,"full render for world 'world'","ten-minute hold; other pause conditions apply",console::add,player::add);
        notices.update(false,"full render for world 'world'","ten-minute hold; other pause conditions apply",console::add,player::add);
        assertEquals(2,console.size());assertEquals(console,player);assertTrue(console.getLast().contains("other pause conditions"));
    }
    @Test void renderStartedWhileAlreadyBelowThresholdStillAlertsAndOfflineOwnerDoesNotSuppressConsole() {
        var console=new ArrayList<String>();var notices=new RenderTpsNotifications();
        notices.update(true,"radius render","ten-minute hold",console::add,null);
        notices.update(true,"radius render","ten-minute hold",console::add,null);
        assertEquals(1,console.size());
    }
}
