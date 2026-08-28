# Lumia Bridge 0.3.1 Modpack Beta 1

These are optional beta bridges for the popular 1.19.2 modpack generation. They use the same local LumiaCraft plugin and the same automatic setup as the stable bridges.

## Install one file only

1. Close Minecraft.
2. Pick the JAR that matches the pack loader:
   - Forge 1.15.2 pack: `lumiabridge-forge-1.15.2-0.3.1-beta.1.jar` (Java 8)
   - Forge 1.16.5 pack: `lumiabridge-forge-1.16.5-0.3.1-beta.1.jar` (Java 8)
   - Forge pack: `lumiabridge-forge-1.19.2-0.3.1-beta.1.jar`
   - Fabric pack: `lumiabridge-fabric-1.19.2-0.3.1-beta.1.jar`
   - NeoForge 26.1.2 pack: `lumiabridge-neoforge-26.1.2-0.3.1-beta.1.jar` (Java 25)
3. Put it in the modpack's `mods` folder.
4. Open the world or server, then open Lumia Stream with the LumiaCraft plugin enabled.
5. Run `/lumiabridge status` in-game. It should show the local endpoint and connected Lumia client count.

## Included gameplay alerts

Damage, healing, death, respawn, potion effects, join/leave, dimension changes, advancements, crafting, and smelting are forwarded to Lumia. Existing LumiaCraft alert keys are used, so actions from other Lumia plugins can be attached normally.

## Beta status

All five JARs passed a clean production build against their target loader. They still need real-world testing in individual packs, especially heavily modified servers. Keep the stable bridge installed if your pack is already covered by a stable release. Please report the exact modpack name, Minecraft version, loader version, and latest log when a beta fails.

## Coming next

The first beta matrix is complete. Future additions will be added only after they compile and are clearly marked beta.
