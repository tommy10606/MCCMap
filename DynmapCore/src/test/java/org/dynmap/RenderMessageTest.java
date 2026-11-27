package org.dynmap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.util.*;
import org.dynmap.common.DynmapCommandSender;
import org.dynmap.utils.DynmapLogger;
import org.junit.jupiter.api.*;

class RenderMessageTest {
    private DynmapLogger console;
    private boolean previousVerbose;
    @BeforeEach void captureConsole() {
        previousVerbose = Log.verbose; Log.verbose = false;
        console = mock(DynmapLogger.class); Log.setLogger(console);
    }
    @AfterEach void restoreConsole() {Log.setLogger((DynmapLogger)null); Log.verbose = previousVerbose;}
    private static Class<?> inner(String name) {
        return Arrays.stream(MapManager.class.getDeclaredClasses()).filter(c -> c.getSimpleName().equals(name)).findFirst().orElseThrow();
    }
    private static void set(Object owner, String field, Object value) throws Exception {
        var f = owner instanceof MapManager ? MapManager.class.getDeclaredField(field) : owner.getClass().getDeclaredField(field);
        f.setAccessible(true); f.set(owner, value);
    }
    private Object job(MapManager manager, DynmapCommandSender sender) throws Exception {
        var core = new DynmapCore(); core.configuration = new ConfigurationNode();
        core.configuration.put("progress-checkpoints", Map.of("enabled", false)); set(manager, "core", core);
        set(manager, "parallelrendercnt", 1); set(manager, "fullRenderOfflinePercent", 125.0);
        set(manager, "fullRenderOnlinePercent", 25.0); set(manager, "fullRenderOnlineWorkers", 1);
        set(manager, "active_renders", new HashMap<>());
        var world = mock(DynmapWorld.class, withSettings().useConstructor("world", 320, 63, -64).defaultAnswer(CALLS_REAL_METHODS));
        var create = inner("FullWorldRenderState").getDeclaredConstructor(MapManager.class, DynmapWorld.class, DynmapLocation.class, DynmapCommandSender.class, String.class, boolean.class, boolean.class);
        create.setAccessible(true);
        return create.newInstance(manager, world, new DynmapLocation("world", 0, 64, 0), sender, "flat", false, false);
    }
    private void invoke(Object job, String method, Class<?>[] types, Object... args) throws Exception {
        var m = job.getClass().getDeclaredMethod(method, types); m.setAccessible(true); m.invoke(job, args);
    }
    @Test void manualRenderMessagesStillReachTheInitiatorOrConsole() throws Exception {
        var manager = mock(MapManager.class, CALLS_REAL_METHODS); var sender = mock(DynmapCommandSender.class);
        invoke(job(manager, sender), "sendMessage", new Class<?>[]{String.class}, "Manual progress");
        verify(sender).sendMessage("Manual progress");
        invoke(job(manager, null), "sendMessage", new Class<?>[]{String.class}, "Console progress");
        verify(console).info("Console progress");
    }
    @Test void automatedMessagesAreQuietButProgressRemainsQueryable() throws Exception {
        var manager = mock(MapManager.class, CALLS_REAL_METHODS); var job = job(manager, null);
        invoke(job, "makeNatural", new Class<?>[0]);
        invoke(job, "sendMessage", new Class<?>[]{String.class}, "Automatic completion");
        verifyNoInteractions(console);
        var construct = inner("NaturalRenders").getDeclaredConstructor(MapManager.class); construct.setAccessible(true);
        var automatic = construct.newInstance(manager); set(automatic, "job", job); set(manager, "naturalRenders", automatic);
        var sender = mock(DynmapCommandSender.class); var messages = new ArrayList<String>();
        doAnswer(call -> {messages.add(call.getArgument(0)); return null;}).when(sender).sendMessage(anyString());
        manager.printProgress(sender, "world");
        assertTrue(messages.stream().anyMatch(message -> message.contains("Natural render") && message.contains("world")), messages.toString());
        verifyNoInteractions(console);
    }
    @Test void verboseModeCanStillExposeAutomaticDiagnostics() throws Exception {
        var manager = mock(MapManager.class, CALLS_REAL_METHODS); var job = job(manager, null);
        invoke(job, "makeNatural", new Class<?>[0]); Log.verbose = true;
        invoke(job, "sendMessage", new Class<?>[]{String.class}, "Automatic completion");
        verify(console).info("Automatic completion");
    }
}
