# HTTPS for MCCMap

The embedded server serves HTTP. Terminate HTTPS at a reverse proxy such as Caddy,
Nginx, HAProxy or Traefik. This keeps certificate renewal independent of Minecraft
restarts. The map uses relative asset/update URLs and works behind a proxy without
restoring accounts, PHP or web-to-server chat.

For Caddy on the same host, point a hostname (for example map.example.com) at the
server. Allow inbound TCP 80 and 443 to Caddy. Use:

```caddyfile
map.example.com {
    reverse_proxy 127.0.0.1:8123
}
```

Caddy obtains and renews a public certificate automatically when the hostname and
ACME validation are reachable. Check both A and AAAA records if publishing IPv6.
In MCCMap configuration.txt set:

```yaml
webserver-bindaddress: 127.0.0.1
webserver-port: 8123
publicURL: https://map.example.com/
```

Restart MCCMap after changing the bind address. Keep port 8123 private behind the
proxy. If the proxy is on a different host/container, use a reachable private
backend address and allow backend access from the proxy only; loopback in a
container refers to that container.

To use an existing certificate instead of Caddy-managed issuance:

```caddyfile
map.example.com {
    tls /etc/ssl/map/fullchain.pem /etc/ssl/map/privkey.pem
    reverse_proxy 127.0.0.1:8123
}
```

Keep the private key outside MCCMap's web directory and readable only by the proxy
service. Manage renewal externally when supplying certificate files. Do not place
certificate keys in configuration downloads, marker folders or source archives.

MCCMap's connection log records the TCP peer, which becomes the reverse proxy.
Use the proxy's access log for original client IP addresses. MCCMap intentionally
does not trust arbitrary forwarded-IP headers from public visitors.

References: https://caddyserver.com/docs/automatic-https and
https://caddyserver.com/docs/caddyfile/directives/tls
