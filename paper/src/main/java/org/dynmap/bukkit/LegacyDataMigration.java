// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap.bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Move an old installation only when it cannot overwrite an MCCMap installation. */
final class LegacyDataMigration {
    static boolean migrate(Path destination) throws IOException {
        Path source = destination.resolveSibling("dynmap");
        if (!Files.isDirectory(source) || Files.isSymbolicLink(source)) return false;
        if (Files.exists(destination)) {
            if (!Files.isDirectory(destination) || Files.isSymbolicLink(destination)) throw new IOException("Invalid MCCMap data directory");
            try (var entries = Files.list(destination)) {
                if (entries.findAny().isPresent()) return false;
            }
            Files.delete(destination);
        }
        Files.move(source, destination);
        return true;
    }
    private LegacyDataMigration() {}
}
