package me.lumiabridge;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.logging.LogUtils;
import me.lumiabridge.common.BridgeRuntime;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(LumiaBridgeMod.MOD_ID)
public final class LumiaBridgeMod {
    public static final String MOD_ID = "lumiabridge";
    public static final String VERSION = "0.3.0";
    private static final float HEALTH_CHANGE_EPSILON = 0.01f;
    private static final Logger LOGGER = LogUtils.getLogger();

    private BridgeRuntime runtime;
    private MinecraftServer server;
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();

    public LumiaBridgeMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        try {
            runtime = BridgeRuntime.start(new ForgeHost(), VERSION);
        } catch (Exception error) {
            LOGGER.error("Could not start Lumia Bridge", error);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (runtime != null) runtime.close();
        runtime = null;
        server = null;
        snapshots.clear();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lumiabridge")
                .then(Commands.literal("status").executes(context -> {
                    String status = runtime == null
                            ? "Lumia Bridge is not running."
                            : "Lumia Bridge " + VERSION + " on " + runtime.endpoint()
                            + " | Lumia clients: " + runtime.subscriberCount()
                            + " | gameplay events sent: " + runtime.eventCount();
                    context.getSource().sendSuccess(new TextComponent(status), false);
                    return 1;
                }))
                .then(Commands.literal("test")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> publishTest(context.getSource().getServer()))));

        // Vanilla added /damage after 1.18.2. Register the same command shape so
        // LumiaCraft's version-independent Damage Player action still works.
        event.getDispatcher().register(Commands.literal("damage")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.players())
                        .then(Commands.argument("amount", FloatArgumentType.floatArg(0.0f))
                                .executes(context -> {
                                    float amount = FloatArgumentType.getFloat(context, "amount");
                                    int affected = 0;
                                    for (ServerPlayer player : EntityArgument.getPlayers(context, "target")) {
                                        if (player.hurt(DamageSource.GENERIC, amount)) affected++;
                                    }
                                    return affected;
                                }))));
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer minecraft = ServerLifecycleHooks.getCurrentServer();
        if (minecraft == null) return;
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

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            snapshots.put(player.getUUID(), snapshot(player));
            publish("player_join", playerData(player));
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            publish("player_leave", playerData(player));
            snapshots.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        snapshots.put(player.getUUID(), snapshot(player));
        JsonObject data = playerData(player);
        data.addProperty("endConquered", event.isEndConquered());
        publish("player_respawn", data);
    }

    private void publishEffectChanges(ServerPlayer player, Map<String, EffectSnapshot> previous, Map<String, EffectSnapshot> current) {
        for (Map.Entry<String, EffectSnapshot> entry : current.entrySet()) {
            EffectSnapshot old = previous.get(entry.getKey());
            EffectSnapshot now = entry.getValue();
            if (old == null || old.amplifier != now.amplifier) publishEffect("effect_applied", player, now);
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
            String id = Registry.MOB_EFFECT.getKey(instance.getEffect()).toString();
            effects.put(id, new EffectSnapshot(
                    id,
                    instance.getEffect().getDisplayName().getString(),
                    instance.getAmplifier(),
                    instance.getDuration(),
                    instance.isAmbient(),
                    instance.isVisible()
            ));
        }
        return new PlayerSnapshot(
                Math.min(finiteNonNegative(player.getHealth()), maxHealth),
                finiteNonNegative(player.getAbsorptionAmount()),
                player.hurtTime > 0,
                player.isDeadOrDying(),
                player.level.dimension().location().toString(),
                effects
        );
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
        data.addProperty("dimension", player.level.dimension().location().toString());
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

    private final class ForgeHost implements BridgeRuntime.Host {
        @Override public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
        @Override public String gameVersion() { return server.getServerVersion(); }
        @Override public String motd() { return server.getMotd(); }
        @Override public int playerCount() { return server.getPlayerCount(); }
        @Override public void schedule(Runnable task) { server.execute(task); }
        @Override public int executeCommand(String command) {
            return server.getCommands().performCommand(
                    server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
        }
        @Override public void info(String message) { LOGGER.info(message); }
        @Override public void warn(String message) { LOGGER.warn(message); }
        @Override public void debug(String message) { LOGGER.debug(message); }
    }
}
