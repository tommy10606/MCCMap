package org.dynmap.bukkit;

import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.dynmap.json.simple.JSONArray;
import org.dynmap.json.simple.JSONObject;
import org.dynmap.json.simple.parser.JSONParser;
import org.dynmap.json.simple.parser.ParseException;

/** One bounded, read-only GitHub Releases check. Never downloads release assets. */
final class GitHubUpdateChecker {
    static final String RELEASES = "https://github.com/tommy10606/MCCMap/releases";
    private static final String API = "https://api.github.com/repos/tommy10606/MCCMap/releases?per_page=100";
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile(
        "^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    static final class Session implements AutoCloseable {
        private boolean stopped;
        private volatile HttpURLConnection connection;
        synchronized boolean active() { return !stopped; }
        synchronized void publish(String current, String json, boolean prereleases, Consumer<String> console) throws ParseException {
            if (!stopped) console.accept(status(current, json, prereleases));
        }
        synchronized void message(String message, Consumer<String> console) { if (!stopped) console.accept(message); }
        synchronized boolean attach(HttpURLConnection value) { if (stopped) return false; connection = value; return true; }
        @Override public void close() { synchronized(this) { stopped = true; } var active = connection; if (active != null) active.disconnect(); }
    }
    static void check(String current, boolean includePrereleases, Consumer<String> console) {
        check(current, includePrereleases, console, new Session());
    }
    static void check(String current, boolean includePrereleases, Consumer<String> console, Session session) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(API).toURL().openConnection();
            if (!session.attach(connection)) return;
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "MCCMap-UpdateChecker");
            int status = connection.getResponseCode();
            if (status != 200) {
                session.message("Update check unavailable (GitHub HTTP " + status + ").", console);
                return;
            }
            byte[] body;
            try (var input = connection.getInputStream()) { body = input.readNBytes(MAX_RESPONSE + 1); }
            if (body.length > MAX_RESPONSE) throw new IOException("Response limit");
            session.publish(current, new String(body, StandardCharsets.UTF_8), includePrereleases, console);
        } catch (IOException | ParseException | IllegalArgumentException failure) {
            session.message("Update check unavailable; could not read GitHub Releases.", console);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static String status(String current, String json, boolean includePrereleases) throws ParseException {
        Version installed = Version.parse(current.replaceFirst("-Dynmap-fork$", ""));
        if (installed == null) return "Update check unavailable; unrecognized installed version.";
        Object parsed = new JSONParser().parse(json);
        if (!(parsed instanceof JSONArray releases)) throw new IllegalArgumentException("Expected releases");
        Version newest = null;
        for (Object value : releases) {
            if (!(value instanceof JSONObject release) || !Boolean.FALSE.equals(release.get("draft"))
                || !(release.get("prerelease") instanceof Boolean prerelease)
                || !(release.get("tag_name") instanceof String tag)) continue;
            Version version = Version.parse(tag);
            if (version == null || (!includePrereleases && (prerelease || !version.pre().isEmpty()))) continue;
            if (newest == null || version.compareTo(newest) > 0) newest = version;
        }
        if (newest == null) return "Update status unknown: no published "
            + (includePrereleases ? "" : "stable ") + "GitHub Release found. " + RELEASES;
        if (newest.compareTo(installed) > 0) return "Update Available! Running " + installed.text()
            + "; latest " + newest.text() + ". Download: " + RELEASES;
        return "Running the most up to date version.";
    }

    private record Version(BigInteger major, BigInteger minor, BigInteger patch, List<String> pre, String text)
        implements Comparable<Version> {
        static Version parse(String value) {
            if (value == null || value.length() > 128) return null;
            var match = VERSION.matcher(value);
            if (!match.matches()) return null;
            List<String> pre = match.group(4) == null ? List.of() : List.of(match.group(4).split("\\."));
            for (String part : pre) if (part.matches("[0-9]+") && part.length() > 1 && part.charAt(0) == '0') return null;
            return new Version(new BigInteger(match.group(1)), new BigInteger(match.group(2)),
                new BigInteger(match.group(3)), pre, value.replaceFirst("^[vV]", ""));
        }
        @Override public int compareTo(Version other) {
            int result = major.compareTo(other.major);
            if (result == 0) result = minor.compareTo(other.minor);
            if (result == 0) result = patch.compareTo(other.patch);
            if (result != 0) return result;
            if (pre.isEmpty() || other.pre.isEmpty()) return Boolean.compare(pre.isEmpty(), other.pre.isEmpty());
            for (int i = 0; i < Math.min(pre.size(), other.pre.size()); i++) {
                String a = pre.get(i), b = other.pre.get(i);
                boolean an = a.matches("[0-9]+"), bn = b.matches("[0-9]+");
                result = an && bn ? new BigInteger(a).compareTo(new BigInteger(b))
                    : an != bn ? (an ? -1 : 1) : a.compareTo(b);
                if (result != 0) return result;
            }
            return Integer.compare(pre.size(), other.pre.size());
        }
    }
    private GitHubUpdateChecker() {}
}
