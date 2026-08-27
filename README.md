# RookieAuctions

RookieAuctions is a safe, immersive inventory-GUI auction plugin based on
[ezAuctions 2.4.4](https://github.com/elian1203/ezAuctions). It supports public and sealed bidding,
two scheduled daily auction sessions, bid confirmation, auction history, reward claims,
SQLite/MariaDB and Paper/Purpur 1.21.4.

The Java package, database schema and legacy `ezauctions.*` permissions remain compatible with the
upstream plugin so existing integrations and auction data can be migrated safely.

### Plugin Dependencies
This plugin requires your server to have `Vault` installed. If you do not have it installed, it can be found 
[here](https://www.spigotmc.org/resources/vault.34315/).

## Developers
RookieCuzz (RookieAuctions fork), Elian and Silverwolfg11 (upstream)

## Building
Install Java 21, clone the project, then run `mvn clean package` in the project directory.

## Immersive sessions

By default the plugin prepares two Asia/Shanghai sessions each day at 14:00 and 20:00. Each session
accepts up to 16 lots, locks submissions ten minutes before its scheduled start and auctions each lot
for 120 seconds with a ten-second intermission. A full session has a base duration of 34 minutes and
30 seconds; buyouts and anti-snipe extensions make the displayed session ETA dynamic.

The immersive scheduler is disabled until an administrator configures the venue with
`/auction admin venue set ...`, validates it, and enables it. Public sealed-auction displays never
show the current price. Existing queued lots are assigned to the next sessions during migration.

Back up `config.yml` and the SQLite/MariaDB database before upgrading an existing server. Generated
sessions retain their persisted start time; schedule changes apply only to sessions created later.

### Mineflayer integration smoke test

The repository includes a disposable Paper 1.21.4 test server and Mineflayer client under
[`integration/`](integration/README.md). Run `mvn -q -DskipTests package`, then execute
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\integration\run-smoke.ps1 -Reset` to verify
plugin loading, Vault/Essentials economy hookup, venue commands, session status, and the auction GUI.

When PlaceholderAPI is installed, every placeholder is available under both the
`%rookieauctions_*%` and legacy `%ezauctions_*%` namespaces. Session placeholders are
`session_state`, `session_start_time`, `session_remaining`, `session_current_lot`,
`session_total_lots`, `session_lot_progress`, `session_capacity`, `next_session_time`,
`session_current_mode`, and `session_current_bid`. Sealed lots return `已密封` for the public bid.
The legacy `remainingtime` placeholder continues to mean the current lot's remaining seconds.

## Anti-snipe configuration migration

Anti-snipe configuration version 2 changes `antisnipe.time` from seconds-to-add to the target
remaining time after a qualifying bid. For example, `time: 100` resets an eligible auction to
100 seconds, but never beyond its original duration and never shortens its current remaining time.

The version-3 configuration migration installs the new semantics with a 30-second trigger window, a
30-second reset target, and at most three resets per auction. Review these values after upgrading if
the previous installation used a customized add-time policy.

## API
To depend on this plugin in your own project, add the following to your maven / gradle project.

Repository:
```
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>
```
Dependency:
```
<dependency>
    <groupId>com.github.RookieCuzz</groupId>
    <artifactId>RookieAuctions</artifactId>
    <version>2.4.4-gui</version>
</dependency>
```
The API remains under the `me.elian.ezauctions` package for binary compatibility. Upstream API usage
is documented [here](https://github.com/elian1203/ezAuctions/wiki/api).
