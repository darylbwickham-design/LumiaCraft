package me.lumiabridge;

import com.google.gson.JsonObject;
import me.lumiabridge.legacy.LegacyBridgeRuntime;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod(
        modid = LumiaBridgeLegacyMod.MOD_ID,
        name = "Lumia Bridge",
        version = LumiaBridgeLegacyMod.VERSION,
        acceptableRemoteVersions = "*"
)
public final class LumiaBridgeLegacyMod {
    public static final String MOD_ID = "lumiabridge";
    public static final String VERSION = "0.3.2";
    private static final Logger LOGGER = LogManager.getLogger("LumiaBridge");
    // LumiaCraft presents health to one decimal place. Snapshot at the same
    // precision so legacy regeneration sub-ticks cannot become "healed 0".
    private static final float EPSILON = 0.05F;
    private static final DamageSource LUMIA_DAMAGE = new DamageSource("lumiacraft");

    private final Map<String, PlayerSnapshot> snapshots = new HashMap<String, PlayerSnapshot>();
    private final ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<Runnable>();
    private final Random random = new Random();
    private MinecraftServer server;
    private LegacyBridgeRuntime runtime;

    public LumiaBridgeLegacyMod() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
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
        for (Map.Entry<String, PlayerSnapshot> entry : snapshots.entrySet()) {
            JsonObject data = identityData(entry.getValue().username, entry.getKey());
            data.addProperty("dimension", String.valueOf(entry.getValue().dimension));
            data.addProperty("reason", "server_stopping");
            publish("player_leave", data);
        }
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

    @SubscribeEvent
    public void onForgeEvent(Event event) {
        // Advancements and AdvancementEvent were added in 1.12. Reflection
        // keeps this shared bridge loadable on the 1.10.2 Forge classpath.
        String eventName = event.getClass().getName();
        if ("net.minecraftforge.event.entity.player.AchievementEvent".equals(eventName)) {
            publishLegacyAchievement(event);
            return;
        }
        if (!"net.minecraftforge.event.entity.player.AdvancementEvent".equals(eventName)) return;
        try {
            Object playerObject = event.getClass().getMethod("getEntityPlayer").invoke(event);
            if (!(playerObject instanceof EntityPlayerMP)) return;
            EntityPlayerMP player = (EntityPlayerMP) playerObject;
            Object advancement = event.getClass().getMethod("getAdvancement").invoke(event);
            String advancementId = String.valueOf(advancement.getClass().getMethod("getId").invoke(advancement));
            Object display = advancement.getClass().getMethod("getDisplay").invoke(advancement);
            String advancementTitle = advancementId;
            if (display != null) {
                Object title = display.getClass().getMethod("getTitle").invoke(display);
                if (title != null) advancementTitle = String.valueOf(title.getClass().getMethod("getUnformattedText").invoke(title));
            }
            JsonObject data = playerData(player);
            data.addProperty("advancement", advancementTitle);
            data.addProperty("advancementId", advancementId);
            publish("advancement", data);
        } catch (ReflectiveOperationException error) {
            LOGGER.warn("Could not publish advancement event", error);
        }
    }

    private void publishLegacyAchievement(Event event) {
        try {
            Object playerObject = event.getClass().getMethod("getEntityPlayer").invoke(event);
            if (!(playerObject instanceof EntityPlayerMP)) return;
            EntityPlayerMP player = (EntityPlayerMP) playerObject;
            Object achievement = event.getClass().getMethod("getAchievement").invoke(event);
            Object title = invokeMethod(achievement, "getStatName", "func_150951_e");
            String achievementTitle = String.valueOf(invokeMethod(title, "getUnformattedText", "func_150260_c"));
            String achievementId = String.valueOf(readField(achievement, "statId", "field_75975_e"));
            JsonObject data = playerData(player);
            data.addProperty("advancement", achievementTitle);
            data.addProperty("advancementId", achievementId);
            publish("advancement", data);
        } catch (ReflectiveOperationException error) {
            LOGGER.warn("Could not publish achievement event", error);
        }
    }

    private static Object invokeMethod(Object target, String primaryName, String srgName) throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(primaryName).invoke(target);
        } catch (NoSuchMethodException ignored) {
            return target.getClass().getMethod(srgName).invoke(target);
        }
    }

    private static Object readField(Object target, String primaryName, String srgName) throws ReflectiveOperationException {
        try {
            return target.getClass().getField(primaryName).get(target);
        } catch (NoSuchFieldException ignored) {
            return target.getClass().getField(srgName).get(target);
        }
    }

    private void drainServerTasks() {
        Runnable task;
        int processed = 0;
        while (processed < 100 && (task = serverTasks.poll()) != null) {
            try { task.run(); } catch (Exception error) { LOGGER.warn("Lumia task failed", error); }
            processed++;
        }
    }

    private void monitorPlayers() {
        List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
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

            float healthDelta = roundMetric(current.health - previous.health);
            float absorptionDelta = roundMetric(current.absorption - previous.absorption);
            float absorptionLost = current.hurt ? Math.max(0.0F, -absorptionDelta) : 0.0F;
            float damage = roundMetric(Math.max(0.0F, -healthDelta) + absorptionLost);
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

    private PlayerSnapshot snapshot(EntityPlayerMP player) {
        Map<String, EffectSnapshot> effects = new HashMap<String, EffectSnapshot>();
        Collection<PotionEffect> active = player.getActivePotionEffects();
        for (PotionEffect effect : active) {
            Potion potion = effect.getPotion();
            ResourceLocation key = Potion.REGISTRY.getNameForObject(potion);
            String id = key == null ? effect.getEffectName() : key.toString();
            effects.put(id, new EffectSnapshot(id, effect.getEffectName(), effect.getAmplifier(), effect.getDuration()));
        }
        return new PlayerSnapshot(
                player.getName(), roundMetric(player.getHealth()), roundMetric(player.getMaxHealth()), roundMetric(player.getAbsorptionAmount()),
                player.hurtTime > 0, player.isDead || player.getHealth() <= 0.0F, player.dimension, effects
        );
    }

    private JsonObject playerData(EntityPlayerMP player) {
        JsonObject data = identityData(player.getName(), player.getUniqueID().toString());
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
        data.addProperty("effectiveHealth", roundMetric(snapshot.health + snapshot.absorption));
    }

    private static float roundMetric(float value) {
        return Math.round(value * 10.0F) / 10.0F;
    }

    private void publish(String event, JsonObject data) {
        if (runtime != null) runtime.publish(event, data);
    }

    private int executeDamage(String command) {
        String[] parts = command.trim().split("\\s+");
        if (parts.length < 3) throw new IllegalArgumentException("Usage: damage <player|@p|@a|@r> <amount>");
        float amount = Math.max(0.0F, Math.min(1000.0F, Float.parseFloat(parts[2])));
        List<EntityPlayerMP> targets = new ArrayList<EntityPlayerMP>();
        List<EntityPlayerMP> all = server.getPlayerList().getPlayers();
        if ("@a".equals(parts[1])) targets.addAll(all);
        else if ("@p".equals(parts[1]) && !all.isEmpty()) targets.add(all.get(0));
        else if ("@r".equals(parts[1]) && !all.isEmpty()) targets.add(all.get(random.nextInt(all.size())));
        else {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(parts[1]);
            if (player != null) targets.add(player);
        }
        for (EntityPlayerMP player : targets) player.attackEntityFrom(LUMIA_DAMAGE, amount);
        return targets.size();
    }

    private String translateLegacyCommand(String command) {
        String lower = command.toLowerCase();
        if (lower.startsWith("effect give ")) {
            return "effect " + command.substring("effect give ".length());
        }
        if (lower.startsWith("execute at ")) {
            int run = lower.indexOf(" run ", "execute at ".length());
            if (run > 0) {
                String target = command.substring("execute at ".length(), run).trim();
                String nested = command.substring(run + " run ".length()).trim();
                return "execute " + target + " ~ ~ ~ " + nested;
            }
        }
        return command;
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
        @Override public Path configDirectory() { return Loader.instance().getConfigDir().toPath(); }
        @Override public String gameVersion() { return ForgeVersion.mcVersion; }
        @Override public String motd() { return server == null ? "" : server.getMOTD(); }
        @Override public int playerCount() { return server == null ? 0 : server.getPlayerList().getPlayers().size(); }
        @Override public void schedule(Runnable task) { serverTasks.add(task); }
        @Override public int executeCommand(String command) throws Exception {
            if (command.toLowerCase().startsWith("damage ")) return executeDamage(command);
            return server.getCommandManager().executeCommand(server, translateLegacyCommand(command));
        }
        @Override public void info(String message) { LOGGER.info(message); }
        @Override public void warn(String message) { LOGGER.warn(message); }
        @Override public void debug(String message) { LOGGER.debug(message); }
    }

    private static final class LumiaBridgeCommand extends net.minecraft.command.CommandBase {
        private final LumiaBridgeLegacyMod mod;
        LumiaBridgeCommand(LumiaBridgeLegacyMod mod) { this.mod = mod; }
        @Override public String getName() { return "lumiabridge"; }
        @Override public String getUsage(net.minecraft.command.ICommandSender sender) { return "/lumiabridge status|test"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public void execute(MinecraftServer server, net.minecraft.command.ICommandSender sender, String[] args) {
            if (args.length > 0 && "test".equalsIgnoreCase(args[0])) {
                if (!sender.canUseCommand(2, getName())) {
                    sender.sendMessage(new TextComponentString("You need operator permission for the test command."));
                    return;
                }
                List<EntityPlayerMP> players = server.getPlayerList().getPlayers();
                if (players.isEmpty()) {
                    sender.sendMessage(new TextComponentString("No player is online for the test event."));
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
                sender.sendMessage(new TextComponentString("Sent a harmless LumiaCraft test alert."));
                return;
            }
            String status = mod.runtime == null ? "Lumia Bridge is stopped." :
                    "Lumia Bridge " + VERSION + " on " + mod.runtime.endpoint()
                            + " | Lumia clients: " + mod.runtime.subscriberCount()
                            + " | events sent: " + mod.runtime.eventCount();
            sender.sendMessage(new TextComponentString(status));
        }
    }
}
