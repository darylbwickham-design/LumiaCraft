package me.lumiabridge;

import com.google.gson.JsonObject;
import me.lumiabridge.legacy.LegacyBridgeRuntime;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.potion.Effect;
import net.minecraft.potion.EffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Forge 1.16.5 adapter. Protocol and configuration stay compatible with the other Lumia Bridge builds. */
@Mod(LumiaBridgeMod.MOD_ID)
public final class LumiaBridgeMod {
    public static final String MOD_ID = "lumiabridge";
    public static final String VERSION = "0.3.1-beta.1";
    private static final Logger LOGGER = LogManager.getLogger("LumiaBridge");
    private static final float EPSILON = 0.01F;

    private final Map<UUID, Snapshot> snapshots = new HashMap<UUID, Snapshot>();
    private final ConcurrentLinkedQueue<Runnable> queuedTasks = new ConcurrentLinkedQueue<Runnable>();
    private MinecraftServer server;
    private LegacyBridgeRuntime runtime;

    public LumiaBridgeMod() { net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void serverStarted(FMLServerStartedEvent event) {
        server = event.getServer();
        try { runtime = LegacyBridgeRuntime.start(new Host(), VERSION); }
        catch (Exception error) { LOGGER.error("Could not start Lumia Bridge", error); }
    }

    @SubscribeEvent
    public void serverStopping(FMLServerStoppingEvent event) {
        for (Map.Entry<UUID, Snapshot> entry : snapshots.entrySet()) {
            JsonObject data = identity(entry.getValue().name, entry.getKey());
            data.addProperty("reason", "server_stopping");
            publish("player_leave", data);
        }
        if (runtime != null) runtime.close();
        runtime = null; server = null; snapshots.clear(); queuedTasks.clear();
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        Runnable task; int completed = 0;
        while (completed++ < 100 && (task = queuedTasks.poll()) != null) {
            try { task.run(); } catch (Exception error) { LOGGER.warn("Lumia task failed", error); }
        }
        monitorPlayers();
    }

    @SubscribeEvent
    public void playerDeath(LivingDeathEvent event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity) || event.isCanceled()) return;
        ServerPlayerEntity player = (ServerPlayerEntity) event.getEntityLiving();
        JsonObject data = playerData(player); addHealth(data, snapshot(player));
        data.addProperty("source", event.getSource().getMsgId()); publish("player_death", data);
    }

    @SubscribeEvent
    public void playerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        snapshots.put(player.getUUID(), snapshot(player)); publish("player_respawn", playerData(player));
    }

    private void monitorPlayers() {
        Set<UUID> online = new HashSet<UUID>();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID(); online.add(id);
            Snapshot current = snapshot(player), previous = snapshots.get(id);
            if (previous == null) { JsonObject data = playerData(player); addHealth(data, current); publish("player_join", data); snapshots.put(id, current); continue; }
            float delta = current.health - previous.health;
            float absorptionDelta = current.absorption - previous.absorption;
            float damage = Math.max(0F, -delta) + (current.hurt ? Math.max(0F, -absorptionDelta) : 0F);
            if (damage > EPSILON) {
                JsonObject data = playerData(player); addHealth(data, current);
                data.addProperty("previousHealth", previous.health); data.addProperty("previousAbsorption", previous.absorption);
                data.addProperty("amount", damage); data.addProperty("originalAmount", damage); data.addProperty("blockedAmount", 0F); data.addProperty("source", "unknown");
                publish("player_damage", data);
            } else if (delta > EPSILON) {
                JsonObject data = playerData(player); addHealth(data, current);
                data.addProperty("previousHealth", previous.health); data.addProperty("previousAbsorption", previous.absorption); data.addProperty("amount", delta);
                publish("player_heal", data);
            }
            effectChanges(player, previous.effects, current.effects); snapshots.put(id, current);
        }
        List<UUID> left = new ArrayList<UUID>();
        for (Map.Entry<UUID, Snapshot> entry : snapshots.entrySet()) if (!online.contains(entry.getKey())) {
            JsonObject data = identity(entry.getValue().name, entry.getKey()); publish("player_leave", data); left.add(entry.getKey());
        }
        for (UUID id : left) snapshots.remove(id);
    }

    private void effectChanges(ServerPlayerEntity player, Map<String, EffectSnapshot> before, Map<String, EffectSnapshot> after) {
        for (Map.Entry<String, EffectSnapshot> entry : after.entrySet()) {
            EffectSnapshot old = before.get(entry.getKey());
            if (old == null || old.amplifier != entry.getValue().amplifier) publishEffect("effect_applied", player, entry.getValue());
        }
        for (Map.Entry<String, EffectSnapshot> entry : before.entrySet()) if (!after.containsKey(entry.getKey())) publishEffect("effect_expired", player, entry.getValue());
    }

    private void publishEffect(String name, ServerPlayerEntity player, EffectSnapshot effect) {
        JsonObject data = playerData(player); data.addProperty("effect", effect.id); data.addProperty("effectName", effect.name);
        data.addProperty("amplifier", effect.amplifier); data.addProperty("level", effect.amplifier + 1); data.addProperty("durationTicks", effect.duration); data.addProperty("durationSeconds", effect.duration / 20);
        publish(name, data);
    }

    private Snapshot snapshot(ServerPlayerEntity player) {
        Map<String, EffectSnapshot> effects = new HashMap<String, EffectSnapshot>();
        Collection<EffectInstance> active = player.getActiveEffects();
        for (EffectInstance effect : active) {
            Effect value = effect.getEffect(); ResourceLocation key = value.getRegistryName();
            String id = key == null ? value.getDescriptionId() : key.toString();
            effects.put(id, new EffectSnapshot(id, value.getDescriptionId(), effect.getAmplifier(), effect.getDuration()));
        }
        return new Snapshot(player.getName().getString(), player.getHealth(), player.getMaxHealth(), player.getAbsorptionAmount(), player.hurtTime > 0, effects);
    }

    private JsonObject playerData(ServerPlayerEntity player) { JsonObject data = identity(player.getName().getString(), player.getUUID()); data.addProperty("dimension", player.level.dimension().location().toString()); data.addProperty("x", player.getX()); data.addProperty("y", player.getY()); data.addProperty("z", player.getZ()); return data; }
    private JsonObject identity(String name, UUID id) { JsonObject data = new JsonObject(); data.addProperty("player", name); data.addProperty("username", name); data.addProperty("displayname", name); data.addProperty("userId", id.toString()); return data; }
    private void addHealth(JsonObject data, Snapshot state) { data.addProperty("health", state.health); data.addProperty("maxHealth", state.maxHealth); data.addProperty("absorption", state.absorption); data.addProperty("effectiveHealth", state.health + state.absorption); }
    private void publish(String event, JsonObject data) { if (runtime != null) runtime.publish(event, data); }

    private final class Host implements LegacyBridgeRuntime.Host {
        public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
        public String gameVersion() { return "1.16.5"; }
        public String motd() { return server == null ? "" : server.getMotd(); }
        public int playerCount() { return server == null ? 0 : server.getPlayerList().getPlayers().size(); }
        public void schedule(Runnable task) { queuedTasks.add(task); }
        public int executeCommand(String command) { return server.getCommands().performCommand(server.createCommandSourceStack(), command); }
        public void info(String message) { LOGGER.info(message); }
        public void warn(String message) { LOGGER.warn(message); }
        public void debug(String message) { LOGGER.debug(message); }
    }

    private static final class Snapshot { final String name; final float health, maxHealth, absorption; final boolean hurt; final Map<String, EffectSnapshot> effects; Snapshot(String name, float health, float maxHealth, float absorption, boolean hurt, Map<String, EffectSnapshot> effects) { this.name=name; this.health=health; this.maxHealth=maxHealth; this.absorption=absorption; this.hurt=hurt; this.effects=effects; } }
    private static final class EffectSnapshot { final String id, name; final int amplifier, duration; EffectSnapshot(String id, String name, int amplifier, int duration) { this.id=id; this.name=name; this.amplifier=amplifier; this.duration=duration; } }
}
