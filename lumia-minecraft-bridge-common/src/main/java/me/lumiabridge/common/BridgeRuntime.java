package me.lumiabridge.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loader-neutral Lumia Bridge wire protocol and safety boundary.
 *
 * <p>Loader adapters own Minecraft event capture and command execution. Keeping
 * the socket implementation here prevents Forge, NeoForge, and Fabric builds
 * from drifting onto incompatible protocols.</p>
 */
public final class BridgeRuntime implements AutoCloseable {
    public static final int PROTOCOL = 1;
    private static final int MAX_LINE_LENGTH = 16_384;
    private static final Gson CONFIG_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson WIRE_GSON = new Gson();

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
    private final ExecutorService clients = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "Lumia Bridge Client");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<ClientConnection> subscribers = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private ServerSocket serverSocket;
    private Thread acceptThread;

    private BridgeRuntime(Host host, String bridgeVersion, Config config) {
        this.host = host;
        this.bridgeVersion = bridgeVersion;
        this.config = config;
    }

    public static BridgeRuntime start(Host host, String bridgeVersion) throws IOException {
        Config config = Config.load(host);
        BridgeRuntime runtime = new BridgeRuntime(host, bridgeVersion, config);
        runtime.startServer();
        return runtime;
    }

    private void startServer() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName(config.bind), config.port));
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "Lumia Bridge Listener");
        acceptThread.setDaemon(true);
        acceptThread.start();
        host.info("Lumia Bridge listening on " + endpoint());
        if (!isLoopback(config.bind)) {
            host.warn("Lumia Bridge is exposed beyond this computer. Keep the token private and firewall the port.");
        }
    }

    public void publish(String event, JsonObject data) {
        if (!running.get() || event == null || event.isBlank() || data == null) return;
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

    public int subscriberCount() {
        return subscribers.size();
    }

    public long eventCount() {
        return sequence.get();
    }

    public String endpoint() {
        return config.bind + ":" + config.port;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30_000);
                clients.execute(() -> handleClient(socket));
            } catch (IOException error) {
                if (running.get()) host.warn("Lumia Bridge accept failed: " + error.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        String remote = String.valueOf(socket.getRemoteSocketAddress());
        ClientConnection client = new ClientConnection();
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
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
                    request = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception error) {
                    write(writer, error("", "Invalid JSON"));
                    continue;
                }
                String type = string(request, "type");
                String id = string(request, "id");
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
                    continue;
                }
                if ("ping".equals(type)) {
                    JsonObject pong = ok(id);
                    pong.addProperty("type", "pong");
                    pong.addProperty("players", host.playerCount());
                    pong.addProperty("eventsPublished", sequence.get());
                    write(writer, pong);
                } else if ("execute".equals(type)) {
                    String command = string(request, "command");
                    String validation = validateCommand(command);
                    if (validation != null) {
                        write(writer, error(id, validation));
                        continue;
                    }
                    host.schedule(() -> executeCommand(id, command, writer));
                } else {
                    write(writer, error(id, "Unknown message type"));
                }
            }
        } catch (IOException error) {
            if (running.get()) host.debug("Lumia client " + remote + " disconnected: " + error.getMessage());
        } finally {
            subscribers.remove(client);
            host.info("Lumia disconnected from " + remote);
        }
    }

    private boolean authenticate(JsonObject request, Socket socket) {
        if (!config.token.isBlank()) return config.token.equals(string(request, "token"));
        return socket.getInetAddress().isLoopbackAddress();
    }

    private String validateCommand(String original) {
        String command = original == null ? "" : original.trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        if (command.isBlank()) return "Command is empty";
        if (command.length() > config.maxCommandLength) return "Command is too long";
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return "Command contains a line break";
        String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!config.allowedCommands.contains(root)) return "Command root is not allowed: " + root;
        if ("execute".equals(root)) {
            int runIndex = command.toLowerCase(Locale.ROOT).lastIndexOf(" run ");
            if (runIndex < 0) return "Execute commands must contain a run clause";
            String nested = command.substring(runIndex + 5).trim();
            String nestedRoot = nested.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            if ("execute".equals(nestedRoot) || !config.allowedCommands.contains(nestedRoot)) {
                return "Nested command root is not allowed: " + nestedRoot;
            }
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
            response.addProperty("result", result);
            response.addProperty("command", command);
            write(writer, response);
        } catch (Exception error) {
            host.warn("Lumia command failed: " + command + " (" + error.getMessage() + ")");
            write(writer, error(id, "Command failed: " + error.getMessage()));
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JsonObject ok(String id) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        if (id != null && !id.isBlank()) response.addProperty("id", id);
        return response;
    }

    private static JsonObject error(String id, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("ok", false);
        if (id != null && !id.isBlank()) response.addProperty("id", id);
        response.addProperty("error", message);
        return response;
    }

    private static boolean write(BufferedWriter writer, JsonObject response) {
        if (writer == null) return false;
        synchronized (writer) {
            try {
                writer.write(WIRE_GSON.toJson(response));
                writer.newLine();
                writer.flush();
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    private static boolean isLoopback(String bind) {
        return "127.0.0.1".equals(bind) || "localhost".equalsIgnoreCase(bind) || "::1".equals(bind);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        subscribers.clear();
        clients.shutdownNow();
        if (acceptThread != null) acceptThread.interrupt();
    }

    private static final class ClientConnection {
        BufferedWriter writer;
    }

    private static final class Config {
        String bind = "127.0.0.1";
        int port = 38931;
        String token = "";
        int maxCommandLength = 512;
        boolean logEvents = false;
        Set<String> allowedCommands = new LinkedHashSet<>(Arrays.asList(
                "summon", "give", "effect", "weather", "time", "title", "tellraw",
                "playsound", "particle", "damage", "clear", "difficulty", "gamerule", "tp", "execute"
        ));

        static Config load(Host host) throws IOException {
            Path path = host.configDirectory().resolve("lumia-bridge.json");
            Config config;
            if (Files.exists(path)) {
                config = CONFIG_GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Config.class);
                if (config == null) config = new Config();
            } else {
                config = new Config();
            }
            config.port = Math.max(1024, Math.min(65535, config.port));
            config.maxCommandLength = Math.max(64, Math.min(4096, config.maxCommandLength));
            if (config.bind == null || config.bind.isBlank()) config.bind = "127.0.0.1";
            if (config.token == null) config.token = "";
            if (config.allowedCommands == null || config.allowedCommands.isEmpty()) {
                config.allowedCommands = new Config().allowedCommands;
            }
            if (!isLoopback(config.bind) && config.token.isBlank()) {
                config.token = randomToken();
                host.warn("Generated a Lumia Bridge token for the non-local bind. Find it in " + path);
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, CONFIG_GSON.toJson(config) + System.lineSeparator(), StandardCharsets.UTF_8);
            return config;
        }

        private static String randomToken() {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            StringBuilder value = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) value.append(String.format("%02x", item));
            return value.toString();
        }
    }
}
