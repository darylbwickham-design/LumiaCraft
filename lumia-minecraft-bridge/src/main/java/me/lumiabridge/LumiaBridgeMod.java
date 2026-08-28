package me.lumiabridge;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import me.lumiabridge.common.BridgeRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.nio.file.Path;
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
    private BridgeRuntime bridgeRuntime;
    private MinecraftServer server;
    private final Map<UUID, HealthSnapshot> healthSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, DamageDetails> pendingDamage = new ConcurrentHashMap<>();

    public LumiaBridgeMod(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        try {
            server = event.getServer();
            bridgeRuntime = BridgeRuntime.start(new NeoForgeHost(), VERSION);
            LOGGER.info("Lumia Bridge world: {}", event.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath());
        } catch (Exception error) {
            LOGGER.error("Could not start Lumia Bridge", error);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (bridgeRuntime != null) {
            bridgeRuntime.close();
            bridgeRuntime = null;
        }
        server = null;
        healthSnapshots.clear();
        pendingDamage.clear();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lumiabridge")
                .then(Commands.literal("status").executes(context -> {
                    String status = bridgeRuntime == null
                            ? "Lumia Bridge is not running. Open a world/server first."
                            : "Lumia Bridge " + VERSION + " on " + bridgeRuntime.endpoint()
                            + " | Lumia clients: " + bridgeRuntime.subscriberCount()
                            + " | gameplay events sent: " + bridgeRuntime.eventCount();
                    context.getSource().sendSuccess(() -> Component.literal(status), false);
                    return 1;
                }))
                .then(Commands.literal("test")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    if (bridgeRuntime == null) {
                        context.getSource().sendSuccess(() -> Component.literal("Lumia Bridge is not running."), false);
                        return 0;
                    }
                    ServerPlayer player = context.getSource().getServer().getPlayerList().getPlayers().stream().findFirst().orElse(null);
                    if (player == null) {
                        context.getSource().sendSuccess(() -> Component.literal("No online player is available for a Lumia Bridge test."), false);
                        return 0;
                    }
                    JsonObject data = playerData(player);
                    data.addProperty("previousHealth", protocolNumber(player.getHealth()));
                    data.addProperty("amount", 0.0f);
                    data.addProperty("source", "lumiabridge:test");
                    data.addProperty("test", true);
                    bridgeRuntime.publish("player_damage", data);
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Sent a test damage alert. Check Lumia's configured actions; connected clients: " + bridgeRuntime.subscriberCount()), false);
                    return 1;
                })));
    }

    @SubscribeEvent
    public void onPlayerDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity attacker = event.getSource().getEntity();
        pendingDamage.put(player.getUUID(), new DamageDetails(
                event.getOriginalDamage(),
                event.getBlockedDamage(),
                damageSource(event.getSource()),
                attacker == null ? "" : attacker.getName().getString(),
                attacker == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString()
        ));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(ServerTickEvent.Post event) {
        Set<UUID> online = ConcurrentHashMap.newKeySet();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            online.add(uuid);
            HealthSnapshot current = healthSnapshot(player);
            HealthSnapshot previous = healthSnapshots.put(uuid, current);
            if (previous == null) continue;

            float healthDelta = current.health - previous.health;
            float absorptionDelta = current.absorption - previous.absorption;
            float healthLost = Math.max(0.0f, -healthDelta);
            DamageDetails details = pendingDamage.remove(uuid);
            // Absorption loss only counts as damage when NeoForge confirmed a damage
            // event. This prevents an expiring absorption effect from looking like a hit.
            float absorptionLost = details == null ? 0.0f : Math.max(0.0f, -absorptionDelta);
            float damageTaken = healthLost + absorptionLost;
            if (damageTaken > HEALTH_CHANGE_EPSILON) {
                JsonObject data = playerData(player);
                data.addProperty("previousHealth", protocolNumber(previous.health));
                data.addProperty("previousAbsorption", protocolNumber(previous.absorption));
                data.addProperty("amount", protocolNumber(damageTaken));
                if (details != null) {
                    data.addProperty("originalAmount", protocolNumber(details.originalAmount));
                    data.addProperty("blockedAmount", protocolNumber(details.blockedAmount));
                    data.addProperty("source", details.source);
                    if (!details.attacker.isBlank()) data.addProperty("attacker", details.attacker);
                    if (!details.attackerType.isBlank()) data.addProperty("attackerType", details.attackerType);
                } else {
                    data.addProperty("source", "unknown");
                }
                publish("player_damage", data);
            } else if (healthDelta > HEALTH_CHANGE_EPSILON) {
                JsonObject data = playerData(player);
                data.addProperty("previousHealth", protocolNumber(previous.health));
                data.addProperty("previousAbsorption", protocolNumber(previous.absorption));
                data.addProperty("amount", protocolNumber(healthDelta));
                publish("player_heal", data);
            }
        }
        healthSnapshots.keySet().removeIf(uuid -> !online.contains(uuid));
        pendingDamage.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    @SubscribeEvent
    public void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        JsonObject data = playerData(player);
        data.addProperty("health", 0.0f);
        data.addProperty("source", damageSource(event.getSource()));
        addEntity(data, "attacker", event.getSource().getEntity());
        publish("player_death", data);
    }

    @SubscribeEvent
    public void onEffectAdded(MobEffectEvent.Added event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        publishEffect("effect_applied", player, event.getEffectInstance(), event.getEffectSource());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEffectRemoved(MobEffectEvent.Remove event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        publishEffect("effect_removed", player, event.getEffectInstance(), null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEffectExpired(MobEffectEvent.Expired event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.isCanceled()) return;
        publishEffect("effect_expired", player, event.getEffectInstance(), null);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            healthSnapshots.put(player.getUUID(), healthSnapshot(player));
            publish("player_join", playerData(player));
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            publish("player_leave", playerData(player));
            healthSnapshots.remove(player.getUUID());
            pendingDamage.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        JsonObject data = playerData(player);
        data.addProperty("endConquered", event.isEndConquered());
        healthSnapshots.put(player.getUUID(), healthSnapshot(player));
        pendingDamage.remove(player.getUUID());
        publish("player_respawn", data);
    }

    @SubscribeEvent
    public void onDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        JsonObject data = playerData(player);
        data.addProperty("fromDimension", event.getFrom().location().toString());
        data.addProperty("toDimension", event.getTo().location().toString());
        publish("dimension_change", data);
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        JsonObject data = playerData(player);
        data.addProperty("advancement", event.getAdvancement().id().toString());
        publish("advancement", data);
    }

    @SubscribeEvent
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        publishItem("item_crafted", player, event.getCrafting());
    }

    @SubscribeEvent
    public void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        publishItem("item_smelted", player, event.getSmelting());
    }

    private void publishEffect(String event, ServerPlayer player, MobEffectInstance instance, Entity source) {
        if (instance == null) return;
        JsonObject data = playerData(player);
        String effect = instance.getEffect().unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
        data.addProperty("effect", effect);
        data.addProperty("effectName", instance.getEffect().value().getDisplayName().getString());
        data.addProperty("amplifier", instance.getAmplifier());
        data.addProperty("level", instance.getAmplifier() + 1);
        data.addProperty("durationTicks", instance.getDuration());
        data.addProperty("durationSeconds", Math.max(0, instance.getDuration() / 20.0));
        data.addProperty("ambient", instance.isAmbient());
        data.addProperty("visible", instance.isVisible());
        addEntity(data, "sourceEntity", source);
        publish(event, data);
    }

    private void publishItem(String event, ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        JsonObject data = playerData(player);
        data.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        data.addProperty("itemName", stack.getHoverName().getString());
        data.addProperty("count", stack.getCount());
        publish(event, data);
    }

    private JsonObject playerData(ServerPlayer player) {
        JsonObject data = new JsonObject();
        float maxHealth = finiteNonNegative(player.getMaxHealth());
        float health = Math.min(finiteNonNegative(player.getHealth()), maxHealth);
        float absorption = finiteNonNegative(player.getAbsorptionAmount());
        data.addProperty("player", player.getGameProfile().getName());
        data.addProperty("username", player.getGameProfile().getName());
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

    private static HealthSnapshot healthSnapshot(ServerPlayer player) {
        float maxHealth = finiteNonNegative(player.getMaxHealth());
        return new HealthSnapshot(
                Math.min(finiteNonNegative(player.getHealth()), maxHealth),
                finiteNonNegative(player.getAbsorptionAmount())
        );
    }

    private static float finiteNonNegative(float value) {
        return Float.isFinite(value) ? Math.max(0.0f, value) : 0.0f;
    }

    private static double protocolNumber(float value) {
        if (!Float.isFinite(value)) return 0.0;
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static void addEntity(JsonObject data, String prefix, Entity entity) {
        if (entity == null) return;
        data.addProperty(prefix, entity.getName().getString());
        data.addProperty(prefix + "Type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    private static String damageSource(net.minecraft.world.damagesource.DamageSource source) {
        return source.typeHolder().unwrapKey()
                .map(key -> key.location().toString())
                .orElse(source.getMsgId());
    }

    private void publish(String event, JsonObject data) {
        if (bridgeRuntime != null) bridgeRuntime.publish(event, data);
    }

    private record HealthSnapshot(float health, float absorption) {}

    private record DamageDetails(float originalAmount, float blockedAmount, String source, String attacker, String attackerType) {}

    private final class NeoForgeHost implements BridgeRuntime.Host {
        @Override public Path configDirectory() { return FMLPaths.CONFIGDIR.get(); }
        @Override public String gameVersion() { return server.getServerVersion(); }
        @Override public String motd() { return server.getMotd(); }
        @Override public int playerCount() { return server.getPlayerCount(); }
        @Override public void schedule(Runnable task) { server.execute(task); }
        @Override public int executeCommand(String command) {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
            return 1;
        }
        @Override public void info(String message) { LOGGER.info(message); }
        @Override public void warn(String message) { LOGGER.warn(message); }
        @Override public void debug(String message) { LOGGER.debug(message); }
    }
}
