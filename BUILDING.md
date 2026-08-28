# Building LumiaCraft

The release page contains tested binaries. These instructions are for contributors who want to build the source.

## Lumia plugin

The plugin requires a current Node.js LTS release.

```powershell
cd lumiacraft
npm ci
npm test
```

Package the plugin with Lumia Stream's plugin-development tooling. Do not commit `node_modules` or generated `.lumiaplugin` archives.

## Modern Minecraft bridges

Use Java 21 for Minecraft 1.21.1, Java 17 for 1.20.1/1.18.2/1.19.2, and Java 25 for NeoForge 26.1.2. The NeoForge projects include their modern Gradle wrappers.

```powershell
# NeoForge 1.21.1
./lumia-minecraft-bridge/gradlew.bat -p lumia-minecraft-bridge build

# Fabric 1.20.1 (the checked-in defaults)
./lumia-minecraft-bridge/gradlew.bat -p lumia-minecraft-bridge-fabric build

# Fabric 1.21.1
./lumia-minecraft-bridge/gradlew.bat -p lumia-minecraft-bridge-fabric build `
  -Pminecraft_version=1.21.1 `
  -Pfabric_loader_version=0.16.14 `
  -Pfabric_api_version=0.116.4+1.21.1 `
  -Pjava_version=21 `
  -Ppack_format=48

# Forge
./lumia-minecraft-bridge/gradlew.bat -p lumia-minecraft-bridge-forge-1.20.1 build
./lumia-minecraft-bridge/gradlew.bat -p lumia-minecraft-bridge-forge-1.18.2 build
./lumia-minecraft-bridge/gradlew.bat -p lumia-minecraft-bridge-forge-1.19.2 build

# NeoForge 26.1.2 (Java 25)
./lumia-minecraft-bridge-neoforge-26.1.2/gradlew.bat build
```

The bridge projects deliberately remain adjacent because their Gradle source sets reference the shared common directories by relative path.

## Legacy Forge bridges

Use a 64-bit Java 8 JDK. Old ForgeGradle releases are not compatible with current Gradle or current Java.

| Minecraft | ForgeGradle | Recommended Gradle |
| --- | --- | --- |
| 1.15.2 | 3.x | 5.6.4 |
| 1.16.5 | 4.1 | 6.8.3 |
| 1.12.2 | 2.3 | 4.10.3 |
| 1.10.2 | 2.2 | 3.5.1 or 4.10.3 |
| 1.7.10 | 1.2 | 2.14.1 |

Run each Gradle version with its project directory, for example:

```powershell
gradle-4.10.3/bin/gradle.bat -p lumia-minecraft-bridge-forge-1.12.2 build
gradle-4.10.3/bin/gradle.bat -p lumia-minecraft-bridge-forge-1.10.2 build
gradle-2.14.1/bin/gradle.bat -p lumia-minecraft-bridge-forge-1.7.10 build
```

ForgeGradle 1.2 points at a retired Mojang HTTP endpoint. The 1.7.10 build therefore expects the official 1.7.10 client and server JARs in ForgeGradle's normal cache paths. The published release JAR was built from the official artifacts and then reobfuscated by ForgeGradle.

## Generated files

Gradle caches, Java toolchains, run directories, build outputs, dependency directories, packaged plugin files, and release ZIPs are intentionally excluded from source control.
