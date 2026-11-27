# Changelog

## 1.0.1

- Redirect plain HTTP GET/HEAD on the HTTPS map port to the configured HTTPS
  hostname and port, preserving map links and query parameters. Plaintext map
  content remains inaccessible; forwarded headers and URI schemes cannot bypass TLS.
- Keep TLS-only behavior when HTTPS redirects are disabled.
- Keep routine automated-render messages out of the normal console log; expose
  their progress through stats/progress commands while retaining manual updates
  and actionable warnings/errors.
- Correct the HTTPS setup examples to mc.megacitycraft.com.

- Add optional native HTTPS with automatic Let's Encrypt HTTP-01 issuance and
  renewal, private certificate storage, persisted orders/retries, live certificate
  replacement, staging support and certificate status in `/mccmap stats`.

## 1.0.0

- Fill missing native base tiles across selected maps before refreshing pre-existing
  tiles during full renders; checkpoint both passes and defer cleanup safely.

- Reduce occupied-server full/radius rendering to 25% and natural rendering to 10%
  of the original baseline, with a one-worker online limit to reduce CPU bursts.

- Default to balanced stock resolution: flat 8, surface 16 and cave 4 pixels/block.
- Cap browser magnification at 16 displayed pixels/block, independently of storage.
- Resample compatible previous raster layouts in either resolution direction,
  including negative coordinates, with persisted mappings and safe base retirement.

- Separate display previews from renderer input and rebuild affected zoom overviews
  once from native tiles, preserving base tiles and full-render progress.
- Keep previously upgraded PNG terrain on its effective grid instead of stretching
  it using stale low-resolution configuration; map and retire JPEGs independently.

- Restore compatible legacy PNG terrain in versioned tile layouts, including
  same-grid maps and persisted quality-upgrade mappings.
- Composite old terrain beneath partial zoom overviews and preserve old overview
  files until replacement coverage can be established; retire base sources safely.

Initial Paper-only MCCMap fork of Dynmap.

- Target Paper 26.3 build 253 and Java 25.
- Provide anonymous map access with player-only server-to-web chat.
- Integrate WorldGuard regions, WorldBorder/vanilla borders and public EssentialsX warps.
- Use local file storage with usage statistics, caps, disk reserve and private connection logs.
- Support high-detail maps, extended zoom and matching tile/overlay projections.
- Add render percentage/ETA, player-aware pacing and TPS/storage pause controls.
- Resume render queues and scanner positions across restarts.
- Refresh stale tiles after 60 days at 10% baseline speed.
- Fill remaining maps automatically at 25% baseline speed online, yielding to foreground work.
- Respect Paper world clock rates and pause state; default the map view to X=0, Z=0.
- Harden map serving, marker content, filesystem access and skin downloads.

See README.md and the configuration file for settings and commands.
