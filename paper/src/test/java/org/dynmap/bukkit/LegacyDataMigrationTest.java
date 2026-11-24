package org.dynmap.bukkit;
import static org.junit.Assert.*;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
public class LegacyDataMigrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void oldDataIsMovedIntoEmptyDestination() throws Exception {
        var root = temporary.getRoot().toPath();
        var old = Files.createDirectory(root.resolve("dynmap"));
        Files.writeString(old.resolve("markers.yml"), "keep me");
        var target = Files.createDirectory(root.resolve("MCCMap"));
        assertTrue(LegacyDataMigration.migrate(target));
        assertEquals("keep me", Files.readString(target.resolve("markers.yml")));
        assertFalse(Files.exists(old));
    }
    @Test public void existingInstallationsAreNeverOverwritten() throws Exception {
        var root = temporary.getRoot().toPath();
        var old = Files.createDirectory(root.resolve("dynmap"));
        var target = Files.createDirectory(root.resolve("MCCMap"));
        Files.writeString(target.resolve("configuration.txt"), "existing");
        assertFalse(LegacyDataMigration.migrate(target));
        assertTrue(Files.exists(old));
        assertEquals("existing", Files.readString(target.resolve("configuration.txt")));
    }
}
