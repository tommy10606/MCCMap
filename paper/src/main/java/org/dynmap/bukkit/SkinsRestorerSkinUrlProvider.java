// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit;

import org.dynmap.SkinUrlProvider;
import org.dynmap.Log;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.exception.DataRequestException;
import java.net.URI;
import java.net.URL;
import java.util.UUID;
import org.bukkit.Bukkit;

/** Runs from the asynchronous skin-image worker, never the server tick thread. */
public class SkinsRestorerSkinUrlProvider implements SkinUrlProvider {
    private final SkinsRestorer api;
    SkinsRestorerSkinUrlProvider() { this(SkinsRestorerProvider.get()); }
    SkinsRestorerSkinUrlProvider(SkinsRestorer api) { this.api = api; }
    @Override public URL getSkinUrl(String name) {
        return getSkinUrl(name, Bukkit.getOfflinePlayer(name).getUniqueId());
    }
    @Override public URL getSkinUrl(String name, UUID uuid) {
        if (uuid == null) return null;
        try {
            var skin = api.getPlayerStorage().getSkinForPlayer(uuid, name);
            if (skin.isEmpty()) return null;
            URI uri = URI.create(PropertyUtils.getSkinTextureUrl(skin.get()));
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return null;
            return uri.toURL();
        } catch (DataRequestException | IllegalArgumentException | java.net.MalformedURLException error) {
            Log.warning("Could not resolve the SkinsRestorer texture for " + name);
            return null;
        }
    }
}
