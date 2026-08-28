package me.lumiabridge;

import com.google.gson.JsonObject;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import me.lumiabridge.legacy.LegacyBridgeRuntime;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.DamageSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod(modid = LumiaBridgeLegacy1710Mod.MOD_ID, name = "Lumia Bridge", version = LumiaBridgeLegacy1710Mod.VERSION, acceptableRemoteVersions = "*")
public final class LumiaBridgeLegacy1710Mod {
    public static final String MOD_ID = "lumiabridge";
    public static final String VERSION = "0.3.0";
    private static final Logger LOGGER = LogManager.getLogger("LumiaBridge");
    private static final float EPSILON = 0.001F;

    private final Map<String, PlayerSnapshot> snapshots = new HashMap<String, PlayerSnapshot>();
    private final ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<Runnable>();
    private final Random random = new Random();
    private MinecraftServer server;
    private LegacyBridgeRuntime runtime;

    public LumiaBridgeLegacy1710Mod() {
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        server = event.getServer();
        event.registerServerCommand(new LumiaBridgeCommand(this));
        try {
            runtime = LegacyBridgeRuntime.start(new ForgeHost(), VERSION);
        } catch (Exception error) {
            LOGGER.error("Could not start Lumia Bridge", error);
        }
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        if (runtime != null) runtime.close();
        runtime = null;
        server = null;
        snapshots.clear();
        serverTasks.clear();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        drainServerTasks();
        monitorPlayers();
    }

    private void drainServerTasks() {
        Runnable task;
        int processed = 0;
        while (processed < 100 && (task = serverTasks.poll()) != null) {
            try { task.run(); } catch (Exception error) { LOGGER.warn("Lumia task failed", error); }
            processed++;
        }
    }

    @SuppressWarnings("unchecked")
    private List<EntityPlayerMP> players() {
        return (List<EntityPlayerMP>) (List<?>) server.getConfigurationManager().playerEntityList;
    }

    private void monitorPlayers() {
        List<EntityPlayerMP> players = players();
        Set<String> online = new HashSet<String>();
        for (EntityPlayerMP player : players) {
            String id = player.getUniqueID().toString();
            online.add(id);
            PlayerSnapshot current = snapshot(player);
            PlayerSnapshot previous = snapshots.get(id);
            if (previous == null) {
                JsonObject data = playerData(player);
                addHealth(data, current);
                publish("player_join", data);
                snapshots.put(id, current);
                continue;
            }

            float healthDelta = current.health - previous.health;
            float absorptionDelta = current.absorption - previous.absorption;
            float absorptionLost = current.hurt ? Math.max(0.0F, -absorptionDelta) : 0.0F;
            float damage = Math.max(0.0F, -healthDelta) + absorptionLost;
            if (damage > EPSILON) {
                JsonObject data = playerData(player);
                addHealth(data, current);
                data.addProperty("previousHealth", previous.health);
                data.addProperty("previousAbsorption", previous.absorption);
                data.addProperty("amount", damage);
                data.addProperty("originalAmount", damage);
                data.addProperty("blockedAmount", 0.0F);
                data.addProperty("source", "unknown");
                publish("player_damage", data);
            } else if (healthDelta > EPSILON) {
                JsonObject data = playerData(player);
                addHealth(data, current);
                data.addProperty("previousHealth", previous.health);
                data.addProperty("previousAbsorption", previous.absorption);
                data.addProperty("amount", healthDelta);
                publish("player_heal", data);
            }

            if (!previous.dead && current.dead) {
                JsonObject data = playerData(player);
                addHealth(data, current);
                data.addProperty("source", "unknown");
                publish("player_death", data);
            } else if (previous.dead && !current.dead) {
                JsonObject data = playerData(player);
                addHealth(data, current);
                publish("player_respawn", data);
            }

            if (previous.dimension != current.dimension) {
                JsonObject data = playerData(player);
                data.addProperty("fromDimension", String.valueOf(previous.dimension));
                data.addProperty("toDimension", String.valueOf(current.dimension));
                publish("dimension_change", data);
            }

            publishEffectChanges(player, previous.effects, current.effects);
            snapshots.put(id, current);
        }

        List<String> left = new ArrayList<String>();
        for (Map.Entry<String, PlayerSnapshot> entry : snapshots.entrySet()) {
            if (!online.contains(entry.getKey())) {
                JsonObject data = identityData(entry.getValue().username, entry.getKey());
                data.addProperty("dimension", String.valueOf(entry.getValue().dimension));
                publish("player_leave", data);
                left.add(entry.getKey());
            }
        }
        for (String id : left) snapshots.remove(id);
    }

    private void publishEffectChanges(EntityPlayerMP player, Map<String, EffectSnapshot> before, Map<String, EffectSnapshot> after) {
        for (Map.Entry<String, EffectSnapshot> entry : after.entrySet()) {
            EffectSnapshot old = before.get(entry.getKey());
            EffectSnapshot value = entry.getValue();
            if (old == null || old.amplifier != value.amplifier) publishEffect("effect_applied", player, value);
        }
        for (Map.Entry<String, EffectSnapshot> entry : before.entrySet()) {
            if (!after.containsKey(entry.getKey())) publishEffect("effect_expired", player, entry.getValue());
        }
    }

    private void publishEffect(String event, EntityPlayerMP player, EffectSnapshot effect) {
        JsonObject data = playerData(player);
        data.addProperty("effect", effect.id);
        data.addProperty("effectName", effect.name);
        data.addProperty("amplifier", effect.amplifier);
        data.addProperty("level", effect.amplifier + 1);
        data.addProperty("durationTicks", effect.durationTicks);
        data.addProperty("durationSeconds", Math.max(0, effect.durationTicks / 20));
        publish(event, data);
    }

    @SuppressWarnings("unchecked")
    private PlayerSnapshot snapshot(EntityPlayerMP player) {
        Map<String, EffectSnapshot> effects = new HashMap<String, EffectSnapshot>();
        Collection<PotionEffect> active = player.getActivePotionEffects();
        for (PotionEffect effect : active) {
            int potionId = effect.getPotionID();
            Potion potion = potionId >= 0 && potionId < Potion.potionTypes.length ? Potion.potionTypes[potionId] : null;
            String id = potion == null ? String.valueOf(potionId) : potion.getName();
            String name = potion == null ? id : potion.getName();
            effects.put(id, new EffectSnapshot(id, name, effect.getAmplifier(), effect.getDuration()));
        }
        return new PlayerSnapshot(
                player.getCommandSenderName(), player.getHealth(), player.getMaxHealth(), player.getAbsorptionAmount(),
                player.hurtTime > 0, player.isDead || player.getHealth() <= 0.0F, player.dimension, effects
        );
    }

    private JsonObject playerData(EntityPlayerMP player) {
        JsonObject data = identityData(player.getCommandSenderName(), player.getUniqueID().toString());
        data.addProperty("dimension", String.valueOf(player.dimension));
        data.addProperty("x", player.posX);
        data.addProperty("y", player.posY);
        data.addProperty("z", player.posZ);
        return data;
    }

    private JsonObject identityData(String username, String userId) {
        JsonObject data = new JsonObject();
        data.addProperty("player", username);
        data.addProperty("username", username);
        data.addProperty("displayname", username);
        data.addProperty("userId", userId);
        return data;
    }

    private void addHealth(JsonObject data, PlayerSnapshot snapshot) {
        data.addProperty("health", snapshot.health);
        data.addProperty("maxHealth", snapshot.maxHealth);
        data.addProperty("absorption", snapshot.absorption);
        data.addProperty("effectiveHealth", snapshot.health + snapshot.absorption);
    }

    private void publish(String event, JsonObject data) {
        if (runtime != null) runtime.publish(event, data);
    }

    private int executeDamage(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 3) throw new IllegalArgumentException("Usage: damage <player|@p|@a|@r> <amount>");
        float amount = Math.max(0.0F, Math.min(1000.0F, Float.parseFloat(parts[2])));
        List<EntityPlayerMP> targets = selectTargets(parts[1]);
        for (EntityPlayerMP player : targets) player.attackEntityFrom(DamageSource.magic, amount);
        return targets.size();
    }

    private List<EntityPlayerMP> selectTargets(String target) {
        List<EntityPlayerMP> all = players();
        List<EntityPlayerMP> selected = new ArrayList<EntityPlayerMP>();
        if ("@a".equals(target)) selected.addAll(all);
        else if ("@p".equals(target) && !all.isEmpty()) selected.add(all.get(0));
        else if ("@r".equals(target) && !all.isEmpty()) selected.add(all.get(random.nextInt(all.size())));
        else for (EntityPlayerMP player : all) if (player.getCommandSenderName().equalsIgnoreCase(target)) selected.add(player);
        return selected;
    }

    private int executeLegacyEffect(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 6) throw new IllegalArgumentException("Usage: effect give <player> <effect> <seconds> <amplifier>");
        List<EntityPlayerMP> targets = selectTargets(parts[2]);
        int potionId = potionId(parts[3]);
        if (potionId < 0) throw new IllegalArgumentException("Unknown 1.7.10 potion: " + parts[3]);
        int seconds = Math.max(1, Math.min(3600, Integer.parseInt(parts[4])));
        int amplifier = Math.max(0, Math.min(255, Integer.parseInt(parts[5])));
        for (EntityPlayerMP player : targets) {
            player.addPotionEffect(new PotionEffect(potionId, seconds * 20, amplifier, false));
        }
        return targets.size();
    }

    private int potionId(String requested) {
        try {
            int numeric = Integer.parseInt(requested);
            return numeric >= 0 && numeric < Potion.potionTypes.length && Potion.potionTypes[numeric] != null ? numeric : -1;
        } catch (NumberFormatException ignored) {}
        String normalized = requested.toLowerCase();
        String legacyName = legacyPotionName(normalized);
        for (int i = 0; i < Potion.potionTypes.length; i++) {
            Potion potion = Potion.potionTypes[i];
            if (potion == null) continue;
            String name = potion.getName();
            if (name.equalsIgnoreCase(requested) || name.equalsIgnoreCase(legacyName)) return i;
        }
        return -1;
    }

    private String legacyPotionName(String value) {
        String name = value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
        if ("speed".equals(name)) return "potion.moveSpeed";
        if ("slowness".equals(name)) return "potion.moveSlowdown";
        if ("haste".equals(name)) return "potion.digSpeed";
        if ("mining_fatigue".equals(name)) return "potion.digSlowDown";
        if ("strength".equals(name)) return "potion.damageBoost";
        if ("instant_health".equals(name)) return "potion.heal";
        if ("instant_damage".equals(name)) return "potion.harm";
        if ("jump_boost".equals(name)) return "potion.jump";
        if ("nausea".equals(name)) return "potion.confusion";
        if ("fire_resistance".equals(name)) return "potion.fireResistance";
        if ("water_breathing".equals(name)) return "potion.waterBreathing";
        if ("night_vision".equals(name)) return "potion.nightVision";
        if ("health_boost".equals(name)) return "potion.healthBoost";
        return "potion." + name;
    }

    private int executeLegacySummon(String command) {
        String lower = command.toLowerCase();
        int run = lower.indexOf(" run ", "execute at ".length());
        if (!lower.startsWith("execute at ") || run < 0) throw new IllegalArgumentException("Unsupported legacy execute command");
        String target = command.substring("execute at ".length(), run).trim();
        String nested = command.substring(run + " run ".length()).trim();
        String[] summon = nested.split("\\s+");
        if (summon.length < 5 || !"summon".equalsIgnoreCase(summon[0])) {
            throw new IllegalArgumentException("Minecraft 1.7.10 only supports Lumia summon presets through execute");
        }
        int spawned = 0;
        for (EntityPlayerMP player : selectTargets(target)) {
            double x = coordinate(player.posX, summon[2]);
            double y = coordinate(player.posY, summon[3]);
            double z = coordinate(player.posZ, summon[4]);
            String entityName = legacyEntityName(summon[1]);
            if ("LightningBolt".equals(entityName)) {
                player.worldObj.addWeatherEffect(new EntityLightningBolt(player.worldObj, x, y, z));
                spawned++;
                continue;
            }
            Entity entity = EntityList.createEntityByName(entityName, player.worldObj);
            if (entity == null && summon[1].indexOf(':') >= 0) {
                entity = EntityList.createEntityByName(summon[1].substring(summon[1].indexOf(':') + 1), player.worldObj);
            }
            if (entity == null) throw new IllegalArgumentException("Unknown 1.7.10 entity: " + summon[1]);
            entity.setPosition(x, y, z);
            if (player.worldObj.spawnEntityInWorld(entity)) spawned++;
        }
        return spawned;
    }

    private double coordinate(double base, String value) {
        if (value.startsWith("~")) return base + (value.length() == 1 ? 0.0D : Double.parseDouble(value.substring(1)));
        return Double.parseDouble(value);
    }

    private String legacyEntityName(String requested) {
        String name = requested.startsWith("minecraft:") ? requested.substring("minecraft:".length()) : requested;
        if ("lightning_bolt".equals(name)) return "LightningBolt";
        if ("zombie".equals(name)) return "Zombie";
        if ("creeper".equals(name)) return "Creeper";
        if ("chicken".equals(name)) return "Chicken";
        if ("skeleton".equals(name)) return "Skeleton";
        if ("spider".equals(name)) return "Spider";
        if ("enderman".equals(name)) return "Enderman";
        if ("pig".equals(name)) return "Pig";
        if ("cow".equals(name)) return "Cow";
        if ("sheep".equals(name)) return "Sheep";
        return requested;
    }

    private int executeLegacyTitle(String command) {
        String[] parts = command.trim().split("\\s+", 4);
        if (parts.length < 4) throw new IllegalArgumentException("Invalid title command");
        return server.getCommandManager().executeCommand(server, "tellraw " + parts[1] + " " + parts[3]);
    }

    private static final class PlayerSnapshot {
        final String username;
        final float health;
        final float maxHealth;
        final float absorption;
        final boolean hurt;
        final boolean dead;
        final int dimension;
        final Map<String, EffectSnapshot> effects;

        PlayerSnapshot(String username, float health, float maxHealth, float absorption, boolean hurt,
                       boolean dead, int dimension, Map<String, EffectSnapshot> effects) {
            this.username = username;
            this.health = health;
            this.maxHealth = maxHealth;
            this.absorption = absorption;
            this.hurt = hurt;
            this.dead = dead;
            this.dimension = dimension;
            this.effects = effects;
        }
    }

    private static final class EffectSnapshot {
        final String id;
        final String name;
        final int amplifier;
        final int durationTicks;

        EffectSnapshot(String id, String name, int amplifier, int durationTicks) {
            this.id = id;
            this.name = name;
            this.amplifier = amplifier;
            this.durationTicks = durationTicks;
        }
    }

    private final class ForgeHost implements LegacyBridgeRuntime.Host {
        @Override public Path configDirectory() { return Paths.get("config").toAbsolutePath(); }
        @Override public String gameVersion() { return "1.7.10"; }
        @Override public String motd() { return server == null ? "" : server.getMOTD(); }
        @Override public int playerCount() { return server == null ? 0 : players().size(); }
        @Override public void schedule(Runnable task) { serverTasks.add(task); }
        @Override public int executeCommand(String command) throws Exception {
            String lower = command.toLowerCase();
            if (lower.startsWith("damage ")) return executeDamage(command);
            if (lower.startsWith("effect give ")) return executeLegacyEffect(command);
            if (lower.startsWith("execute at ")) return executeLegacySummon(command);
            if (lower.startsWith("title ")) return executeLegacyTitle(command);
            return server.getCommandManager().executeCommand(server, command);
        }
        @Override public void info(String message) { LOGGER.info(message); }
        @Override public void warn(String message) { LOGGER.warn(message); }
        @Override public void debug(String message) { LOGGER.debug(message); }
    }

    private static final class LumiaBridgeCommand extends CommandBase {
        private final LumiaBridgeLegacy1710Mod mod;
        LumiaBridgeCommand(LumiaBridgeLegacy1710Mod mod) { this.mod = mod; }
        @Override public String getCommandName() { return "lumiabridge"; }
        @Override public String getCommandUsage(ICommandSender sender) { return "/lumiabridge status|test"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public void processCommand(ICommandSender sender, String[] args) {
            if (args.length > 0 && "test".equalsIgnoreCase(args[0])) {
                if (!sender.canCommandSenderUseCommand(2, getCommandName())) {
                    sender.addChatMessage(new ChatComponentText("You need operator permission for the test command."));
                    return;
                }
                List<EntityPlayerMP> players = mod.players();
                if (players.isEmpty()) {
                    sender.addChatMessage(new ChatComponentText("No player is online for the test event."));
                    return;
                }
                EntityPlayerMP player = players.get(0);
                JsonObject data = mod.playerData(player);
                PlayerSnapshot snapshot = mod.snapshot(player);
                mod.addHealth(data, snapshot);
                data.addProperty("previousHealth", snapshot.health);
                data.addProperty("amount", 1.0F);
                data.addProperty("source", "lumiabridge:test");
                mod.publish("player_damage", data);
                sender.addChatMessage(new ChatComponentText("Sent a harmless LumiaCraft test alert."));
                return;
            }
            String status = mod.runtime == null ? "Lumia Bridge is stopped." :
                    "Lumia Bridge " + VERSION + " on " + mod.runtime.endpoint()
                            + " | Lumia clients: " + mod.runtime.subscriberCount()
                            + " | events sent: " + mod.runtime.eventCount();
            sender.addChatMessage(new ChatComponentText(status));
        }
    }
}
