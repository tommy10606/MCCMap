// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.debug;

import org.dynmap.ConfigurationNode;
import org.dynmap.DynmapCore;

public class NullDebugger implements Debugger {
    public static final NullDebugger instance = new NullDebugger(null, null);

    public NullDebugger(DynmapCore core, ConfigurationNode configuration) {
    }

    public void debug(String message) {
    }

    public void error(String message) {
    }

    public void error(String message, Throwable thrown) {
    }

}
