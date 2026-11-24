# MCCMap

An independent, Apache-2.0-licensed, Paper-only fork of [Dynmap](https://github.com/webbukkit/dynmap).
Based on upstream commit `93b454efb8802dc7406d6873434f2aeec5c636f4`.
This fork is maintained separately and is not endorsed or supported by the Dynmap team.

## Target

- Paper **26.3 build 253**, API `26.3.build.253-stable`.
- Java **25**.
- One Paper adapter using Mojang-mapped server internals. Older versions, standalone
  Spigot/CraftBukkit, Forge, Fabric, and Folia are outside this fork's support scope.
- No website accounts, registration, passwords, or login sessions.
- Only player-authored Minecraft chat is shown in the browser.
- Console messages, plugin broadcasts, synthetic system chat, and join/quit announcements are excluded.
- The former `/dynmap sendtoweb` command and public chat-posting APIs are removed.
- The browser cannot submit chat into Minecraft.

## Retained features

The initial map view defaults to X=0, Z=0 at the world's sea level. Set `center`
in a world's configuration to choose another starting position. Coordinate links
and positions remembered while switching worlds in the same session take priority.

World rendering, flat/isometric/cave maps, live tile updates, player markers and
global visibility controls, marker commands and APIs, resource pack support, and the
local file storage. Minecraft administrative commands still use
Bukkit permissions, with optional LuckPerms 5 support for offline permission checks.
Optional SkinsRestorer integration targets API 15.12.6 and resolves custom skins by
player UUID from the asynchronous skin worker.
The plugin identifier is `MCCMap`; commands are `/mccmap`, `/mccmarker`,
`/mccmapconfig`, and `/mccmapexport`. Permissions use `mccmap.*`.
Update permission grants and plugin integrations that depend on the old `dynmap`
plugin identifier. Upstream `org.dynmap` Java API packages and renderer module names
remain for source compatibility and attribution. Do not install alongside Dynmap.
Both `/version MCCMap` and `/mccmap version` identify this as a Dynmap fork.

## Native overlays and connection logging

WorldGuard regions are displayed natively when WorldGuard/WorldEdit are installed;
no Dynmap-WorldGuard add-on is needed. Dedicated PryPurity WorldBorder borders have a native, read-only adapter; Paper
borders are the automatic fallback when that plugin is absent or disabled, and can also be selected explicitly. The dedicated plugin is not bundled.
Optional TCP peer IP:port logging is disabled by default. See `WEB-OVERLAYS.md` for
configuration, log rotation/privacy controls, and overlay behavior.

## Dependencies

See `DEPENDENCIES.md` for the dependency audit, compatibility pins and runtime
ownership. This remains development version 1.0.0; changing the JAR refreshes
bundled assets through a build fingerprint without a version bump.

## Build

Install JDK 25, set `JAVA_HOME`, then run:

```sh
./gradlew :paper:shadowJar :paper:test :DynmapCore:test
```

Windows:

```bat
gradlew.bat :paper:shadowJar :paper:test :DynmapCore:test
```

The installable file is `target/MCCMap-1.0.0-paper.jar`. Other module JARs are build
components, not standalone plugins. Gradle downloads the matching Paper development
bundle; do not bundle or redistribute a Minecraft server JAR with this source.

## Install or upgrade

1. Stop Paper and back up the existing `plugins/dynmap` or `plugins/MCCMap` folder.
2. Remove the old Dynmap plugin JAR and place the MCCMap JAR in `plugins`.
3. Start Paper using Java 25. The default map address is `http://SERVER:8123/`.
4. Use `/mccmap radiusrender` or `/mccmap fullrender` as appropriate for your world.

The data folder is `plugins/MCCMap`. If it is absent or empty, an existing
`plugins/dynmap` directory is moved there without overwriting an MCCMap installation.
When both directories contain data, MCCMap uses its own directory and leaves the old
one alone; reconcile the desired data while Paper is stopped. Database/object-storage
backends are removed. An old non-filetree configuration stops with a clear message;
export or rebuild those maps before selecting filetree. Existing data is not deleted.
Obsolete web-account and incoming-chat settings are removed from configuration
during upgrade. This one-time configuration rewrite normalizes YAML formatting
and removes comments; back up your existing configuration first. Known obsolete
local web endpoints, `webauth.txt`, and `ids-by-ip.txt` are deleted during startup. If you
copied the site to a separate external host or S3 bucket, replace that deployment
with a clean copy of this fork's web assets so old endpoints are removed there too.
Customized web files should be backed up before upgrading.

The website is public to anyone who can reach it. Account-restricted maps are no
longer supported. Existing player hiding remains available. The old `protected-player-info` option
is migrated to `hide-player-info`, which hides live player positions and health from everyone.
Per-viewer access lists and world/map web-account permissions are removed.

Use the existing `SimpleWebChatComponent` with `allowchat: true` for Minecraft-to-web
chat. Cancelled messages, offline senders, system messages, and sender-identity
mismatches are rejected. The `chat`, `chatbox`, and optional `chatballoon` client
components display received player messages only. Old `allowwebchat: true` cannot enable incoming messages.

## Java web server and PHP

MCCMap includes no PHP/ASP.NET scripts, PHP execution integration, or generated PHP
credential files. Its embedded web server is Java. The PHP runtime vulnerabilities
CVE-2026-109618, CVE-2026-109619, and CVE-2026-115560 do not apply to that runtime.
This does not assess any separate PHP services installed on your server.
Official advisories: https://www.php.net/archive/2026.php

Database-backed maps require the embedded Java server (`disable-webserver: false`).
A reverse proxy can serve it under an existing public web address. Static external
hosting remains available for local files using plain JSON exports and static
assets; database/object-storage backends and PHP hosting are removed. Clean separately deployed
old sites as described above; the plugin can only clean files on its local host.

## Source layout

| Module | Purpose |
| --- | --- |
| `paper` | Paper plugin, commands, events, permissions |
| `bukkit-helper` | Current Paper block, biome, and chunk adapter |
| `DynmapCore` | Rendering, storage, HTTP server, browser assets |
| `DynmapCoreAPI` | Shared rendering and marker interfaces |
| `dynmap-api` | Bukkit/Paper integration interfaces |

Chat-posting methods, incoming-chat event classes, and per-viewer player-set APIs
are removed, including their compatibility definitions. Integrations using those
retired methods must be updated. Map player IDs use the `playerId` JSON field;
refresh the browser assets together with the plugin. If you disabled
`update-webpath-files`, enable it for this upgrade or deploy the new assets yourself.
Original renderer definitions and texture assets are intentionally retained:
removing a platform port does not prove its shared render resources are unused.

## License and attribution

Apache License 2.0; see `LICENSE` and `NOTICE`. Preserve applicable upstream and
third-party notices. Modified files carry modification notices. Dynmap is a
registered trademark of Michael Primm. Direct issues for this fork to its
maintainer, rather than Dynmap's support channels.

## Update checks

MCCMap checks [published GitHub Releases](https://github.com/tommy10606/MCCMap/releases)
once at startup in the background. Status and download links appear only in the
console. There are no periodic checks, player notifications, update commands or
automatic downloads/installations. Set `update-checker.enabled: false` to disable;
`update-checker.include-prereleases` defaults to false. Drafts are always ignored.
Use version tags such as `v1.0.1` on published releases; a tag alone is insufficient.
No published release means the update status is unknown, not a claim of being current.

## Full-render speed with players online

Full/radius renders target 125% of their original configured throughput with no
players online, and 25% of that same original baseline while players are online.
Natural background renders use 10% while occupied. The online percentages are
relative to the original baseline, not the boosted speed. The policy uses the existing
`timesliceinterval` and `parallelrendercnt` as the immutable baseline.

```yaml
full-render-speed:
  no-players-percent: 125
  players-online-percent: 25
  players-online-max-workers: 1
```

`players-online-max-workers` limits each full/radius/natural job to one tile worker
by default while players are present, reducing CPU bursts as well as average
work. An already-running batch finishes safely; later batches use the limit.
The old 50% full/radius and 25% natural defaults migrate once to 25% and 10%;
other custom percentages are preserved. The marker
`online-render-speed-settings-version: 2` prevents repeating this migration.

Join/quit events and server ticks update presence. Pending render delays recheck
presence at least once per second. Empty-server boosts use limited extra parallel
work (fractional headroom is spread across batches) and a shorter configured
interval. Actual throughput remains limited by CPU, storage and chunk budgets.
TPS, storage and manual/player-limit pauses still take priority. Additional boost
workers are reserved for full/radius renders; the normal update executor and
stale-refresh pacing retain their existing settings and capacity.

The 60-day stale refresh continues to target 10% of the original baseline, without
applying either player-count multiplier. Normal block-change updates also keep
their existing scheduling. `/mccmap stats` shows the current full/radius speed multiplier.

## Render completion estimates

Use `/mccmap progress [world]` for a concise, colored status, or `/mccmap stats`
for progress alongside the other statistics. Both use the operator-only
`mccmap.stats` permission. Regular render updates also include the estimate and
still respect `/mccmap quiet` and `progressloginterval`.

Full/radius renders first count generated region headers and project those chunks
into base tiles, in bounded batches off the server thread. This does not generate
chunks. The inventory cursor and partial totals survive restarts. Percentages
apply to the **current map/render group**, not all worlds or subsequent zoom
repairs, and are labeled approximate: disconnected terrain, visibility limits,
empty tiles and terrain changes can affect the final count. They stay below
100% until that render actually finishes. Unbounded update renders, whose stopping
point depends on changed tiles, show counts with percentage/ETA unavailable.

ETA uses recent elapsed throughput, including pacing and parallelism. It needs at
least five seconds of samples, resets after restart, pauses or a player-presence
speed change, and shows the pause reason rather than a running countdown while
held. ETA assumes the current speed and does not predict future TPS pauses or
player joins. Background stale refresh and live update queues are continuous
services with no finite overall completion percentage.

## Natural background rendering

MCCMap automatically fills other enabled, writable maps when manual renders and
normal updates have finished. It keeps one automatic job separate from manual
jobs, so a new manual command can take priority even in the same world. It yields
at batch boundaries to manual jobs, queued/in-flight tiles, unprocessed block or
volume events, and delayed tile invalidations. An already-running batch can finish.
TPS, storage, full-render pauses and update-render pauses also hold this work.

```yaml
natural-render:
  enabled: true
  players-online-percent: 10
  startup-delay-seconds: 60
  idle-seconds: 10
```

With players online, automatic work targets 25% of the original full-render
baseline. Empty servers use `full-render-speed/no-players-percent` (125% by
default). Manual full/radius renders retain their independent 125%/50% policy;
60-day stale refresh retains its separate 10% pacing.

The automatic pass reuses current native tiles only when all configured variants
exist. It preserves older terrain rather than purging it. Completed manual full
renders and completed automatic maps are remembered by world/map layout, so this
is one pass per layout, not a continuous full rerender. Radius/update renders do
not mark the whole map complete. New layouts become eligible automatically.
Generated-chunk inventory supplies additional seeds for disconnected terrain,
using the same bounded queue and pacing. These automatic repairs never enter
the normal-speed update queue. Manual full-render repair checks remain separate.
Existing stale tiles are handled by the stale refresher.

`/mccmap naturalrender [status|on|off|resume]` requires `mccmap.fullrender`.
On/off state and explicit world cancellations persist with recovery checkpoints.
`off` holds the saved frontier; `on` enables the service; `resume` also clears
cancelled-world suspensions and retries a stopped job. `/mccmap cancelrender world`
cancels manual work and suspends that world's automatic queue until `resume`.
Setting `natural-render.enabled: false` disables it at startup. Stats and
`/mccmap progress [world]` expose automatic work and its waiting reasons.
Frontiers, inventory/resume cursors and completed layouts use the existing
private checkpoint interval and clean-shutdown recovery.

## Automatic stale-tile refresh

Existing base tiles across all loaded/enabled worlds and maps become eligible
60 days after their last successful render. Players do not need to be present in
those areas. The first background scan starts five minutes after startup, then
a new scan waits at least 24 hours after the prior scan finishes.

The scanner streams batches of up to 4096 jobs, oldest first within each batch.
The batch size bounds memory, rather than limiting the whole map to 4096 jobs
per day. Only one stale refresh can be queued or running at a time. After it
finishes, an adaptive cooldown targets at most 10% of normal full-render
throughput, accounting for measured chunk-load/render time, the configured
full-render timeslice and parallelism. Queue delays or pauses can make it slower;
this is a throughput target, not a 10% CPU or TPS guarantee. There is no catch-up
burst after idle time. Normal update backlogs (20 queued tiles),
manual full/radius renders, manual update pauses and the existing low-TPS pause
hold this work. A render job may update multiple maps sharing a perspective.
The existing render concurrency and chunk budgets still apply. Only existing base
tiles are selected; zoomed-out images follow the normal update path. Saved terrain
can be read without a player nearby, and this does not request terrain generation.

```yaml
stale-tile-refresh:
  enabled: true
  max-age-days: 60
  scan-interval-hours: 24
  startup-delay-seconds: 300
  render-speed-percent: 10
  max-tiles-per-scan: 4096
  backlog-limit: 20
```

Existing installations using the old 30-day default migrate once to 60 days and
10% pacing. Explicit custom ages remain intact. The old fixed `tiles-per-minute`
setting is replaced by `render-speed-percent` (1–100). Full/radius player-count
multipliers are separate and do not affect stale refresh. Normal block-change
updates retain their existing scheduling.

The age is a rolling 60 × 24 hours, rather than a calendar-month boundary. It is
an eligibility threshold: a large backlog or busy server can take longer to drain.
Scans use image-file dates initially, without reading or decoding image content. Unknown dates are skipped.
Successful renders, including unchanged images, keep separate dates in private
`plugins/MCCMap/tile-refresh-history` files. Keep this directory when moving or
backing up the map. Losing it falls back to storage dates and can cause another
refresh of old unchanged images; it does not remove tiles. Invalid history paths
or background I/O errors disable only this refresher, leaving normal rendering
available. Disabled/unloaded worlds wait until they are available for a later scan.

## Restart recovery

Long-running map scans, full/radius/update renders, tile and zoom queues now save
progress every 60 seconds and on clean shutdown. Stale jobs resume through their
independent throttled queue. A hard crash may replay work since the last checkpoint.
See [docs/RECOVERY.md](docs/RECOVERY.md) for coverage, configuration and backup details.

## Storage usage and render budgets

`/mccmap stats` (`mccmap.stats`, now operator-only by default) now includes managed file
bytes/count, measurement age and scan status, the configured cap, reserved-growth
accounting, filesystem free space/reserve, installed JAR size, and render pause reasons. Stale-refresh statistics
show pending/awaiting jobs, submitted/completed/failed counts since startup,
current scan scope, checkpoint availability and eligibility/pacing settings. A
completed job means its renderer finished without a write or raytrace failure;
images may be unchanged. Failed refreshes remain eligible for a later scan.
Counters reset when the plugin restarts.

Only local files are supported. SQL/S3 implementations, drivers and SDK libraries
are removed. The balanced profile uses 16 pixels/block for surface maps, 8 for flat
maps and 4 for caves. Normal rendering does not reduce quality further when a
storage limit is reached; it pauses using the existing safeguards.

```yaml
storage:
  type: filetree
  max-usage-gib: 0
  minimum-free-disk-percent: 10
  free-space-failure-max-gb: 10
  usage-scan-interval-seconds: 60
```

`0` means unlimited. Positive values are GiB (1024^3 bytes), including decimals
such as `10.5`. Change the configuration and restart Paper to apply the cap.
The monitor counts logical regular-file sizes under the plugin data directory
plus configured web, tile, import and export directories. Nested/duplicate roots
are counted once; directory symlinks inside those roots are not traversed. The
installed JAR is reported separately and is outside the data cap. Files outside
these managed roots and filesystem allocation/inode overhead are not included.

Directory scans run off the server thread. Stats show a cached measurement and
request a new background scan; repeat the command to see the completed result.
Unreadable scans are reported; a configured cap holds rendering until usage can
be measured successfully. With an unlimited cap, logical accounting failures do not
stop rendering; the independent filesystem reserve still applies.

At the cap, normal/full/radius/stale/zoom-out rendering waits. Image writes reserve
positive file growth before writing so concurrent renderers cannot spend the same
budget. Refused writes preserve existing images and retain pending work for retry.
Free space within the budget resumes rendering after the next successful scan;
raising/removing the cap takes effect on restart, including saved pending jobs.
Files are never purged by the cap. This is a rendering budget, not an OS filesystem
quota: configuration/log/history/standalone metadata writes and transient image
replacement space can still grow beyond the configured number. Measurements are
cached, and writes made during a scan are conservatively added to budget usage,
so the budget figure may temporarily exceed measured bytes until a quieter scan.

The filesystem reserve defaults to **10%**, sampled at startup and every **24 hours**.
Between checks, MCCMap deducts committed map image growth and outstanding temporary
image reservations from cached headroom. Stats and tile writes do not trigger extra
free-space queries. Other applications' intervening writes are reflected at the
next scheduled check; this is not an OS disk quota.

If measurement fails, MCCMap logs an error and applies a fallback rendering cap
from `storage.free-space-failure-max-gb`: default **10 decimal GB**
(10,000,000,000 bytes). An explicitly smaller map cap takes precedence. After a
failure, capacity measurements stop for the rest of the run; the fallback cap
remains active until MCCMap starts again. Logical plugin usage is still
scanned every 60 seconds by default; a cap waits if that usage cannot be measured.
Existing tiles and pending work are preserved. Metadata and logs continue normally.
Zero percent disables the percentage reserve, while failure detection remains on.

## World selection and render pauses

`auto-enable-new-worlds: false` keeps newly discovered, unlisted worlds unpublished
until explicitly enabled. The default is true. Explicit enable/disable choices
persist even for previously unconfigured worlds. Manual pauses and player limits
are independent; `/mccmap pause none` removes manual pauses while keeping player,
TPS, storage and disk safeguards in effect.

## Skin and marker reliability

Two dedicated skin workers use a bounded 64-job queue, UUID coalescing and a
10-minute per-player cooldown. Downloads have connect/read/deadline limits,
a 1 MiB payload limit, at most three redirects and validated dimensions before
pixel decoding: widths 64..1024, with standard or legacy proportions. Invalid or
unavailable skins fall back to the bundled skin. Only HTTP/HTTPS URLs are accepted.

Marker disk writes are serialized and coalesced; failed writes retain their latest
snapshot for retry. Persistent YAML uses atomic replacement where supported.
Shutdown flushes pending marker files before storage closes. Tile enumeration
skips symbolic-link directories and files, including cyclic links.

All marker file imports, including `updateicon`, read filenames inside MCCMap's
import folder. Absolute paths, directory traversal and linked files are rejected.
Import files are limited to 1 MiB; icons must be PNGs at most 1024 by 1024 pixels.
Invalid images are rejected before replacing an existing icon.

The embedded map accepts GET and HEAD requests only and blocks hidden files.
`max-open-connections` defaults to 256 accepted TCP clients, including idle clients;
`max-connections` controls HTTP worker threads. Public image reads are bounded to
32 MiB and marker JSON reads to 64 MiB. Reads, writes and purges reject links below
the configured tile root; the configured root may itself be a symbolic link.

Minecraft 26.3 sign models cover all 13 wood families, including poplar, standing
and wall signs, attached/unattached hanging signs and wall-hanging supports.
Snowy grass/mycelium tops use their untinted textures; quarter-turn geometry stays
on exact block boundaries. Biome colors use Paper's native seed-dependent block
biome sampling and a five-by-five blend of cached snapshot colors.

## TPS notices, region details and zoom

When upgrading from an earlier unreleased build affected by missing grass/leaves,
restart with the new JAR and run `/mccmap fullrender <world>` (substitute the actual
world name). The corrected reader preserves native default and non-default states;
existing bad tiles need rendering again. Map files do not need to be deleted.
If an old render automatically resumes, cancel it with `/mccmap cancelrender <world>`
before starting the fresh full render so previously completed bad tiles are revisited.

TPS thresholds default to 15 for full/radius, update and zoom-out rendering. A
low sample holds rendering for ten minutes before another TPS decision; continued
low TPS extends the hold without another pause notice. With no online players,
TPS limits are ignored immediately. Console and the online command owner receive
one pause/recovery notice per transition. Manual, population and storage pauses
remain independent. Existing unreleased configurations using the old default 18
are migrated once to 15; other configured thresholds are preserved.

Operators can run `/mccmap tpscheck off` to bypass TPS checks for 24 hours,
`/mccmap tpscheck on` to restore them early, and `/mccmap tpscheck status` to see
the remaining time. Permission: `mccmap.tpscheck` (operator/admin by default).
The expiry persists across restarts. This bypass affects only TPS checks.

After a successful full render, a background missing-tile pass reads generated
chunk locations inside the effective WorldBorder plugin boundary, or the native
Minecraft border when that provider is absent. It checks the rendered maps' base
tiles and their zoom parents, including day/night variants, and queues missing
images through normal rendering. It does not generate terrain. Repairs respect manual pauses, TPS,
storage limits and update backlog; zoom tiles follow normal processing. Empty
projected map areas can be checked again because transparent tiles intentionally
have no image. The check runs once per full-render completion, without recursion.

`/mccmap stats` uses aqua headings, gray labels, green values, yellow pause/wait
states and red errors. It includes missing-tile progress and TPS bypass status.

WorldGuard regions use filled, selectable footprints instead of stacked faces.
Click anywhere inside one to see its name, height range, priority, owners, members
and groups. Cached player names are used where available, with UUID fallback.
Ownership changes update at the configured WorldGuard refresh interval.

`web-map-zoom.out-multiplier` defaults to 1.5 and `in-multiplier` to 1.25. These
extend the existing browser zoom limits by those scale factors; they do not reduce
native tile quality. Both accept factors from 1 through 16. The separate
`max-pixels-per-block` cap restricts zoom-in even when these multipliers permit more. The controls use fractional steps and share links
retain fractional zoom values.

Version output credits Tommy10606, Mike Primm (mikeprimm) and the original Dynmap
contributors. Embedded Jetty startup messages use the MCCMap package namespace;
compatibility API names, the license and upstream attribution remain intact.

Browser zoom fixes in this build preserve fractional display limits while using
integer stored tile levels. After replacing the JAR, restart Paper and hard-refresh
the map page (Ctrl+F5) to load the updated scripts. Existing tiles can be reused.

## Render resolution and storage

`render-quality: balanced` is the default. It uses 8 pixels/block for flat maps,
16 for stock surface views and 4 for caves. It disables boost detail for those
stock maps. Custom perspectives and read-only maps retain their declared layout.
Default-format stock maps use lossless PNG; explicit formats remain unchanged.

Compared with the previous high profile (flat 16, surface 32, cave 8), this halves
linear resolution and reduces base tile count by about 75% for the same world
area and tile size. Compressed bytes depend on terrain, map coverage, filesystem
allocation and retained migration data; 75% is not a guaranteed disk saving.
Changing browser zoom alone does not reduce stored images.

The old high default migrates once to balanced. `render-quality-settings-version: 2`
records that migration. Subsequently choosing `high` is respected. Use `configured`
to preserve explicit map perspectives without applying a stock quality profile.

`web-map-zoom.max-pixels-per-block: 16` caps browser magnification near one displayed
16-pixel texture per block, independently of stored resolution. Thus the balanced
flat map can be enlarged 2x without storing the former high-resolution images.
Set the cap to 0 to restore the map's older zoom-in range. This cap does not alter
world projection, region geometry or border coordinates.

A resolution change uses a separate raster namespace. Compatible old native PNG
terrain is resampled for display using its original projection and tile footprint;
it is never treated as a new render or baked into new stored overviews. Saved
source mappings are bound to the new layout. Negative-coordinate tiles and the
inverted vertical tile origin use the same mapping as terrain and overlays.

Install with a clean restart and hard-refresh the browser. Affected renders and
scanners cannot resume coordinate queues from a different geometry; they restart
safely on the new grid. Natural rendering can fill the new layout when foreground
work is idle, or use `/mccmap fullrender <world>`. The map remains viewable through
compatible previews while replacement terrain is rendered.

Disk use does not fall immediately when settings change. After a successful new
base write, old compatible base images are retired only when their complete world
footprint is covered by native replacements. Failed writes preserve old coverage.
Old overview files are retained conservatively during migration; do not delete
whole tile folders to reclaim space. Temporary overlap can increase disk use until
replacement rendering catches up. Existing storage caps and disk reserve remain
active. Tile dimensions remain 128 pixels by default.

## Public overlays, chat and world-border zoom

WorldGuard popups show inclusive X, Y and Z ranges, priority, owners and members.
The blue fill defaults to 0.06 opacity; set `worldguard-regions.fill-opacity` from
0 through 1 to adjust it. In-game command/progress output shares a colored MCCMap
prefix, highlighted commands and values, and distinct error/pause/success colors.
Existing formatted stats output is retained; console text remains unchanged.

With VentureChat installed, MCCMap uses its accepted channel event instead of
duplicating the cancelled native chat event. Only local online players in
`player-chat.venturechat-public-channels` (default: Global) are eligible. The
channel must have no permission or distance restriction, and its message must
match a recent native player chat event. Private/channel-restricted messages,
console/plugin broadcasts, remote-server messages, repeats and unmatched
moderation rewrites are excluded. The ordinary Paper signed-player relay remains
active when VentureChat is absent, including vanilla chat and EssentialsX Chat.
The relay uses the final processed message, excluding cancelled messages and
server/plugin broadcasts. Web-to-server chat and accounts remain removed.
Set `player-chat.venturechat-enabled: false` to disable the optional adapter.

The EssentialsX Warps layer publishes all warps by default, including warps with
per-warp permissions, and updates when a warp moves or is deleted. A nonempty
`essentials-warps.allow-list` limits publication; `hide-list` always wins. Set
`respect-per-warp-permissions: true` to require allow-listing restricted warps.
Homes are never read.
The public API adapter is optional and stays offline if EssentialsX is absent or
incompatible. Refresh defaults to 30 seconds and publication is bounded to 10000
markers. Click a warp marker to see its world and X/Y/Z position.

`web-map-zoom.fit-world-border: true` fits the effective dedicated WorldBorder
boundary, falling back to Minecraft's border. The default `border-padding: 1.1`
allows 10% more visible space along each dimension. The viewport determines the
minimum zoom; resize recalculates it. Additional coarse native levels are generated
from existing tiles, without generating terrain, and parents feed the next level
in the same background cycle. Interrupted work survives a pause. Border snapshots
are captured on the server thread; reload MCCMap after changing a border to update
the fit and generated level count. Set fit-world-border false for the previous
multiplier-based zoom limits.

For HTTPS and certificate options, see [docs/HTTPS.md](docs/HTTPS.md) and the
[example Caddyfile](docs/Caddyfile.example). Native embedded serving remains HTTP.

New public-map settings are added to existing configuration files during startup;
explicit channel lists, fill opacity and disabled integrations are preserved.

## Tile alignment across resolution changes

Raster tiles now use a storage namespace derived from their projection matrix,
tile size, boost and image format. Configured map names and prefixes remain stable
in worlds.txt. Changing the raster layout cannot silently reuse native or zoom
images from the previous geometry. Existing read-only archives retain their
configured layout; automatic quality upgrades do not modify read-only maps.

Compatible legacy PNG and JPEG tiles can be displayed in the new PNG namespace
without moving or renaming their files. Earlier default-format MCCMap quality
upgrades rendered PNGs on the effective high-detail grid while leaving the
lower-detail perspective in configuration. Recovery therefore maps those PNGs
separately from JPEGs on the original configured grid. Explicit PNG configurations
retain their configured source grid. The mappings are saved separately on reload;
older shared mapping hints no longer stretch default-format PNGs.

For a known stock source grid that differs from these migration defaults, the map
setting `legacy-preview-png-scale-shift` overrides the PNG mapping (0 means the
same grid, 1 means half the current source resolution, 2 means one quarter; -1
disables PNG recovery). Set it only when the source grid is known. Camera changes
and unknown perspective layouts are not preview compatible.

Previews are web-only: renderers read only stored native pixels, so recovery
imagery can never be baked into newly generated zoom overviews. A one-time,
checkpointed repair rebuilds affected native overviews from native children,
including removing orphaned overview artifacts; it does not rerender or delete
base tiles. The completion marker prevents repeating this repair on later boots.
Previews never count as freshly rendered native base tiles.
New base renders take precedence. For zoomed-out views, legacy terrain fills the
transparent portions of a partially generated new overview, so the existence of
one new parent file cannot blank the rest of the old map. Responses use a
content-derived checksum for the composed image; stored native pixels stay intact.

After a new base tile is safely written, old compatible PNG/JPEG base sources are
retired only when every overlapping native base replacement exists. Old overview
files are retained conservatively during migration, since a partially generated
new overview does not prove complete replacement coverage. Failed writes, blank
responses and linked paths do not retire old base sources.

For this recovery fix, install the JAR with a clean server restart and refresh the
map page. Format-only recovery on an unchanged grid can resume saved render
progress. A resolution change invalidates coordinate queues from the old grid;
replacement work starts on the new layout. Keep existing tile folders in place. The embedded
server supplies these previews; a separate static file server cannot compose them.

## Security review and custom skins

See [docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md) for findings, fixes and scope.
Skin downloads trust `textures.minecraft.net` by default and use HTTPS. Custom skin
providers must use HTTPS and have their exact hosts (including redirect hosts)
listed in `skin-download-allowed-hosts`. Unapproved downloads use the default skin.

## Paper world clocks

The website reads the selected world's default clock through Paper's World Clock
API. Its digital clock respects the clock's paused state and rate between server
updates; worlds without a clock hide the time display. The website retains its
24,000-tick dial; arbitrary custom timeline day lengths are not interpreted.
Clock reads never change server time, pause state or rate. Render ETA, stale-tile
age and checkpoint schedules use elapsed/real time independently of world clocks.
