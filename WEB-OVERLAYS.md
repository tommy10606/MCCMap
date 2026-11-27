# Web connection logging and native overlays

Available in MCCMap 1.0.1.

## Optional connection log

In plugins/MCCMap/configuration.txt, set:

```yaml
webserver-connection-log:
  enabled: true
  timezone: system
  max-file-size-mb: 10
  retained-files: 5
```

Restart MCCMap/Paper after changing these settings. Logging is disabled by default.
The default timezone is `system`, meaning Java's host/JVM default (normally the
operating-system timezone; container TZ or -Duser.timezone may override it).
To override it only for this log, use an IANA zone such as `America/New_York`,
`Europe/London`, or `Asia/Tokyo`, or use `UTC`. Region timezones follow daylight
saving automatically. Every timestamp includes its UTC offset so repeated local
times during daylight-saving transitions are distinguishable. Existing log lines
are not rewritten when settings change. Invalid timezone settings produce a fixed
warning and fall back to the host timezone without stopping logging or the map.
It records each accepted TCP connection once, including its source port:

```text
2026-11-07T09:00:00-05:00 CONNECT 192.0.2.10:54321
2026-11-07T09:00:01-05:00 CONNECT [2001:db8:0:0:0:0:0:10]:54322
```

The file is plugins/MCCMap/logs/web-connections.log. Rotated files use
web-connections.1.log, web-connections.2.log, and so on. retained-files is the
number of backups in addition to the active file. File-size settings are clamped
to 1–100 MiB through the normal configuration; backup counts are clamped to 1–20.
Log records use only generated date/time stamps with a numeric UTC offset, a fixed event name, an IP constructed from
socket address bytes, and an integer port. No DNS lookup or remote hostname,
request URL, header, cookie, user agent, forwarding header, or chat text is logged.
This prevents request strings from adding log lines, terminal controls, HTML,
interpolation expressions, or executable text to this log.

Files have owner-only read/write permissions on POSIX hosts, and the log directory
has owner-only access. Symlink file targets are refused; hardlink targets are also refused where the
filesystem exposes link counts. The path is
fixed outside the web directory; unsafe/public log locations are refused, and
connection-log filenames are blocked by the HTTP filter as an additional safeguard.
Do not move logs into a separately hosted public website. Windows access also
depends on the service account's filesystem ACLs.

A bounded queue keeps disk work off server/network threads. Under overload,
records may be dropped and an aggregate DROPPED count is written. Rotation bounds
normal retention. An I/O failure stops logging with a warning and does not stop
the map. Logging failure is not silent proof that no connections occurred.

The address is the actual TCP peer. Behind a reverse proxy, this will be the proxy
address. X-Forwarded-For and similar headers are deliberately not trusted. HTTP
keep-alive requests share one connection record. This is not a full request log.

## Native WorldGuard layer

Install compatible WorldGuard and WorldEdit plugins; no Dynmap-WorldGuard/map
add-on is needed. This build targets WorldGuard 7.0.21 and WorldEdit 7.4.6. Neither
plugin is bundled in MCCMap. MCCMap also starts normally when either plugin is absent or disabled. Both must
be running before the overlay accesses their APIs. If either stops while MCCMap
is running, its region markers are cleared at the next refresh and the layer stays
offline. Restart/reload MCCMap after enabling previously absent dependencies.

```yaml
worldguard-regions:
  enabled: true
  refresh-interval-seconds: 10
```

The native WorldGuard Regions layer is enabled by default when WorldGuard is
available. It displays cuboid and polygonal physical regions for enabled map
worlds, with their names, Y ranges, and priority. The global region has no finite
outline and is omitted. Owners, members and region flags are not published.
Labels are treated as text and generated popup descriptions are HTML-escaped and
sanitized. MCCMap reads region data on the server thread and does not change
WorldGuard's regions or protection rules.

Region creation, geometry/height/priority changes, deletion and unloaded worlds
are reflected by periodic refresh. Generated markers are not persisted as manual
markers. Use the root enabled option to disable the integration. Marker components
and the website's marker layer must remain enabled to display overlays.

## Dedicated WorldBorder plugin compatibility

The default source prefers the dedicated WorldBorder plugin and automatically
uses Minecraft/Paper's built-in border when that plugin is absent or disabled. The repository https://github.com/PryPurity/WorldBorder was inspected
as an API reference only. No WorldBorder implementation, protection logic, JAR,
source, or build dependency is copied, bundled, modified or distributed with MCCMap.

```yaml
world-border:
  source: worldborder
  refresh-interval-seconds: 5
```

MCCMap reads only the running plugin's public getWorldBorder(worldName) and border
getters: center X/Z, radius X/Z and nullable per-world shape. A null shape follows
the plugin's public global ShapeRound setting. Rectangles use their exact configured
bounds; circles/ellipses retain both radii in circle markers. Borders appear on the
native World Border layer, which updates after changes/removal. Per-world showborder
settings are respected. MCCMap does not set borders, change enforcement, save the
provider's configuration, or require the provider's existing Dynmap connector.

When WorldBorder is absent or disabled, the same layer displays Minecraft/Paper's
built-in square border for each mapped world. Provider availability is checked on
each refresh, so enabling or disabling the provider switches sources automatically.
When WorldBorder is enabled but has no border for a world, returns invalid geometry,
or exposes an incompatible API, the corresponding outline stays offline.
MCCMap never changes either provider's borders, and the map continues running.
The provider's own Dynmap connector looks up a plugin named dynmap; this native
read-only adapter avoids relying on that lookup after MCCMap's rename.

Tests cover the inspected API contract using independently authored fixtures,
including the public getter signatures. The actual third-party plugin was not
installed, built or executed as part of this compatibility work. Compatibility with
its public interface is verified; the provider's own compatibility with your Paper
version remains outside this adapter's control.

## Optional native Paper border

To display Paper's built-in square border instead, explicitly select:

```yaml
world-border:
  source: vanilla
```

Use source: disabled to turn off both native border sources. With vanilla selected,
the marker component's showworldborder and each world's showborder flags control the
outline. Missing/invalid native border data is skipped safely. The outline follows
Paper's center, size and absolute limits, including large and one-block borders.
A separate border add-on is not needed for the vanilla source.

Per-world visibility can be changed while renders are paused:

```text
/mccmap pause all
/mccmapconfig worldset world showborder:true
/mccmap pause none
```

Use showborder:false to hide a world's outline. Marker components and the client
marker layer must remain enabled to display overlays.
