# Lumia Minecraft Bridge — NeoForge 1.21.1

This directory contains the NeoForge 1.21.1 adapter for LumiaCraft. It uses the shared runtime in `../lumia-minecraft-bridge-common` and requires Java 21.

From the repository root on Windows:

```powershell
./lumia-minecraft-bridge/gradlew.bat -p lumia-minecraft-bridge build
```

The reobfuscated mod JAR is produced under `build/libs`. For installation and security guidance, see the [repository README](../README.md).

Developer references:

- [NeoForge documentation](https://docs.neoforged.net/)
- [NeoForge version list](https://projects.neoforged.net/neoforged/neoforge)
- [Mojang mappings licence](https://github.com/NeoForged/NeoForm/blob/main/Mojang.md)
