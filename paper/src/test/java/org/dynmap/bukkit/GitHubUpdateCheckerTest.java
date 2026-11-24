package org.dynmap.bukkit;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GitHubUpdateCheckerTest {
    private String release(String tag, boolean draft, boolean prerelease) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease + "}";
    }
    @Test void comparesNumericVersionsAndIgnoresReleaseOrder() throws Exception {
        String json = "[" + release("v1.9.0", false, false) + "," + release("1.10.0", false, false) + "]";
        String status = GitHubUpdateChecker.status("1.0.0-Dynmap-fork", json, false);
        assertTrue(status.contains("Update Available!")); assertTrue(status.contains("latest 1.10.0"));
        assertTrue(status.contains(GitHubUpdateChecker.RELEASES));
    }
    @Test void equalOlderAndBuildMetadataAreNotUpdates() throws Exception {
        for (String tag : new String[]{"v1.0.0", "0.9.0", "1.0.0+build.42"})
            assertEquals("Running the most up to date version.",
                GitHubUpdateChecker.status("1.0.0-Dynmap-fork", "[" + release(tag, false, false) + "]", false));
    }
    @Test void excludesDraftsAndPrereleasesByDefault() throws Exception {
        String json = "[" + release("2.0.0", true, false) + "," + release("1.1.0-beta.1", false, true) + "]";
        assertTrue(GitHubUpdateChecker.status("1.0.0", json, false).contains("status unknown"));
        assertTrue(GitHubUpdateChecker.status("1.0.0", json, true).contains("latest 1.1.0-beta.1"));
    }
    @Test void stableBeatsPrereleaseAndPrereleaseNumbersCompareNumerically() throws Exception {
        String json = "[" + release("1.1.0-beta.2", false, true) + "," + release("1.1.0-beta.10", false, true) + "]";
        assertTrue(GitHubUpdateChecker.status("1.1.0-beta.3", json, true).contains("latest 1.1.0-beta.10"));
        assertEquals("Running the most up to date version.", GitHubUpdateChecker.status("1.1.0", json, true));
        json = json.substring(0, json.length()-1) + "," + release("1.1.0", false, false) + "]";
        assertTrue(GitHubUpdateChecker.status("1.1.0-beta.3", json, true).contains("latest 1.1.0."));
    }
    @Test void missingReleasesAndUntrustedTagsCannotProduceFalseStatusOrLogText() throws Exception {
        assertTrue(GitHubUpdateChecker.status("1.0.0", "[]", false).contains("status unknown"));
        String json = "[" + release("9.0.0\\nFORGED", false, false) + "," + release("01.0.0", false, false) + "]";
        String status = GitHubUpdateChecker.status("1.0.0", json, false);
        assertTrue(status.contains("status unknown")); assertFalse(status.contains("FORGED"));
        assertThrows(IllegalArgumentException.class, () -> GitHubUpdateChecker.status("1.0.0", "{}", false));
    }
}
