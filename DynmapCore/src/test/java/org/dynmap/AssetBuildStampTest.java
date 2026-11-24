package org.dynmap;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class AssetBuildStampTest {
    @TempDir Path folder;
    @Test void changedJarForcesRefreshWithoutAVersionChange() throws Exception {
        Path jar = folder.resolve("MCCMap.jar");
        Files.writeString(jar, "build one");
        String original = AssetBuildStamp.fingerprint(jar);
        AssetBuildStamp.save(folder, original);
        assertTrue(AssetBuildStamp.matches(folder, original));
        Files.writeString(jar, "build two");
        assertFalse(AssetBuildStamp.matches(folder, AssetBuildStamp.fingerprint(jar)));
    }
    @Test void missingOrUnreadableStampForcesRefresh() throws Exception {
        assertFalse(AssetBuildStamp.matches(folder, "new-build"));
        Files.createDirectory(folder.resolve("build.sha256"));
        assertFalse(AssetBuildStamp.matches(folder, "new-build"));
    }
}
