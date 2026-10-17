# AFKable Client

AFKable Client is a Fabric client mod for the AFKable server. It includes inventory sorting, Factory NPC tools, forge and stock helpers, player stats, HUD widgets, and custom tab badges.

## Install

1. Install Fabric Loader and Fabric API for Minecraft 1.21.8 or newer versions supported by the mod.
2. Download `AFKableClient.jar` from the [latest GitHub release](https://github.com/armadillo122c-blip/AFKable-Client/releases/latest).
3. Put the JAR in the Minecraft `mods` folder and launch the game.

The client checks GitHub Releases on login, world join/leave, and every 10 minutes. Release downloads are SHA-256 checked and staged for installation after Minecraft closes. The **Versions** screen also has a manual check button. Developers can broadcast an update check to online AFKable clients from the Dev Panel; offline players check on their next login.

## Build

Use Java 21 and run:

```powershell
.\gradlew.bat remapJar
```

The distributable is `build/libs/AFKableClient.jar`.

## Publish a release

Push a version tag such as `v3.20.2`. GitHub Actions builds the distributable and attaches `AFKableClient.jar` to a GitHub Release. The in-game updater follows the latest published non-prerelease release.
