package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.common.DynmapPlayer;
import org.junit.jupiter.api.Test;

class ProgressCommandTest {
    @Test void consoleAndAuthorizedPlayerCanReadProgressWithWorldFilter() {
        var core = new DynmapCore(); core.mapManager = mock(MapManager.class);
        var console = mock(DynmapCommandSender.class);
        assertTrue(core.processCommand(console, "mccmap", "mccmap", new String[]{"progress"}));
        verify(core.mapManager).printProgress(console, null);
        var player = mock(DynmapPlayer.class); when(player.hasPrivilege("stats")).thenReturn(true);
        assertTrue(core.processCommand(player, "mccmap", "mccmap", new String[]{"progress", "world"}));
        verify(core.mapManager).printProgress(player, "world");
        assertTrue(core.getSubcommandSuggestions(console, "mccmap", "prog").contains("progress"));
    }
    @Test void deniedPlayerCannotReadProgress() {
        var core = new DynmapCore(); core.mapManager = mock(MapManager.class);
        var player = mock(DynmapPlayer.class);
        core.processCommand(player, "mccmap", "mccmap", new String[]{"progress"});
        verify(player).hasPrivilege("stats");
        verifyNoInteractions(core.mapManager);
    }
    @Test void naturalControlRequiresFullRenderPermissionAndSupportsStatus() {
        var core = new DynmapCore(); core.mapManager = mock(MapManager.class);
        var player = mock(DynmapPlayer.class);
        core.processCommand(player, "mccmap", "mccmap", new String[]{"naturalrender", "on"});
        verifyNoInteractions(core.mapManager);
        when(player.hasPrivilege("fullrender")).thenReturn(true);
        core.processCommand(player, "mccmap", "mccmap", new String[]{"naturalrender", "resume"});
        verify(core.mapManager).naturalRenderControl(player, "resume");
        var console = mock(DynmapCommandSender.class);
        core.processCommand(console, "mccmap", "mccmap", new String[]{"naturalrender"});
        verify(core.mapManager).naturalRenderControl(console, "status");
    }

}
