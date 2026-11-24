// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import static org.dynmap.JSONUtils.s;

import org.dynmap.common.DynmapListenerManager;
import org.dynmap.common.DynmapListenerManager.ChatEventListener;
import org.dynmap.common.DynmapListenerManager.EventType;
import org.dynmap.common.DynmapPlayer;
import org.json.simple.JSONObject;

public class SimpleWebChatComponent extends Component {

    public SimpleWebChatComponent(final DynmapCore plugin, final ConfigurationNode configuration) {
        super(plugin, configuration);
        plugin.events.addListener("buildclientconfiguration", new Event.Listener<JSONObject>() {
            @Override
            public void triggered(JSONObject t) {
                s(t, "allowchat", configuration.getBoolean("allowchat", false));
            }
        });

        if (configuration.getBoolean("allowchat", false)) {
            plugin.listenerManager.addListener(EventType.PLAYER_CHAT, new ChatEventListener() {
                @Override
                public void chatEvent(DynmapPlayer p, String msg) {
                    if(core.disable_chat_to_web || p == null || !p.isOnline()) return;
                    msg = core.scanAndReplaceLog4JMacro(msg);
                    if(core.mapManager != null)
                        core.mapManager.pushUpdate(new Client.ChatMessage(p.getDisplayName(), msg, p.getName()));
                }
            });
        }
    }
}
