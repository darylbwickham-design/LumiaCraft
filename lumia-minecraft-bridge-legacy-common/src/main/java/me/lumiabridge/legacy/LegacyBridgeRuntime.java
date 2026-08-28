package me.lumiabridge.legacy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Java 8-compatible Lumia Bridge protocol for legacy Forge versions. */
public final class LegacyBridgeRuntime implements Closeable {
    public static final int PROTOCOL = 1;
    private static final int MAX_LINE_LENGTH = 16384;
    private static final Gson CONFIG_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson WIRE_GSON = new Gson();
    private static final JsonParser JSON_PARSER = new JsonParser();

    public interface Host {
        Path configDirectory();
        String gameVersion();
        String motd();
        int playerCount();
        void schedule(Runnable task);
        int executeCommand(String command) throws Exception;
        void info(String message);
        void warn(String message);
        void debug(String message);
    }

    private final Host host;
    private final String bridgeVersion;
    private final Config config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Set<ClientConnection> subscribers = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private ServerSocket serverSocket;
    private Thread acceptThread;

    private LegacyBridgeRuntime(Host host, String bridgeVersion, Config config) {
        this.host = host;
        this.bridgeVersion = bridgeVersion;
        this.config = config;
    }

    public static LegacyBridgeRuntime start(Host host, String bridgeVersion) throws IOException {
        Config config = Config.load(host);
        LegacyBridgeRuntime runtime = new LegacyBridgeRuntime(host, bridgeVersion, config);
        runtime.startServer();
        return runtime;
    }

    private void startServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(config.bind), config.port));
        running.set(true);
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "Lumia Bridge Legacy Listener");
        acceptThread.setDaemon(true);
        acceptThread.start();
        host.info("Lumia Bridge listening on " + endpoint());
        if (!isLoopback(config.bind)) {
            host.warn("Lumia Bridge is exposed beyond this computer. Keep the token private and firewall the port.");
        }
    }

    public void publish(String event, JsonObject data) {
        if (!running.get() || event == null || event.trim().isEmpty() || data == null) return;
        JsonObject message = new JsonObject();
        message.addProperty("type", "event");
        message.addProperty("event", event);
        message.addProperty("sequence", sequence.incrementAndGet());
        message.addProperty("timestamp", System.currentTimeMillis());
        message.add("data", data);
        if (config.logEvents) {
            host.info("Lumia event " + event + " #" + sequence.get() + " for "
                    + subscribers.size() + " subscriber(s): " + CONFIG_GSON.toJson(data));
        }
        for (ClientConnection client : subscribers) {
            if (!write(client.writer, message)) subscribers.remove(client);
        }
    }

    public int subscriberCount() { return subscribers.size(); }
    public long eventCount() { return sequence.get(); }
    public String endpoint() { return config.bind + ":" + config.port; }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30000);
                clients.execute(new Runnable() {
                    @Override public void run() { handleClient(socket); }
                });
            } catch (IOException error) {
                if (running.get()) host.warn("Lumia Bridge accept failed: " + error.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        ClientConnection client = new ClientConnection();
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            client.writer = writer;
            boolean authenticated = false;
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.length() > MAX_LINE_LENGTH) {
                    write(writer, error("", "Message is too large"));
                    break;
                }
                JsonObject request;
                try {
                    request = JSON_PARSER.parse(line).getAsJsonObject();
                } catch (Exception error) {
                    write(writer, error("", "Invalid JSON"));
                    continue;
                }
                String type = string(request, "type");
                final String id = string(request, "id");
                if (!authenticated) {
                    if (!"hello".equals(type) || !authenticate(request, socket)) {
                        write(writer, error(id, "Authentication failed"));
                        break;
                    }
                    authenticated = true;
                    subscribers.add(client);
                    JsonObject hello = ok(id);
                    hello.addProperty("type", "hello");
                    hello.addProperty("protocol", PROTOCOL);
                    hello.addProperty("modVersion", bridgeVersion);
                    hello.addProperty("minecraftVersion", host.gameVersion());
                    hello.addProperty("motd", host.motd());
                    hello.addProperty("players", host.playerCount());
                    hello.addProperty("eventsPublished", sequence.get());
                    hello.addProperty("eventsSupported", true);
                    write(writer, hello);
                    host.info("Lumia connected from " + remote);
                } else if ("ping".equals(type)) {
                    JsonObject pong = ok(id);
                    pong.addProperty("type", "pong");
                    pong.addProperty("players", host.playerCount());
                    pong.addProperty("eventsPublished", sequence.get());
                    write(writer, pong);
                } else if ("execute".equals(type)) {
                    final String command = string(request, "command");
                    String validation = validateCommand(command);
                    if (validation != null) {
                        write(writer, error(id, validation));
                        continue;
                    }
                    final BufferedWriter responseWriter = writer;
                    host.schedule(new Runnable() {
                        @Override public void run() { executeCommand(id, command, responseWriter); }
                    });
                } else {
                    write(writer, error(id, "Unknown message type"));
                }
            }
        } catch (IOException error) {
            if (running.get()) host.debug("Lumia client " + remote + " disconnected: " + error.getMessage());
        } finally {
            subscribers.remove(client);
            closeQuietly(reader);
            closeQuietly(writer);
            closeQuietly(socket);
            host.info("Lumia disconnected from " + remote);
        }
    }

    private boolean authenticate(JsonObject request, Socket socket) {
        if (!config.token.trim().isEmpty()) return config.token.equals(string(request, "token"));
        return socket.getInetAddress().isLoopbackAddress();
    }

    private String validateCommand(String original) {
        if (original == null) return "Command is missing";
        String command = original.trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isEmpty() || command.length() > config.maxCommandLength || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            return "Command is empty, too long, or contains a line break";
        }
        String[] parts = command.split("\\s+");
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (!config.allowedCommands.contains(root)) return "Command root is not allowed: " + root;
        if ("execute".equals(root)) {
            int runIndex = -1;
            for (int i = 1; i < parts.length; i++) if ("run".equalsIgnoreCase(parts[i])) runIndex = i;
            if (runIndex < 0 || runIndex + 1 >= parts.length) return "Execute command must contain run";
            String nested = parts[runIndex + 1].toLowerCase(Locale.ROOT);
            if ("execute".equals(nested) || !config.allowedCommands.contains(nested)) return "Nested command root is not allowed: " + nested;
        }
        return null;
    }

    private void executeCommand(String id, String original, BufferedWriter writer) {
        String command = original.trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        try {
            int result = host.executeCommand(command);
            JsonObject response = ok(id);
            response.addProperty("type", "result");
            response.addProperty("command", command);
            response.addProperty("result", result);
            write(writer, response);
        } catch (Exception error) {
            write(writer, error(id, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private static JsonObject ok(String id) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("ok", true);
        return value;
    }

    private static JsonObject error(String id, String message) {
        JsonObject value = new JsonObject();
        value.addProperty("id", id);
        value.addProperty("ok", false);
        value.addProperty("error", message == null ? "Unknown error" : message);
        return value;
    }

    private static String string(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
        catch (Exception ignored) { return ""; }
    }

    private static boolean write(BufferedWriter writer, JsonObject message) {
        if (writer == null) return false;
        synchronized (writer) {
            try {
                writer.write(WIRE_GSON.toJson(message));
                writer.write('\n');
                writer.flush();
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    @Override public void close() {
        running.set(false);
        closeQuietly(serverSocket);
        subscribers.clear();
        clients.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) {}
    }

    private static boolean isLoopback(String bind) {
        try { return InetAddress.getByName(bind).isLoopbackAddress(); }
        catch (Exception ignored) { return false; }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class ClientConnection {
        private BufferedWriter writer;
    }

    private static final class Config {
        private String bind = "127.0.0.1";
        private int port = 38931;
        private String token = "";
        private int maxCommandLength = 512;
        private boolean logEvents = false;
        private Set<String> allowedCommands = new LinkedHashSet<String>(Arrays.asList(
                "summon", "give", "effect", "weather", "time", "title", "tellraw",
                "playsound", "particle", "damage", "clear", "difficulty", "gamerule", "tp", "execute"
        ));

        private static Config load(Host host) throws IOException {
            Path directory = host.configDirectory();
            Files.createDirectories(directory);
            Path path = directory.resolve("lumia-bridge.json");
            Config config = new Config();
            if (Files.exists(path)) {
                try {
                    String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    Config parsed = CONFIG_GSON.fromJson(json, Config.class);
                    if (parsed != null) config = parsed;
                } catch (Exception error) {
                    host.warn("Could not read lumia-bridge.json; using safe defaults: " + error.getMessage());
                }
            }
            config.port = Math.max(1024, Math.min(65535, config.port));
            config.maxCommandLength = Math.max(64, Math.min(4096, config.maxCommandLength));
            if (config.bind == null || config.bind.trim().isEmpty()) config.bind = "127.0.0.1";
            if (config.token == null) config.token = "";
            if (config.allowedCommands == null || config.allowedCommands.isEmpty()) config.allowedCommands = new Config().allowedCommands;
            if (!isLoopback(config.bind) && config.token.trim().isEmpty()) config.token = randomToken();
            Files.write(path, (CONFIG_GSON.toJson(config) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            return config;
        }
    }
}
