package org.dynmap.bukkit;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.Optional;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.storage.PlayerStorage;
import net.skinsrestorer.api.property.SkinProperty;
import org.junit.jupiter.api.Test;
class SkinsRestorerSkinUrlProviderTest {
    private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private SkinsRestorerSkinUrlProvider provider(String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        SkinProperty property = SkinProperty.of(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)), "signature");
        PlayerStorage storage = (PlayerStorage) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PlayerStorage.class}, (p,m,a) -> {
            assertEquals("getSkinForPlayer", m.getName());
            assertEquals(id, a[0]); assertEquals("Player", a[1]);
            return url.isEmpty() ? Optional.empty() : Optional.of(property);
        });
        SkinsRestorer api = (SkinsRestorer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SkinsRestorer.class}, (p,m,a) -> storage);
        return new SkinsRestorerSkinUrlProvider(api);
    }
    @Test void resolvesCustomSkinWithThePlayersUuid() {
        assertEquals("https://textures.minecraft.net/texture/example", provider("https://textures.minecraft.net/texture/example").getSkinUrl("Player", id).toString());
    }
    @Test void missingSkinKeepsTheNormalSkinFallback() { assertNull(provider("").getSkinUrl("Player", id)); }
    @Test void unsupportedUrlSchemeIsRejected() { assertNull(provider("file:///etc/passwd").getSkinUrl("Player", id)); }
}
