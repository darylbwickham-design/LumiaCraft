# LumiaCraft actions

LumiaCraft actions appear anywhere Lumia lets you add an action: Twitch rewards, follows, subs, bits, TikTok events, chat commands, Stream Deck buttons, and automations.

## Recommended first test

Add **Show In-Game Message** to a Lumia command. Keep the target blank to use the default player and set the message to `{{username}} says hello!`. Trigger the command while a world is open.

## Modded content

The mob, item, and effect fields accept typed registry IDs. Use the full `modid:name` form, for example `minecraft:zombie`. Invalid or missing registry IDs are rejected by Minecraft without crashing the bridge.

## Cooldowns and bursts

Each gameplay action can have its own cooldown. The global action-per-minute setting is a second safety net. A multi-command preset counts as one Lumia action but still obeys the bridge command allow-list.

## Custom commands

Enable **Allow custom command actions** only when you need **Run Minecraft Command**, **Pick a Random Command**, or **Run Command Sequence**. The server independently restricts command roots in `config/lumia-bridge.json`.
