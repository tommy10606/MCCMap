// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.regions;

import org.dynmap.Component;
import org.dynmap.ConfigurationNode;
import org.dynmap.DynmapCore;
import org.dynmap.Log;

public class RegionsComponent extends Component {
    private static String deprecated_ids[] = { "Residence", "Factions", "Towny", "WorldGuard" };
    private static String deprecated_new_plugins[] = { "dynmap-residence", "Dynmap-Factions", "Dynmap-Towny", "Dynmap-WorldGuard" };
    
    public RegionsComponent(final DynmapCore plugin, final ConfigurationNode configuration) {
        super(plugin, configuration);
        
        String regiontype = configuration.getString("name", "WorldGuard");
        if (regiontype.equals("WorldGuard")) {
            Log.info("WorldGuard regions are native in MCCMap; configure worldguard-regions instead of a map add-on");
            return;
        }
        /* Check if a deprecated component */
        for(int i = 0; i < deprecated_ids.length; i++) {
            if(regiontype.equals(deprecated_ids[i])) {  /* If match */
                Log.info("Region component for '" + regiontype + "' has been RETIRED - migrate to '" + deprecated_new_plugins[i] + "' plugin");
            }
        }
    }
}
