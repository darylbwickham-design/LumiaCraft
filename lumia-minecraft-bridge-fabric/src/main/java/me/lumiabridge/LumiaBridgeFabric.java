package me.lumiabridge;

import com.google.gson.JsonObject;
import me.lumiabridge.common.BridgeRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LumiaBridgeFabric implements ModInitializer {
    public static final String VERSION = "0.3.0";
    private static final float HEALTH_CHANGE_EPSILON = 0.01f;
    private static final Logger LOGGER = LoggerFactory.getLogger("LumiaBridge");

    private BridgeRuntime runtime;
    private MinecraftServer server;
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, minecraft) -> onPlayerJoin(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> onPlayerLeave(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onPlayerRespawn(newPlayer));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("lumiabridge")
                        .then(Commands.literal("status").executes(context -> {
                            String status = runtime == null
                                    ? "Lumia Bridge is not running."
                                    : "Lumia Bridge " + VERSION + " on " + runtime.endpoint()
                                    + " | Lumia clients: " + runtime.subscriberCount()
                                    + " | gameplay events sent: " + runtime.eventCount();
                            context.getSource().sendSuccess(() -> Component.literal(status), false);
                            return 1;
                        }))
                        .then(Commands.literal("test")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> publishTest(context.getSource().getServer())))));
    }

    private void onServerStarted(MinecraftServer minecraft) {
        server = minecraft;
        try {
            runtime = BridgeRuntime.start(new FabricHost(), VERSION);
        } catch (Exception error) {
            LOGGER.error("Could not start Lumia Bridge", error);
        }
    }

    private void onServerStopping(MinecraftServer minecraft) {
        if (runtime != null) runtime.close();
        runtime = null;
        server = null;
        snapshots.clear();
    }

    private int publishTest(MinecraftServer minecraft) {
        if (runtime == null) return 0;
        ServerPlayer player = minecraft.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (player == null) return 0;
        JsonObject data = playerData(player);
        data.addProperty("previousHealth", protocolNumber(player.getHealth()));
        data.addProperty("amount", 0.0);
        data.addProperty("source", "lumiabridge:test");
        data.addProperty("test", true);
        runtime.publish("player_damage", data);
        return 1;
    }

    private void onPlayerJoin(ServerPlayer player) {
        snapshots.put(player.getUUID(), snapshot(player));
        publish("player_join", playerData(player));
    }

    private void onPlayerLeave(ServerPlayer player) {
        publish("player_leave", playerData(player));
        snapshots.remove(player.getUUID());
    }

    private void onPlayerRespawn(ServerPlayer player) {
        snapshots.put(player.getUUID(), snapshot(player));
        JsonObject data = playerData(player);
        data.addProperty("endConquered", false);
        publish("player_respawn", data);
    }

    private void onServerTick(MinecraftServer minecraft) {
        Set<UUID> online = ConcurrentHashMap.newKeySet();
        for (ServerPlayer player : minecraft.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            online.add(uuid);
            PlayerSnapshot current = snapshot(player);
            PlayerSnapshot previous = snapshots.put(uuid, current);
            if (previous == null) continue;

            float healthDelta = current.health - previous.health;
            float absorptionDelta = current.absorption - previous.absorption;
            // Absorption can disappear because an effect expires. Count lost
            // absorption as damage only while Minecraft marks the player hurt.
            float absorptionLost = current.hurt ? Math.max(0.0f, -absorptionDelta) : 0.0f;
            float damageTaken = Math.max(0.0f, -healthDelta) + absorptionLost;
            if (damageTaken > HEALTH_CHANGE_EPSILON) {
                JsonObject data = playerData(player);
                data.addProperty("previousHealth", protocolNumber(previous.health));
                data.addProperty("previousAbsorption", protocolNumber(previous.absorption));
                data.addProperty("amount", protocolNumber(damageTaken));
                data.addProperty("source", "unknown");
                publish("player_damage", data);
            } else if (healthDelta > HEALTH_CHANGE_EPSILON) {
                JsonObject data = playerData(player);
                data.addProperty("previousHealth", protocolNumber(previous.health));
                data.addProperty("previousAbsorption", protocolNumber(previous.absorption));
                data.addProperty("amount", protocolNumber(healthDelta));
                publish("player_heal", data);
            }

            if (!previous.dead && current.dead) {
                JsonObject data = playerData(player);
                data.addProperty("health", 0.0);
                data.addProperty("source", "unknown");
                publish("player_death", data);
            }
            if (!previous.dimension.equals(current.dimension)) {
                JsonObject data = playerData(player);
                data.addProperty("fromDimension", previous.dimension);
                data.addProperty("toDimension", current.dimension);
                publish("dimension_change", data);
            }
            publishEffectChanges(player, previous.effects, current.effects);
        }
        snapshots.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    private void publishEffectChanges(ServerPlayer player, Map<String, EffectSnapshot> previous, Map<String, EffectSnapshot> current) {
        for (Map.Entry<String, EffectSnapshot> entry : current.entrySet()) {
            EffectSnapshot old = previous.get(entry.getKey());
            EffectSnapshot now = entry.getValue();
            if (old == null || old.amplifier != now.amplifier) {
                publishEffect("effect_applied", player, now);
            }
        }
        for (Map.Entry<String, EffectSnapshot> entry : previous.entrySet()) {
            if (current.containsKey(entry.getKey())) continue;
            EffectSnapshot old = entry.getValue();
            publishEffect(old.durationTicks <= 1 ? "effect_expired" : "effect_removed", player, old);
        }
    }

    private void publishEffect(String event, ServerPlayer player, EffectSnapshot effect) {
        JsonObject data = playerData(player);
        data.addProperty("effect", effect.id);
        data.addProperty("effectName", effect.name);
        data.addProperty("amplifier", effect.amplifier);
        data.addProperty("level", effect.amplifier + 1);
        data.addProperty("durationTicks", effect.durationTicks);
        data.addProperty("durationSeconds", Math.max(0, effect.durationTicks / 20.0));
        data.addProperty("ambient", effect.ambient);
        data.addProperty("visible", effect.visible);
        publish(event, data);
    }

    private PlayerSnapshot snapshot(ServerPlayer player) {
        float maxHealth = finiteNonNegative(player.getMaxHealth());
        Map<String, EffectSnapshot> effects = new LinkedHashMap<>();
        for (MobEffectInstance instance : player.getActiveEffects()) {
            EffectSnapshot effect = effectSnapshot(instance);
            effects.put(effect.id, effect);
        }
        return new PlayerSnapshot(
                Math.min(finiteNonNegative(player.getHealth()), maxHealth),
                finiteNonNegative(player.getAbsorptionAmount()),
                player.hurtTime > 0,
                player.isDeadOrDying(),
                player.level().dimension().location().toString(),
                effects
        );
    }

    private static EffectSnapshot effectSnapshot(MobEffectInstance instance) {
        MobEffect effect = unwrapEffect(instance.getEffect());
        String id = effect == null ? "unknown" : BuiltInRegistries.MOB_EFFECT.getKey(effect).toString();
        String name = effect == null ? id : effect.getDisplayName().getString();
        return new EffectSnapshot(id, name, instance.getAmplifier(), instance.getDuration(), instance.isAmbient(), instance.isVisible());
    }

    private static MobEffect unwrapEffect(Object value) {
        if (value instanceof MobEffect effect) return effect;
        try {
            Method method = value.getClass().getMethod("value");
            Object unwrapped = method.invoke(value);
            return unwrapped instanceof MobEffect effect ? effect : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonObject playerData(ServerPlayer player) {
        JsonObject data = new JsonObject();
        float maxHealth = finiteNonNegative(player.getMaxHealth());
        float health = Math.min(finiteNonNegative(player.getHealth()), maxHealth);
        float absorption = finiteNonNegative(player.getAbsorptionAmount());
        String playerName = player.getGameProfile().getName();
        data.addProperty("player", playerName);
        data.addProperty("username", playerName);
        data.addProperty("displayname", player.getDisplayName().getString());
        data.addProperty("userId", player.getUUID().toString());
        data.addProperty("uuid", player.getUUID().toString());
        data.addProperty("health", protocolNumber(health));
        data.addProperty("maxHealth", protocolNumber(maxHealth));
        data.addProperty("absorption", protocolNumber(absorption));
        data.addProperty("effectiveHealth", protocolNumber(health + absorption));
        data.addProperty("healthPercent", maxHealth > 0.0f ? Math.round((health / maxHealth) * 1000.0) / 10.0 : 0.0);
        data.addProperty("food", player.getFoodData().getFoodLevel());
        data.addProperty("dimension", player.level().dimension().location().toString());
        data.addProperty("x", Math.round(player.getX() * 10.0) / 10.0);
        data.addProperty("y", Math.round(player.getY() * 10.0) / 10.0);
        data.addProperty("z", Math.round(player.getZ() * 10.0) / 10.0);
        return data;
    }

    private static float finiteNonNegative(float value) {
        return Float.isFinite(value) ? Math.max(0.0f, value) : 0.0f;
    }

    private static double protocolNumber(float value) {
        if (!Float.isFinite(value)) return 0.0;
        return Math.round(value * 1000.0) / 1000.0;
    }

    private void publish(String event, JsonObject data) {
        if (runtime != null) runtime.publish(event, data);
    }

    private record EffectSnapshot(String id, String name, int amplifier, int durationTicks, boolean ambient, boolean visible) {}
    private record PlayerSnapshot(float health, float absorption, boolean hurt, boolean dead, String dimension,
                                  Map<String, EffectSnapshot> effects) {}

    private final class FabricHost implements BridgeRuntime.Host {
        @Override public Path configDirectory() { return FabricLoader.getInstance().getConfigDir(); }
        @Override public String gameVersion() { return server.getServerVersion(); }
        @Override public String motd() { return server.getMotd(); }
        @Override public int playerCount() { return server.getPlayerCount(); }
        @Override public void schedule(Runnable task) { server.execute(task); }
        @Override public int executeCommand(String command) throws Exception {
            Object commands = server.getCommands();
            Object source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            for (Method method : commands.getClass().getMethods()) {
                if (!method.getName().equals("performPrefixedCommand") || method.getParameterCount() != 2) continue;
                Object result = method.invoke(commands, source, command);
                return result instanceof Number number ? number.intValue() : 1;
            }
            throw new NoSuchMethodException("Minecraft command dispatcher does not expose performPrefixedCommand");
        }
        @Override public void info(String message) { LOGGER.info(message); }
        @Override public void warn(String message) { LOGGER.warn(message); }
        @Override public void debug(String message) { LOGGER.debug(message); }
    }
}
