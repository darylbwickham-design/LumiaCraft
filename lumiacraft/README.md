# LumiaCraft 0.3.0

LumiaCraft turns Lumia Stream alerts, channel-point rewards, chat commands, Stream Deck buttons, and automations into safe Minecraft interactions. It supports the packaged NeoForge, Forge, and Fabric bridges and does not need RCON, Twitch credentials inside Minecraft, Bukkit, or a permissions plugin.

It also works in the other direction: Minecraft gameplay becomes native Lumia alerts. Player damage and healing report the new health value, while potion-effect alerts expose the exact vanilla or modded effect ID for Lumia variations.

## Two-minute setup

1. Put the Lumia Bridge 0.3.0 JAR matching the modpack's Minecraft version and loader in its `mods` folder.
2. Import `lumiacraft-0.3.0.lumiaplugin` in Lumia Stream and enable it.
3. Start or join a world. LumiaCraft connects to `127.0.0.1:38931` automatically.
4. In Lumia, edit any alert/reward/command and add a LumiaCraft action such as **Run Chaos Preset**, **Spawn Mob Near Player**, or **Give Item**.

Minecraft bridge downloads and project updates are available from [Hungry_Williams64 on CurseForge](https://www.curseforge.com/members/hungry_williams64/projects).

## Stream chat in Minecraft

Enable **Show stream chat in Minecraft** to relay Lumia's unified Twitch, YouTube, and other connected chat into the game as `displayname: message`. If a platform does not provide a display name, LumiaCraft uses its username. The feature is off by default.

Copy the token from **Lumia Stream > Settings > API** into **Lumia Developers API token**. Keep the default API host `127.0.0.1` and port `39231` when LumiaCraft runs inside Lumia on the same computer. Chat recipients default to `@a`; this can instead be a Minecraft username, `@p`, or `@r`.

Messages are safely JSON-escaped, flattened to one line, length-limited, de-duplicated when Lumia supplies a message ID, and protected by an independent per-minute limit. Chat relay does not consume the gameplay-action safety allowance.

The default local setup has no code to copy and no port to expose. The integrated single-player server starts the bridge when a world opens and stops it when the world closes.
LumiaCraft keeps checking for the bridge every 30 seconds, so it can be opened before Minecraft without becoming permanently disconnected.

## Dedicated or remote server

- If Lumia runs on the same computer as the server, the defaults still work.
- If Lumia runs on another computer, edit `config/lumia-bridge.json` on the server, change `bind` to the server's LAN address or `0.0.0.0`, restart, and copy the generated `token` into LumiaCraft settings.
- Allow TCP port `38931` only from the Lumia computer. Never expose it publicly unless you understand the firewall implications.

## Safety

- The bridge binds to localhost by default.
- A remote bind automatically generates a strong token.
- Minecraft enforces a command-root allow-list in `config/lumia-bridge.json`.
- LumiaCraft validates player names and item/entity/effect IDs, bounds counts and durations, rate-limits actions, and keeps raw command actions disabled by default.
- Dry-run mode logs the commands without sending them.

## Included actions

- Spawn vanilla or modded mobs
- Give vanilla or modded items
- Apply status effects
- Damage players
- Change time/weather
- Show titles, actionbar, or chat messages using Lumia variables
- Ready-made chaos presets
- Random command pools and timed sequences
- Advanced allow-listed commands

## Minecraft event alerts

Minecraft gameplay events enter Lumia's alert-processing queue so configured
actions—including actions from other plugins such as Pixelboard—can run.
Version 0.3.0 follows Lumia's utility-plugin alert path: ordinary gameplay
alerts send variables through `extraSettings` without requesting Event List
recording, while potion alerts explicitly declare effect-ID variations. This
keeps configured alert actions—including actions from other plugins—attached to
the normal alert-processing path.

- Player damaged: amount, final health, maximum health, absorption, damage source and attacker
- Player healed: actual healed amount, previous health and new health
- Player death and respawn
- Potion effect applied, removed or expired, including effect ID, display name, level and duration
- Player join, leave and dimension change
- Advancement earned
- Item crafted or smelted (optional because it can be noisy)

Damage and healing are detected from server-authoritative values every tick. Calculations retain full precision, while Lumia-facing health values default to one decimal place; this is adjustable from 0 to 2 decimal places in plugin settings. Health is validated and clamped, tiny floating-point noise is ignored, absorption expiry is not misreported as damage, and absorption gain is not misreported as healing. The `healthPercent` and `effectiveHealth` alert variables are also available.

Use the **Read Minecraft Event Status** action to compare the bridge's published-event count with LumiaCraft's received-event count. The current bridge version and both counters are also exposed as Lumia variables.

You can also run `/lumiabridge status` in Minecraft without enabling cheats. It displays the bridge endpoint, the number of connected Lumia clients, and event count. As an operator, run `/lumiabridge test` to send a harmless test damage alert; it does not alter player health.

For a potion variation, open the **Minecraft: Potion Effect Applied** alert in Lumia and add a variation condition on the alert's dynamic value. Use an exact registry ID such as `minecraft:speed`, `minecraft:poison`, or `yourmod:effect_name`.

## Compatibility

The Lumia plugin side is game-version independent. Bridge 0.3.0 builds are provided for NeoForge 1.21.1; Fabric 1.21.1 and 1.20.1; and Forge 1.20.1, 1.18.2, 1.12.2, 1.10.2, and 1.7.10. Fabric builds require Fabric API. Legacy Forge builds require Java 8. See the release matrix README for exact filenames and event coverage.
