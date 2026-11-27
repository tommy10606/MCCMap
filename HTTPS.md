# Built-in HTTPS

MCCMap can obtain and renew a Let's Encrypt certificate itself. HTTPS is disabled
by default; an existing HTTP installation is unchanged until you enable it.
Rendering and certificate work run separately. The plugin version is 1.0.2.

## Requirements

- A public DNS hostname pointing to your Minecraft server, for example
  `mc.megacitycraft.com`. Check both A and AAAA records; any advertised IPv6
  address must work too. A hostname is required; this implementation does not
  issue IP-address or wildcard certificates.
- Public **TCP port 80** forwarded to the plugin's challenge listener, by default
  TCP 8080. Let's Encrypt cannot validate HTTP-01 on public port 8123 or 8080.
  Keep this forwarding available for renewals. If port 80 already serves another
  website, route `/.well-known/acme-challenge/` for this hostname to port 8080
  and preserve its Host header. A DNS record alone does not forward ports.
- A reachable map TLS port, normally TCP 8123; outbound HTTPS to Let's Encrypt;
  correct server time; writable private plugin storage.
- Read and accept [Let's Encrypt's terms](https://letsencrypt.org/repository/).
  Set the agreement flag yourself. MCCMap does not infer consent.

Do not run Paper as root to bind port 80 or 443. Use firewall/NAT port forwarding.
A CDN/proxy must allow the validation request through, or temporarily use a DNS-only
record for setup. This implementation uses HTTP-01, not DNS-provider credentials.

## Configure

Back up `plugins/MCCMap/configuration.txt`, stop Paper, and add or edit:

```yaml
webserver-port: 8123
https:
  enabled: true
  domain: "mc.megacitycraft.com"
  public-port: 0
  redirect-http: true
  letsencrypt:
    enabled: true
    agree-to-terms: true
    email: ""
    staging: false
    challenge-bindaddress: 0.0.0.0
    challenge-port: 8080
```

Replace the example hostname. Contact email is optional. `public-port: 0` uses
`webserver-port`, giving `https://mc.megacitycraft.com:8123/`. To expose a normal
HTTPS address, forward public 443 to plugin 8123 and set `public-port: 443`.
Map content on `webserver-port` is **HTTPS only**. With `redirect-http: true`,
plain HTTP GET/HEAD requests to that same port receive a 308 redirect to the
configured HTTPS hostname and public port, preserving the path and query string.
For example, `http://mc.megacitycraft.com:8123/` redirects to
`https://mc.megacitycraft.com:8123/`. Other plaintext methods are rejected and never
reach map files or API handlers. Set `redirect-http: false` to retain a strictly
TLS-only map port. HTTPS still requires a valid certificate before the map listener
starts; redirects do not bypass certificate validation.
The HTTP challenge listener serves active validation tokens and, optionally,
redirects other requests to the configured HTTPS root. It never serves map files.

Start Paper and check console and `/mccmap stats` for certificate status. Initial
issuance can take several minutes. The map listener starts only after a valid
certificate is installed. Once HTTPS is enabled, invalid settings or certificate
failures never cause a silent fallback to public HTTP. Rendering can continue.
If `/mccmap url` uses a configured `publicURL`, update that URL to HTTPS too.

## Test and verify

Use `staging: true` first to test DNS and port forwarding without consuming
production issuance limits. Staging certificates are intentionally untrusted by
browsers. After validation works, stop Paper, switch staging to false, and restart.
Staging, production and manually supplied certificates have separate private stores.

Verify the browser shows a trusted HTTPS connection and check the certificate
hostname. Confirm tiles, markers, player positions and chat update over HTTPS.
Check that public HTTP redirects to your configured HTTPS address. If issuance
fails, check DNS, IPv6, port forwarding, clock and console status; do not repeatedly
remove certificate files or restart to force more orders.

## Renewal and private files

The plugin checks hourly and renews after about two thirds of the certificate's
actual lifetime. Renewal replaces the certificate without restarting Paper or the
map listener. A valid cached certificate keeps the map available during a CA outage.
Retries wait at least an hour and respect CA rate-limit retry times; retry state and
pending orders survive restarts. If the certificate expires, the map listener is
stopped on the next check; it never downgrades to HTTP. Browsers also enforce expiry.

Private files live under `plugins/MCCMap/tls/production-HOSTNAME/` (or `staging-`
and `manual-`), outside the web root. Keep the entire `tls` directory in private,
access-controlled backups. Never publish it, copy it into `web`, or commit it to
GitHub. It contains account and certificate private keys, the PKCS12 certificate,
and its password. Owner-only filesystem permissions are required; linked files
and public storage locations are rejected. Restoring the complete directory avoids
unnecessary replacement orders. No certificates or private keys are in the source ZIP.

## Bring your own certificate

Set `https.letsencrypt.enabled: false` and keep HTTPS enabled with the correct
hostname. First startup creates `tls/manual-HOSTNAME/store-password`; the map stays
offline until a valid certificate is present. Create an owner-only PKCS12 file named
`certificate.p12` in that directory, containing the private key and complete chain,
with alias **mccmap** and the password from `store-password`. Use that password for
both the store and key. Do not put it in shell history. The certificate must have an
exact DNS SAN for the configured hostname. Restart Paper or wait for the hourly
check after replacing the file atomically. Manual mode does not issue, renew or
serve a separate HTTP challenge listener. Same-port redirects still work when
`redirect-http` is enabled.

## Developer verification

Run `./gradlew :DynmapCore:testHttpsBundle` to check the finished plugin JAR
contains working relocated ACME providers, CSR generation and JWS signing.
`PebbleAcmeTest` is an optional local integration test: it runs only when
`MCCMAP_PEBBLE_CERT` points to a local Pebble server TLS certificate. It expects
Pebble on `https://localhost:14000/dir`, a DNS test server resolving the test name
to loopback, and Pebble HTTP-01 validation directed to port 18080. It exercises
real challenge serving, issuance, saved-order resumption and replacement. Never
point this test at a production CA or copy its keys into a live installation.

## Roll back

Stop Paper, set `https.enabled: false`, and restart to return the map port to HTTP.
Update the advertised URL and firewall forwarding accordingly. Keep the private TLS
folder for future use; disabling HTTPS does not delete it or any rendered tiles.
