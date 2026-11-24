package org.dynmap.storage;
import static org.junit.Assert.*;
import org.dynmap.storage.filetree.FileTreeMapStorage;
import org.junit.Test;
public class StandaloneStorageConfigTest {
    @Test public void localFilesUseScriptFreePublicUrls() {
        MapStorage storage = new FileTreeMapStorage();
        assertTrue(storage.getConfigurationJSONURI().contains("mccmap_config.json"));
        assertTrue(storage.getUpdateJSONURI().contains("mccmap_{world}.json"));
        assertFalse(storage.getTilesURI().contains(".php"));
        assertFalse(storage.getMarkersURI().contains(".php"));
    }
}
