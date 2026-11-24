// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Refresh bundled assets when a development build changes without a version bump. */
final class AssetBuildStamp {
    static String fingerprint(Path jar) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = new BufferedInputStream(new FileInputStream(jar.toFile()))) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static boolean matches(Path folder, String fingerprint) {
        if (fingerprint == null) return false;
        try { return fingerprint.equals(Files.readString(folder.resolve("build.sha256"))); }
        catch (IOException ignored) { return false; }
    }
    static void save(Path folder, String fingerprint) throws IOException {
        Files.writeString(folder.resolve("build.sha256"), fingerprint);
    }
    private AssetBuildStamp() {}
}
