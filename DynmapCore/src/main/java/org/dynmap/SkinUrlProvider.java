// Modified for MCCMap: independent Paper fork (2026).
package org.dynmap;

import java.net.URL;

public interface SkinUrlProvider {
    URL getSkinUrl(String playerName);
    default URL getSkinUrl(String playerName, java.util.UUID uuid) { return getSkinUrl(playerName); }
}