# Contributing to MCCMap

This document is modified for the independent Paper-only fork. Upstream Dynmap's
multi-platform contribution requirements do not describe this project.

Version 1.0.0 is the initial baseline, including natural background rendering.
Subsequent changes must increment the plugin version (normally the patch version)
and update the changelog and artifact version consistently.

Use Java 25 and the Paper version pinned in the Gradle build files. Keep rendering,
markers, storage, and integration APIs working. The website is anonymous and
read-only: do not add account registration, login, or browser-to-server chat.
Minecraft-to-browser chat must originate from online player chat events with a
matching message identity. Reject cancelled and system messages. Do not restore
plugin/console chat APIs or user-specific website access control.

Run `./gradlew :paper:shadowJar :paper:test :DynmapCore:test`. Changes involving Paper internals
also require a test server run against the pinned build. Test a real render, not
just plugin startup; Minecraft's serialized chunk format can change independently
of whether the plugin compiles. Check modern block-state palettes and world heights.

Retain Apache 2.0 and applicable third-party notices. Clearly mark modified files.
Report fork issues to this fork's maintainer, not upstream Dynmap support.
