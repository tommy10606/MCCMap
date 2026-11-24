# MCCMap 1.0.0 security review

Reviewed November 11, 2026. Scope: the current Paper-only plugin, bundled local-file
web server, public map endpoints, skin retrieval, marker publication, chat relay,
file access, connection logging and Java runtime dependencies and bundled browser packages.

## Patched findings

| Finding | Conditions and impact | Fix |
| --- | --- | --- |
| Skin URL server-side request forgery exposure | A selected player profile or skin provider could supply an arbitrary HTTP(S) host, including a private service or cloud metadata address. Redirects could change that host. Exploitation depends on which profile/provider the server accepts. | Downloads default to `textures.minecraft.net` only. Every redirect is checked again. Additional providers require exact administrator-approved hostnames and HTTPS on port 443. Credentials, fragments, alternate ports, IP literals and non-HTTP protocols are rejected. Historical official HTTP texture URLs are fetched over HTTPS. |
| Stored HTML injection in point markers and layer names | Marker creation accepted unsanitized HTML when markup was enabled. Marker-set labels were sent to an HTML-based layer control without encoding. Unsafe data reaching these APIs could execute script in visitors' browsers. This requires marker input through an installed integration or marker-management access; the website has no marker-writing endpoint. | Sanitize initial point labels, encode layer names in snapshots and updates, and preserve supported benign marker formatting. |
| Persisted marker sanitization bypass | A stored `isSafe` flag skipped sanitization during reload, allowing previously unsafe content to survive updates and restart. | Reapply the current sanitizer to all saved point/area/line/circle labels and descriptions regardless of the old flag. |
| Generated JavaScript quoting | URL configuration was interpolated into JavaScript string literals. This requires configuration access, so it is defense in depth rather than a demonstrated unauthenticated attack. | Serialize URL values as JSON data, escaping quotes, control characters and script terminators. |

## Custom skin providers

Official Minecraft skins require no new setting. Custom providers must use HTTPS
and be listed explicitly, including any redirect destinations:

```yaml
skin-download-allowed-hosts:
  - skins.example.com
```

Entries are exact hostnames, not URLs, suffixes or wildcards. Do not add hosts
controlled by players or untrusted parties. The allow-list grants those hosts
outbound network access; it is an administrator trust boundary, not a mechanism
for safely approving arbitrary domains. Rejected downloads use the existing
fallback skin. Existing byte, image-dimension, worker, queue, timeout and redirect
limits remain in place.

## Existing controls reviewed

- Public serving accepts GET/HEAD only; accounts, login, registration, sessions,
  PHP and web-to-server chat remain absent.
- Player chat retains sender-identity, online and cancellation checks. VentureChat
  also requires recent native player origin and an approved public channel.
- Storage and marker imports reject traversal and linked child paths, bound reads
  and image dimensions, and use atomic native tile replacement.
- Connection logs contain numeric socket peers and timestamps rather than URI,
  header or other client-supplied text. Logs remain private and rotate with bounds.
- YAML uses the safe constructor. Marker descriptions and player display HTML pass
  through the bundled sanitizer.

## Verification and limits

Regression tests cover malicious marker creation, saved content marked safe,
layer names, generated JavaScript quoting, unapproved skin destinations and
redirects, HTTPS upgrades, custom-provider approval and the existing security
suite. Run the Java and JavaScript tests using the build commands in
`CONTRIBUTING.md`.

This review does not audit the host operating system, Java distribution, Paper
implementation, reverse proxy or independently installed plugins. The embedded
map server still serves HTTP; use the existing HTTPS reverse-proxy instructions
for transport encryption. No production server or external service was scanned
or reconfigured. The public map intentionally exposes enabled map data and public
warp/region information. This is a source/dependency review with targeted tests,
not a claim of complete penetration-test coverage or protection against a
volumetric denial-of-service attack.
