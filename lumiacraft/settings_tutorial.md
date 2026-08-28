# Set up LumiaCraft

LumiaCraft needs two pieces:

1. The **LumiaCraft plugin** imported into Lumia Stream.
2. The matching **Lumia Bridge mod** installed in Minecraft.

Download the Minecraft mod from the creator's CurseForge projects:
[Hungry_Williams64 on CurseForge](https://www.curseforge.com/members/hungry_williams64/projects)

## 1. Choose the correct Minecraft mod

Install exactly one bridge JAR matching both your Minecraft version and mod loader:

| Minecraft | Loader | Java |
| --- | --- | --- |
| 1.21.1 | NeoForge | 21 |
| 1.21.1 | Fabric | 21 |
| 1.20.1 | Forge | 17 |
| 1.20.1 | Fabric | 17 |
| 1.18.2 | Forge | 17 |
| 1.12.2 | Forge | 8 |
| 1.10.2 | Forge | 8 |
| 1.7.10 | Forge | 8 |

Put the JAR in the modpack's `mods` folder. Fabric installations also need Fabric API. Remove older Lumia Bridge JARs so the pack contains only one version.

## 2. Start Lumia and Minecraft

Enable LumiaCraft in Lumia Stream, then start Minecraft and open a world or dedicated server. For a normal single-player setup on the same PC, keep these defaults:

- Minecraft host: `127.0.0.1`
- Bridge port: `38931`
- Server token: blank
- Default player: your Minecraft username, or `@p`

LumiaCraft keeps checking for Minecraft in the background. If it does not connect, run the **Reconnect to Minecraft** action after the world has loaded.

In Minecraft, `/lumiabridge status` shows the bridge address, connected Lumia clients, and event count. Operators can run `/lumiabridge test` to send a harmless test alert without changing player health.

## 3. Add your first Lumia action

Open any Lumia alert, channel-point reward, chat command, Stream Deck button, or automation. Add a LumiaCraft action such as **Show In-Game Message**.

For an easy first test:

- Placement: **Chat**
- Target: leave blank
- Message: `Hello {{username}}!`

Trigger it while the Minecraft world is open. The message should appear in Minecraft chat.

## Minecraft events into Lumia

Damage, healing, death, respawn, potion effects, joins, leaves, dimension changes, and supported progression/item events can trigger Lumia alerts and attached actions.

Leave **Player whose events become alerts** blank to accept every player's events. On a multiplayer server, enter your exact Minecraft username to ignore other players. Potion variations use exact registry IDs such as `minecraft:regeneration` or `modid:effect_name`. Minecraft 1.7.10 predates namespaced potion registries, so its values look like `potion.regeneration` instead.

## Optional: show Twitch and YouTube chat in Minecraft

Enable **Show stream chat in Minecraft**, then copy the token from **Lumia Stream > Settings > API** into **Lumia Developers API token**.

Connected Twitch, YouTube, and other Lumia chat appears as:

`Display Name: message`

If the platform provides no display name, LumiaCraft uses the username. The default recipient `@a` shows messages to everyone; you can enter an exact Minecraft username, `@p`, or `@r` instead.

## Dedicated or remote server

If Lumia runs on the same machine as the server, keep the local defaults.

If Lumia runs on another machine:

1. Stop the server.
2. Open `config/lumia-bridge.json` on the server.
3. Change `bind` to the server's private LAN or VPN address.
4. Restart the server.
5. Copy the generated token into LumiaCraft's **Server token** setting.
6. Set **Minecraft host** to that same private address.
7. Allow TCP port `38931` only from the Lumia computer.

Do not expose the bridge port openly to the internet. A private VPN such as Tailscale is recommended for a remote hosted server.

## Troubleshooting

- **Connection refused:** confirm the world/server is running and that the correct bridge JAR loaded, then reconnect.
- **Authentication failed:** the LumiaCraft server token must exactly match `config/lumia-bridge.json`.
- **Fabric will not start:** install the compatible Fabric API version.
- **No gameplay alert action runs:** edit the corresponding LumiaCraft alert and attach the desired action, then use `/lumiabridge test`.
- **Chat relay is disconnected:** verify the Developers API token and keep the local API address at `127.0.0.1:39231`.
- **Command root is not allowed:** the server owner must add the exact command root to `allowedCommands` and restart.
- **Action is cooling down:** wait for the action cooldown or adjust its safety settings.
