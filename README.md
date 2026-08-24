# RookieAuctions

RookieAuctions is a safe, 54-slot inventory-GUI auction plugin based on
[ezAuctions 2.4.4](https://github.com/elian1203/ezAuctions). It supports public and sealed bidding,
auction queues, bid confirmation, auction history, reward claims, SQLite/MariaDB, Paper and Folia.

The Java package, database schema and legacy `ezauctions.*` permissions remain compatible with the
upstream plugin so existing integrations and auction data can be migrated safely.

### Plugin Dependencies
This plugin requires your server to have `Vault` installed. If you do not have it installed, it can be found 
[here](https://www.spigotmc.org/resources/vault.34315/).

## Developers
RookieCuzz (RookieAuctions fork), Elian and Silverwolfg11 (upstream)

## Building
Clone the project from GitHub, then run `mvn clean package` in your terminal at the project directory to build the project.

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
