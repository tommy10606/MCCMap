package org.dynmap;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapPlayer;
import org.junit.Before;
import org.junit.Test;

public class UrlCommandPermissionTest {
    private DynmapCore core;
    @Before public void setup() throws Exception {
        core = new DynmapCore(); core.mapManager = mock(MapManager.class);
        Field url = DynmapCore.class.getDeclaredField("publicURL"); url.setAccessible(true); url.set(core, "https://map.example.test/");
    }
    private void run(DynmapCommandSender sender) { assertTrue(core.processCommand(sender, "mccmap", "mccmap", new String[]{"url"})); }
    @Test public void deniedPlayerCannotReadConfiguredUrl() {
        DynmapPlayer player = mock(DynmapPlayer.class); run(player);
        verify(player).hasPrivilege("url");
        verify(player, never()).sendMessage(contains("https://map.example.test/"));
        verify(player).sendMessage(any());
    }
    @Test public void allowedPlayerCanReadConfiguredUrl() {
        DynmapPlayer player = mock(DynmapPlayer.class); when(player.hasPrivilege("url")).thenReturn(true); run(player);
        verify(player).sendMessage("MCCMap URL for this server is: https://map.example.test/");
    }
    @Test public void operatorAndConsoleKeepAdministrativeAccess() {
        DynmapPlayer operator = mock(DynmapPlayer.class); when(operator.isOp()).thenReturn(true); run(operator);
        DynmapCommandSender console = mock(DynmapCommandSender.class); run(console);
        verify(operator).sendMessage(contains("https://map.example.test/"));
        verify(console).sendMessage(contains("https://map.example.test/"));
    }
    @Test public void deniedPlayerCannotProbeWhetherUrlIsConfigured() throws Exception {
        Field url = DynmapCore.class.getDeclaredField("publicURL"); url.setAccessible(true); url.set(core, "");
        DynmapPlayer player = mock(DynmapPlayer.class); run(player);
        verify(player, never()).sendMessage("URL of MCCMap not configured");
    }
}
