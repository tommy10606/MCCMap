# MCCMap dependencies — November 24, 2026

MCCMap version **1.0.1**. Map storage is local-file-only. SQL/S3
implementations and PostgreSQL, AWS SDK, EventStream and Reactive Streams runtime
libraries have been removed. No JDBC drivers or remote-storage clients are bundled.

| Component | Version / ownership |
| --- | --- |
| Java | 25 |
| Paper API / internals | 26.3 build 253; provided by Paper |
| Gradle | 9.9.0; wrapper distribution checksum pinned |
| Shadow | 9.6.1 |
| paperweight | 2.0.0-beta.24 |
| Jetty server and EE8 nested support | 12.1.14 |
| Jetty servlet API | 4.0.9 |
| acme4j client | 5.1.0; automatic Let's Encrypt HTTP-01 |
| jose4j | 0.9.7; ACME signed requests |
| Bouncy Castle provider / PKIX / util | 1.87; certificate signing requests |
| SnakeYAML | 2.7 |
| OWASP Java HTML Sanitizer | 20261103.2 |
| JSON-simple | 1.1.1 |
| SLF4J API constraint | 2.0.20; server supplies logging bindings |
| jQuery / Leaflet / Mousewheel | 4.0.0 / 1.9.4 / 3.2.2 |
| LuckPerms / SkinsRestorer APIs | 5.5 / 15.12.6; optional plugins supply implementations |
| WorldGuard / WorldEdit APIs | 7.0.21 / 7.4.6; optional plugins, not bundled |
| WorldBorder | Independent read-only reflection adapter; no upstream implementation dependency |
| JUnit Platform / Mockito / AssertJ | 6.1.3 / 5.24.0 / 3.27.7; tests only |

Renderer and web libraries are relocated in the product JAR to reduce plugin
conflicts. Paper, Minecraft, optional plugins and external image encoders remain
separately maintained applications. Third-party notices and browser library licenses
remain included. See build files and the Gradle wrapper for exact build/test pins.

Primary sources: https://repo.maven.apache.org/maven2/,
https://jetty.org/security.html, https://github.com/OWASP/java-html-sanitizer/security,
https://osv.dev/, https://docs.papermc.io/.
