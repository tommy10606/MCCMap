// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import static org.dynmap.JSONUtils.a;
import static org.dynmap.JSONUtils.s;
import org.dynmap.Event.Listener;
import org.json.simple.JSONObject;

public class ClientConfigurationComponent extends Component {
    public ClientConfigurationComponent(final DynmapCore core, ConfigurationNode configuration) {
        super(core, configuration);
        core.events.<JSONObject>addListener("buildclientconfiguration", new Listener<JSONObject>() {
            @Override
            public void triggered(JSONObject t) {
                ConfigurationNode c = core.configuration;
                s(t, "confighash", core.getConfigHashcode());
                s(t, "updaterate", c.getFloat("updaterate", 1.0f));
                s(t, "showplayerfacesinmenu", c.getBoolean("showplayerfacesinmenu", true));
                s(t, "defaultzoom", c.getInteger("defaultzoom", 0));
                s(t, "zoomoutmultiplier", c.getDouble("web-map-zoom/out-multiplier", 1.5));
                s(t, "zoominmultiplier", c.getDouble("web-map-zoom/in-multiplier", 1.25));
                s(t, "maxpixelsperblock", c.getDouble("web-map-zoom/max-pixels-per-block", 16));
                s(t, "borderpadding", c.getDouble("web-map-zoom/border-padding", 1.1));
                s(t, "sidebaropened", c.getString("sidebaropened", "false"));
                s(t, "mccmapversion", core.getDynmapPluginVersion());
                s(t, "forkof", "Dynmap 3.9-SNAPSHOT (93b454e)");
                s(t, "coreversion", core.getDynmapCoreVersion());
                s(t, "cyrillic", c.getBoolean("cyrillic-support", false));
                s(t, "showlayercontrol", c.getString("showlayercontrol", "true"));
                s(t, "grayplayerswhenhidden", c.getBoolean("grayplayerswhenhidden", true));
                String sn = core.getServer().getServerName();
                if(sn.equals("Unknown Server"))
                    sn = "Minecraft Dynamic Map";
                s(t, "title", c.getString("webpage-title", "MCCMap — " + sn));
                s(t, "msg-maptypes", c.getString("msg/maptypes", "Map Types"));
                s(t, "msg-players", c.getString("msg/players", "Players"));
                s(t, "maxcount", core.getMaxPlayers());

                DynmapWorld defaultWorld = null;
                String defmap = null;
                a(t, "worlds", null);
                for(DynmapWorld world : core.mapManager.getWorlds()) {
                    if (world.maps.size() == 0) continue;
                    if (defaultWorld == null) defaultWorld = world;
                    JSONObject wo = new JSONObject();
                    s(wo, "name", world.getName());
                    s(wo, "title", world.getTitle());
                    DynmapLocation center = world.getCenterLocation();
                    s(wo, "center/x", center.x);
                    s(wo, "center/y", center.y);
                    s(wo, "center/z", center.z);
                    s(wo, "extrazoomout", world.getExtraZoomOutLevels());
                    s(wo, "sealevel", world.sealevel);
                    if (world.mapWorldBorder != null) {
                        org.json.simple.JSONArray border = new org.json.simple.JSONArray();
                        for(int i=0;i<world.mapWorldBorder.size();i++) {
                            var point=world.mapWorldBorder.getVertex(i);
                            org.json.simple.JSONArray pair=new org.json.simple.JSONArray();pair.add(point.x);pair.add(point.y);border.add(pair);
                        }
                        s(wo,"worldborder",border);
                    }
                    s(wo, "worldheight", world.worldheight);
                    a(t, "worlds", wo);

                    for(MapType mt : world.maps) {
                        mt.buildClientConfiguration(wo, world);
                        if(defmap == null) defmap = mt.getName();
                    }
                }
                s(t, "defaultworld", c.getString("defaultworld", defaultWorld == null ? "world" : defaultWorld.getName()));
                s(t, "defaultmap", c.getString("defaultmap", defmap == null ? "surface" : defmap));
                if(c.getString("followmap", null) != null)
                    s(t, "followmap", c.getString("followmap"));
                if(c.getInteger("followzoom",-1) >= 0)
                    s(t, "followzoom", c.getInteger("followzoom", 0));
            }
        });
    }

}
