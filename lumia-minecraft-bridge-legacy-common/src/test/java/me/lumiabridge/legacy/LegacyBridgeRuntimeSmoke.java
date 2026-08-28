package me.lumiabridge.legacy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LegacyBridgeRuntimeSmoke {
    public static void main(String[] args) throws Exception {
        ServerSocket reservation = new ServerSocket(0);
        int port = reservation.getLocalPort();
        reservation.close();
        final Path config = Files.createTempDirectory("lumiabridge-legacy-smoke");
        String json = "{\"bind\":\"127.0.0.1\",\"port\":" + port
                + ",\"token\":\"\",\"allowedCommands\":[\"tellraw\"],\"logEvents\":false}";
        Files.write(config.resolve("lumia-bridge.json"), json.getBytes(StandardCharsets.UTF_8));

        final String[] executed = new String[1];
        LegacyBridgeRuntime runtime = LegacyBridgeRuntime.start(new LegacyBridgeRuntime.Host() {
            @Override public Path configDirectory() { return config; }
            @Override public String gameVersion() { return "1.7.10"; }
            @Override public String motd() { return "smoke"; }
            @Override public int playerCount() { return 2; }
            @Override public void schedule(Runnable task) { task.run(); }
            @Override public int executeCommand(String command) { executed[0] = command; return 1; }
            @Override public void info(String message) {}
            @Override public void warn(String message) {}
            @Override public void debug(String message) {}
        }, "0.3.0");

        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        writer.write("{\"id\":\"hello-1\",\"type\":\"hello\",\"token\":\"\",\"protocol\":1}\n");
        writer.flush();
        JsonObject hello = new JsonParser().parse(reader.readLine()).getAsJsonObject();
        require(hello.get("ok").getAsBoolean(), "hello rejected");
        require(hello.get("protocol").getAsInt() == 1, "protocol mismatch");
        require(hello.get("eventsSupported").getAsBoolean(), "events unsupported");

        JsonObject data = new JsonObject();
        data.addProperty("player", "LegacyPlayer");
        data.addProperty("health", 19.0F);
        runtime.publish("player_damage", data);
        JsonObject event = new JsonParser().parse(reader.readLine()).getAsJsonObject();
        require("player_damage".equals(event.get("event").getAsString()), "event mismatch");
        require("LegacyPlayer".equals(event.getAsJsonObject("data").get("player").getAsString()), "event data mismatch");

        writer.write("{\"id\":\"execute-1\",\"type\":\"execute\",\"command\":\"tellraw @a {\\\"text\\\":\\\"hello\\\"}\"}\n");
        writer.flush();
        JsonObject result = new JsonParser().parse(reader.readLine()).getAsJsonObject();
        require(result.get("ok").getAsBoolean(), "command rejected");
        require(executed[0] != null && executed[0].startsWith("tellraw @a"), "command not executed");

        writer.close();
        reader.close();
        socket.close();
        runtime.close();
        Files.deleteIfExists(config.resolve("lumia-bridge.json"));
        Files.deleteIfExists(config);
        System.out.println("Legacy Lumia Bridge protocol smoke test passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
